/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.StashBuffer
import akka.util.Timeout
import DurableProducerQueue.ConfirmationQualifier

object WorkPullingProducerController {

  sealed trait InternalCommand

  sealed trait Command[A] extends InternalCommand

  final case class Start[A](producer: ActorRef[RequestNext[A]]) extends Command[A]

  final case class RequestNext[A](sendNextTo: ActorRef[A], askNextTo: ActorRef[MessageWithConfirmation[A]])

  /**
   * For sending confirmation message back to the producer when the message has been fully delivered, processed,
   * and confirmed by the consumer. Typically used with `ask` from the producer.
   */
  final case class MessageWithConfirmation[A](message: A, replyTo: ActorRef[Done]) extends InternalCommand

  final case class GetWorkerStats[A](replyTo: ActorRef[WorkerStats]) extends Command[A]

  final case class WorkerStats(numberOfWorkers: Int)

  private type TotalSeqNr = Long
  private type OutSeqNr = Long
  private type OutKey = String

  private final case class WorkerRequestNext[A](next: ProducerController.RequestNext[A]) extends InternalCommand

  private final case class Ack(outKey: OutKey, confirmedSeqNr: OutSeqNr) extends InternalCommand

  private case object RegisterConsumerDone extends InternalCommand

  private case class LoadStateReply[A](state: DurableProducerQueue.State[A]) extends InternalCommand
  private case class LoadStateFailed(attempt: Int) extends InternalCommand
  private case class StoreMessageSentReply(ack: DurableProducerQueue.StoreMessageSentAck)
  private case class StoreMessageSentFailed[A](messageSent: DurableProducerQueue.MessageSent[A], attempt: Int)
      extends InternalCommand
  private case class StoreMessageSentCompleted[A](messageSent: DurableProducerQueue.MessageSent[A])
      extends InternalCommand

  private final case class OutState[A](
      producerController: ActorRef[ProducerController.Command[A]],
      consumerController: ActorRef[ConsumerController.Command[A]],
      seqNr: OutSeqNr,
      unconfirmed: Vector[Unconfirmed[A]],
      askNextTo: Option[ActorRef[ProducerController.MessageWithConfirmation[A]]]) {
    def confirmationQualifier: ConfirmationQualifier = producerController.path.name
  }

  private final case class Unconfirmed[A](
      totalSeqNr: TotalSeqNr,
      outSeqNr: OutSeqNr,
      msg: A,
      replyTo: Option[ActorRef[Done]])

  private object State {
    def empty[A]: State[A] = State(1, Set.empty, Map.empty, Map.empty, Map.empty, Map.empty, hasRequested = false)
  }

  private final case class State[A](
      currentSeqNr: TotalSeqNr, // only updated when durableQueue is enabled
      workers: Set[ActorRef[ConsumerController.Command[A]]],
      out: Map[OutKey, OutState[A]],
      // when durableQueue is enabled the worker must be selecting before storage
      // to know the confirmationQualifier up-front
      preselectedWorkers: Map[TotalSeqNr, PreselectedWorker],
      // replyAfterStore is used when durableQueue is enabled, otherwise they are tracked in OutState
      replyAfterStore: Map[TotalSeqNr, ActorRef[Done]],
      // when the worker is deregistered but there are still unconfirmed
      handOver: Map[TotalSeqNr, HandOver],
      hasRequested: Boolean)

  private case class PreselectedWorker(outKey: OutKey, confirmationQualifier: ConfirmationQualifier)

  private case class HandOver(oldConfirmationQualifier: ConfirmationQualifier, oldSeqNr: TotalSeqNr)

  // registration of workers via Receptionist
  private final case class CurrentWorkers[A](workers: Set[ActorRef[ConsumerController.Command[A]]])
      extends InternalCommand

  private final case class Msg[A](msg: A, wasStashed: Boolean, replyTo: Option[ActorRef[Done]]) extends InternalCommand

