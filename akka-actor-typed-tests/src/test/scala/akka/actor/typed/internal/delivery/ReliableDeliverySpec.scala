/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import scala.concurrent.duration._

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

class ReliableDeliverySpec extends ScalaTestWithActorTestKit with WordSpecLike with LogCapturing {
  import TestConsumer.defaultConsumerDelay
  import TestProducer.defaultProducerDelay

  private var idCount = 0
  private def nextId(): Int = {
    idCount += 1
    idCount
  }

  "ReliableDelivery" must {

    "illustrate point-to-point usage" in {
      nextId()
      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, 42, consumerEndProbe.ref, consumerController),
        name = s"destination-${idCount}")

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController-${idCount}")
      val producer = spawn(TestProducer(defaultProducerDelay, producerController), name = s"producer-${idCount}")

      consumerController ! ConsumerController.RegisterToProducerController(producerController)

      consumerEndProbe.receiveMessage(5.seconds)

      testKit.stop(producer)
      testKit.stop(producerController)
      testKit.stop(consumerController)
    }

    "illustrate point-to-point usage with ask" in {
      nextId()
      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, 42, consumerEndProbe.ref, consumerController),
        name = s"destination-${idCount}")

      val replyProbe = createTestProbe[Long]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController-${idCount}")
      val producer =
        spawn(
          TestProducerWithAsk(defaultProducerDelay, replyProbe.ref, producerController),
          name = s"producer-${idCount}")

      consumerController ! ConsumerController.RegisterToProducerController(producerController)

      consumerEndProbe.receiveMessage(5.seconds)

      replyProbe.receiveMessages(42, 5.seconds).toSet should ===((1L to 42L).toSet)

      testKit.stop(producer)
      testKit.stop(producerController)
      testKit.stop(consumerController)
    }

    def testWithDelays(producerDelay: FiniteDuration, consumerDelay: FiniteDuration): Unit = {
      nextId()
      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
      spawn(TestConsumer(consumerDelay, 42, consumerEndProbe.ref, consumerController), name = s"destination-${idCount}")

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController-${idCount}")
      val producer = spawn(TestProducer(producerDelay, producerController), name = s"producer-${idCount}")

      consumerController ! ConsumerController.RegisterToProducerController(producerController)

      consumerEndProbe.receiveMessage(5.seconds)

      testKit.stop(producer)
      testKit.stop(producerController)
      testKit.stop(consumerController)
    }

    "work with slow producer and fast consumer" in {
      testWithDelays(producerDelay = 30.millis, consumerDelay = Duration.Zero)
    }

    "work with fast producer and slow consumer" in {
      testWithDelays(producerDelay = Duration.Zero, consumerDelay = 30.millis)
    }

    "work with fast producer and fast consumer" in {
      testWithDelays(producerDelay = Duration.Zero, consumerDelay = Duration.Zero)
    }

    "allow replacement of destination" in {
      nextId()
      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController1-${idCount}")
      spawn(TestConsumer(defaultConsumerDelay, 42, consumerEndProbe.ref, consumerController), s"consumer1-${idCount}")

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController-${idCount}")
      val producer = spawn(TestProducer(defaultProducerDelay, producerController), name = s"producer-${idCount}")

      consumerController ! ConsumerController.RegisterToProducerController(producerController)

      consumerEndProbe.receiveMessage(5.seconds)

      val consumerEndProbe2 = createTestProbe[TestConsumer.CollectedProducerIds]()
      val consumerController2 =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController2-${idCount}")
      spawn(TestConsumer(defaultConsumerDelay, 42, consumerEndProbe2.ref, consumerController2), s"consumer2-${idCount}")
      consumerController2 ! ConsumerController.RegisterToProducerController(producerController)

      consumerEndProbe2.receiveMessage(5.seconds)

      testKit.stop(producer)
      testKit.stop(producerController)
      testKit.stop(consumerController)
    }

    "allow replacement of producer" in {
      nextId()
      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, 42, consumerEndProbe.ref, consumerController),
        name = s"destination-${idCount}")

      val producerController1 =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController1-${idCount}")
      val producer1 = spawn(TestProducer(defaultProducerDelay, producerController1), name = s"producer1-${idCount}")

      producerController1 ! ProducerController.RegisterConsumer(consumerController)

      // FIXME better way of testing this
      Thread.sleep(300)
      testKit.stop(producer1)
      testKit.stop(producerController1)

      val producerController2 =
        spawn(
          ProducerController[TestConsumer.Job](
            s"p-${idCount}", // must keep the same producerId
            None),
          s"producerController2-${idCount}")
      val producer2 = spawn(TestProducer(defaultProducerDelay, producerController2), name = s"producer2-${idCount}")

      producerController2 ! ProducerController.RegisterConsumer(consumerController)

      consumerEndProbe.receiveMessage(5.seconds)

      testKit.stop(producer2)
      testKit.stop(producerController2)
      testKit.stop(consumerController)
    }

  }

}
