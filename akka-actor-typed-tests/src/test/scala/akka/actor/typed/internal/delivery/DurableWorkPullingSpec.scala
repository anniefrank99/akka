/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._

import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.internal.delivery.DurableProducerQueue.MessageSent
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import org.scalatest.WordSpecLike

class DurableWorkPullingSpec extends ScalaTestWithActorTestKit with WordSpecLike with LogCapturing {
  import DurableProducerQueue.NoQualifier

  private var idCount = 0
  private def nextId(): Int = {
    idCount += 1
    idCount
  }

  private def producerId: String = s"p-$idCount"

  private def awaitWorkersRegistered(
      controller: ActorRef[WorkPullingProducerController.Command[TestConsumer.Job]],
      count: Int): Unit = {
    val probe = createTestProbe[WorkPullingProducerController.WorkerStats]()
    probe.awaitAssert {
      controller ! WorkPullingProducerController.GetWorkerStats(probe.ref)
      probe.receiveMessage().numberOfWorkers should ===(count)
    }
  }

  val workerServiceKey: ServiceKey[ConsumerController.Command[TestConsumer.Job]] = ServiceKey("worker")

  // don't compare the UUID fields
  private def assertState(
      s: DurableProducerQueue.State[TestConsumer.Job],
      expected: DurableProducerQueue.State[TestConsumer.Job]): Unit = {

    def cleanup(a: DurableProducerQueue.State[TestConsumer.Job]) =
      a.copy(
        confirmedSeqNr = Map.empty,
        unconfirmed = s.unconfirmed.map(m => m.copy(confirmationQualifier = DurableProducerQueue.NoQualifier)))

    cleanup(s) should ===(cleanup(expected))
  }

  "ReliableDelivery with work-pulling and durable queue" must {

    "load initial state and resend unconfirmed" in {
      nextId()

      val durable = TestDurableProducerQueue[TestConsumer.Job](
        Duration.Zero,
        DurableProducerQueue.State(
          currentSeqNr = 5,
          highestConfirmedSeqNr = 2,
          confirmedSeqNr = Map(NoQualifier -> 2),
          unconfirmed = Vector(
            DurableProducerQueue.MessageSent(3, TestConsumer.Job("msg-3"), false, NoQualifier),
            DurableProducerQueue.MessageSent(4, TestConsumer.Job("msg-4"), false, NoQualifier))))

      val workPullingController =
        spawn(
          WorkPullingProducerController[TestConsumer.Job](producerId, workerServiceKey, Some(durable)),
          s"workPullingController-${idCount}")
      val producerProbe = createTestProbe[WorkPullingProducerController.RequestNext[TestConsumer.Job]]()
      workPullingController ! WorkPullingProducerController.Start(producerProbe.ref)

      val workerController1Probe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()
      system.receptionist ! Receptionist.Register(workerServiceKey, workerController1Probe.ref)
      awaitWorkersRegistered(workPullingController, 1)

      // no request to producer since it has unconfirmed to begin with
      producerProbe.expectNoMessage()

      val seqMsg3 = workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg3.msg should ===(TestConsumer.Job("msg-3"))
      seqMsg3.producer ! ProducerController.Internal.Request(1L, 10L, true, false)

      workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]].msg should ===(
        TestConsumer.Job("msg-4"))
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-5")

