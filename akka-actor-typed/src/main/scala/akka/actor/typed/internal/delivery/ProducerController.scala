/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import scala.concurrent.duration._
import scala.reflect.ClassTag

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.TimerScheduler
import akka.actor.typed.scaladsl.LoggerOps

// FIXME Scaladoc describes how it works, internally. Rewrite for end user and keep internals as impl notes.

/**
 * The `ProducerController` sends `RequestNext` to the actual producer, which is then allowed to send
 * one message to the `ProducerController`. The `RequestMessage` message is defined via a factory
 * function so that the producer can decide what type to use. The producer and `ProducerController`
 * are supposed to be local so that these messages are fast and not lost.
 *
 * The `ProducerController` sends the first message to the `ConsumerController` without waiting for
 * a `Request` from the `ConsumerController`. The main reason for this is that when used with
 * Cluster Sharding the first message will typically create the `ConsumerController`. It's
 * also a way to connect the ProducerController and ConsumerController in a dynamic way, for
 * example when the ProducerController is replaced.
 *
 * When the first message is received by the `ConsumerController` it sends back the initial `Request`.
 *
 * Apart from the first message the `ProducerController` will not send more messages than requested
 * by the `ConsumerController`.
 *
 * When there is demand from the consumer side the `ProducerController` sends `RequestNext` to the
 * actual producer, which is then allowed to send one more message.
 *
 * Each message is wrapped by the `ProducerController` in [[ConsumerController.SequencedMessage]] with
 * a monotonically increasing sequence number without gaps, starting at 1.
 *
 * The `Request` message also contains a `confirmedSeqNr` that is the acknowledgement
 * from the consumer that it has received and processed all messages up to that sequence number.
 *
 * The `ConsumerController` will send [[ProducerController.Internal.Resend]] if a lost message is detected
 * and then the `ProducerController` will resend all messages from that sequence number. The producer keeps
 * unconfirmed messages in a buffer to be able to resend them. The buffer size is limited
 * by the request window size.
 *
 * The resending is optional, and the `ConsumerController` can be started with `resendLost=false`
 * to ignore lost messages, and then the `ProducerController` will not buffer unconfirmed messages.
 * In that mode it provides only flow control but no reliable delivery.
 */
object ProducerController {

  sealed trait InternalCommand

  sealed trait Command[A] extends InternalCommand

  final case class RegisterConsumer[A](
      consumerController: ActorRef[ConsumerController.Command[A]],
      replyTo: ActorRef[Done])
      extends Command[A]

  final case class RequestNextParam[A](currentSeqNr: Long, confirmedSeqNr: Long, sendNextTo: ActorRef[A])

  final case class MessageWithConfirmation[A](message: A, replyTo: ActorRef[Long])

  object Internal {
    final case class Request(confirmedSeqNr: Long, upToSeqNr: Long, supportResend: Boolean, viaReceiveTimeout: Boolean)
        extends InternalCommand {
      require(confirmedSeqNr < upToSeqNr)
    }
    final case class Resend(fromSeqNr: Long) extends InternalCommand
  }

  private case class Msg[A](msg: A) extends InternalCommand
  private case object ResendFirst extends InternalCommand

  private final case class State[A](
      requested: Boolean,
      currentSeqNr: Long,
      confirmedSeqNr: Long,
      requestedSeqNr: Long,
      pendingReplies: Map[Long, ActorRef[Long]],
      unconfirmed: Option[Vector[ConsumerController.SequencedMessage[A]]], // FIXME use OptionVal
      firstSeqNr: Long,
      send: ConsumerController.SequencedMessage[A] => Unit)

  def apply[A: ClassTag, RequestNext](
      producerId: String,
      requestNextFactory: RequestNextParam[A] ⇒ RequestNext,
      producer: ActorRef[RequestNext]): Behavior[Command[A]] = {

    Behaviors
      .receiveMessagePartial[InternalCommand] {
        case RegisterConsumer(consumerController: ActorRef[ConsumerController.Command[A]] @unchecked, replyTo) =>
          replyTo ! Done
          becomeActive(producerId, requestNextFactory, producer, consumerController)
      }
      .narrow
  }

  /**
   * For custom `send` function. For example used with Sharding where the message must be wrapped in
   * `ShardingEnvelope(SequencedMessage(msg))`.
   */
  def apply[A: ClassTag, RequestNext](
      producerId: String,
      requestNextFactory: RequestNextParam[A] ⇒ RequestNext,
      producer: ActorRef[RequestNext],
      send: ConsumerController.SequencedMessage[A] => Unit): Behavior[Command[A]] = {
    becomeActive(producerId, requestNextFactory, producer, send).narrow
  }

