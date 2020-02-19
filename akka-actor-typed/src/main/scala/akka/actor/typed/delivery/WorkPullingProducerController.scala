/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery

import java.util.Optional

import scala.reflect.ClassTag

import scala.compat.java8.OptionConverters._
import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.internal.WorkPullingProducerControllerImpl
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.Config

object WorkPullingProducerController {

  import WorkPullingProducerControllerImpl.UnsealedInternalCommand

  sealed trait Command[A] extends UnsealedInternalCommand

  final case class Start[A](producer: ActorRef[RequestNext[A]]) extends Command[A]

  final case class RequestNext[A](sendNextTo: ActorRef[A], askNextTo: ActorRef[MessageWithConfirmation[A]])

  /**
   * For sending confirmation message back to the producer when the message has been fully delivered, processed,
   * and confirmed by the consumer. Typically used with `ask` from the producer.
   */
  final case class MessageWithConfirmation[A](message: A, replyTo: ActorRef[Done]) extends UnsealedInternalCommand

  final case class GetWorkerStats[A](replyTo: ActorRef[WorkerStats]) extends Command[A]

  final case class WorkerStats(numberOfWorkers: Int)

  object Settings {

    /**
     * Scala API: Factory method from config `akka.reliable-delivery.work-pulling.producer-controller`
     * of the `ActorSystem`.
     */
    def apply(system: ActorSystem[_]): Settings =
      apply(system.settings.config.getConfig("akka.reliable-delivery.work-pulling.producer-controller"))

    /**
     * Scala API: Factory method from Config corresponding to
     * `akka.reliable-delivery.work-pulling.producer-controller`.
     */
    def apply(config: Config): Settings = {
      new Settings(bufferSize = config.getInt("buffer-size"), ProducerController.Settings(config))
    }

    /**
     * Java API: Factory method from config `akka.reliable-delivery.work-pulling.producer-controller`
     * of the `ActorSystem`.
     */
    def create(system: ActorSystem[_]): Settings =
      apply(system)

    /**
     * java API: Factory method from Config corresponding to
     * `akka.reliable-delivery.work-pulling.producer-controller`.
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
      workerServiceKey: ServiceKey[ConsumerController.Command[A]],
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]]): Behavior[Command[A]] = {
    Behaviors.setup { context =>
      WorkPullingProducerControllerImpl(producerId, workerServiceKey, durableQueueBehavior, Settings(context.system))
    }
  }

  def apply[A: ClassTag](
      producerId: String,
      workerServiceKey: ServiceKey[ConsumerController.Command[A]],
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]],
      settings: Settings): Behavior[Command[A]] = {
    WorkPullingProducerControllerImpl(producerId, workerServiceKey, durableQueueBehavior, settings)
  }

  /**
   * Java API
   */
  def create[A](
      messageClass: Class[A],
      producerId: String,
      workerServiceKey: ServiceKey[ConsumerController.Command[A]],
      durableQueueBehavior: Optional[Behavior[DurableProducerQueue.Command[A]]]): Behavior[Command[A]] = {
    apply(producerId, workerServiceKey, durableQueueBehavior.asScala)(ClassTag(messageClass))
  }

  /**
   * Java API
   */
  def apply[A: ClassTag](
      messageClass: Class[A],
      producerId: String,
      workerServiceKey: ServiceKey[ConsumerController.Command[A]],
      durableQueueBehavior: Optional[Behavior[DurableProducerQueue.Command[A]]],
      settings: Settings): Behavior[Command[A]] = {
    apply(producerId, workerServiceKey, durableQueueBehavior.asScala, settings)(ClassTag(messageClass))
  }
}
