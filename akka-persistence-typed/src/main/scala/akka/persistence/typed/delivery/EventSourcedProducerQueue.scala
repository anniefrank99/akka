/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.delivery

import scala.concurrent.duration._

import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.delivery.DurableProducerQueue
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.RetentionCriteria

/**
 * [[DurableProducerQueue]] that can be used with [[akka.actor.typed.delivery.ProducerController]]
 * for reliable delivery of messages. It is implemented with event sourcing and stores one
 * event before sending the message to the destination and one event for the confirmation
 * that the message has been delivered and processed.
 *
 * The [[DurableProducerQueue.LoadState]] request is used at startup to retrieve the unconfirmed messages.
 */
object EventSourcedProducerQueue {
  import DurableProducerQueue._

  def apply[A](persistenceId: PersistenceId): Behavior[DurableProducerQueue.Command[A]] = {
    Behaviors.setup { context =>
      context.setLoggerName(classOf[EventSourcedProducerQueue[A]])
      val impl = new EventSourcedProducerQueue[A](context)
      EventSourcedBehavior[Command[A], Event, State[A]](
        persistenceId,
        State.empty,
        (state, command) => impl.onCommand(state, command),
        (state, event) => impl.onEvent(state, event))
      // FIXME config snapshot numberOfEvents
        .withRetention(
          RetentionCriteria.snapshotEvery(numberOfEvents = 1000, keepNSnapshots = 2).withDeleteEventsOnSnapshot)
        // FIXME config of backoff
        .onPersistFailure(SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.1))
    }
  }

}

/**
 * INTERNAL API
 */
private class EventSourcedProducerQueue[A](context: ActorContext[DurableProducerQueue.Command[A]]) {
  import DurableProducerQueue._

  def onCommand(state: State[A], command: Command[A]): Effect[Event, State[A]] = {
    command match {
      case StoreMessageSent(sent, replyTo) =>
        if (sent.seqNr == state.currentSeqNr) {
          context.log.info(
            "StoreMessageSent seqNr [{}], confirmationQualifier [{}]",
            sent.seqNr,
            sent.confirmationQualifier)
          Effect.persist(sent).thenReply(replyTo)(_ => StoreMessageSentAck(sent.seqNr))
        } else if (sent.seqNr == state.currentSeqNr - 1) {
          // already stored, could be a retry after timout
          context.log.info("Duplicate seqNr [{}], currentSeqNr [{}]", sent.seqNr, state.currentSeqNr)
          Effect.reply(replyTo)(StoreMessageSentAck(sent.seqNr))
        } else {
          // may happen after failure
          context.log.info("Ignoring unexpected seqNr [{}], currentSeqNr [{}]", sent.seqNr, state.currentSeqNr)
          Effect.unhandled // no reply, request will timeout
        }

      case StoreMessageConfirmed(seqNr, confirmationQualifier) =>
        context.log.info("StoreMessageConfirmed seqNr [{}], confirmationQualifier [{}]", seqNr, confirmationQualifier)
        if (seqNr > state.confirmedSeqNr.getOrElse(confirmationQualifier, 0L))
          Effect.persist(Confirmed(seqNr, confirmationQualifier))
        else
          Effect.none // duplicate

      case LoadState(replyTo) =>
        Effect.reply(replyTo)(state)
    }
  }

  def onEvent(state: State[A], event: Event): State[A] = {
    event match {
      case sent: MessageSent[A] @unchecked =>
        state.copy(currentSeqNr = sent.seqNr + 1, unconfirmed = state.unconfirmed :+ sent)
      case Confirmed(seqNr, confirmationQualifier) =>
        val newUnconfirmed = state.unconfirmed.filterNot { u =>
          u.confirmationQualifier == confirmationQualifier && u.seqNr <= seqNr
        }
        state.copy(
          highestConfirmedSeqNr = math.max(state.highestConfirmedSeqNr, seqNr),
          confirmedSeqNr = state.confirmedSeqNr.updated(confirmationQualifier, seqNr),
          unconfirmed = newUnconfirmed)
    }
  }

  // TODO for sharding it can become many different confirmation qualifier and there should be
  // a cleanup task removing qualifiers from `state.confirmedSeqNr` that have not been used for a while.

}