  /**
   * For confirmation message back to the producer when the message has been fully delivered, processed,
   * and confirmed by the consumer. Typically used with `ask` from the producer.
   */
  def withConfirmation[A: ClassTag, RequestNext](
      producerId: String,
      requestNextFactory: RequestNextParam[MessageWithConfirmation[A]] ⇒ RequestNext,
      producer: ActorRef[RequestNext]): Behavior[Command[A]] = {
    Behaviors
      .receiveMessagePartial[InternalCommand] {
        case RegisterConsumer(consumerController: ActorRef[ConsumerController.Command[A]] @unchecked, replyTo) =>
          replyTo ! Done
          becomeActive[A, MessageWithConfirmation[A], RequestNext](
            producerId,
            requestNextFactory,
            producer,
            consumerController)
      }
      .narrow
  }

  private def becomeActive[A: ClassTag, B: ClassTag, RequestNext](
      producerId: String,
      requestNextFactory: RequestNextParam[B] ⇒ RequestNext,
      producer: ActorRef[RequestNext],
      consumerController: ActorRef[ConsumerController.Command[A]]): Behavior[InternalCommand] = {
    val send: ConsumerController.SequencedMessage[A] => Unit = consumerController ! _
    becomeActive[A, B, RequestNext](producerId, requestNextFactory, producer, send)
  }

  private def becomeActive[A: ClassTag, B: ClassTag, RequestNext](
      producerId: String,
      requestNextFactory: RequestNextParam[B] ⇒ RequestNext,
      producer: ActorRef[RequestNext],
      send: ConsumerController.SequencedMessage[A] => Unit): Behavior[InternalCommand] = {

    Behaviors.setup { ctx ⇒
      Behaviors.withTimers { timers =>
        val msgAdapter: ActorRef[B] = ctx.messageAdapter(msg ⇒ Msg(msg))
        producer ! requestNextFactory(RequestNextParam(1L, 0L, msgAdapter))
        new ProducerController[A, B, RequestNext](ctx, producerId, producer, requestNextFactory, msgAdapter, timers)
          .active(
            State(
              requested = true,
              currentSeqNr = 1L,
              confirmedSeqNr = 0L,
              requestedSeqNr = 1L,
              pendingReplies = Map.empty,
              unconfirmed = Some(Vector.empty),
              firstSeqNr = 1L,
              send))
      }
    }
  }

}

