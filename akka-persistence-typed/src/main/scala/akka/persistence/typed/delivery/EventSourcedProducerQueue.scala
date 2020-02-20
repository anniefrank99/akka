/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.delivery

import java.time.{ Duration => JavaDuration }

import scala.concurrent.duration._

import akka.util.JavaDurationConverters._
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.delivery.DurableProducerQueue
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.ApiMayChange
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.RetentionCriteria
import com.typesafe.config.Config

/**
 * [[DurableProducerQueue]] that can be used with [[akka.actor.typed.delivery.ProducerController]]
 * for reliable delivery of messages. It is implemented with event sourcing and stores one
 * event before sending the message to the destination and one event for the confirmation
 * that the message has been delivered and processed.
 *
 * The [[DurableProducerQueue.LoadState]] request is used at startup to retrieve the unconfirmed messages.
 */
@ApiMayChange
object EventSourcedProducerQueue {
  import DurableProducerQueue._

  object Settings {

    /**
     * Scala API: Factory method from config `akka.reliable-delivery.producer-controller.event-sourced-durable-queue`
     * of the `ActorSystem`.
     */
    def apply(system: ActorSystem[_]): Settings =
      apply(system.settings.config.getConfig("akka.reliable-delivery.producer-controller.event-sourced-durable-queue"))

    /**
     * Scala API: Factory method from Config corresponding to
     * `akka.reliable-delivery.producer-controller.event-sourced-durable-queue`.
     */
    def apply(config: Config): Settings = {
      new Settings(
        restartMaxBackoff = config.getDuration("restart-max-backoff").asScala,
        snapshotEvery = config.getInt("snapshot-every"),
        keepNSnapshots = config.getInt("keep-n-snapshots"),
        deleteEvents = config.getBoolean("delete-events"))
    }

    /**
     * Java API: Factory method from config `akka.reliable-delivery.producer-controller.event-sourced-durable-queue`
     * of the `ActorSystem`.
     */
    def create(system: ActorSystem[_]): Settings =
      apply(system)

    /**
     * java API: Factory method from Config corresponding to
     * `akka.reliable-delivery.producer-controller.event-sourced-durable-queue`.
     */
    def create(config: Config): Settings =
      apply(config)
  }

  final class Settings private (
      val restartMaxBackoff: FiniteDuration,
      val snapshotEvery: Int,
      val keepNSnapshots: Int,
      val deleteEvents: Boolean) {

    // FIXME add journal and snapshot plugin config

    def withSnapshotEvery(newSnapshotEvery: Int): Settings =
      copy(snapshotEvery = newSnapshotEvery)

    def withKeepNSnapshots(newKeepNSnapshots: Int): Settings =
      copy(keepNSnapshots = newKeepNSnapshots)

    def withDeleteEvents(newDeleteEvents: Boolean): Settings =
      copy(deleteEvents = newDeleteEvents)

    /**
     * Scala API
     */
    def withRestartMaxBackoff(newRestartMaxBackoff: FiniteDuration): Settings =
      copy(restartMaxBackoff = newRestartMaxBackoff)

    /**
     * Java API
     */
    def withRestartMaxBackoff(newRestartMaxBackoff: JavaDuration): Settings =
      copy(restartMaxBackoff = newRestartMaxBackoff.asScala)

    /**
     * Java API
     */
    def getRestartMaxBackoff(): JavaDuration =
      restartMaxBackoff.asJava

    /**
     * Private copy method for internal use only.
     */
    private def copy(
        restartMaxBackoff: FiniteDuration = restartMaxBackoff,
        snapshotEvery: Int = snapshotEvery,
        keepNSnapshots: Int = keepNSnapshots,
        deleteEvents: Boolean = deleteEvents) =
      new Settings(restartMaxBackoff, snapshotEvery, keepNSnapshots, deleteEvents)

    override def toString: String =
      s"Settings($restartMaxBackoff, $snapshotEvery, $keepNSnapshots, $deleteEvents)"
  }

  def apply[A](persistenceId: PersistenceId): Behavior[DurableProducerQueue.Command[A]] = {
    Behaviors.setup { context =>
      apply(persistenceId, Settings(context.system))
    }
  }

  def apply[A](persistenceId: PersistenceId, settings: Settings): Behavior[DurableProducerQueue.Command[A]] = {
    Behaviors.setup { context =>
      context.setLoggerName(classOf[EventSourcedProducerQueue[A]])
      val impl = new EventSourcedProducerQueue[A](context)

      val retentionCriteria = RetentionCriteria.snapshotEvery(
        numberOfEvents = settings.snapshotEvery,
        keepNSnapshots = settings.keepNSnapshots)
      val retentionCriteria2 =
        if (settings.deleteEvents) retentionCriteria.withDeleteEventsOnSnapshot else retentionCriteria

      EventSourcedBehavior[Command[A], Event, State[A]](
        persistenceId,
        State.empty,
        (state, command) => impl.onCommand(state, command),
        (state, event) => impl.onEvent(state, event))
        .withRetention(retentionCriteria2)
        .onPersistFailure(SupervisorStrategy
          .restartWithBackoff(1.second.min(settings.restartMaxBackoff), settings.restartMaxBackoff, 0.1))
    }
  }

  /**
   * Java API
   */
  def create[A](persistenceId: PersistenceId): Behavior[DurableProducerQueue.Command[A]] =
    apply(persistenceId)

  /**
   * Java API
   */
  def create[A](persistenceId: PersistenceId, settings: Settings): Behavior[DurableProducerQueue.Command[A]] =
    apply(persistenceId, settings)

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
