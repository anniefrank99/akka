/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.delivery

import scala.reflect.ClassTag

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.delivery.DurableProducerQueue
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.delivery.internal.ShardingProducerControllerImpl

object ShardingProducerController {

  import ShardingProducerControllerImpl.UnsealedInternalCommand

  type EntityId = String

  sealed trait Command[A] extends UnsealedInternalCommand

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
  final case class MessageWithConfirmation[A](entityId: EntityId, message: A, replyTo: ActorRef[Done])
      extends UnsealedInternalCommand

  final case class RequestNext[A](
      sendNextTo: ActorRef[ShardingEnvelope[A]],
      askNextTo: ActorRef[MessageWithConfirmation[A]],
      entitiesWithDemand: Set[EntityId],
      bufferedForEntitiesWithoutDemand: Map[EntityId, Int]) {

    /** Java API */
    def getEntitiesWithDemand: java.util.Set[String] = {
      import akka.util.ccompat.JavaConverters._
      entitiesWithDemand.asJava
    }

    /** Java API */
    def getBufferedForEntitiesWithoutDemand: java.util.Map[String, Integer] = {
      import akka.util.ccompat.JavaConverters._
      bufferedForEntitiesWithoutDemand.iterator.map { case (k, v) => k -> v.asInstanceOf[Integer] }.toMap.asJava
    }
  }

  def apply[A: ClassTag](
      producerId: String,
      region: ActorRef[ShardingEnvelope[ConsumerController.SequencedMessage[A]]],
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]]): Behavior[Command[A]] = {
    ShardingProducerControllerImpl(producerId, region, durableQueueBehavior)
  }

  // FIXME javadsl create

}
