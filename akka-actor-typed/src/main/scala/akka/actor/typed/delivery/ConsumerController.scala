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

object ConsumerController {
  import ConsumerControllerImpl.UnsealedInternalCommand

  type SeqNr = Long

  sealed trait Command[+A] extends UnsealedInternalCommand

  final case class SequencedMessage[A](producerId: String, seqNr: SeqNr, msg: A, first: Boolean, ack: Boolean)(
      /** INTERNAL API */
      @InternalApi private[akka] val producer: ActorRef[ProducerControllerImpl.InternalCommand])
      extends Command[A]

  final case class Delivery[A](producerId: String, seqNr: SeqNr, msg: A, confirmTo: ActorRef[Confirmed])
  final case class Start[A](deliverTo: ActorRef[Delivery[A]]) extends Command[A]
  final case class Confirmed(seqNr: SeqNr) extends UnsealedInternalCommand

  final case class RegisterToProducerController[A](producerController: ActorRef[ProducerController.Command[A]])
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
