/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.internal.delivery.SimuatedSharding.ShardingEnvelope
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.StashBuffer
import akka.util.Timeout

// FIXME this will be moved to akka-cluster-sharding-typed

object ShardingProducerController {

  sealed trait InternalCommand

  sealed trait Command[A] extends InternalCommand

  final case class Start[A](producer: ActorRef[RequestNext[A]]) extends Command[A]

  /**
   * For sending confirmation message back to the producer when the message has been confirmed.
   * Typically used with `ask` from the producer.
   *
   * If `DurableProducerQueue` is used the confirmation reply is sent when the message has been
   * successfully stored, meaning that the actual delivery to the consumer may happen later.
   * If `DurableProducerQueue` is not used the confirmation reply is sent when the message has been
   * fully delivered, processed, and confirmed by the consumer.
   */
  final case class MessageWithConfirmation[A](entityId: String, message: A, replyTo: ActorRef[Done])
      extends InternalCommand

  private final case class Ack(outKey: String, confirmedSeqNr: Long) extends InternalCommand

  final case class RequestNext[A](
      sendNextTo: ActorRef[ShardingEnvelope[A]],
      askNextTo: ActorRef[MessageWithConfirmation[A]],
      entitiesWithDemand: Set[String],
      bufferedForEntitiesWithoutDemand: Map[String, Int])

  private final case class WrappedRequestNext[A](next: ProducerController.RequestNext[A]) extends InternalCommand

  private final case class Msg[A](envelope: ShardingEnvelope[A]) extends InternalCommand

  private case class LoadStateReply[A](state: DurableProducerQueue.State[A]) extends InternalCommand
  private case class LoadStateFailed(attempt: Int) extends InternalCommand
  private case class StoreMessageSentReply(ack: DurableProducerQueue.StoreMessageSentAck)
  private case class StoreMessageSentFailed[A](messageSent: DurableProducerQueue.MessageSent[A], attempt: Int)
      extends InternalCommand
  private case class StoreMessageSentCompleted[A](messageSent: DurableProducerQueue.MessageSent[A])
      extends InternalCommand

  private final case class OutState[A](
      entityId: String,
      producerController: ActorRef[ProducerController.Command[A]],
      nextTo: Option[ProducerController.RequestNext[A]],
      buffered: Vector[Buffered[A]],
      seqNr: Long,
      unconfirmed: Vector[Unconfirmed[A]]) {
    if (nextTo.nonEmpty && buffered.nonEmpty)
      throw new IllegalStateException("nextTo and buffered shouldn't both be nonEmpty.")
  }

  private final case class Buffered[A](totalSeqNr: Long, msg: A, replyTo: Option[ActorRef[Done]])

  private final case class Unconfirmed[A](totalSeqNr: Long, outSeqNr: Long, replyTo: Option[ActorRef[Done]])

  private final case class State[A](
      currentSeqNr: Long,
      out: Map[String, OutState[A]],
      // replyAfterStore is used when durableQueue is enabled, otherwise they are tracked in OutState
      replyAfterStore: Map[Long, ActorRef[Done]])

