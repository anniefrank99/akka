/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.StashBuffer
import akka.util.Timeout

object WorkPullingProducerController {

  sealed trait InternalCommand

  sealed trait Command[A] extends InternalCommand

  final case class Start[A](producer: ActorRef[RequestNext[A]]) extends Command[A]

  final case class RequestNext[A](sendNextTo: ActorRef[A], askNextTo: ActorRef[MessageWithConfirmation[A]])

  /**
   * For sending confirmation message back to the producer when the message has been fully delivered, processed,
   * and confirmed by the consumer. Typically used with `ask` from the producer.
   */
  final case class MessageWithConfirmation[A](message: A, replyTo: ActorRef[Long]) extends InternalCommand

  final case class GetWorkerStats[A](replyTo: ActorRef[WorkerStats]) extends Command[A]

  final case class WorkerStats(numberOfWorkers: Int)

  private final case class WorkerRequestNext[A](next: ProducerController.RequestNext[A]) extends InternalCommand

  private final case class Ack(outKey: String, confirmedSeqNr: Long) extends InternalCommand

  private case object RegisterConsumerDone extends InternalCommand

  private final case class OutState[A](
      producerController: ActorRef[ProducerController.Command[A]],
      consumerController: ActorRef[ConsumerController.Command[A]],
      seqNr: Long,
      unconfirmed: Vector[Unconfirmed[A]],
      askNextTo: Option[ActorRef[ProducerController.MessageWithConfirmation[A]]])

  private final case class Unconfirmed[A](seqNr: Long, msg: A, replyTo: Option[ActorRef[Long]])

  private object State {
    def empty[A]: State[A] = State(Set.empty, Map.empty, 0, hasRequested = false)
  }

  private final case class State[A](
      workers: Set[ActorRef[ConsumerController.Command[A]]],
      out: Map[String, OutState[A]],
      producerIdCount: Long,
      hasRequested: Boolean)

  // registration of workers via Receptionist
  private final case class CurrentWorkers[A](workers: Set[ActorRef[ConsumerController.Command[A]]])
      extends InternalCommand

  private final case class Msg[A](msg: A, wasStashed: Boolean, replyTo: Option[ActorRef[Long]]) extends InternalCommand

  def apply[A: ClassTag](
      producerId: String,
      workerServiceKey: ServiceKey[ConsumerController.Command[A]]): Behavior[Command[A]] = {
    Behaviors
      .withStash[InternalCommand](1000) { stashBuffer => // FIXME stash config
        Behaviors.setup[InternalCommand] { context =>
          context.setLoggerName(classOf[WorkPullingProducerController[_]])
          val listingAdapter = context.messageAdapter[Receptionist.Listing](listing =>
            CurrentWorkers[A](listing.allServiceInstances(workerServiceKey)))
          context.system.receptionist ! Receptionist.Subscribe(workerServiceKey, listingAdapter)

          Behaviors.receiveMessage {
            case start: Start[A] @unchecked =>
              val msgAdapter: ActorRef[A] = context.messageAdapter(msg => Msg(msg, wasStashed = false, replyTo = None))
              val requestNext = RequestNext[A](msgAdapter, context.self)
              val b = new WorkPullingProducerController(context, stashBuffer, producerId, start.producer, requestNext)
                .active(State.empty)
              stashBuffer.unstashAll(b)

            case other =>
              stashBuffer.stash(other)
              Behaviors.same
          }
        }
      }
      .narrow
  }

  // FIXME MessageWithConfirmation not implemented yet, see ProducerController

}

