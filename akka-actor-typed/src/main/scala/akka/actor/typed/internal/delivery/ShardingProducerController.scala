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
  final case class MessageWithConfirmation[A](message: ShardingEnvelope[A], replyTo: ActorRef[Done])
      extends InternalCommand

  private final case class Ack(replyTo: ActorRef[Done]) extends InternalCommand

  // FIXME include Set(entityId) that have demand in requestNext message? Include number of buffered per entityId.
  final case class RequestNext[A](
      sendNextTo: ActorRef[ShardingEnvelope[A]],
      askNextTo: ActorRef[MessageWithConfirmation[A]])

  private final case class WrappedRequestNext[A](next: ProducerController.RequestNext[A]) extends InternalCommand

  private final case class Msg[A](msg: ShardingEnvelope[A]) extends InternalCommand

  private final case class OutState[A](
      producerController: ActorRef[ProducerController.Command[A]],
      nextTo: Option[ProducerController.RequestNext[A]],
      // FIXME use better Queue than Vector for this
      pending: Vector[(A, Option[ActorRef[Done]])]) {
    if (nextTo.nonEmpty && pending.nonEmpty)
      throw new IllegalStateException("nextTo and pending shouldn't both be nonEmpty.")
  }

  private final case class State[A](out: Map[String, OutState[A]], hasRequested: Boolean)

  def apply[A: ClassTag](
      producerId: String,
      region: ActorRef[ShardingEnvelope[ConsumerController.SequencedMessage[A]]]): Behavior[Command[A]] = {
    Behaviors
      .setup[InternalCommand] { context =>
        context.setLoggerName(classOf[ShardingProducerController[_]])
        Behaviors.receiveMessagePartial {
          case start: Start[A] @unchecked =>
            val msgAdapter: ActorRef[ShardingEnvelope[A]] = context.messageAdapter(msg => Msg(msg))
            val requestNext = RequestNext(msgAdapter, context.self)
            start.producer ! requestNext
            new ShardingProducerController(context, producerId, start.producer, requestNext, region)
              .active(State(Map.empty, hasRequested = false))
        }
      }
      .narrow
  }

}

class ShardingProducerController[A: ClassTag](
    context: ActorContext[ShardingProducerController.InternalCommand],
    producerId: String,
    producer: ActorRef[ShardingProducerController.RequestNext[A]],
    requestNext: ShardingProducerController.RequestNext[A],
    region: ActorRef[ShardingEnvelope[ConsumerController.SequencedMessage[A]]]) {
  import ShardingProducerController._

  private val requestNextAdapter: ActorRef[ProducerController.RequestNext[A]] =
    context.messageAdapter(WrappedRequestNext.apply)

  private def active(s: State[A]): Behavior[InternalCommand] = {

    def onMsg(entityId: String, msg: A, replyTo: Option[ActorRef[Done]]): Behavior[InternalCommand] = {
      val outKey = s"$producerId-$entityId"
      val newProducers =
        s.out.get(outKey) match {
          case Some(out @ OutState(_, Some(nextTo), _)) =>
            send(msg, replyTo, nextTo)
            s.out.updated(outKey, out.copy(nextTo = None))
          case Some(out @ OutState(_, None, pending)) =>
            context.log.info("Buffering message to entityId [{}], buffer size [{}]", entityId, pending.size + 1)
            s.out.updated(outKey, out.copy(pending = pending :+ msg -> replyTo))
          case None =>
            context.log.info("Creating ProducerController for entity [{}]", entityId)
            val send: ConsumerController.SequencedMessage[A] => Unit = { seqMsg =>
              region ! ShardingEnvelope(entityId, seqMsg)
            }
            // FIXME support DurableProducerQueue
            val p = context.spawn(ProducerController[A](outKey, durableQueueBehavior = None, send), entityId)
            p ! ProducerController.Start(requestNextAdapter)
            s.out.updated(outKey, OutState(p, None, Vector(msg -> replyTo)))
        }

      // FIXME some way to limit the pending buffers.
      val hasMoreDemand = newProducers.valuesIterator.exists(_.nextTo.nonEmpty)
      if (hasMoreDemand)
        producer ! requestNext
      active(s.copy(newProducers, hasMoreDemand))
    }

    Behaviors.receiveMessage {

      case w: WrappedRequestNext[A] =>
        val next = w.next
        val outKey = next.producerId
        s.out.get(outKey) match {
          case Some(out) =>
            if (out.nextTo.nonEmpty)
              throw new IllegalStateException(s"Received RequestNext but already has demand for [$outKey]")

            if (out.pending.nonEmpty) {
              val (msg, replyTo) = out.pending.head
              send(msg, replyTo, next)
              val newProducers = s.out.updated(outKey, out.copy(pending = out.pending.tail))
              active(s.copy(newProducers))
            } else {
              val newProducers =
                s.out.updated(outKey, out.copy(nextTo = Some(next)))
              if (!s.hasRequested)
                producer ! requestNext
              active(s.copy(newProducers, hasRequested = true))
            }

          case None =>
            // FIXME support termination and removal of ProducerController
            throw new IllegalStateException(s"Unexpected RequestNext for unknown [$outKey]")
        }

      case Msg(ShardingEnvelope(entityId, msg: A)) =>
        onMsg(entityId, msg, None)

      case MessageWithConfirmation(ShardingEnvelope(entityId, msg: A), replyTo) =>
        onMsg(entityId, msg, Some(replyTo))

      case Ack(replyTo) =>
        replyTo ! Done
        Behaviors.same
    }
  }

  private def send(msg: A, replyTo: Option[ActorRef[Done]], nextTo: ProducerController.RequestNext[A]): Unit = {
    replyTo match {
      case None =>
        nextTo.sendNextTo ! msg
      case Some(r) =>
        implicit val askTimeout: Timeout = 60.seconds // FIXME config
        context.ask[ProducerController.MessageWithConfirmation[A], Long](
          nextTo.askNextTo,
          ProducerController.MessageWithConfirmation(msg, _)) {
          case Success(_) => Ack(r)
          case Failure(exc) =>
            throw exc // FIXME what to do for AskTimeout? can probably be ignored since actual producer ask will have it's own timeout
        }
    }
  }
}
