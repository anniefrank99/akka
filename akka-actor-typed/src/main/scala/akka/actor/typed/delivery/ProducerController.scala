/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery

import java.time.{ Duration => JavaDuration }
import java.util.Optional
import java.util.function.{ Consumer => JConsumer }

import scala.compat.java8.OptionConverters._
import scala.concurrent.duration._
import scala.reflect.ClassTag

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.internal.ProducerControllerImpl
import akka.actor.typed.scaladsl.Behaviors
import akka.util.JavaDurationConverters._
import com.typesafe.config.Config

object ProducerController {
  import ProducerControllerImpl.UnsealedInternalCommand

  type SeqNr = Long

  sealed trait Command[A] extends UnsealedInternalCommand

  final case class Start[A](producer: ActorRef[RequestNext[A]]) extends Command[A]

  final case class RequestNext[A](
      producerId: String,
      currentSeqNr: SeqNr,
      confirmedSeqNr: SeqNr,
      sendNextTo: ActorRef[A],
      askNextTo: ActorRef[MessageWithConfirmation[A]])

  final case class RegisterConsumer[A](consumerController: ActorRef[ConsumerController.Command[A]]) extends Command[A]

  object Settings {

    /**
     * Scala API: Factory method from config `akka.reliable-delivery.producer-controller`
     * of the `ActorSystem`.
     */
    def apply(system: ActorSystem[_]): Settings =
      apply(system.settings.config.getConfig("akka.reliable-delivery.producer-controller"))

    /**
     * Scala API: Factory method from Config corresponding to
     * `akka.reliable-delivery.producer-controller`.
     */
    def apply(config: Config): Settings = {
      new Settings(
        durableQueueRequestTimeout = config.getDuration("durable-queue.request-timeout").asScala,
        durableQueueRetryAttempts = config.getInt("durable-queue.retry-attempts"))
    }

    /**
     * Java API: Factory method from config `akka.reliable-delivery.producer-controller`
     * of the `ActorSystem`.
     */
    def create(system: ActorSystem[_]): Settings =
      apply(system)

    /**
     * java API: Factory method from Config corresponding to
     * `akka.reliable-delivery.producer-controller`.
     */
    def create(config: Config): Settings =
      apply(config)
  }

  final class Settings private (val durableQueueRequestTimeout: FiniteDuration, val durableQueueRetryAttempts: Int) {

    def withDurableQueueRetryAttempts(newDurableQueueRetryAttempts: Int): Settings =
      copy(durableQueueRetryAttempts = newDurableQueueRetryAttempts)

    /**
     * Scala API
     */
    def withDurableQueueRequestTimeout(newDurableQueueRequestTimeout: FiniteDuration): Settings =
      copy(durableQueueRequestTimeout = newDurableQueueRequestTimeout)

    /**
     * Java API
     */
    def withDurableQueueRequestTimeout(newDurableQueueRequestTimeout: JavaDuration): Settings =
      copy(durableQueueRequestTimeout = newDurableQueueRequestTimeout.asScala)

    /**
     * Java API
     */
    def getDurableQueueRequestTimeout(): JavaDuration =
      durableQueueRequestTimeout.asJava

    /**
     * Private copy method for internal use only.
     */
    private def copy(
        durableQueueRequestTimeout: FiniteDuration = durableQueueRequestTimeout,
        durableQueueRetryAttempts: Int = durableQueueRetryAttempts) =
      new Settings(durableQueueRequestTimeout, durableQueueRetryAttempts)

    override def toString: String =
      s"Settings($durableQueueRequestTimeout, $durableQueueRetryAttempts)"
  }

  /**
   * For sending confirmation message back to the producer when the message has been confirmed.
   * Typically used with `ask` from the producer.
   *
   * If `DurableProducerQueue` is used the confirmation reply is sent when the message has been
   * successfully stored, meaning that the actual delivery to the consumer may happen later.
   * If `DurableProducerQueue` is not used the confirmation reply is sent when the message has been
   * fully delivered, processed, and confirmed by the consumer.
   */
  final case class MessageWithConfirmation[A](message: A, replyTo: ActorRef[SeqNr]) extends UnsealedInternalCommand

  def apply[A: ClassTag](
      producerId: String,
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]]): Behavior[Command[A]] = {
    Behaviors.setup { context =>
      ProducerControllerImpl(producerId, durableQueueBehavior, ProducerController.Settings(context.system))
    }
  }

  def apply[A: ClassTag](
      producerId: String,
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]],
      settings: Settings): Behavior[Command[A]] = {
    ProducerControllerImpl(producerId, durableQueueBehavior, settings)
  }

  /**
   * For custom `send` function. For example used with Sharding where the message must be wrapped in
   * `ShardingEnvelope(SequencedMessage(msg))`.
   */
  def apply[A: ClassTag](
      producerId: String,
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]],
      settings: Settings,
      send: ConsumerController.SequencedMessage[A] => Unit): Behavior[Command[A]] = {
    ProducerControllerImpl(producerId, durableQueueBehavior, settings, send)
  }

  /**
   * Java API
   */
  def create[A](
      messageClass: Class[A],
      producerId: String,
      durableQueueBehavior: Optional[Behavior[DurableProducerQueue.Command[A]]]): Behavior[Command[A]] = {
    apply(producerId, durableQueueBehavior.asScala)(ClassTag(messageClass))
  }

  /**
   * Java API
   */
  def create[A](
      messageClass: Class[A],
      producerId: String,
      durableQueueBehavior: Optional[Behavior[DurableProducerQueue.Command[A]]],
      settings: Settings): Behavior[Command[A]] = {
    apply(producerId, durableQueueBehavior.asScala, settings)(ClassTag(messageClass))
  }

  /**
   * Java API: For custom `send` function. For example used with Sharding where the message must be wrapped in
   * `ShardingEnvelope(SequencedMessage(msg))`.
   */
  def create[A](
      messageClass: Class[A],
      producerId: String,
      durableQueueBehavior: Optional[Behavior[DurableProducerQueue.Command[A]]],
      settings: Settings,
      send: JConsumer[ConsumerController.SequencedMessage[A]]): Behavior[Command[A]] = {
    apply(producerId, durableQueueBehavior.asScala, settings, send.accept)(ClassTag(messageClass))
  }

}
