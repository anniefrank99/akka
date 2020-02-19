/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery.internal

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.delivery.ProducerController
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.StashBuffer
import akka.actor.typed.scaladsl.TimerScheduler
import akka.annotation.InternalApi

/**
 * INTERNAL API
 *
 * ==== Design notes ====
 *
 * The destination consumer will start the flow by sending an initial `Start` message
 * to the `ConsumerController`.
 *
 * The `ProducerController` sends the first message to the `ConsumerController` without waiting for
 * a `Request` from the `ConsumerController`. The main reason for this is that when used with
 * Cluster Sharding the first message will typically create the `ConsumerController`. It's
 * also a way to connect the ProducerController and ConsumerController in a dynamic way, for
 * example when the ProducerController is replaced.
 *
 * The `ConsumerController` sends [[ProducerControllerImpl.Request]] to the `ProducerController`
 * to specify it's ready to receive up to the requested sequence number.
 *
 * The `ConsumerController` sends the first `Request` when it receives the first `SequencedMessage`
 * and has received the `Start` message from the consumer.
 *
 * It sends new `Request` when half of the requested window is remaining, but it also retries
 * the `Request` if no messages are received because that could be caused by lost messages.
 *
 * Apart from the first message the producer will not send more messages than requested.
 *
 * Received messages are wrapped in [[ConsumerController.Delivery]] when sent to the consumer,
 * which is supposed to reply with [[ConsumerController.Confirmed]] when it has processed the message.
 * Next message is not delivered until the previous is confirmed.
 * More messages from the producer that arrive while waiting for the confirmation are stashed by
 * the `ConsumerController` and delivered when previous message was confirmed.
 *
 * In other words, the "request" protocol to the application producer and consumer is one-by-one, but
 * between the `ProducerController` and `ConsumerController` it's window of messages in flight.
 *
 * The consumer and the `ConsumerController` are supposed to be local so that these messages are fast and not lost.
 *
 * If the `ConsumerController` receives a message with unexpected sequence number (not previous + 1)
 * it sends [[ProducerControllerImpl.Resend]] to the `ProducerController` and will ignore all messages until
 * the expected sequence number arrives.
 */
@InternalApi private[akka] object ConsumerControllerImpl {
  import ConsumerController.Command
  import ConsumerController.RegisterToProducerController
  import ConsumerController.SeqNr
  import ConsumerController.SequencedMessage
  import ConsumerController.Start

  sealed trait InternalCommand

  /** For commands defined in public ConsumerController */
  trait UnsealedInternalCommand extends InternalCommand

  private final case object Retry extends InternalCommand

  private final case class ConsumerTerminated(consumer: ActorRef[_]) extends InternalCommand

  private final case class State[A](
      producer: ActorRef[ProducerControllerImpl.InternalCommand],
      consumer: ActorRef[ConsumerController.Delivery[A]],
      receivedSeqNr: SeqNr,
      confirmedSeqNr: SeqNr,
      requestedSeqNr: SeqNr,
      registering: Option[ActorRef[ProducerController.Command[A]]]) {

    def isNextExpected(seqMsg: SequencedMessage[A]): Boolean =
      seqMsg.seqNr == receivedSeqNr + 1

    def isProducerChanged(seqMsg: SequencedMessage[A]): Boolean =
      seqMsg.producer != producer || receivedSeqNr == 0

    def updatedRegistering(seqMsg: SequencedMessage[A]): Option[ActorRef[ProducerController.Command[A]]] = {
      registering match {
        case None          => None
        case s @ Some(reg) => if (seqMsg.producer == reg) None else s
      }
    }
  }

  def apply[A](
      serviceKey: Option[ServiceKey[Command[A]]],
      settings: ConsumerController.Settings): Behavior[Command[A]] = {
    Behaviors
      .withStash[InternalCommand](settings.flowControlWindow) { stashBuffer =>
        Behaviors.setup { context =>
          context.setLoggerName("akka.actor.typed.delivery.ConsumerController")
          serviceKey.foreach { key =>
            context.system.receptionist ! Receptionist.Register(key, context.self)
          }
          Behaviors.withTimers { timers =>
            // wait for the `Start` message from the consumer, SequencedMessage will be stashed
            def waitForStart(register: Option[ActorRef[ProducerController.Command[A]]]): Behavior[InternalCommand] = {
              Behaviors.receiveMessagePartial {
                case reg: RegisterToProducerController[A] @unchecked =>
                  reg.producerController ! ProducerController.RegisterConsumer(context.self)
                  waitForStart(Some(reg.producerController))

                case s: Start[A] @unchecked =>
                  context.watchWith(s.deliverTo, ConsumerTerminated(s.deliverTo))

                  val activeBehavior =
                    new ConsumerControllerImpl[A](context, timers, stashBuffer, settings)
                      .active(initialState(context, s))
                  context.log.info("Received Start, unstash [{}]", stashBuffer.size)
                  stashBuffer.unstashAll(activeBehavior)

                case seqMsg: SequencedMessage[A] @unchecked =>
                  stashBuffer.stash(seqMsg)
                  Behaviors.same

                case Retry =>
                  register.foreach { reg =>
                    context.log.info("retry RegisterConsumer to [{}]", reg)
                    reg ! ProducerController.RegisterConsumer(context.self)
                  }
                  Behaviors.same

                case ConsumerTerminated(c) =>
                  context.log.info("Consumer [{}] terminated", c)
                  Behaviors.stopped

              }

            }

            timers.startTimerWithFixedDelay(Retry, Retry, settings.resendInterval)
            waitForStart(None)
          }
        }
      }
      .narrow // expose Command, but not InternalCommand
  }

  private def initialState[A](context: ActorContext[InternalCommand], start: Start[A]): State[A] = {
    State(
      producer = context.system.deadLetters,
      start.deliverTo,
      receivedSeqNr = 0,
      confirmedSeqNr = 0,
      requestedSeqNr = 0,
      registering = None)
  }
}

