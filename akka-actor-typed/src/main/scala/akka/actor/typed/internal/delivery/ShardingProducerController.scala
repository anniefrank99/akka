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

  private final case class Msg[A](msg: ShardingEnvelope[A]) extends InternalCommand

  private final case class Next[A](sendNextTo: ActorRef[A], entityId: String) extends InternalCommand

  private final case class OutState[A](
      producerController: ActorRef[ProducerController.Command[A]],
      sendNextTo: Option[ActorRef[A]],
      // FIXME use better Queue than Vector for this
      pending: Vector[A]) {
    if (sendNextTo.nonEmpty && pending.nonEmpty)
      throw new IllegalStateException("sendNextTo and pending shouldn't both be nonEmpty.")
  }

  private final case class State[A](producerControllersByEntityId: Map[String, OutState[A]], hasRequested: Boolean)

  def apply[A: ClassTag, RequestNext](
      producerId: String,
      requestNextFactory: ActorRef[ShardingEnvelope[A]] ⇒ RequestNext,
      producer: ActorRef[RequestNext],
      region: ActorRef[ShardingEnvelope[ConsumerController.SequencedMessage[A]]]): Behavior[Command[A]] = {
    Behaviors
      .setup[InternalCommand] { context =>
        val msgAdapter: ActorRef[ShardingEnvelope[A]] = context.messageAdapter(envelope ⇒ Msg(envelope))
        val requestNext = requestNextFactory(msgAdapter)
        producer ! requestNext
        new ShardingProducerController(context, producerId, requestNext, producer, region).active(
          State(Map.empty, hasRequested = false))
      }
      .narrow
  }

  // FIXME withConfirmation not implemented yet, see ProducerController.withConfirmation
}

class ShardingProducerController[A: ClassTag, RequestNext](
    context: ActorContext[ShardingProducerController.InternalCommand],
    producerId: String,
    requestNext: RequestNext,
    producer: ActorRef[RequestNext],
    region: ActorRef[ShardingEnvelope[ConsumerController.SequencedMessage[A]]]) {
  import ShardingProducerController._

  private def active(s: State[A]): Behavior[InternalCommand] = {

    Behaviors.receiveMessage {

      case Next(sendNextTo: ActorRef[A] @unchecked, entityId) =>
        s.producerControllersByEntityId.get(entityId) match {
          case Some(out) =>
            if (out.sendNextTo.nonEmpty)
              throw new IllegalStateException(s"Received Next but already has demand for entityId [$entityId]")

            if (out.pending.nonEmpty) {
              sendNextTo ! out.pending.head
              val newProducers = s.producerControllersByEntityId.updated(entityId, out.copy(pending = out.pending.tail))
              active(s.copy(newProducers))
            } else {
              val newProducers =
                s.producerControllersByEntityId.updated(entityId, out.copy(sendNextTo = Some(sendNextTo)))
              if (!s.hasRequested)
                producer ! requestNext // FIXME way to include entityId in requestNext message?
              active(s.copy(newProducers, hasRequested = true))
            }

          case None =>
            // FIXME support termination and removal of ProducerController
            throw new IllegalStateException(s"Unexpected Next for unknown entityId [$entityId]")
        }

      case Msg(ShardingEnvelope(entityId, msg: A)) =>
        val newProducers =
          s.producerControllersByEntityId.get(entityId) match {
            case Some(out @ OutState(_, Some(sendTo), _)) =>
              sendTo ! msg
              s.producerControllersByEntityId.updated(entityId, out.copy(sendNextTo = None))
            case Some(out @ OutState(_, None, pending)) =>
              context.log.info("Buffering message to entityId [{}], buffer size [{}]", entityId, pending.size + 1)
              s.producerControllersByEntityId.updated(entityId, out.copy(pending = pending :+ msg))
            case None =>
              context.log.info("Creating ProducerController for entity [{}]", entityId)
              val send: ConsumerController.SequencedMessage[A] => Unit = { seqMsg =>
                region ! ShardingEnvelope(entityId, seqMsg)
              }
              // FIXME confirmedSeqNr in RequestNextParam not handled yet
              val p = context.spawn(
                ProducerController[A, Next[A]](
                  producerId,
                  (nextParam: ProducerController.RequestNextParam[A]) => Next(nextParam.sendNextTo, entityId),
                  context.self.narrow,
                  send),
                entityId)
              s.producerControllersByEntityId.updated(entityId, OutState(p, None, Vector(msg)))
          }

        // FIXME some way to limit the pending buffers
        val hasMoreDemand = newProducers.valuesIterator.exists(_.sendNextTo.nonEmpty)
        if (hasMoreDemand)
          producer ! requestNext
        active(s.copy(newProducers, hasMoreDemand))

    }
  }

}