private class ProducerController[A: ClassTag, B: ClassTag, RequestNext](
    ctx: ActorContext[ProducerController.InternalCommand],
    producerId: String,
    producer: ActorRef[RequestNext],
    requestNextFactory: ProducerController.RequestNextParam[B] ⇒ RequestNext,
    msgAdapter: ActorRef[B],
    timers: TimerScheduler[ProducerController.InternalCommand]) {
  import ProducerController._
  import ProducerController.Internal._
  import ConsumerController.SequencedMessage

  private def active(s: State[A]): Behavior[InternalCommand] = {

    def onMsg(m: A, newPendingReplies: Map[Long, ActorRef[Long]]): Behavior[InternalCommand] = {
      if (s.requested && s.currentSeqNr <= s.requestedSeqNr) {
        // FIXME adjust all logging, most should probably be debug
        ctx.log.info("sent [{}]", s.currentSeqNr)
        val seqMsg = SequencedMessage(producerId, s.currentSeqNr, m, s.currentSeqNr == s.firstSeqNr)(ctx.self)
        val newUnconfirmed = s.unconfirmed match {
          case Some(u) ⇒ Some(u :+ seqMsg)
          case None ⇒ None // no resending, no need to keep unconfirmed
        }
        if (s.currentSeqNr == s.firstSeqNr)
          timers.startTimerWithFixedDelay(ResendFirst, ResendFirst, 1.second)

        s.send(seqMsg)
        val newRequested =
          if (s.currentSeqNr == s.requestedSeqNr)
            false
          else {
            producer ! requestNextFactory(RequestNextParam(s.currentSeqNr, s.confirmedSeqNr, msgAdapter))
            true
          }
        active(
          s.copy(
            requested = newRequested,
            currentSeqNr = s.currentSeqNr + 1,
            pendingReplies = newPendingReplies,
            unconfirmed = newUnconfirmed))
      } else {
        throw new IllegalStateException(
          s"Unexpected Msg when no demand, requested ${s.requested}, " +
          s"requestedSeqNr ${s.requestedSeqNr}, currentSeqNr ${s.currentSeqNr}")
      }
    }

    Behaviors.receiveMessage {
      case Msg(MessageWithConfirmation(m: A, replyTo)) =>
        onMsg(m, s.pendingReplies.updated(s.currentSeqNr, replyTo))
      case Msg(m: A) ⇒
        onMsg(m, s.pendingReplies)

      case Request(newConfirmedSeqNr, newRequestedSeqNr, supportResend, viaReceiveTimeout) ⇒
        ctx.log.infoN(
          "request confirmed [{}], requested [{}], current [{}]",
          newConfirmedSeqNr,
          newRequestedSeqNr,
          s.currentSeqNr)

        // FIXME Better to have a separate Ack message for confirmedSeqNr to be able to have
        // more frequent Ack than Request (incr window)

        // FIXME use more efficient Map for pendingReplies, sorted, maybe just `Vector[(Long, ActorRef)]`
        val newPendingReplies =
          if (s.pendingReplies.isEmpty)
            s.pendingReplies
          else {
            val replies = s.pendingReplies.keys.filter(_ <= newConfirmedSeqNr).toVector.sorted
            if (replies.nonEmpty)
              ctx.log.info("Confirmation replies from [{}] to [{}]", replies.min, replies.max)
            replies.foreach { seqNr =>
              s.pendingReplies(seqNr) ! seqNr
            }
            s.pendingReplies -- replies
          }

        val newUnconfirmed =
          if (supportResend) s.unconfirmed match {
            case Some(u) ⇒ Some(u.dropWhile(_.seqNr <= newConfirmedSeqNr))
            case None ⇒ Some(Vector.empty)
          } else None

        if (newConfirmedSeqNr == s.firstSeqNr)
          timers.cancel(ResendFirst)

        if (viaReceiveTimeout && newUnconfirmed.nonEmpty) {
          // the last message was lost and no more message was sent that would trigger Resend
          newUnconfirmed.foreach { u ⇒
            ctx.log.info("resending after ReceiveTimeout [{}]", u.map(_.seqNr).mkString(", "))
            u.foreach(s.send)
          }
        }

        if (newRequestedSeqNr > s.requestedSeqNr) {
          if (!s.requested && (newRequestedSeqNr - s.currentSeqNr) > 0)
            producer ! requestNextFactory(RequestNextParam(s.currentSeqNr, newConfirmedSeqNr, msgAdapter))
          active(
            s.copy(
              requested = true,
              confirmedSeqNr = math.max(s.confirmedSeqNr, newConfirmedSeqNr),
              requestedSeqNr = newRequestedSeqNr,
              pendingReplies = newPendingReplies,
              unconfirmed = newUnconfirmed))
        } else {
          active(
            s.copy(
              confirmedSeqNr = math.max(s.currentSeqNr, newConfirmedSeqNr),
              pendingReplies = newPendingReplies,
              unconfirmed = newUnconfirmed))
        }

      case Resend(fromSeqNr) ⇒
        s.unconfirmed match {
          case Some(u) ⇒
            val newUnconfirmed = u.dropWhile(_.seqNr < fromSeqNr)
            ctx.log.info("resending [{}]", newUnconfirmed.map(_.seqNr).mkString(", "))
            newUnconfirmed.foreach(s.send)
            active(s.copy(unconfirmed = Some(newUnconfirmed)))
          case None ⇒
            throw new IllegalStateException("Resend not supported, run the ConsumerController with resendLost = true")
        }

      case ResendFirst =>
        s.unconfirmed match {
          case Some(u) if u.nonEmpty && u.head.seqNr == s.firstSeqNr ⇒
            ctx.log.info("resending first, [{}]", s.firstSeqNr)
            s.send(u.head.copy(first = true)(ctx.self))
          case _ =>
            if (s.currentSeqNr > s.firstSeqNr)
              timers.cancel(ResendFirst)
        }
        Behaviors.same

      case RegisterConsumer(consumerController: ActorRef[ConsumerController.Command[A]] @unchecked, replyTo) =>
        val newFirstSeqNr =
          if (s.unconfirmed.isEmpty) s.currentSeqNr else s.unconfirmed.map(_.head.seqNr).getOrElse(s.currentSeqNr)
        ctx.log.info(
          "Register new ConsumerController [{}], starting with seqNr [{}].",
          consumerController,
          newFirstSeqNr)
        if (s.unconfirmed.nonEmpty) {
          timers.startTimerWithFixedDelay(ResendFirst, ResendFirst, 1.second)
          ctx.self ! ResendFirst
        }
        replyTo ! Done
        // update the send function
        val newSend = consumerController ! _
        active(s.copy(firstSeqNr = newFirstSeqNr, send = newSend))
    }
  }
}

// FIXME it must be possible to restart the producer, and then it needs to retrieve the request state,
// which would complicate the protocol. A more pragmatic, and easier to use, approach might be to
// allow for buffering of messages from the producer in the ProducerController if it it has no demand.
// Then the restarted producer can assume that it can send next message. As a recommendation it
// should not abuse this capability.

// FIXME there should also be a durable version of this (using EventSouredBehavior) that stores the
// unconfirmed messages before sending and stores ack event when confirmed.
