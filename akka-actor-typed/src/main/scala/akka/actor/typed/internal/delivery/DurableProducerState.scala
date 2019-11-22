/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import akka.actor.typed.ActorRef

object DurableProducerState {

  sealed trait Command[A]

  final case class LoadState[A](replyTo: ActorRef[State[A]]) extends Command[A]

  final case class StoreMessageSent[A](sent: MessageSent[A], replyTo: ActorRef[StoreMessageSentAck]) extends Command[A]

  final case class StoreMessageSentAck(confirmedSeqNr: Long)

  final case class StoreMessageConfirmed[A](confirmedSeqNr: Long) extends Command[A]

  final case class State[A](
      currentSeqNr: Long,
      confirmedSeqNr: Long,
      unconfirmed: Vector[MessageSent[A]],
      firstSeqNr: Long)

  final case class MessageSent[A](seqNr: Long, msg: A, first: Boolean, ack: Boolean)

}
