/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery

import java.time.{ Duration => JavaDuration }

import scala.concurrent.duration._

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.internal.ConsumerControllerImpl
import akka.actor.typed.delivery.internal.ProducerControllerImpl
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.util.JavaDurationConverters._
import com.typesafe.config.Config

/**
 * `ConsumerController` and [[ProducerController]] or [[WorkPullingProducerController]] are used
 * together. See the descriptions in those classes or the Akka reference documentation for
 * how they are intended to be used.
 *
 * The destination consumer actor will start the flow by sending an initial [[ConsumerController.Start]]
 * message to the `ConsumerController`. The `ActorRef` in the `Start` message is typically constructed
 * as a message adapter to map the [[ConsumerController.Delivery]] to the protocol of the consumer actor.
 *
 * Received messages from the producer are wrapped in [[ConsumerController.Delivery]] when sent to the consumer,
 * which is supposed to reply with [[ConsumerController.Confirmed]] when it has processed the message.
 * Next message is not delivered until the previous is confirmed.
 * More messages from the producer that arrive while waiting for the confirmation are stashed by
 * the `ConsumerController` and delivered when previous message was confirmed.
 *
 * The consumer and the `ConsumerController` actors are supposed to be local so that these messages are fast
 * and not lost.
 */
object ConsumerController {
  import ConsumerControllerImpl.UnsealedInternalCommand

  type SeqNr = Long

  sealed trait Command[+A] extends UnsealedInternalCommand

  /**
   * Initial message from the consumer actor. The `deliverTo` is typically constructed
   * as a message adapter to map the [[Delivery]] to the protocol of the consumer actor.
   *
   * If the producer is restarted it should send a new `Start` message to the
   * `ConsumerController`.
   */
  final case class Start[A](deliverTo: ActorRef[Delivery[A]]) extends Command[A]

  /**
   * Received messages from the producer are wrapped in `Delivery` when sent to the consumer.
   * When the message has been processed the consumer is supposed to send [[Confirmed]] back
   * to the `ConsumerController` via the `confirmTo`.
   */
  final case class Delivery[A](producerId: String, seqNr: SeqNr, msg: A, confirmTo: ActorRef[Confirmed])

  /**
   * When the message has been processed the consumer is supposed to send `Confirmed` back
   * to the `ConsumerController` via the `confirmTo` in the [[Delivery]] message.
   *
   * The `seqNr` must correspond to the `seqNr` in the [[Delivery]].
   */
  final case class Confirmed(seqNr: SeqNr) extends UnsealedInternalCommand

  /**
   * Register the `ConsumerController` to the given `producerController`. It will
   * retry the registration until the `ProducerConsumer` has acknowledged by sending its
   * first message.
   *
   * Alternatively, this registration can be done on the producer side with the
   * [[ProducerController.RegisterConsumer]] message.
   */
  final case class RegisterToProducerController[A](producerController: ActorRef[ProducerController.Command[A]])
      extends Command[A]

  /**
   * This is used between the `ProducerController` and `ConsumerController`. Should rarely be used in
   * application code but is public because it's possible to wrap it or send it in other ways when
   * building higher level abstractions that are using the `ProducerController`.
   */
  final case class SequencedMessage[A](producerId: String, seqNr: SeqNr, msg: A, first: Boolean, ack: Boolean)(
      /** INTERNAL API */
      @InternalApi private[akka] val producer: ActorRef[ProducerControllerImpl.InternalCommand])
      extends Command[A]

  object Settings {

    /**
     * Scala API: Factory method from config `akka.reliable-delivery.consumer-controller`
     * of the `ActorSystem`.
     */
    def apply(system: ActorSystem[_]): Settings =
      apply(system.settings.config.getConfig("akka.reliable-delivery.consumer-controller"))

    /**
     * Scala API: Factory method from Config corresponding to
     * `akka.reliable-delivery.consumer-controller`.
     */
    def apply(config: Config): Settings = {
      new Settings(
        flowControlWindow = config.getInt("flow-control-window"),
        resendInterval = config.getDuration("resend-interval").asScala,
        onlyFlowControl = config.getBoolean("only-flow-control"))
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

  final class Settings private (
      val flowControlWindow: Int,
      val resendInterval: FiniteDuration,
      val onlyFlowControl: Boolean) {

    def withFlowControlWindow(newFlowControlWindow: Int): Settings =
      copy(flowControlWindow = newFlowControlWindow)

    /**
     * Scala API
     */
    def withResendInterval(newResendInterval: FiniteDuration): Settings =
      copy(resendInterval = newResendInterval)

    /**
     * Java API
     */
    def withResendInterval(newResendInterval: JavaDuration): Settings =
      copy(resendInterval = newResendInterval.asScala)

    /**
     * Java API
     */
    def getResendInterval(): JavaDuration =
      resendInterval.asJava

    def withOnlyFlowControl(newOnlyFlowControl: Boolean): Settings =
      copy(onlyFlowControl = newOnlyFlowControl)

    /**
     * Private copy method for internal use only.
     */
    private def copy(
        flowControlWindow: Int = flowControlWindow,
        resendInterval: FiniteDuration = resendInterval,
        onlyFlowControl: Boolean = onlyFlowControl) =
      new Settings(flowControlWindow, resendInterval, onlyFlowControl)

    override def toString: String =
      s"Settings($flowControlWindow, $resendInterval, $onlyFlowControl)"
  }

  def apply[A](): Behavior[Command[A]] =
    Behaviors.setup { context =>
      apply(serviceKey = None, Settings(context.system))
    }

  def apply[A](settings: Settings): Behavior[Command[A]] =
    apply(serviceKey = None, settings)

  /**
   * To be used with [[WorkPullingProducerController]]. It will register itself to the
   * [[akka.actor.typed.receptionist.Receptionist]] with the given `serviceKey`, and the
   * `WorkPullingProducerController` subscribes to the same key to find active workers.
   */
  def apply[A](serviceKey: ServiceKey[Command[A]]): Behavior[Command[A]] =
    Behaviors.setup { context =>
      apply(Some(serviceKey), Settings(context.system))
    }

  def apply[A](serviceKey: ServiceKey[Command[A]], settings: Settings): Behavior[Command[A]] =
    apply(Some(serviceKey), settings)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def apply[A](
      serviceKey: Option[ServiceKey[Command[A]]],
      settings: Settings): Behavior[Command[A]] = {
    ConsumerControllerImpl(serviceKey, settings)
  }

  /**
   * Java API
   */
  def create[A](): Behavior[Command[A]] =
    apply()

  /**
   * Java API
   */
  def create[A](settings: Settings): Behavior[Command[A]] =
    apply(settings)

  /**
   * Java API: To be used with [[WorkPullingProducerController]]. It will register itself to the
   * [[akka.actor.typed.receptionist.Receptionist]] with the given `serviceKey`, and the
   * `WorkPullingProducerController` subscribes to the same key to find active workers.
   */
  def create[A](serviceKey: ServiceKey[Command[A]]): Behavior[Command[A]] =
    apply(serviceKey)

  /**
   * Java API
   */
  def create[A](serviceKey: ServiceKey[Command[A]], settings: Settings): Behavior[Command[A]] =
    apply(Some(serviceKey), settings)

}
