/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import scala.concurrent.duration._

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.StashBuffer
import akka.actor.typed.scaladsl.TimerScheduler
import akka.annotation.InternalApi

// FIXME Scaladoc describes how it works, internally. Rewrite for end user and keep internals as impl notes.

/**
 * The destination consumer will start the flow by sending an initial `Start` message
 * to the `ConsumerController`.
 *
 * The `ProducerController` sends the first message to the `ConsumerController` without waiting for
 * a `Request` from the `ConsumerController`. The main reason for this is that when used with
 * Cluster Sharding the first message will typically create the `ConsumerController`. It's
 * also a way to connect the ProducerController and ConsumerController in a dynamic way, for
 * example when the ProducerController is replaced.
 *
 * The `ConsumerController` sends [[ProducerController.Internal.Request]] to the `ProducerController`
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
 * The consumer and the `ConsumerController` are supposed to be local so that these messages are fast and not lost.
 *
 * If the `ConsumerController` receives a message with unexpected sequence number (not previous + 1)
 * it sends [[ProducerController.Internal.Resend]] to the `ProducerController` and will ignore all messages until
 * the expected sequence number arrives.
 */
object ConsumerController {

  sealed trait InternalCommand
  sealed trait Command[+A] extends InternalCommand
  final case class SequencedMessage[A](producerId: String, seqNr: Long, msg: A, first: Boolean)(
      /** INTERNAL API */
      @InternalApi private[akka] val producer: ActorRef[ProducerController.InternalCommand])
      extends Command[A]
  private final case object Retry extends InternalCommand

  final case class Delivery[A](producerId: String, seqNr: Long, msg: A, confirmTo: ActorRef[Confirmed])
  final case class Start[A](deliverTo: ActorRef[Delivery[A]]) extends Command[A]
  final case class Confirmed(seqNr: Long) extends InternalCommand

  final case class RegisterToProducerController[A](producerController: ActorRef[ProducerController.Command[A]])
      extends Command[A]

  private final case class State(
      producer: ActorRef[ProducerController.InternalCommand],
      receivedSeqNr: Long,
      confirmedSeqNr: Long,
      requestedSeqNr: Long)

  private val RequestWindow = 20 // FIXME should be a param, ofc

  def apply[A](resendLost: Boolean): Behavior[Command[A]] = {
    Behaviors
      .setup[InternalCommand] { ctx =>
        Behaviors.withTimers { timers =>
          Behaviors.withStash(100) { stashBuffer =>
            def becomeActive(
                producerId: String,
                producer: ActorRef[ProducerController.InternalCommand],
                start: Start[A],
                firstSeqNr: Long): Behavior[InternalCommand] = {
              val requestedSeqNr = firstSeqNr - 1 + RequestWindow

              // FIXME adjust all logging, most should probably be debug
              ctx.log.infoN(
                "Become active for producer [{}] / [{}], requestedSeqNr [{}]",
                producerId,
                producer,
                requestedSeqNr)

              producer ! ProducerController.Internal.Request(
                confirmedSeqNr = 0,
                requestedSeqNr,
                resendLost,
                viaTimeout = false)

              val next = new ConsumerController[A](ctx, timers, producerId, start.deliverTo, stashBuffer, resendLost)
                .active(State(producer, receivedSeqNr = 0, confirmedSeqNr = 0, requestedSeqNr))
              stashBuffer.unstashAll(next)
            }

            // wait for both the `Start` message from the consumer and the first `SequencedMessage` from the producer
            def idle(
                register: Option[ActorRef[ProducerController.Command[A]]],
                producer: Option[(String, ActorRef[ProducerController.InternalCommand])],
                start: Option[Start[A]],
                firstSeqNr: Long): Behavior[InternalCommand] = {
              Behaviors.receiveMessagePartial {
                case reg: RegisterToProducerController[A] @unchecked =>
                  reg.producerController ! ProducerController.RegisterConsumer(ctx.self)
                  idle(Some(reg.producerController), producer, start, firstSeqNr)

                case s: Start[A] @unchecked =>
                  producer match {
                    case None           => idle(register, None, Some(s), firstSeqNr)
                    case Some((pid, p)) => becomeActive(pid, p, s, firstSeqNr)

                  }

                case seqMsg: SequencedMessage[A] @unchecked =>
                  if (seqMsg.first) {
                    ctx.log.info("Received first SequencedMessage [{}]", seqMsg.seqNr)
                    stashBuffer.stash(seqMsg)
                    start match {
                      case None    => idle(None, Some((seqMsg.producerId, seqMsg.producer)), start, seqMsg.seqNr)
                      case Some(s) => becomeActive(seqMsg.producerId, seqMsg.producer, s, seqMsg.seqNr)
                    }
                  } else if (seqMsg.seqNr > firstSeqNr) {
                    ctx.log.info("Stashing non-first SequencedMessage [{}]", seqMsg.seqNr)
                    stashBuffer.stash(seqMsg)
                    Behaviors.same
                  } else {
                    ctx.log.info("Dropping non-first SequencedMessage [{}]", seqMsg.seqNr)
                    Behaviors.same
                  }

                case Retry =>
                  register.foreach { reg =>
                    ctx.log.info("retry RegisterConsumer to [{}]", reg)
                    reg ! ProducerController.RegisterConsumer(ctx.self)
                  }
                  Behaviors.same

              }

            }

            timers.startTimerWithFixedDelay(Retry, Retry, 1.second) // FIXME config interval
            idle(None, None, None, 1L)
          }
        }
      }
      .narrow // expose Command, but not InternalCommand
  }

}