private class ConsumerControllerImpl[A](
    context: ActorContext[ConsumerControllerImpl.InternalCommand],
    timers: TimerScheduler[ConsumerControllerImpl.InternalCommand],
    stashBuffer: StashBuffer[ConsumerControllerImpl.InternalCommand],
    settings: ConsumerController.Settings) {

  import ConsumerController.Confirmed
  import ConsumerController.Delivery
  import ConsumerController.RegisterToProducerController
  import ConsumerController.SeqNr
  import ConsumerController.SequencedMessage
  import ConsumerController.Start
  import ConsumerControllerImpl._
  import ProducerControllerImpl.Ack
  import ProducerControllerImpl.Request
  import ProducerControllerImpl.Resend
  import settings.flowControlWindow

  startRetryTimer()

  private def resendLost = !settings.onlyFlowControl

  // Expecting a SequencedMessage from ProducerController, that will be delivered to the consumer if
  // the seqNr is right.
  private def active(s: State[A]): Behavior[InternalCommand] = {
    Behaviors.receiveMessage {
      case seqMsg: SequencedMessage[A] =>
        val pid = seqMsg.producerId
        val seqNr = seqMsg.seqNr
        val expectedSeqNr = s.receivedSeqNr + 1

        if (s.isProducerChanged(seqMsg)) {
          receiveChangedProducer(s, seqMsg)
        } else if (s.registering.isDefined) {
          context.log.infoN(
            "from producer [{}], discarding message because registering to new ProducerController, seqNr [{}]",
            pid,
            seqNr)
          Behaviors.same
        } else if (s.isNextExpected(seqMsg)) {
          deliver(s.copy(receivedSeqNr = seqNr, registering = s.updatedRegistering(seqMsg)), seqMsg)
        } else if (seqNr > expectedSeqNr) {
          context.log.infoN("from producer [{}], missing [{}], received [{}]", pid, expectedSeqNr, seqNr)
          if (resendLost) {
            seqMsg.producer ! Resend(fromSeqNr = expectedSeqNr)
            resending(s.copy(registering = s.updatedRegistering(seqMsg)))
          } else {
            context.log.infoN("from producer [{}], missing [{}], deliver [{}] to consumer", pid, expectedSeqNr, seqNr)
            s.consumer ! Delivery(pid, seqNr, seqMsg.msg, context.self)
            waitingForConfirmation(s.copy(receivedSeqNr = seqNr, registering = s.updatedRegistering(seqMsg)), seqMsg)
          }
        } else { // seqNr < expectedSeqNr
          context.log.infoN("from producer [{}], deduplicate [{}], expected [{}]", pid, seqNr, expectedSeqNr)
          if (seqMsg.first)
            active(retryRequest(s).copy(registering = s.updatedRegistering(seqMsg)))
          else
            active(s.copy(registering = s.updatedRegistering(seqMsg)))
        }

      case Retry =>
        receiveRetry(s, () => active(retryRequest(s)))

      case Confirmed(seqNr) =>
        receiveUnexpectedConfirmed(seqNr)

      case start: Start[A] =>
        receiveStart(s, start, newState => active(newState))

      case ConsumerTerminated(c) =>
        receiveConsumerTerminated(c)

      case reg: RegisterToProducerController[A] =>
        receiveRegisterToProducerController(s, reg, newState => active(newState))

      case _: UnsealedInternalCommand =>
        Behaviors.unhandled
    }
  }

  private def receiveChangedProducer(s: State[A], seqMsg: SequencedMessage[A]): Behavior[InternalCommand] = {
    val pid = seqMsg.producerId
    val seqNr = seqMsg.seqNr

    if (seqMsg.first) {
      context.log.infoN(
        "changing producer [{}] from [{}] to [{}], seqNr [{}]",
        seqMsg.producerId,
        s.producer,
        seqMsg.producer,
        seqNr)

      val newRequestedSeqNr = seqMsg.seqNr - 1 + flowControlWindow
      context.log.info("Request first [{}]", newRequestedSeqNr)
      seqMsg.producer ! Request(confirmedSeqNr = 0L, newRequestedSeqNr, resendLost, viaTimeout = false)

      deliver(
        s.copy(
          producer = seqMsg.producer,
          receivedSeqNr = seqNr,
          confirmedSeqNr = 0L,
          requestedSeqNr = newRequestedSeqNr,
          registering = s.updatedRegistering(seqMsg)),
        seqMsg)
    } else {
      context.log.infoN(
        "from producer [{}], discarding message because was from unexpected producer [{}] when expecting [{}], seqNr [{}]",
        pid,
        seqMsg.producer,
        s.producer,
        seqNr)
      Behaviors.same
    }
  }

  // It has detected a missing seqNr and requested a Resend. Expecting a SequencedMessage from the
  // ProducerController with the missing seqNr. Other SequencedMessage with different seqNr will be
  // discarded since they were in flight before the Resend request and will anyway be sent again.
  private def resending(s: State[A]): Behavior[InternalCommand] = {
    Behaviors.receiveMessage {
      case seqMsg: SequencedMessage[A] =>
        val pid = seqMsg.producerId
        val seqNr = seqMsg.seqNr

        if (s.isProducerChanged(seqMsg)) {
          receiveChangedProducer(s, seqMsg)
        } else if (s.registering.isDefined) {
          context.log.infoN(
            "from producer [{}], discarding message because registering to new ProducerController, seqNr [{}]",
            pid,
            seqNr)
          Behaviors.same
        } else if (s.isNextExpected(seqMsg)) {
          context.log.infoN("from producer [{}], received missing [{}]", pid, seqNr)
          deliver(s.copy(receivedSeqNr = seqNr, registering = s.updatedRegistering(seqMsg)), seqMsg)
        } else {
          context.log.infoN("from producer [{}], ignoring [{}], waiting for [{}]", pid, seqNr, s.receivedSeqNr + 1)
          if (seqMsg.first)
            retryRequest(s)
          Behaviors.same // ignore until we receive the expected
        }

      case Retry =>
        receiveRetry(s, () => {
          // in case the Resend message was lost
          context.log.info("retry Resend [{}]", s.receivedSeqNr + 1)
          s.producer ! Resend(fromSeqNr = s.receivedSeqNr + 1)
          Behaviors.same
        })

      case Confirmed(seqNr) =>
        receiveUnexpectedConfirmed(seqNr)

      case start: Start[A] =>
        receiveStart(s, start, newState => resending(newState))

      case ConsumerTerminated(c) =>
        receiveConsumerTerminated(c)

      case reg: RegisterToProducerController[A] =>
        receiveRegisterToProducerController(s, reg, newState => active(newState))

      case _: UnsealedInternalCommand =>
        Behaviors.unhandled
    }
  }

  private def deliver(s: State[A], seqMsg: SequencedMessage[A]): Behavior[InternalCommand] = {
    context.log.info("from producer [{}], deliver [{}] to consumer", seqMsg.producerId, seqMsg.seqNr)
    s.consumer ! Delivery(seqMsg.producerId, seqMsg.seqNr, seqMsg.msg, context.self)
    waitingForConfirmation(s, seqMsg)
  }

  // The message has been delivered to the consumer and it is now waiting for Confirmed from
  // the consumer. New SequencedMessage from the ProducerController will be stashed.
  private def waitingForConfirmation(s: State[A], seqMsg: SequencedMessage[A]): Behavior[InternalCommand] = {
    Behaviors.receiveMessage {
      case Confirmed(seqNr) =>
        val expectedSeqNr = s.receivedSeqNr
        if (seqNr > expectedSeqNr) {
          throw new IllegalStateException(
            s"Expected confirmation of seqNr [$expectedSeqNr], but received higher [$seqNr]")
        } else if (seqNr != expectedSeqNr) {
          context.log.info(
            "Expected confirmation of seqNr [{}] but received [{}]. Perhaps the consumer was restarted.",
            expectedSeqNr,
            seqNr)
        }
        context.log.info("Confirmed [{}], stashed size [{}]", seqNr, stashBuffer.size)

        val newRequestedSeqNr =
          if (seqMsg.first) {
            // confirm the first message immediately to cancel resending of first
            val newRequestedSeqNr = seqNr - 1 + flowControlWindow
            context.log.info("Request after first [{}]", newRequestedSeqNr)
            s.producer ! Request(confirmedSeqNr = seqNr, newRequestedSeqNr, resendLost, viaTimeout = false)
            newRequestedSeqNr
          } else if ((s.requestedSeqNr - seqNr) == flowControlWindow / 2) {
            val newRequestedSeqNr = s.requestedSeqNr + flowControlWindow / 2
            context.log.info("Request [{}]", newRequestedSeqNr)
            s.producer ! Request(confirmedSeqNr = seqNr, newRequestedSeqNr, resendLost, viaTimeout = false)
            startRetryTimer() // reset interval since Request was just sent
            newRequestedSeqNr
          } else {
            if (seqMsg.ack) {
              context.log.info("Ack [{}]", seqNr)
              s.producer ! Ack(confirmedSeqNr = seqNr)
            }

            s.requestedSeqNr
          }

        // FIXME can we use unstashOne instead of all?
        if (stashBuffer.nonEmpty)
          context.log.info("Unstash [{}]", stashBuffer.size)
        stashBuffer.unstashAll(active(s.copy(confirmedSeqNr = seqNr, requestedSeqNr = newRequestedSeqNr)))

      case seqMsg: SequencedMessage[A] =>
        if (stashBuffer.isFull) {
          // possible that the stash is full if ProducerController resends unconfirmed (duplicates)
          // dropping them since they can be resent
          context.log.info("Stash is full, dropping [{}]", seqMsg)
        } else {
          context.log.info("Stash [{}]", seqMsg)
          stashBuffer.stash(seqMsg)
        }
        Behaviors.same

      case Retry =>
        receiveRetry(s, () => waitingForConfirmation(retryRequest(s), seqMsg))

      case start: Start[A] =>
        start.deliverTo ! Delivery(seqMsg.producerId, seqMsg.seqNr, seqMsg.msg, context.self)
        receiveStart(s, start, newState => waitingForConfirmation(newState, seqMsg))

      case ConsumerTerminated(c) =>
        receiveConsumerTerminated(c)

      case reg: RegisterToProducerController[A] =>
        receiveRegisterToProducerController(s, reg, newState => waitingForConfirmation(newState, seqMsg))

      case _: UnsealedInternalCommand =>
        Behaviors.unhandled
    }
  }

  private def receiveRetry(s: State[A], nextBehavior: () => Behavior[InternalCommand]): Behavior[InternalCommand] = {
    s.registering match {
      case None => nextBehavior()
      case Some(reg) =>
        reg ! ProducerController.RegisterConsumer(context.self)
        Behaviors.same
    }
  }

  private def receiveStart(
      s: State[A],
      start: Start[A],
      nextBehavior: State[A] => Behavior[InternalCommand]): Behavior[InternalCommand] = {
    // if consumer is restarted it may send Start again
    context.unwatch(s.consumer)
    context.watchWith(start.deliverTo, ConsumerTerminated(start.deliverTo))
    nextBehavior(s.copy(consumer = start.deliverTo))
  }

  private def receiveRegisterToProducerController(
      s: State[A],
      reg: RegisterToProducerController[A],
      nextBehavior: State[A] => Behavior[InternalCommand]): Behavior[InternalCommand] = {
    if (reg.producerController != s.producer) {
      context.log.info2(
        "Register to new ProducerController [{}], previous was [{}]",
        reg.producerController,
        s.producer)
      reg.producerController ! ProducerController.RegisterConsumer(context.self)
      resending(s.copy(registering = Some(reg.producerController)))
      nextBehavior(s.copy(registering = Some(reg.producerController)))
    } else {
      Behaviors.same
    }
  }

  private def receiveConsumerTerminated(c: ActorRef[_]): Behavior[InternalCommand] = {
    context.log.info("Consumer [{}] terminated", c)
    Behaviors.stopped
  }

  private def receiveUnexpectedConfirmed(seqNr: SeqNr): Behavior[InternalCommand] = {
    context.log.warn("Unexpected confirmed [{}]", seqNr)
    Behaviors.unhandled
  }

  private def startRetryTimer(): Unit = {
    timers.startTimerWithFixedDelay(Retry, Retry, settings.resendInterval)
  }

  // in case the Request or the SequencedMessage triggering the Request is lost
  private def retryRequest(s: State[A]): State[A] = {
    val newRequestedSeqNr = if (resendLost) s.requestedSeqNr else s.receivedSeqNr + flowControlWindow / 2
    context.log.info("retry Request [{}]", newRequestedSeqNr)
    // FIXME may watch the producer to avoid sending retry Request to dead producer
    s.producer ! Request(s.confirmedSeqNr, newRequestedSeqNr, resendLost, viaTimeout = true)
    s.copy(requestedSeqNr = newRequestedSeqNr)
  }

}