  private final case class ResendDurableMsg[A](
      msg: A,
      oldConfirmationQualifier: ConfirmationQualifier,
      oldSeqNr: TotalSeqNr)
      extends InternalCommand

  def apply[A: ClassTag](
      producerId: String,
      workerServiceKey: ServiceKey[ConsumerController.Command[A]],
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]]): Behavior[Command[A]] = {
    Behaviors
      .withStash[InternalCommand](1000) { stashBuffer => // FIXME stash config
        Behaviors.setup[InternalCommand] { context =>
          context.setLoggerName(classOf[WorkPullingProducerController[_]])
          val listingAdapter = context.messageAdapter[Receptionist.Listing](listing =>
            CurrentWorkers[A](listing.allServiceInstances(workerServiceKey)))
          context.system.receptionist ! Receptionist.Subscribe(workerServiceKey, listingAdapter)

          val durableQueue = askLoadState(context, durableQueueBehavior)

          waitingForStart(
            producerId,
            context,
            stashBuffer,
            durableQueue,
            None,
            createInitialState(durableQueue.nonEmpty))
        }
      }
      .narrow
  }

  private def createInitialState[A: ClassTag](hasDurableQueue: Boolean) = {
    if (hasDurableQueue) None else Some(DurableProducerQueue.State.empty[A])
  }

  private def waitingForStart[A: ClassTag](
      producerId: String,
      context: ActorContext[InternalCommand],
      stashBuffer: StashBuffer[InternalCommand],
      durableQueue: Option[ActorRef[DurableProducerQueue.Command[A]]],
      producer: Option[ActorRef[RequestNext[A]]],
      initialState: Option[DurableProducerQueue.State[A]]): Behavior[InternalCommand] = {

    def becomeActive(p: ActorRef[RequestNext[A]], s: DurableProducerQueue.State[A]): Behavior[InternalCommand] = {
      // resend unconfirmed to self, order doesn't matter for work pulling
      s.unconfirmed.foreach {
        case DurableProducerQueue.MessageSent(oldSeqNr, msg, _, oldConfirmationQualifier) =>
          context.self ! ResendDurableMsg(msg, oldConfirmationQualifier, oldSeqNr)
      }

      val msgAdapter: ActorRef[A] = context.messageAdapter(msg => Msg(msg, wasStashed = false, replyTo = None))
      val requestNext = RequestNext[A](msgAdapter, context.self)
      val b = new WorkPullingProducerController(context, stashBuffer, producerId, p, requestNext, durableQueue)
        .active(State.empty[A].copy(currentSeqNr = s.currentSeqNr))
      stashBuffer.unstashAll(b)
    }

    Behaviors.receiveMessage {
      case start: Start[A] @unchecked =>
        initialState match {
          case Some(s) =>
            becomeActive(start.producer, s)
          case None =>
            // waiting for LoadStateReply
            waitingForStart(producerId, context, stashBuffer, durableQueue, Some(start.producer), initialState)
        }

      case load: LoadStateReply[A] @unchecked =>
        producer match {
          case Some(p) =>
            becomeActive(p, load.state)
          case None =>
            // waiting for LoadStateReply
            waitingForStart(producerId, context, stashBuffer, durableQueue, producer, Some(load.state))
        }

      case LoadStateFailed(attempt) =>
        // FIXME attempt counter, and give up
        context.log.info("LoadState attempt [{}] failed, retrying.", attempt)
        // retry
        askLoadState(context, durableQueue, attempt + 1)
        Behaviors.same

      case other =>
        stashBuffer.stash(other)
        Behaviors.same
    }
  }

  private def askLoadState[A: ClassTag](
      context: ActorContext[InternalCommand],
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]])
      : Option[ActorRef[DurableProducerQueue.Command[A]]] = {

    durableQueueBehavior.map { b =>
      val ref = context.spawn(b, "durable")
      context.watch(ref) // FIXME handle terminated, but it's not supposed to be restarted so death pact is alright
      askLoadState(context, Some(ref), attempt = 1)
      ref
    }
  }

  private def askLoadState[A: ClassTag](
      context: ActorContext[InternalCommand],
      durableQueue: Option[ActorRef[DurableProducerQueue.Command[A]]],
      attempt: Int): Unit = {
    implicit val loadTimeout: Timeout = 3.seconds // FIXME config
    durableQueue.foreach { ref =>
      context.ask[DurableProducerQueue.LoadState[A], DurableProducerQueue.State[A]](
        ref,
        askReplyTo => DurableProducerQueue.LoadState[A](askReplyTo)) {
        case Success(s) => LoadStateReply(s)
        case Failure(_) => LoadStateFailed(attempt) // timeout
      }
    }
  }

}

