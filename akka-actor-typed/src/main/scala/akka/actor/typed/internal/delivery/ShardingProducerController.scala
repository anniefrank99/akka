/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import scala.reflect.ClassTag

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.internal.delivery.SimuatedSharding.ShardingEnvelope
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors

// FIXME this will be moved to akka-cluster-sharding-typed

// FIXME there should also be a ShardingConsumerController, see TestShardingConsumer in ReliableDeliverySpec

object ShardingProducerController {

  sealed trait InternalCommand

  sealed trait Command[A] extends InternalCommand

  final case class Start[A](producer: ActorRef[RequestNext[A]]) extends Command[A]

  final case class RequestNext[A](sendNextTo: ActorRef[ShardingEnvelope[A]])

  private final case class WrappedRequestNext[A](next: ProducerController.RequestNext[A]) extends InternalCommand

  private final case class Msg[A](msg: ShardingEnvelope[A]) extends InternalCommand

  private final case class OutState[A](
      producerController: ActorRef[ProducerController.Command[A]],
      sendNextTo: Option[ActorRef[A]],
      // FIXME use better Queue than Vector for this
      pending: Vector[A]) {
    if (sendNextTo.nonEmpty && pending.nonEmpty)
      throw new IllegalStateException("sendNextTo and pending shouldn't both be nonEmpty.")
  }

  private final case class State[A](out: Map[String, OutState[A]], hasRequested: Boolean)

  def apply[A: ClassTag](
      producerId: String,
      region: ActorRef[ShardingEnvelope[ConsumerController.SequencedMessage[A]]]): Behavior[Command[A]] = {
    Behaviors
      .setup[InternalCommand] { context =>
        Behaviors.receiveMessagePartial {
          case start: Start[A] @unchecked =>
            val msgAdapter: ActorRef[ShardingEnvelope[A]] = context.messageAdapter(msg => Msg(msg))
            val requestNext = RequestNext(msgAdapter)
            start.producer ! requestNext
            new ShardingProducerController(context, producerId, start.producer, requestNext, region)
              .active(State(Map.empty, hasRequested = false))
        }
      }
      .narrow
  }

  // FIXME MessageWithConfirmation not implemented yet, see ProducerController
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

    Behaviors.receiveMessage {

      case w: WrappedRequestNext[A] =>
        val next = w.next
        val outKey = next.producerId
        s.out.get(outKey) match {
          case Some(out) =>
            if (out.sendNextTo.nonEmpty)
              throw new IllegalStateException(s"Received RequestNext but already has demand for [$outKey]")

            if (out.pending.nonEmpty) {
              next.sendNextTo ! out.pending.head
              val newProducers = s.out.updated(outKey, out.copy(pending = out.pending.tail))
              active(s.copy(newProducers))
            } else {
              val newProducers =
                s.out.updated(outKey, out.copy(sendNextTo = Some(next.sendNextTo)))
              if (!s.hasRequested)
                producer ! requestNext // FIXME way to include entityId in requestNext message?
              active(s.copy(newProducers, hasRequested = true))
            }

          case None =>
            // FIXME support termination and removal of ProducerController
            throw new IllegalStateException(s"Unexpected RequestNext for unknown [$outKey]")
        }

      case Msg(ShardingEnvelope(entityId, msg: A)) =>
        val outKey = s"$producerId-$entityId"
        val newProducers =
          s.out.get(outKey) match {
            case Some(out @ OutState(_, Some(sendTo), _)) =>
              sendTo ! msg
              s.out.updated(outKey, out.copy(sendNextTo = None))
            case Some(out @ OutState(_, None, pending)) =>
              context.log.info("Buffering message to entityId [{}], buffer size [{}]", entityId, pending.size + 1)
              s.out.updated(outKey, out.copy(pending = pending :+ msg))
            case None =>
              context.log.info("Creating ProducerController for entity [{}]", entityId)
              val send: ConsumerController.SequencedMessage[A] => Unit = { seqMsg =>
                region ! ShardingEnvelope(entityId, seqMsg)
              }
              // FIXME support durableState
              val p = context.spawn(ProducerController[A](outKey, durableState = None, send), entityId)
              p ! ProducerController.Start(requestNextAdapter)
              s.out.updated(outKey, OutState(p, None, Vector(msg)))
          }

        // FIXME some way to limit the pending buffers
        val hasMoreDemand = newProducers.valuesIterator.exists(_.sendNextTo.nonEmpty)
        if (hasMoreDemand)
          producer ! requestNext
        active(s.copy(newProducers, hasMoreDemand))

    }
  }

}