class WorkPullingProducerController[A: ClassTag](
    context: ActorContext[WorkPullingProducerController.InternalCommand],
    stashBuffer: StashBuffer[WorkPullingProducerController.InternalCommand],
    producerId: String,
    producer: ActorRef[WorkPullingProducerController.RequestNext[A]],
    requestNext: WorkPullingProducerController.RequestNext[A]) {
  import WorkPullingProducerController._

  private val workerRequestNextAdapter: ActorRef[ProducerController.RequestNext[A]] =
    context.messageAdapter(WorkerRequestNext.apply)

  private def active(s: State[A]): Behavior[InternalCommand] = {
    def onMessage(msg: A, wasStashed: Boolean, replyTo: Option[ActorRef[Long]]): Behavior[InternalCommand] = {
      val consumersWithDemand = s.out.iterator.filter { case (_, out) => out.askNextTo.isDefined }.toVector
      context.log.infoN(
        "Received Msg [{}], wasStashed [{}], consumersWithDemand [{}]",
        msg,
        wasStashed,
        consumersWithDemand.map(_._1).mkString(", "))
      if (consumersWithDemand.isEmpty) {
        context.log.info("Stash [{}]", msg)
        stashBuffer.stash(Msg(msg, wasStashed = true, replyTo))
        Behaviors.same
      } else {
        val i = ThreadLocalRandom.current().nextInt(consumersWithDemand.size)
        val (outKey, out) = consumersWithDemand(i)
        val newUnconfirmed = out.unconfirmed :+ Unconfirmed(out.seqNr, msg, replyTo)
        val newOut = s.out.updated(outKey, out.copy(unconfirmed = newUnconfirmed, askNextTo = None))
        implicit val askTimeout: Timeout = 60.seconds // FIXME config
        context.ask[ProducerController.MessageWithConfirmation[A], Long](
          out.askNextTo.get,
          ProducerController.MessageWithConfirmation(msg, _)) {
          case Success(seqNr) => Ack(outKey, seqNr)
          case Failure(exc)   => throw exc // FIXME what to do for AskTimeout?
        }

        val hasMoreDemand = consumersWithDemand.size >= 2
        val requested =
          if (hasMoreDemand && (!wasStashed || stashBuffer.isEmpty)) {
            context.log.info("RequestNext after Msg [{}]", msg)
            producer ! requestNext
            true
          } else if (wasStashed) {
            s.hasRequested
          } else {
            false
          }
        active(s.copy(out = newOut, hasRequested = requested))
      }
    }

    def onAck(outState: OutState[A], confirmedSeqNr: Long): Vector[Unconfirmed[A]] = {
      val (replies, newUnconfirmed) = outState.unconfirmed.partition {
        case Unconfirmed(seqNr, _, _) => seqNr <= confirmedSeqNr
      }
      replies.foreach {
        case Unconfirmed(_, _, None) => // no reply
        case Unconfirmed(_, _, Some(replyTo)) =>
          replyTo ! -1 // FIXME use another global seqNr counter or reply with Done
      }
      newUnconfirmed
    }

    Behaviors.receiveMessage {
      case Msg(msg: A, wasStashed, replyTo) =>
        onMessage(msg, wasStashed, replyTo)

      case MessageWithConfirmation(m: A, replyTo) =>
        onMessage(m, wasStashed = false, Some(replyTo))

      case Ack(outKey, confirmedSeqNr) =>
        s.out.get(outKey) match {
          case Some(outState) =>
            val newUnconfirmed = onAck(outState, confirmedSeqNr)
            active(s.copy(out = s.out.updated(outKey, outState.copy(unconfirmed = newUnconfirmed))))
          case None =>
            // obsolete Next, ConsumerController already deregistered
            Behaviors.unhandled
        }

      case curr: CurrentWorkers[A] @unchecked =>
        // FIXME adjust all logging, most should probably be debug
        val addedWorkers = curr.workers.diff(s.workers)
        val removedWorkers = s.workers.diff(curr.workers)

        val newState = addedWorkers.foldLeft(s) { (acc, c) =>
          context.log.info("Registered worker [{}]", c)
          val newProducerIdCount = acc.producerIdCount + 1
          val outKey = s"$producerId-$newProducerIdCount"
          // FIXME support DurableProducerQueue
          val p =
            context.spawnAnonymous(ProducerController[A](outKey, durableQueueBehavior = None))
          p ! ProducerController.Start(workerRequestNextAdapter)
          p ! ProducerController.RegisterConsumer(c)
          acc.copy(
            out = s.out.updated(outKey, OutState(p, c, 0L, Vector.empty, None)),
            producerIdCount = newProducerIdCount)
        }

        val newState2 = removedWorkers.foldLeft(newState) { (acc, c) =>
          context.log.info("Deregistered worker [{}]", c)
          acc.out.find { case (_, outState) => outState.consumerController == c } match {
            case Some((key, outState)) =>
              context.stop(outState.producerController)
              // resend the unconfirmed, sending to self since order of messages for WorkPulling doesn't matter anyway
              if (outState.unconfirmed.nonEmpty)
                context.log.infoN(
                  "Resending unconfirmed from deregistered worker [{}], from seqNr [{}] to [{}]",
                  c,
                  outState.unconfirmed.head.seqNr,
                  outState.unconfirmed.last.seqNr)
              outState.unconfirmed.foreach {
                case Unconfirmed(_, msg, replyTo) => context.self ! Msg(msg, wasStashed = true, replyTo)
              }
              acc.copy(out = acc.out - key)

            case None => acc
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
              context.log.info("Unstash [{}]", stashBuffer.size)
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

    }
  }
}