      workerController1Probe.stop()
      awaitWorkersRegistered(workPullingController, 0)
      testKit.stop(workPullingController)
    }

    "reply to MessageWithConfirmation after storage" in {
      import WorkPullingProducerController.MessageWithConfirmation
      nextId()
      val durable =
        TestDurableProducerQueue[TestConsumer.Job](Duration.Zero, DurableProducerQueue.State.empty[TestConsumer.Job])
      val workPullingController =
        spawn(
          WorkPullingProducerController[TestConsumer.Job](producerId, workerServiceKey, Some(durable)),
          s"workPullingController-${idCount}")
      val producerProbe = createTestProbe[WorkPullingProducerController.RequestNext[TestConsumer.Job]]()
      workPullingController ! WorkPullingProducerController.Start(producerProbe.ref)

      val workerController1Probe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()
      system.receptionist ! Receptionist.Register(workerServiceKey, workerController1Probe.ref)
      awaitWorkersRegistered(workPullingController, 1)

      val replyProbe = createTestProbe[Done]()
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-1"), replyProbe.ref)
      val seqMsg1 = workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg1.msg should ===(TestConsumer.Job("msg-1"))
      seqMsg1.ack should ===(true)
      seqMsg1.producer ! ProducerController.Internal.Request(1L, 10L, true, false)
      replyProbe.receiveMessage()

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-2"), replyProbe.ref)
      // reply after storage, doesn't wait for ack from consumer
      replyProbe.receiveMessage()
      val seqMsg2 = workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg2.msg should ===(TestConsumer.Job("msg-2"))
      seqMsg2.ack should ===(true)
      seqMsg2.producer ! ProducerController.Internal.Ack(2L)

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-3"), replyProbe.ref)
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-4"), replyProbe.ref)
      replyProbe.receiveMessages(2)
      workerController1Probe.receiveMessages(2)
      seqMsg2.producer ! ProducerController.Internal.Ack(4L)

      workerController1Probe.stop()
      awaitWorkersRegistered(workPullingController, 0)
      testKit.stop(workPullingController)
    }

    "store confirmations" in {
      import WorkPullingProducerController.MessageWithConfirmation
      nextId()

      val stateHolder =
        new AtomicReference[DurableProducerQueue.State[TestConsumer.Job]](DurableProducerQueue.State.empty)
      val durable = TestDurableProducerQueue[TestConsumer.Job](
        Duration.Zero,
        stateHolder,
        (_: DurableProducerQueue.Command[_]) => false)

      val workPullingController =
        spawn(
          WorkPullingProducerController[TestConsumer.Job](producerId, workerServiceKey, Some(durable)),
          s"workPullingController-${idCount}")
      val producerProbe = createTestProbe[WorkPullingProducerController.RequestNext[TestConsumer.Job]]()
      workPullingController ! WorkPullingProducerController.Start(producerProbe.ref)

      val workerController1Probe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()
      system.receptionist ! Receptionist.Register(workerServiceKey, workerController1Probe.ref)
      awaitWorkersRegistered(workPullingController, 1)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      producerProbe.awaitAssert {
        assertState(
          stateHolder.get(),
          DurableProducerQueue
            .State(2, 0, Map.empty, Vector(MessageSent(1, TestConsumer.Job("msg-1"), ack = false, NoQualifier))))
      }
      val seqMsg1 = workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg1.msg should ===(TestConsumer.Job("msg-1"))
      seqMsg1.producer ! ProducerController.Internal.Request(1L, 5L, true, false)
      producerProbe.awaitAssert {
        assertState(stateHolder.get(), DurableProducerQueue.State(2, 1, Map.empty, Vector.empty))
      }

      val replyTo = createTestProbe[Done]()
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-2"), replyTo.ref)
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-3")
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-4"), replyTo.ref)
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-5")
      workerController1Probe.receiveMessage() // msg-2
      workerController1Probe.receiveMessage() // msg-3
      workerController1Probe.receiveMessage() // msg-4
      val seqMsg5 = workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg5.seqNr should ===(5)

      // no more demand, since 5 messages sent but no Ack
      producerProbe.expectNoMessage()
      producerProbe.awaitAssert {
        assertState(
          stateHolder.get(),
          DurableProducerQueue.State(
            6,
            1,
            Map.empty,
            Vector(
              MessageSent(2, TestConsumer.Job("msg-2"), ack = true, NoQualifier),
              MessageSent(3, TestConsumer.Job("msg-3"), ack = false, NoQualifier),
              MessageSent(4, TestConsumer.Job("msg-4"), ack = true, NoQualifier),
              MessageSent(5, TestConsumer.Job("msg-5"), ack = false, NoQualifier))))
      }

      // start another worker
      val workerController2Probe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()
      system.receptionist ! Receptionist.Register(workerServiceKey, workerController2Probe.ref)
      awaitWorkersRegistered(workPullingController, 2)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-6")
      producerProbe.awaitAssert {
        assertState(
          stateHolder.get(),
          DurableProducerQueue.State(
            7,
            1,
            Map.empty,
            Vector(
              MessageSent(2, TestConsumer.Job("msg-2"), ack = true, NoQualifier),
              MessageSent(3, TestConsumer.Job("msg-3"), ack = false, NoQualifier),
              MessageSent(4, TestConsumer.Job("msg-4"), ack = true, NoQualifier),
              MessageSent(5, TestConsumer.Job("msg-5"), ack = false, NoQualifier),
              MessageSent(6, TestConsumer.Job("msg-6"), ack = false, NoQualifier))))
      }
      val seqMsg6 = workerController2Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg6.msg should ===(TestConsumer.Job("msg-6"))
      seqMsg6.seqNr should ===(1) // different ProducerController-ConsumerController
      seqMsg6.producer ! ProducerController.Internal.Request(1L, 5L, true, false)
      producerProbe.awaitAssert {
        assertState(
          stateHolder.get(),
          DurableProducerQueue.State(
            7,
            6,
            Map.empty,
            Vector(
              MessageSent(2, TestConsumer.Job("msg-2"), ack = true, NoQualifier),
              MessageSent(3, TestConsumer.Job("msg-3"), ack = false, NoQualifier),
              MessageSent(4, TestConsumer.Job("msg-4"), ack = true, NoQualifier),
              MessageSent(5, TestConsumer.Job("msg-5"), ack = false, NoQualifier))))
      }

      seqMsg1.producer ! ProducerController.Internal.Ack(3)
      producerProbe.awaitAssert {
        assertState(
          stateHolder.get(),
          DurableProducerQueue.State(
            7,
            6,
            Map.empty,
            Vector(
              MessageSent(4, TestConsumer.Job("msg-4"), ack = true, NoQualifier),
              MessageSent(5, TestConsumer.Job("msg-5"), ack = false, NoQualifier))))
      }

      workerController1Probe.stop()
      workerController2Probe.stop()
      awaitWorkersRegistered(workPullingController, 0)
      testKit.stop(workPullingController)
    }

    "hand over, and resend unconfirmed when worker is unregistered" in {
      nextId()

      val stateHolder =
        new AtomicReference[DurableProducerQueue.State[TestConsumer.Job]](DurableProducerQueue.State.empty)
      val durable = TestDurableProducerQueue[TestConsumer.Job](
        Duration.Zero,
        stateHolder,
        (_: DurableProducerQueue.Command[_]) => false)

      val workPullingController =
        spawn(
          WorkPullingProducerController[TestConsumer.Job](producerId, workerServiceKey, Some(durable)),
          s"workPullingController-${idCount}")
      val producerProbe = createTestProbe[WorkPullingProducerController.RequestNext[TestConsumer.Job]]()
      workPullingController ! WorkPullingProducerController.Start(producerProbe.ref)

      val workerController1Probe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()
      system.receptionist ! Receptionist.Register(workerServiceKey, workerController1Probe.ref)
      awaitWorkersRegistered(workPullingController, 1)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      producerProbe.awaitAssert {
        assertState(
          stateHolder.get(),
          DurableProducerQueue
            .State(2, 0, Map.empty, Vector(MessageSent(1, TestConsumer.Job("msg-1"), ack = false, NoQualifier))))
      }
      val seqMsg1 = workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg1.msg should ===(TestConsumer.Job("msg-1"))
      seqMsg1.producer ! ProducerController.Internal.Request(1L, 5L, true, false)
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-2")
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-3")
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-4")
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-5")
      workerController1Probe.receiveMessage() // msg-2
      workerController1Probe.receiveMessage() // msg-3
      workerController1Probe.receiveMessage() // msg-4
      workerController1Probe.receiveMessage() // msg-5

      // no more demand, since 5 messages sent but no Ack
      producerProbe.expectNoMessage()
      producerProbe.awaitAssert {
        assertState(
          stateHolder.get(),
          DurableProducerQueue.State(
            6,
            1,
            Map.empty,
            Vector(
              MessageSent(2, TestConsumer.Job("msg-2"), ack = false, NoQualifier),
              MessageSent(3, TestConsumer.Job("msg-3"), ack = false, NoQualifier),
              MessageSent(4, TestConsumer.Job("msg-4"), ack = false, NoQualifier),
              MessageSent(5, TestConsumer.Job("msg-5"), ack = false, NoQualifier))))
      }

      // start another worker
      val workerController2Probe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()
      system.receptionist ! Receptionist.Register(workerServiceKey, workerController2Probe.ref)
      awaitWorkersRegistered(workPullingController, 2)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-6")
      val seqMsg6 = workerController2Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg6.msg should ===(TestConsumer.Job("msg-6"))
      // note that it's only requesting 3
      seqMsg6.producer ! ProducerController.Internal.Request(1L, 3L, true, false)
      producerProbe.awaitAssert {
        assertState(
          stateHolder.get(),
          DurableProducerQueue.State(
            7,
            6,
            Map.empty,
            Vector(
              MessageSent(2, TestConsumer.Job("msg-2"), ack = true, NoQualifier),
              MessageSent(3, TestConsumer.Job("msg-3"), ack = false, NoQualifier),
              MessageSent(4, TestConsumer.Job("msg-4"), ack = true, NoQualifier),
              MessageSent(5, TestConsumer.Job("msg-5"), ack = false, NoQualifier))))
      }

      workerController1Probe.stop()
      awaitWorkersRegistered(workPullingController, 1)

      // msg-2, msg-3, msg-4, msg-5 were originally sent to worker1, but not confirmed
      // so they will be resent and delivered to worker2
      val seqMsg7 = workerController2Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg7.msg should ===(TestConsumer.Job("msg-2"))
      val seqMsg8 = workerController2Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg8.msg should ===(TestConsumer.Job("msg-3"))
      seqMsg8.seqNr should ===(3)
      // but it has only requested 3 so no more
      workerController2Probe.expectNoMessage()
      // then request more, and confirm 3
      seqMsg8.producer ! ProducerController.Internal.Request(3L, 10L, true, false)
      val seqMsg9 = workerController2Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg9.msg should ===(TestConsumer.Job("msg-4"))
      val seqMsg10 = workerController2Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg10.msg should ===(TestConsumer.Job("msg-5"))

      seqMsg9.producer ! ProducerController.Internal.Ack(seqMsg9.seqNr)
      producerProbe.awaitAssert {
        assertState(
          stateHolder.get(),
          DurableProducerQueue.State(
            11,
            9,
            Map.empty,
            Vector(
              // note that it has a different seqNr than before
              MessageSent(10, TestConsumer.Job("msg-5"), ack = false, NoQualifier))))
      }

      workerController1Probe.stop()
      workerController2Probe.stop()
      awaitWorkersRegistered(workPullingController, 0)
      testKit.stop(workPullingController)
    }

  }

}
