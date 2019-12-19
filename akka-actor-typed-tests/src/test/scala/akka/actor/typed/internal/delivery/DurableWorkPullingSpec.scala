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

  "ReliableDelivery with work-pulling and durable queue" must {

    "load initial state and resend unconfirmed" in {
      nextId()

      val durable = TestDurableProducerQueue[TestConsumer.Job](
        Duration.Zero,
        DurableProducerQueue.State(
          currentSeqNr = 5,
          confirmedSeqNr = 2,
          unconfirmed = Vector(
            DurableProducerQueue.MessageSent(3, TestConsumer.Job("msg-3"), false),
            DurableProducerQueue.MessageSent(4, TestConsumer.Job("msg-4"), false))))

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
        stateHolder.get() should ===(
          DurableProducerQueue.State(2, 0, Vector(MessageSent(1, TestConsumer.Job("msg-1"), ack = false))))
      }
      val seqMsg1 = workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg1.msg should ===(TestConsumer.Job("msg-1"))
      seqMsg1.producer ! ProducerController.Internal.Request(1L, 10L, true, false)
      producerProbe.awaitAssert {
        stateHolder.get() should ===(DurableProducerQueue.State(2, 1, Vector.empty))
      }

      val replyTo = createTestProbe[Done]()
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-2"), replyTo.ref)
      workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-3"), replyTo.ref)
      workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-4"), replyTo.ref)
      workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg1.producer ! ProducerController.Internal.Ack(3)
      producerProbe.awaitAssert {
        stateHolder.get() should ===(
          DurableProducerQueue.State(5, 3, Vector(MessageSent(4, TestConsumer.Job("msg-4"), ack = true))))
      }

      workerController1Probe.stop()
      awaitWorkersRegistered(workPullingController, 0)
      testKit.stop(workPullingController)
    }

  }

}
