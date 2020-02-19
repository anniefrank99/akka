/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.delivery

import java.util.Optional

import scala.compat.java8.OptionConverters._
import scala.reflect.ClassTag

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.delivery.DurableProducerQueue
import akka.actor.typed.delivery.ProducerController
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.delivery.internal.ShardingProducerControllerImpl
import com.typesafe.config.Config

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

  object Settings {

    /**
     * Scala API: Factory method from config `akka.reliable-delivery.sharding.producer-controller`
     * of the `ActorSystem`.
     */
    def apply(system: ActorSystem[_]): Settings =
      apply(system.settings.config.getConfig("akka.reliable-delivery.sharding.producer-controller"))

    /**
     * Scala API: Factory method from Config corresponding to
     * `akka.reliable-delivery.sharding.producer-controller`.
     */
    def apply(config: Config): Settings = {
      new Settings(bufferSize = config.getInt("buffer-size"), ProducerController.Settings(config))
    }

    /**
     * Java API: Factory method from config `akka.reliable-delivery.sharding.producer-controller`
     * of the `ActorSystem`.
     */
    def create(system: ActorSystem[_]): Settings =
      apply(system)

    /**
     * java API: Factory method from Config corresponding to
     * `akka.reliable-delivery.sharding.producer-controller`.
     */
    def create(config: Config): Settings =
      apply(config)
  }

  final class Settings private (val bufferSize: Int, val producerControllerSettings: ProducerController.Settings) {

    def withBufferSize(newBufferSize: Int): Settings =
      copy(bufferSize = newBufferSize)

    def withProducerControllerSettings(newProducerControllerSettings: ProducerController.Settings): Settings =
      copy(producerControllerSettings = newProducerControllerSettings)

    /**
     * Private copy method for internal use only.
     */
    private def copy(
        bufferSize: Int = bufferSize,
        producerControllerSettings: ProducerController.Settings = producerControllerSettings) =
      new Settings(bufferSize, producerControllerSettings)

    override def toString: String =
      s"Settings($bufferSize,$producerControllerSettings)"
  }

  def apply[A: ClassTag](
      producerId: String,
      region: ActorRef[ShardingEnvelope[ConsumerController.SequencedMessage[A]]],
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]]): Behavior[Command[A]] = {
    Behaviors.setup { context =>
      ShardingProducerControllerImpl(producerId, region, durableQueueBehavior, Settings(context.system))
    }
  }

  def apply[A: ClassTag](
      producerId: String,
      region: ActorRef[ShardingEnvelope[ConsumerController.SequencedMessage[A]]],
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]],
      settings: Settings): Behavior[Command[A]] = {
    ShardingProducerControllerImpl(producerId, region, durableQueueBehavior, settings)
  }

  /**
   * Java API
   */
  def create[A](
      messageClass: Class[A],
      producerId: String,
      region: ActorRef[ShardingEnvelope[ConsumerController.SequencedMessage[A]]],
      durableQueueBehavior: Optional[Behavior[DurableProducerQueue.Command[A]]]): Behavior[Command[A]] = {
    apply(producerId, region, durableQueueBehavior.asScala)(ClassTag(messageClass))
  }

  /**
   * Java API
   */
  def create[A](
      messageClass: Class[A],
      producerId: String,
      region: ActorRef[ShardingEnvelope[ConsumerController.SequencedMessage[A]]],
      durableQueueBehavior: Optional[Behavior[DurableProducerQueue.Command[A]]],
      settings: Settings): Behavior[Command[A]] = {
    apply(producerId, region, durableQueueBehavior.asScala, settings)(ClassTag(messageClass))
  }
}
