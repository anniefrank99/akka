/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import scala.collection.immutable
import akka.actor.typed.ActorRef
import akka.annotation.InternalApi

/**
 * Actor message protocol for storing and confirming reliable delivery of messages. A [[akka.actor.typed.Behavior]]
 * implementation of this protocol can optionally be used with [[ProducerController]] when messages shall survive
 * a crash of the producer side.
 *
 * An implementation of this exists in `akka.persistence.typed.delivery.EventSourcedProducerQueue`.
 */
object DurableProducerQueue {

  type SeqNr = Long

  type ConfirmationQualifier = String

  val NoQualifier: ConfirmationQualifier = ""

  sealed trait Command[A]

  /**
   * Request that is used at startup to retrieve the unconfirmed messages and current sequence number..
   */
  final case class LoadState[A](replyTo: ActorRef[State[A]]) extends Command[A]

  /**
   * Store the fact that a message is to be sent. Replies with [[StoreMessageSentAck]] when
   * the message has been successfully been stored.
   */
  final case class StoreMessageSent[A](sent: MessageSent[A], replyTo: ActorRef[StoreMessageSentAck]) extends Command[A]

  final case class StoreMessageSentAck(storedSeqNr: SeqNr)

  /**
   * Store the fact that a message has been confirmed to be delivered and processed.
   */
  final case class StoreMessageConfirmed[A](seqNr: SeqNr, confirmationQualifier: ConfirmationQualifier)
      extends Command[A]

  object State {
    def empty[A]: State[A] = State(1L, 0L, Map.empty, Vector.empty)
  }
  final case class State[A](
      currentSeqNr: SeqNr,
      highestConfirmedSeqNr: SeqNr,
      confirmedSeqNr: Map[ConfirmationQualifier, SeqNr],
      unconfirmed: immutable.IndexedSeq[MessageSent[A]])

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] sealed trait Event

  /**
   * The fact (event) that a message has been sent.
   */
  final case class MessageSent[A](seqNr: SeqNr, msg: A, ack: Boolean, confirmationQualifier: ConfirmationQualifier)
      extends Event

  /**
   * INTERNAL API: The fact (event) that a message has been confirmed to be delivered and processed.
   */
  @InternalApi private[akka] final case class Confirmed[A](seqNr: SeqNr, confirmationQualifier: ConfirmationQualifier)
      extends Event

}
