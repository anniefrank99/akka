/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import akka.actor.typed.ActorRef

// Implement with EventSourceBehavior in akka-persistence-typed
// FIXME how much of this should be public? Should it be possible to plug-in different implementation?
// FIXME consider a Future based SPI instead if we make it public
object DurableProducerQueue {

  type SeqNr = Long

  type ConfirmationQualifier = String

  val NoQualifier: ConfirmationQualifier = ""

  sealed trait Command[A]

  final case class LoadState[A](replyTo: ActorRef[State[A]]) extends Command[A]

  final case class StoreMessageSent[A](sent: MessageSent[A], replyTo: ActorRef[StoreMessageSentAck]) extends Command[A]

  final case class StoreMessageSentAck(storedSeqNr: SeqNr)

  final case class StoreMessageConfirmed[A](seqNr: SeqNr, confirmationQualifier: ConfirmationQualifier)
      extends Command[A]

  object State {
    def empty[A]: State[A] = State(1L, 0L, Map.empty, Vector.empty)
  }
  final case class State[A](
      currentSeqNr: SeqNr,
      highestConfirmedSeqNr: SeqNr,
      confirmedSeqNr: Map[ConfirmationQualifier, SeqNr],
      unconfirmed: Vector[MessageSent[A]])

  final case class MessageSent[A](seqNr: SeqNr, msg: A, ack: Boolean, confirmationQualifier: ConfirmationQualifier)

}