private class ConsumerController[A](
    context: ActorContext[ConsumerController.InternalCommand],
    timers: TimerScheduler[ConsumerController.InternalCommand],
    producerId: String,
    deliverTo: ActorRef[ConsumerController.Delivery[A]],
    stashBuffer: StashBuffer[ConsumerController.InternalCommand],
    resendLost: Boolean) {

  startRetryTimer()

  import ConsumerController._
  import ProducerController.Internal.Request
  import ProducerController.Internal.Resend

  // Expecting a SequencedMessage from ProducerController, that will be delivered to the consumer if
  // the seqNr is right.
  private def active(s: State): Behavior[InternalCommand] = {
    Behaviors.receiveMessage {
      case seqMsg @ SequencedMessage(pid, seqNr, msg: A @unchecked, first) =>
        checkProducerId(producerId, pid, seqNr)
        val expectedSeqNr = s.receivedSeqNr + 1
        if (seqNr == expectedSeqNr || (first && seqNr >= expectedSeqNr) || (first && seqMsg.producer != s.producer)) {
          logIfChangingProducer(s.producer, seqMsg, pid, seqNr)
          deliverTo ! Delivery(pid, seqNr, msg, context.self)
          waitingForConfirmation(
            s.copy(producer = seqMsg.producer, receivedSeqNr = seqNr, requestedSeqNr = s.requestedSeqNr),
            first)
        } else if (seqNr > expectedSeqNr) {
          logIfChangingProducer(s.producer, seqMsg, pid, seqNr)
          context.log.infoN("from producer [{}], missing [{}], received [{}]", pid, expectedSeqNr, seqNr)
          if (resendLost) {
            seqMsg.producer ! Resend(fromSeqNr = expectedSeqNr)
            resending(s.copy(producer = seqMsg.producer))
          } else {
            deliverTo ! Delivery(pid, seqNr, msg, context.self)
            waitingForConfirmation(s.copy(producer = seqMsg.producer, receivedSeqNr = seqNr), first)
          }
        } else { // seqNr < expectedSeqNr
          context.log.infoN("from producer [{}], deduplicate [{}], expected [{}]", pid, seqNr, expectedSeqNr)
          if (seqMsg.first)
            retryRequest(s)
          Behaviors.same
        }

      case Retry =>
        retryRequest(s)
        Behaviors.same

      case Confirmed(seqNr) =>
        context.log.warn("Unexpected confirmed [{}]", seqNr)
        Behaviors.unhandled

      case Start(_) =>
        Behaviors.unhandled

      case _: RegisterToProducerController[_] =>
        Behaviors.unhandled // already registered
    }
  }

  // It has detected a missing seqNr and requested a Resend. Expecting a SequencedMessage from the
  // ProducerController with the missing seqNr. Other SequencedMessage with different seqNr will be
  // discarded since they were in flight before the Resend request and will anyway be sent again.
  private def resending(s: State): Behavior[InternalCommand] = {
    Behaviors.receiveMessage {
      case seqMsg @ SequencedMessage(pid, seqNr, msg: A @unchecked, first) =>
        checkProducerId(producerId, pid, seqNr)

        // FIXME is SequencedMessage.first possible here?
        if (seqNr == s.receivedSeqNr + 1) {
          logIfChangingProducer(s.producer, seqMsg, pid, seqNr)
          context.log.info("from producer [{}], received missing [{}]", pid, seqNr)
          deliverTo ! Delivery(pid, seqNr, msg, context.self)
          waitingForConfirmation(s.copy(producer = seqMsg.producer, receivedSeqNr = seqNr), first)
        } else {
          context.log.infoN("from producer [{}], ignoring [{}], waiting for [{}]", pid, seqNr, s.receivedSeqNr + 1)
          if (seqMsg.first)
            retryRequest(s)
          Behaviors.same // ignore until we receive the expected
        }

      case Retry =>
        // in case the Resend message was lost
        context.log.info("retry Resend [{}]", s.receivedSeqNr + 1)
        s.producer ! Resend(fromSeqNr = s.receivedSeqNr + 1)
        Behaviors.same

      case Confirmed(seqNr) =>
        context.log.warn("Unexpected confirmed [{}]", seqNr)
        Behaviors.unhandled

      case Start(_) =>
        Behaviors.unhandled

      case _: RegisterToProducerController[_] =>
        Behaviors.unhandled // already registered
    }
  }

  // The message has been delivered to the consumer and it is now waiting for Confirmed from
  // the consumer. New SequencedMessage from the ProducerController will be stashed.
  private def waitingForConfirmation(s: State, first: Boolean): Behavior[InternalCommand] = {
    Behaviors.receiveMessage {
      case Confirmed(seqNr) =>
        val expectedSeqNr = s.receivedSeqNr
        if (seqNr > expectedSeqNr) {
          throw new IllegalStateException(
            s"Expected confirmation of seqNr [$expectedSeqNr], but received higher [$seqNr]")
        } else if (seqNr != expectedSeqNr) {
          // FIXME restart of consumer is not fully thought about yet
          context.log.info(
            "Expected confirmation of seqNr [{}] but received [{}]. Perhaps the consumer was restarted.",
            expectedSeqNr,
            seqNr)
        }
        context.log.info("Confirmed [{}], stashed size [{}]", seqNr, stashBuffer.size)
        val newRequestedSeqNr =
          if (first) {
            // confirm the first message immediately to cancel resending of first
            val newRequestedSeqNr = seqNr - 1 + RequestWindow
            context.log.info("Request after first [{}]", newRequestedSeqNr)
            s.producer ! Request(confirmedSeqNr = seqNr, newRequestedSeqNr, resendLost, viaTimeout = false)
            newRequestedSeqNr
          } else if ((s.requestedSeqNr - seqNr) == RequestWindow / 2) {
            val newRequestedSeqNr = s.requestedSeqNr + RequestWindow / 2
            context.log.info("Request [{}]", newRequestedSeqNr)
            s.producer ! Request(confirmedSeqNr = seqNr, newRequestedSeqNr, resendLost, viaTimeout = false)
            startRetryTimer() // reset interval since Request was just sent
            newRequestedSeqNr
          } else {
            s.requestedSeqNr
          }
        // FIXME can we use unstashOne instead of all?
        stashBuffer.unstashAll(active(s.copy(confirmedSeqNr = seqNr, requestedSeqNr = newRequestedSeqNr)))

      case Retry =>
        retryRequest(s)
        Behaviors.same

      case msg =>
        context.log.info("Stash [{}]", msg)
        stashBuffer.stash(msg)
        Behaviors.same
    }
  }

  private def startRetryTimer(): Unit = {
    timers.startTimerWithFixedDelay(Retry, Retry, 1.second) // FIXME config interval
  }

  // in case the Request or the SequencedMessage triggering the Request is lost
  private def retryRequest(s: State): Unit = {
    context.log.info("retry Request [{}]", s.requestedSeqNr)
    // FIXME may watch the producer to avoid sending retry Request to dead producer
    s.producer ! Request(s.confirmedSeqNr, s.requestedSeqNr, resendLost, viaTimeout = true)
  }

  private def checkProducerId(producerId: String, incomingProducerId: String, seqNr: Long): Unit = {
    if (incomingProducerId != producerId)
      throw new IllegalArgumentException(
        s"Unexpected producerId, expected [$producerId], received [$incomingProducerId], " +
        s"seqNr [$seqNr].")
  }

  private def logIfChangingProducer(
      producer: ActorRef[ProducerController.InternalCommand],
      seqMsg: SequencedMessage[Any],
      producerId: String,
      seqNr: Long): Unit = {
    if (seqMsg.producer != producer)
      context.log.infoN(
        "changing producer [{}] from [{}] to [{}], seqNr [{}]",
        producerId,
        producer,
        seqMsg.producer,
        seqNr)
  }

}

// FIXME it must be possible to restart the consumer, then it might send a non-matching Confirmed(seqNr)
// FIXME could use watch to detect when producer or consumer are terminated