class WorkPullingProducerController[A: ClassTag](
    context: ActorContext[WorkPullingProducerController.InternalCommand],
    stashBuffer: StashBuffer[WorkPullingProducerController.InternalCommand],
    producerId: String,
    producer: ActorRef[WorkPullingProducerController.RequestNext[A]],
    requestNext: WorkPullingProducerController.RequestNext[A],
    durableQueue: Option[ActorRef[DurableProducerQueue.Command[A]]]) {
  import WorkPullingProducerController._
  import DurableProducerQueue.StoreMessageSent
  import DurableProducerQueue.StoreMessageSentAck
  import DurableProducerQueue.StoreMessageConfirmed
  import DurableProducerQueue.MessageSent

  private val workerRequestNextAdapter: ActorRef[ProducerController.RequestNext[A]] =
    context.messageAdapter(WorkerRequestNext.apply)

  private def active(s: State[A]): Behavior[InternalCommand] = {

    def onMessage(msg: A, wasStashed: Boolean, replyTo: Option[ActorRef[Done]], totalSeqNr: TotalSeqNr): State[A] = {
      val consumersWithDemand = s.out.iterator.filter { case (_, out) => out.askNextTo.isDefined }.toVector
      context.log.infoN(
        "Received Msg [{}], wasStashed [{}], consumersWithDemand [{}], hasRequested [{}]",
        msg,
        wasStashed,
        consumersWithDemand.map(_._1).mkString(", "),
        s.hasRequested)
      if (!s.hasRequested && !wasStashed && durableQueue.isEmpty)
        throw new IllegalStateException(s"Unexpected Msg [$msg], wasn't requested nor unstashed.")

      val selectedWorker =
        if (durableQueue.isDefined) {
          s.preselectedWorkers.get(totalSeqNr) match {
            case Some(PreselectedWorker(outKey, confirmationQualifier)) =>
              s.out.get(outKey) match {
                case Some(out) => Right(outKey -> out)
                case None      =>
                  // the preselected was deregistered in the meantime
                  context.self ! ResendDurableMsg(msg, confirmationQualifier, totalSeqNr)
                  Left(s)
              }
            case None =>
              throw new IllegalStateException(s"Expected preselected worker for seqNr [$totalSeqNr]")
          }
        } else {
          selectWorker() match {
            case Some(w) => Right(w)
            case None =>
              context.log.info("Stash [{}]", msg)
              stashBuffer.stash(Msg(msg, wasStashed = true, replyTo))
              val newRequested = if (wasStashed) s.hasRequested else false
              Left(s.copy(hasRequested = newRequested))
          }
        }

      selectedWorker match {
        case Right((outKey, out)) =>
          val newUnconfirmed = out.unconfirmed :+ Unconfirmed(totalSeqNr, out.seqNr, msg, replyTo)
          val newOut = s.out.updated(outKey, out.copy(unconfirmed = newUnconfirmed, askNextTo = None))
          implicit val askTimeout: Timeout = 60.seconds // FIXME config
          context.ask[ProducerController.MessageWithConfirmation[A], OutSeqNr](
            out.askNextTo.get,
            ProducerController.MessageWithConfirmation(msg, _)) {
            case Success(seqNr) => Ack(outKey, seqNr)
            case Failure(exc)   => throw exc // FIXME what to do for AskTimeout?
          }

          def tellRequestNext(): Unit = {
            context.log.info("RequestNext after Msg [{}]", msg)
            producer ! requestNext
          }

          val hasMoreDemand = consumersWithDemand.size >= 2
          // decision table based on s.hasRequested, wasStashed, hasMoreDemand, stashBuffer.isEmpty
          val newRequested =
            if (s.hasRequested && !wasStashed && hasMoreDemand) {
              // request immediately since more demand
              tellRequestNext()
              true
            } else if (s.hasRequested && !wasStashed && !hasMoreDemand) {
              // wait until more demand
              false
            } else if (!s.hasRequested && wasStashed && hasMoreDemand && stashBuffer.isEmpty) {
              // msg was unstashed, the last from stash
              tellRequestNext()
              true
            } else if (!s.hasRequested && wasStashed && hasMoreDemand && stashBuffer.nonEmpty) {
              // more in stash
              false
            } else if (!s.hasRequested && wasStashed && !hasMoreDemand) {
              // wait until more demand
              false
            } else if (s.hasRequested && wasStashed) {
              // msg was unstashed, but pending request alread in progress
              true
            } else if (durableQueue.isDefined && !s.hasRequested && !wasStashed) {
              // msg ResendDurableMsg, and stashed before storage
              false
            } else {
              throw new IllegalStateException(s"Invalid combination of hasRequested [${s.hasRequested}], " +
              s"wasStashed [$wasStashed], hasMoreDemand [$hasMoreDemand], stashBuffer.isEmpty [${stashBuffer.isEmpty}]")
            }

          s.copy(out = newOut, hasRequested = newRequested, preselectedWorkers = s.preselectedWorkers - totalSeqNr)

        case Left(newState) =>
          newState
      }
    }

    def onAck(outState: OutState[A], confirmedSeqNr: OutSeqNr): Vector[Unconfirmed[A]] = {
      val (confirmed, newUnconfirmed) = outState.unconfirmed.partition {
        case Unconfirmed(_, seqNr, _, _) => seqNr <= confirmedSeqNr
      }

      if (confirmed.nonEmpty) {
        context.log.info("Confirmed seqNr [{}] from worker [{}]", confirmedSeqNr, outState.confirmationQualifier)
        context.log.info(s"Confirmed [$confirmed], remaining unconfirmed [$newUnconfirmed]") // FIXME remove
        confirmed.foreach {
          case Unconfirmed(_, _, _, None) => // no reply
          case Unconfirmed(_, _, _, Some(replyTo)) =>
            replyTo ! Done
        }

        durableQueue.foreach { d =>
          // Storing the confirmedSeqNr can be "write behind", at-least-once delivery
          d ! StoreMessageConfirmed(confirmed.last.totalSeqNr, outState.confirmationQualifier)
        }
      }

      newUnconfirmed
    }

    def workersWithDemand: Vector[(OutKey, OutState[A])] =
      s.out.iterator.filter { case (_, out) => out.askNextTo.isDefined }.toVector

    def selectWorker(): Option[(OutKey, OutState[A])] = {
      val preselected = s.preselectedWorkers.valuesIterator.map(_.outKey).toSet
      val workers = workersWithDemand.filterNot {
        case (outKey, _) => preselected(outKey)
      }
      if (workers.isEmpty) {
        None
      } else {
        val i = ThreadLocalRandom.current().nextInt(workers.size)
        Some(workers(i))
      }
    }

    def onMessageBeforeDurableQueue(msg: A, replyTo: Option[ActorRef[Done]]): Behavior[InternalCommand] = {
      selectWorker() match {
        case Some((outKey, out)) =>
          storeMessageSent(
            MessageSent(s.currentSeqNr, msg, ack = replyTo.isDefined, out.confirmationQualifier),
            attempt = 1)
          val newReplyAfterStore = replyTo match {
            case None    => s.replyAfterStore
            case Some(r) => s.replyAfterStore.updated(s.currentSeqNr, r)
          }
          active(
            s.copy(
              currentSeqNr = s.currentSeqNr + 1,
              preselectedWorkers =
                s.preselectedWorkers.updated(s.currentSeqNr, PreselectedWorker(outKey, out.confirmationQualifier)),
              replyAfterStore = newReplyAfterStore))
        case None =>
          // no demand from any workers, or all already preselected
          context.log.info("Stash before storage [{}]", msg)
          // not stored yet, so don't treat it as stashed
          stashBuffer.stash(Msg(msg, wasStashed = false, replyTo))
          active(s)
      }
    }

    def onResendDurableMsg(resend: ResendDurableMsg[A]): Behavior[InternalCommand] = {
      require(durableQueue.isDefined, "Unexpected ResendDurableMsg when DurableQueue not defined.")
      selectWorker() match {
        case Some((outKey, out)) =>
          storeMessageSent(MessageSent(s.currentSeqNr, resend.msg, false, out.confirmationQualifier), attempt = 1)
          // When StoreMessageSentCompleted (oldConfirmationQualifier, oldSeqNr) confirmation will be stored
          active(
            s.copy(
              currentSeqNr = s.currentSeqNr + 1,
              preselectedWorkers =
                s.preselectedWorkers.updated(s.currentSeqNr, PreselectedWorker(outKey, out.confirmationQualifier)),
              handOver = s.handOver.updated(s.currentSeqNr, HandOver(resend.oldConfirmationQualifier, resend.oldSeqNr))))
        case None =>
          // no demand from any workers, or all already preselected
          context.log.info("Stash before storage [{}]", resend)
          // not stored yet, so don't treat it as stashed
          stashBuffer.stash(resend)
          active(s)
      }
    }

    Behaviors.receiveMessage {
      case Msg(msg: A, wasStashed, replyTo) =>
        if (durableQueue.isEmpty || wasStashed)
          active(onMessage(msg, wasStashed, replyTo, s.currentSeqNr))
        else
          onMessageBeforeDurableQueue(msg, replyTo)

      case MessageWithConfirmation(msg: A, replyTo) =>
        if (durableQueue.isEmpty)
          active(onMessage(msg, wasStashed = false, Some(replyTo), s.currentSeqNr))
        else
          onMessageBeforeDurableQueue(msg, Some(replyTo))

      case m: ResendDurableMsg[A] =>
        onResendDurableMsg(m)

      case StoreMessageSentCompleted(MessageSent(seqNr, m: A, _, _)) =>
        s.replyAfterStore.get(seqNr).foreach { replyTo =>
          context.log.info("Confirmation reply to [{}] after storage", seqNr)
          replyTo ! Done
        }

        s.handOver.get(seqNr).foreach {
          case HandOver(oldConfirmationQualifier, oldSeqNr) =>
            durableQueue.foreach { d =>
              d ! StoreMessageConfirmed(oldSeqNr, oldConfirmationQualifier)
            }
        }

        val newState = onMessage(m, wasStashed = false, replyTo = None, seqNr)
        active(newState.copy(replyAfterStore = newState.replyAfterStore - seqNr, handOver = newState.handOver - seqNr))

      case f: StoreMessageSentFailed[A] =>
        // FIXME attempt counter, and give up
        context.log.info(s"StoreMessageSent seqNr [{}] failed, attempt [{}], retrying.", f.messageSent.seqNr, f.attempt)
        // retry
        storeMessageSent(f.messageSent, attempt = f.attempt + 1)
        Behaviors.same

      case Ack(outKey, confirmedSeqNr) =>
        s.out.get(outKey) match {
          case Some(outState) =>
            val newUnconfirmed = onAck(outState, confirmedSeqNr)
            active(s.copy(out = s.out.updated(outKey, outState.copy(unconfirmed = newUnconfirmed))))
          case None =>
            // obsolete Next, ConsumerController already deregistered
            Behaviors.unhandled
        }

      case curr: CurrentWorkers[A] =>
        // TODO we could also track unreachable workers and avoid them when selecting worker
        val addedWorkers = curr.workers.diff(s.workers)
        val removedWorkers = s.workers.diff(curr.workers)

        val newState = addedWorkers.foldLeft(s) { (acc, c) =>
          val uuid = UUID.randomUUID().toString
          val outKey = s"$producerId-$uuid"
          // FIXME adjust all logging, most should probably be debug
          context.log.info2("Registered worker [{}], with producerId [{}]", c, outKey)
          val p = context.spawn(ProducerController[A](outKey, durableQueueBehavior = None), uuid)
          p ! ProducerController.Start(workerRequestNextAdapter)
          p ! ProducerController.RegisterConsumer(c)
          acc.copy(out = acc.out.updated(outKey, OutState(p, c, 0L, Vector.empty, None)))
        }

        val newState2 = removedWorkers.foldLeft(newState) { (acc, c) =>
          acc.out.find { case (_, outState) => outState.consumerController == c } match {
            case Some((key, outState)) =>
              context.log.info2("Deregistered non-existing worker [{}], with producerId [{}]", c, key)
              context.stop(outState.producerController)
              // resend the unconfirmed, sending to self since order of messages for WorkPulling doesn't matter anyway
              if (outState.unconfirmed.nonEmpty)
                context.log.infoN(
                  "Resending unconfirmed from deregistered worker with producerId [{}], from seqNr [{}] to [{}]",
                  key,
                  outState.unconfirmed.head.outSeqNr,
                  outState.unconfirmed.last.outSeqNr)
              outState.unconfirmed.foreach {
                case Unconfirmed(totalSeqNr, _, msg, replyTo) =>
                  if (durableQueue.isEmpty)
                    context.self ! Msg(msg, wasStashed = true, replyTo)
                  else
                    context.self ! ResendDurableMsg(msg, outState.confirmationQualifier, totalSeqNr)
              }
              acc.copy(out = acc.out - key)

            case None =>
              context.log.info("Deregistered non-existing worker [{}]", c)
              acc
          }
        }

        active(newState2.copy(workers = curr.workers))

      case w: WorkerRequestNext[A] =>
        val next = w.next
        val outKey = next.producerId
        s.out.get(outKey) match {
          case Some(outState) =>
            val confirmedSeqNr = w.next.confirmedSeqNr
            context.log.info2("WorkerRequestNext from [{}], confirmedSeqNr [{}]", w.next.producerId, confirmedSeqNr)

            val newUnconfirmed = onAck(outState, confirmedSeqNr)

            val newOut =
              s.out.updated(
                outKey,
                outState
                  .copy(seqNr = w.next.currentSeqNr, unconfirmed = newUnconfirmed, askNextTo = Some(next.askNextTo)))

            if (stashBuffer.nonEmpty) {
              context.log.info2("Unstash [{}] after WorkerRequestNext from [{}]", stashBuffer.size, w.next.producerId)
              stashBuffer.unstashAll(active(s.copy(out = newOut)))
            } else if (s.hasRequested) {
              active(s.copy(out = newOut))
            } else {
              context.log.info("RequestNext after WorkerRequestNext from [{}]", w.next.producerId)
              producer ! requestNext
              active(s.copy(out = newOut, hasRequested = true))
            }

          case None =>
            // obsolete Next, ConsumerController already deregistered
            Behaviors.unhandled
        }

      case GetWorkerStats(replyTo) =>
        replyTo ! WorkerStats(s.workers.size)
        Behaviors.same

      case RegisterConsumerDone =>
        Behaviors.same

      // FIXME case Start register of new producer, e.g. restart

    }
  }

  private def storeMessageSent(messageSent: MessageSent[A], attempt: Int): Unit = {
    implicit val askTimeout: Timeout = 3.seconds // FIXME config
    context.ask[StoreMessageSent[A], StoreMessageSentAck](
      durableQueue.get,
      askReplyTo => StoreMessageSent(messageSent, askReplyTo)) {
      case Success(_) => StoreMessageSentCompleted(messageSent)
      case Failure(_) => StoreMessageSentFailed(messageSent, attempt) // timeout
    }
  }
}
