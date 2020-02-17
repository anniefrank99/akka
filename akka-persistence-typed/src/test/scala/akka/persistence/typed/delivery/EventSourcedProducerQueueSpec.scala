/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.delivery

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.internal.delivery.DurableProducerQueue.Confirmed
import akka.actor.typed.internal.delivery.DurableProducerQueue.LoadState
import akka.actor.typed.internal.delivery.DurableProducerQueue.MessageSent
import akka.actor.typed.internal.delivery.DurableProducerQueue.NoQualifier
import akka.actor.typed.internal.delivery.DurableProducerQueue.State
import akka.actor.typed.internal.delivery.DurableProducerQueue.StoreMessageConfirmed
import akka.actor.typed.internal.delivery.DurableProducerQueue.StoreMessageSent
import akka.actor.typed.internal.delivery.DurableProducerQueue.StoreMessageSentAck
import akka.persistence.journal.inmem.InmemJournal
import akka.persistence.typed.PersistenceId
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object EventSourcedProducerQueueSpec {
  def conf: Config =
    ConfigFactory.parseString(s"""
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    akka.persistence.journal.inmem.test-serialization = off # FIXME on
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/EventSourcedDurableProducerQueueSpec-${UUID
      .randomUUID()
      .toString}"
    """)
}

class EventSourcedProducerQueueSpec
    extends ScalaTestWithActorTestKit(ReliableDeliveryWithEventSourcedProducerQueueSpec.conf)
    with AnyWordSpecLike
    with LogCapturing {

  private val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId.ofUniqueId(s"pid-${pidCounter.incrementAndGet()})")

  private val journalOperations = createTestProbe[InmemJournal.Operation]()
  system.eventStream ! EventStream.Subscribe(journalOperations.ref)

  private val stateProbe = createTestProbe[State[String]]()

  "EventSourcedDurableProducerQueue" must {

    "persist MessageSent" in {
      val pid = nextPid()
      val ackProbe = createTestProbe[StoreMessageSentAck]()
      val queue = spawn(EventSourcedProducerQueue[String](pid))

      val msg1 = MessageSent(seqNr = 1, "a", ack = true, NoQualifier)
      queue ! StoreMessageSent(msg1, ackProbe.ref)
      ackProbe.expectMessage(StoreMessageSentAck(storedSeqNr = 1))
      journalOperations.expectMessage(InmemJournal.Write(msg1, pid.id, 1))

      val msg2 = MessageSent(seqNr = 2, "b", ack = true, NoQualifier)
      queue ! StoreMessageSent(msg2, ackProbe.ref)
      ackProbe.expectMessage(StoreMessageSentAck(storedSeqNr = 2))
      journalOperations.expectMessage(InmemJournal.Write(msg2, pid.id, 2))

      queue ! LoadState(stateProbe.ref)
      val expectedState =
        State(currentSeqNr = 3, highestConfirmedSeqNr = 0, confirmedSeqNr = Map.empty, unconfirmed = Vector(msg1, msg2))
      stateProbe.expectMessage(expectedState)

      // replay
      testKit.stop(queue)
      val queue2 = spawn(EventSourcedProducerQueue[String](pid))
      queue2 ! LoadState(stateProbe.ref)
      stateProbe.expectMessage(expectedState)
    }

    "not persist MessageSent if lower seqNr than already stored" in {
      val pid = nextPid()
      val ackProbe = createTestProbe[StoreMessageSentAck]()
      val queue = spawn(EventSourcedProducerQueue[String](pid))

      val msg1 = MessageSent(seqNr = 1, "a", ack = true, NoQualifier)
      queue ! StoreMessageSent(msg1, ackProbe.ref)
      ackProbe.expectMessage(StoreMessageSentAck(storedSeqNr = 1))
      journalOperations.expectMessage(InmemJournal.Write(msg1, pid.id, 1))

      val msg2 = MessageSent(seqNr = 2, "b", ack = true, NoQualifier)
      queue ! StoreMessageSent(msg2, ackProbe.ref)
      ackProbe.expectMessage(StoreMessageSentAck(storedSeqNr = 2))
      journalOperations.expectMessage(InmemJournal.Write(msg2, pid.id, 2))

      // duplicate msg2
      queue ! StoreMessageSent(msg2, ackProbe.ref)
      ackProbe.expectMessage(StoreMessageSentAck(storedSeqNr = 2))
      journalOperations.expectNoMessage()

      // further back is ignored
      queue ! StoreMessageSent(msg1, ackProbe.ref)
      ackProbe.expectNoMessage()
      journalOperations.expectNoMessage()
    }

    "persist Confirmed" in {
      val pid = nextPid()
      val ackProbe = createTestProbe[StoreMessageSentAck]()
      val queue = spawn(EventSourcedProducerQueue[String](pid))

      val msg1 = MessageSent(seqNr = 1, "a", ack = true, NoQualifier)
      queue ! StoreMessageSent(msg1, ackProbe.ref)
      journalOperations.expectMessage(InmemJournal.Write(msg1, pid.id, 1))

      val msg2 = MessageSent(seqNr = 2, "b", ack = true, NoQualifier)
      queue ! StoreMessageSent(msg2, ackProbe.ref)
      journalOperations.expectMessage(InmemJournal.Write(msg2, pid.id, 2))

      val msg3 = MessageSent(seqNr = 3, "c", ack = true, NoQualifier)
      queue ! StoreMessageSent(msg3, ackProbe.ref)
      journalOperations.expectMessage(InmemJournal.Write(msg3, pid.id, 3))

      queue ! StoreMessageConfirmed(seqNr = 2, NoQualifier)
      journalOperations.expectMessage(InmemJournal.Write(Confirmed(seqNr = 2, NoQualifier), pid.id, 4))

      queue ! LoadState(stateProbe.ref)
      // note that msg1 is also confirmed (removed) by the confirmation of msg2
      val expectedState =
        State(
          currentSeqNr = 4,
          highestConfirmedSeqNr = 2,
          confirmedSeqNr = Map(NoQualifier -> 2),
          unconfirmed = Vector(msg3))
      stateProbe.expectMessage(expectedState)

      // replay
      testKit.stop(queue)
      val queue2 = spawn(EventSourcedProducerQueue[String](pid))
      queue2 ! LoadState(stateProbe.ref)
      stateProbe.expectMessage(expectedState)
    }

    "not persist Confirmed with lower seqNr than already confirmed" in {
      val pid = nextPid()
      val ackProbe = createTestProbe[StoreMessageSentAck]()
      val queue = spawn(EventSourcedProducerQueue[String](pid))

      val msg1 = MessageSent(seqNr = 1, "a", ack = true, NoQualifier)
      queue ! StoreMessageSent(msg1, ackProbe.ref)
      journalOperations.expectMessage(InmemJournal.Write(msg1, pid.id, 1))

      val msg2 = MessageSent(seqNr = 2, "b", ack = true, NoQualifier)
      queue ! StoreMessageSent(msg2, ackProbe.ref)
      journalOperations.expectMessage(InmemJournal.Write(msg2, pid.id, 2))

      queue ! StoreMessageConfirmed(seqNr = 2, NoQualifier)
      journalOperations.expectMessage(InmemJournal.Write(Confirmed(seqNr = 2, NoQualifier), pid.id, 3))

      // lower
      queue ! StoreMessageConfirmed(seqNr = 1, NoQualifier)
      journalOperations.expectNoMessage()

      // duplicate
      queue ! StoreMessageConfirmed(seqNr = 2, NoQualifier)
      journalOperations.expectNoMessage()
    }

    "keep track of confirmations per confirmationQualifier" in {
      val pid = nextPid()
      val ackProbe = createTestProbe[StoreMessageSentAck]()
      val queue = spawn(EventSourcedProducerQueue[String](pid))

      val msg1 = MessageSent(seqNr = 1, "a", ack = true, confirmationQualifier = "q1")
      queue ! StoreMessageSent(msg1, ackProbe.ref)
      journalOperations.expectMessage(InmemJournal.Write(msg1, pid.id, 1))

      val msg2 = MessageSent(seqNr = 2, "b", ack = true, confirmationQualifier = "q1")
      queue ! StoreMessageSent(msg2, ackProbe.ref)
      journalOperations.expectMessage(InmemJournal.Write(msg2, pid.id, 2))

      val msg3 = MessageSent(seqNr = 3, "c", ack = true, "q2")
      queue ! StoreMessageSent(msg3, ackProbe.ref)
      journalOperations.expectMessage(InmemJournal.Write(msg3, pid.id, 3))

      val msg4 = MessageSent(seqNr = 4, "d", ack = true, "q2")
      queue ! StoreMessageSent(msg4, ackProbe.ref)
      journalOperations.expectMessage(InmemJournal.Write(msg4, pid.id, 4))

      val msg5 = MessageSent(seqNr = 5, "e", ack = true, "q2")
      queue ! StoreMessageSent(msg5, ackProbe.ref)
      journalOperations.expectMessage(InmemJournal.Write(msg5, pid.id, 5))

      queue ! StoreMessageConfirmed(seqNr = 4, "q2")
      journalOperations.expectMessage(InmemJournal.Write(Confirmed(seqNr = 4, "q2"), pid.id, 6))

      queue ! LoadState(stateProbe.ref)
      // note that msg3 is also confirmed (removed) by the confirmation of msg4, same qualifier
      // but msg1 and msg2 are still unconfirmed
      val expectedState =
        State(
          currentSeqNr = 6,
          highestConfirmedSeqNr = 4,
          confirmedSeqNr = Map("q2" -> 4),
          unconfirmed = Vector(msg1, msg2, msg5))
      stateProbe.expectMessage(expectedState)

      // replay
      testKit.stop(queue)
      val queue2 = spawn(EventSourcedProducerQueue[String](pid))
      queue2 ! LoadState(stateProbe.ref)
      stateProbe.expectMessage(expectedState)
    }

  }

}
