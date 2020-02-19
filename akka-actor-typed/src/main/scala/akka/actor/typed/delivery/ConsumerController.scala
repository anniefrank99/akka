/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.internal.ConsumerControllerImpl
import akka.actor.typed.delivery.internal.ProducerControllerImpl
import akka.actor.typed.receptionist.ServiceKey
import akka.annotation.InternalApi

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

  val RequestWindow = 20 // FIXME should be a param, ofc

  def apply[A](): Behavior[Command[A]] =
    apply(resendLost = true, serviceKey = None)

  /**
   * Lost messages will not be resent, but flow control is used.
   * This can be more efficient since messages doesn't have to be
   * kept in memory in the `ProducerController` until they have been
   * confirmed.
   */
  def onlyFlowControl[A](): Behavior[Command[A]] =
    apply(resendLost = false, serviceKey = None)

  /**
   * To be used with [[WorkPullingProducerController]]. It will register itself to the
   * [[akka.actor.typed.receptionist.Receptionist]] with the given `serviceKey`, and the
   * `WorkPullingProducerController` subscribes to the same key to find active workers.
   */
  def apply[A](serviceKey: ServiceKey[Command[A]]): Behavior[Command[A]] =
    apply(resendLost = true, Some(serviceKey))

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def apply[A](
      resendLost: Boolean,
      serviceKey: Option[ServiceKey[Command[A]]]): Behavior[Command[A]] = {
    ConsumerControllerImpl(resendLost, serviceKey)
  }

}