  def apply[A: ClassTag](
      producerId: String,
      region: ActorRef[ShardingEnvelope[ConsumerController.SequencedMessage[A]]],
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]]): Behavior[Command[A]] = {
    Behaviors
      .withStash[InternalCommand](1000) { stashBuffer => // FIXME stash config
        Behaviors.setup[InternalCommand] { context =>
          context.setLoggerName(classOf[ShardingProducerController[_]])

          val durableQueue = askLoadState(context, durableQueueBehavior)

          waitingForStart(
            producerId,
            context,
            stashBuffer,
            region,
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
      region: ActorRef[ShardingEnvelope[ConsumerController.SequencedMessage[A]]],
      durableQueue: Option[ActorRef[DurableProducerQueue.Command[A]]],
      producer: Option[ActorRef[RequestNext[A]]],
      initialState: Option[DurableProducerQueue.State[A]]): Behavior[InternalCommand] = {

    def becomeActive(p: ActorRef[RequestNext[A]], s: DurableProducerQueue.State[A]): Behavior[InternalCommand] = {
      // resend unconfirmed before other stashed messages
      Behaviors.withStash[InternalCommand](1000) { newStashBuffer => // FIXME stash config
        Behaviors.setup { _ =>
          s.unconfirmed.foreach { m =>
            newStashBuffer.stash(Msg(ShardingEnvelope(m.confirmationQualifier, m.msg)))
          }
          // append other stashed messages after the unconfirmed
          stashBuffer.foreach(newStashBuffer.stash)

          val msgAdapter: ActorRef[ShardingEnvelope[A]] = context.messageAdapter(msg => Msg(msg))
          if (s.unconfirmed.isEmpty)
            p ! RequestNext(msgAdapter, context.self, Set.empty, Map.empty)
          val b = new ShardingProducerController(context, producerId, p, msgAdapter, region, durableQueue)
            .active(State(s.currentSeqNr, Map.empty, Map.empty))

          newStashBuffer.unstashAll(b)
        }
      }
    }

    Behaviors.receiveMessage {
      case start: Start[A] @unchecked =>
        initialState match {
          case Some(s) =>
            becomeActive(start.producer, s)
          case None =>
            // waiting for LoadStateReply
            waitingForStart(producerId, context, stashBuffer, region, durableQueue, Some(start.producer), initialState)
        }

      case load: LoadStateReply[A] @unchecked =>
        producer match {
          case Some(p) =>
            becomeActive(p, load.state)
          case None =>
            // waiting for LoadStateReply
            waitingForStart(producerId, context, stashBuffer, region, durableQueue, producer, Some(load.state))
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

class ShardingProducerController[A: ClassTag](
    context: ActorContext[ShardingProducerController.InternalCommand],
    producerId: String,
    producer: ActorRef[ShardingProducerController.RequestNext[A]],
    msgAdapter: ActorRef[ShardingEnvelope[A]],
    region: ActorRef[ShardingEnvelope[ConsumerController.SequencedMessage[A]]],
    durableQueue: Option[ActorRef[DurableProducerQueue.Command[A]]]) {
  import ShardingProducerController._
  import DurableProducerQueue.StoreMessageSent
  import DurableProducerQueue.StoreMessageSentAck
  import DurableProducerQueue.StoreMessageConfirmed
  import DurableProducerQueue.MessageSent

  private val requestNextAdapter: ActorRef[ProducerController.RequestNext[A]] =
    context.messageAdapter(WrappedRequestNext.apply)

  private def active(s: State[A]): Behavior[InternalCommand] = {

    def onMessage(
        entityId: String,
        msg: A,
        replyTo: Option[ActorRef[Done]],
        totalSeqNr: Long,
        newReplyAfterStore: Map[Long, ActorRef[Done]]): Behavior[InternalCommand] = {

      val outKey = s"$producerId-$entityId"
      val newState =
        s.out.get(outKey) match {
          case Some(out @ OutState(_, _, Some(nextTo), _, _, _)) =>
            // there is demand, send immediately
            send(msg, outKey, out.seqNr, nextTo)
            val newUnconfirmed = out.unconfirmed :+ Unconfirmed(totalSeqNr, out.seqNr, replyTo)
            s.copy(
              out = s.out.updated(outKey, out.copy(seqNr = out.seqNr + 1, nextTo = None, unconfirmed = newUnconfirmed)),
              replyAfterStore = newReplyAfterStore)
          case Some(out @ OutState(_, _, None, buffered, _, _)) =>
            // no demand, buffer
            // FIXME limit the buffers.
            context.log.info("Buffering message to entityId [{}], buffer size [{}]", entityId, buffered.size + 1)
            val newBuffered = buffered :+ Buffered(totalSeqNr, msg, replyTo)
            val newS =
              s.copy(
                out = s.out.updated(outKey, out.copy(buffered = newBuffered)),
                replyAfterStore = newReplyAfterStore)
            // send an updated RequestNext to indicate buffer usage
            producer ! createRequestNext(newS)
            newS
          case None =>
            context.log.info("Creating ProducerController for entity [{}]", entityId)
            val send: ConsumerController.SequencedMessage[A] => Unit = { seqMsg =>
              region ! ShardingEnvelope(entityId, seqMsg)
            }
            val p = context.spawn(ProducerController[A](outKey, durableQueueBehavior = None, send), entityId)
            p ! ProducerController.Start(requestNextAdapter)
            s.copy(
              out = s.out.updated(
                outKey,
                OutState(entityId, p, None, Vector(Buffered(totalSeqNr, msg, replyTo)), 1L, Vector.empty)),
              replyAfterStore = newReplyAfterStore)
        }

      active(newState)
    }

    def onAck(outState: OutState[A], confirmedSeqNr: Long): Vector[Unconfirmed[A]] = {
      val (confirmed, newUnconfirmed) = outState.unconfirmed.partition {
        case Unconfirmed(_, seqNr, _) => seqNr <= confirmedSeqNr
      }

      if (confirmed.nonEmpty) {
        context.log.info("Confirmed seqNr [{}] from entity [{}]", confirmedSeqNr, outState.entityId)
        context.log.info(s"Confirmed [$confirmed], remaining unconfirmed [$newUnconfirmed]") // FIXME remove
        confirmed.foreach {
          case Unconfirmed(_, _, None) => // no reply
          case Unconfirmed(_, _, Some(replyTo)) =>
            replyTo ! Done
        }

        durableQueue.foreach { d =>
          // Storing the confirmedSeqNr can be "write behind", at-least-once delivery
          d ! StoreMessageConfirmed(confirmed.last.totalSeqNr, outState.entityId)
        }
      }

      newUnconfirmed
    }

    Behaviors.receiveMessage {

      case msg: Msg[A] =>
        if (durableQueue.isEmpty) {
          // currentSeqNr is only updated when durableQueue is enabled
          onMessage(msg.envelope.entityId, msg.envelope.message, None, s.currentSeqNr, s.replyAfterStore)
        } else {
          storeMessageSent(MessageSent(s.currentSeqNr, msg.envelope.message, false, msg.envelope.entityId), attempt = 1)
          active(s.copy(currentSeqNr = s.currentSeqNr + 1))
        }

      case MessageWithConfirmation(entityId, message: A, replyTo) =>
        if (durableQueue.isEmpty) {
          onMessage(entityId, message, Some(replyTo), s.currentSeqNr, s.replyAfterStore)
        } else {
          storeMessageSent(MessageSent(s.currentSeqNr, message, ack = true, entityId), attempt = 1)
          val newReplyAfterStore = s.replyAfterStore.updated(s.currentSeqNr, replyTo)
          active(s.copy(currentSeqNr = s.currentSeqNr + 1, replyAfterStore = newReplyAfterStore))
        }

      case StoreMessageSentCompleted(MessageSent(seqNr, msg: A, _, entityId)) =>
        s.replyAfterStore.get(seqNr).foreach { replyTo =>
          context.log.info("Confirmation reply to [{}] after storage", seqNr)
          replyTo ! Done
        }
        val newReplyAfterStore = s.replyAfterStore - seqNr

        onMessage(entityId, msg, replyTo = None, seqNr, newReplyAfterStore)

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

      case w: WrappedRequestNext[A] =>
        val next = w.next
        val outKey = next.producerId
        s.out.get(outKey) match {
          case Some(out) =>
            if (out.nextTo.nonEmpty)
              throw new IllegalStateException(s"Received RequestNext but already has demand for [$outKey]")

            val confirmedSeqNr = w.next.confirmedSeqNr
            context.log.info("RequestNext from [{}], confirmed seqNr [{}]", out.entityId, confirmedSeqNr)
            val newUnconfirmed = onAck(out, confirmedSeqNr)

            if (out.buffered.nonEmpty) {
              val buf = out.buffered.head
              send(buf.msg, outKey, out.seqNr, next)
              val newUnconfirmed2 = newUnconfirmed :+ Unconfirmed(buf.totalSeqNr, out.seqNr, buf.replyTo)
              val newProducers = s.out.updated(
                outKey,
                out.copy(
                  seqNr = out.seqNr + 1,
                  nextTo = None,
                  unconfirmed = newUnconfirmed2,
                  buffered = out.buffered.tail))
              active(s.copy(out = newProducers))
            } else {
              val newProducers =
                s.out.updated(outKey, out.copy(nextTo = Some(next), unconfirmed = newUnconfirmed))
              val newState = s.copy(out = newProducers)
              // send an updated RequestNext
              producer ! createRequestNext(newState)
              active(newState)
            }

          case None =>
            // FIXME support termination and removal of ProducerController
            throw new IllegalStateException(s"Unexpected RequestNext for unknown [$outKey]")
        }
      // FIXME case Start register of new produce, e.g. restart
    }
  }

  private def createRequestNext(s: State[A]) = {
    val entitiesWithDemand = s.out.valuesIterator.collect { case out if out.nextTo.nonEmpty => out.entityId }.toSet
    val bufferedForEntitesWithoutDemand = s.out.valuesIterator.collect {
      case out if out.nextTo.isEmpty => out.entityId -> out.buffered.size
    }.toMap
    RequestNext(msgAdapter, context.self, entitiesWithDemand, bufferedForEntitesWithoutDemand)
  }

  private def send(msg: A, outKey: String, outSeqNr: Long, nextTo: ProducerController.RequestNext[A]): Unit = {
    context.log.info("send [{}], outSeqNr [{}]", msg, outSeqNr) // FIXME remove
    implicit val askTimeout: Timeout = 60.seconds // FIXME config
    context.ask[ProducerController.MessageWithConfirmation[A], Long](
      nextTo.askNextTo,
      ProducerController.MessageWithConfirmation(msg, _)) {
      case Success(seqNr) =>
        if (seqNr != outSeqNr)
          context.log.error("Inconsistent Ack seqNr [{}] != [{}]", seqNr, outSeqNr)
        Ack(outKey, outSeqNr)
      case Failure(exc) =>
        throw exc // FIXME what to do for AskTimeout? can probably be ignored since actual producer ask will have it's own timeout
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
