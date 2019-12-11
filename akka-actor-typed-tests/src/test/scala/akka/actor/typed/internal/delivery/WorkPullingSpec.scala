/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import scala.concurrent.duration._

import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.WordSpecLike

object WorkPullingSpec {
  object TestProducerWorkPulling {

    trait Command
    final case class RequestNext(sendTo: ActorRef[TestConsumer.Job]) extends Command
    private final case object Tick extends Command

    def apply(
        delay: FiniteDuration,
        producerController: ActorRef[WorkPullingProducerController.Start[TestConsumer.Job]]): Behavior[Command] = {
      Behaviors.setup { context =>
        context.setLoggerName("TestProducerWorkPulling")
        val requestNextAdapter: ActorRef[WorkPullingProducerController.RequestNext[TestConsumer.Job]] =
          context.messageAdapter(req => RequestNext(req.sendNextTo))
        producerController ! WorkPullingProducerController.Start(requestNextAdapter)

        Behaviors.withTimers { timers =>
          timers.startTimerWithFixedDelay(Tick, Tick, delay)
          idle(0)
        }
      }
    }

    private def idle(n: Int): Behavior[Command] = {
      Behaviors.receiveMessage {
        case Tick                => Behaviors.same
        case RequestNext(sendTo) => active(n + 1, sendTo)
      }
    }

    private def active(n: Int, sendTo: ActorRef[TestConsumer.Job]): Behavior[Command] = {
      Behaviors.receive { (ctx, msg) =>
        msg match {
          case Tick =>
            val msg = s"msg-$n"
            ctx.log.info("sent {}", msg)
            sendTo ! TestConsumer.Job(msg)
            idle(n)

          case RequestNext(_) =>
            throw new IllegalStateException("Unexpected RequestNext, already got one.")
        }
      }
    }

  }
}

class WorkPullingSpec extends ScalaTestWithActorTestKit with WordSpecLike with LogCapturing {
  import TestConsumer.defaultConsumerDelay
  import TestProducer.defaultProducerDelay
  import WorkPullingSpec._

  private var idCount = 0
  private def nextId(): Int = {
    idCount += 1
    idCount
  }

  private def producerId: String = s"p-$idCount"

  "ReliableDelivery with work-pulling" must {

    "illustrate work-pulling usage" in {
      nextId()
      val workPullingController =
        spawn(WorkPullingProducerController[TestConsumer.Job](producerId), s"workPullingController-${idCount}")
      val jobProducer =
        spawn(TestProducerWorkPulling(defaultProducerDelay, workPullingController), name = s"jobProducer-${idCount}")

      val consumerEndProbe1 = createTestProbe[TestConsumer.CollectedProducerIds]()
      val workerController1 =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"workerController1-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, 42, consumerEndProbe1.ref, workerController1),
        name = s"worker1-${idCount}")

      val consumerEndProbe2 = createTestProbe[TestConsumer.CollectedProducerIds]()
      val workerController2 =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"workerController2-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, 42, consumerEndProbe2.ref, workerController2),
        name = s"worker2-${idCount}")

      val registrationReplyProbe = createTestProbe[Done]()
      workPullingController ! WorkPullingProducerController.RegisterWorker(
        workerController1,
        registrationReplyProbe.ref)
      workPullingController ! WorkPullingProducerController.RegisterWorker(
        workerController2,
        registrationReplyProbe.ref)
      registrationReplyProbe.expectMessage(Done)
      registrationReplyProbe.expectMessage(Done)

      consumerEndProbe1.receiveMessage(10.seconds)
      consumerEndProbe2.receiveMessage()

      testKit.stop(jobProducer)
      testKit.stop(workPullingController)
      testKit.stop(workerController1)
      testKit.stop(workerController2)
    }

  }

}
