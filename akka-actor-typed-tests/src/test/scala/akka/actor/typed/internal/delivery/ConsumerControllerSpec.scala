/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

class ConsumerControllerSpec extends ScalaTestWithActorTestKit with WordSpecLike with LogCapturing {
  import TestConsumer.sequencedMessage

  private var idCount = 0
  private def nextId(): Int = {
    idCount += 1
    idCount
  }

  private def producerId: String = s"p-$idCount"

  "ConsumerController" must {
    "resend RegisterConsumer" in {
      nextId()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
      val producerControllerProbe = createTestProbe[ProducerController.InternalCommand]()

      consumerController ! ConsumerController.RegisterToProducerController(producerControllerProbe.ref)
      producerControllerProbe.expectMessage(ProducerController.RegisterConsumer(consumerController))
      producerControllerProbe.expectMessage(ProducerController.RegisterConsumer(consumerController))

      testKit.stop(consumerController)
    }

    "resend initial Request" in {
      nextId()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
          .unsafeUpcast[ConsumerController.InternalCommand]
      val producerControllerProbe = createTestProbe[ProducerController.InternalCommand]()

      consumerController ! sequencedMessage(producerId, 1, producerControllerProbe.ref)

      val consumerProbe = createTestProbe[ConsumerController.Delivery[TestConsumer.Job]]()
      consumerController ! ConsumerController.Start(consumerProbe.ref)

      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]

      producerControllerProbe.expectMessage(ProducerController.Internal.Request(0, 20, true, false))
      producerControllerProbe.expectMessage(ProducerController.Internal.Request(0, 20, true, true))

      consumerController ! ConsumerController.Confirmed(1)
      producerControllerProbe.expectMessage(ProducerController.Internal.Request(1, 20, true, false))

      testKit.stop(consumerController)
    }

    "send Request after half window size" in {
      nextId()
      val windowSize = 20
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
          .unsafeUpcast[ConsumerController.InternalCommand]
      val producerControllerProbe = createTestProbe[ProducerController.InternalCommand]()

      val consumerProbe = createTestProbe[ConsumerController.Delivery[TestConsumer.Job]]()
      consumerController ! ConsumerController.Start(consumerProbe.ref)

      (1 until windowSize / 2).foreach { n =>
        consumerController ! sequencedMessage(producerId, n, producerControllerProbe.ref)
      }

      producerControllerProbe.expectMessage(ProducerController.Internal.Request(0, windowSize, true, false))
      (1 until windowSize / 2).foreach { n =>
        consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
        consumerController ! ConsumerController.Confirmed(n)
        if (n == 1)
          producerControllerProbe.expectMessage(ProducerController.Internal.Request(1, windowSize, true, false))
      }

      producerControllerProbe.expectNoMessage()

      consumerController ! sequencedMessage(producerId, windowSize / 2, producerControllerProbe.ref)

      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      producerControllerProbe.expectNoMessage()
      consumerController ! ConsumerController.Confirmed(windowSize / 2)
      producerControllerProbe.expectMessage(
        ProducerController.Internal.Request(windowSize / 2, windowSize + windowSize / 2, true, false))

      testKit.stop(consumerController)
    }

    "detect lost message" in {
      nextId()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
          .unsafeUpcast[ConsumerController.InternalCommand]
      val producerControllerProbe = createTestProbe[ProducerController.InternalCommand]()

      val consumerProbe = createTestProbe[ConsumerController.Delivery[TestConsumer.Job]]()
      consumerController ! ConsumerController.Start(consumerProbe.ref)

      consumerController ! sequencedMessage(producerId, 1, producerControllerProbe.ref)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(1)

      producerControllerProbe.expectMessage(ProducerController.Internal.Request(0, 20, true, false))
      producerControllerProbe.expectMessage(ProducerController.Internal.Request(1, 20, true, false))

      consumerController ! sequencedMessage(producerId, 2, producerControllerProbe.ref)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(2)

      consumerController ! sequencedMessage(producerId, 5, producerControllerProbe.ref)
      producerControllerProbe.expectMessage(ProducerController.Internal.Resend(3))

      consumerController ! sequencedMessage(producerId, 3, producerControllerProbe.ref)
      consumerController ! sequencedMessage(producerId, 4, producerControllerProbe.ref)
      consumerController ! sequencedMessage(producerId, 5, producerControllerProbe.ref)

      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]].seqNr should ===(3)
      consumerController ! ConsumerController.Confirmed(3)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]].seqNr should ===(4)
      consumerController ! ConsumerController.Confirmed(4)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]].seqNr should ===(5)
      consumerController ! ConsumerController.Confirmed(5)

      testKit.stop(consumerController)
    }

    "resend Request" in {
      nextId()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
          .unsafeUpcast[ConsumerController.InternalCommand]
      val producerControllerProbe = createTestProbe[ProducerController.InternalCommand]()

      val consumerProbe = createTestProbe[ConsumerController.Delivery[TestConsumer.Job]]()
      consumerController ! ConsumerController.Start(consumerProbe.ref)

      consumerController ! sequencedMessage(producerId, 1, producerControllerProbe.ref)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(1)

      producerControllerProbe.expectMessage(ProducerController.Internal.Request(0, 20, true, false))
      producerControllerProbe.expectMessage(ProducerController.Internal.Request(1, 20, true, false))

      consumerController ! sequencedMessage(producerId, 2, producerControllerProbe.ref)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(2)

      consumerController ! sequencedMessage(producerId, 3, producerControllerProbe.ref)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]].seqNr should ===(3)

      producerControllerProbe.expectMessage(ProducerController.Internal.Request(2, 20, true, true))

      consumerController ! ConsumerController.Confirmed(3)
      producerControllerProbe.expectMessage(ProducerController.Internal.Request(3, 20, true, true))

      testKit.stop(consumerController)
    }

    "optionally ack messages" in {
      nextId()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
          .unsafeUpcast[ConsumerController.InternalCommand]
      val producerControllerProbe = createTestProbe[ProducerController.InternalCommand]()

      val consumerProbe = createTestProbe[ConsumerController.Delivery[TestConsumer.Job]]()
      consumerController ! ConsumerController.Start(consumerProbe.ref)

      consumerController ! sequencedMessage(producerId, 1, producerControllerProbe.ref, ack = true)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(1)

      producerControllerProbe.expectMessage(ProducerController.Internal.Request(0, 20, true, false))
      producerControllerProbe.expectMessage(ProducerController.Internal.Request(1, 20, true, false))

      consumerController ! sequencedMessage(producerId, 2, producerControllerProbe.ref, ack = true)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(2)
      producerControllerProbe.expectMessage(ProducerController.Internal.Ack(2))

      consumerController ! sequencedMessage(producerId, 3, producerControllerProbe.ref, ack = true)
      consumerController ! sequencedMessage(producerId, 4, producerControllerProbe.ref, ack = false)
      consumerController ! sequencedMessage(producerId, 5, producerControllerProbe.ref, ack = true)

      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]].seqNr should ===(3)
      consumerController ! ConsumerController.Confirmed(3)
      producerControllerProbe.expectMessage(ProducerController.Internal.Ack(3))
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]].seqNr should ===(4)
      consumerController ! ConsumerController.Confirmed(4)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]].seqNr should ===(5)
      consumerController ! ConsumerController.Confirmed(5)
      producerControllerProbe.expectMessage(ProducerController.Internal.Ack(5))

      testKit.stop(consumerController)
    }

    "allow restart of ConsumerController" in {
      nextId()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
          .unsafeUpcast[ConsumerController.InternalCommand]
      val producerControllerProbe = createTestProbe[ProducerController.InternalCommand]()

      val consumerProbe1 = createTestProbe[ConsumerController.Delivery[TestConsumer.Job]]()
      consumerController ! ConsumerController.Start(consumerProbe1.ref)

      consumerController ! sequencedMessage(producerId, 1, producerControllerProbe.ref)
      consumerProbe1.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(1)

      producerControllerProbe.expectMessage(ProducerController.Internal.Request(0, 20, true, false))
      producerControllerProbe.expectMessage(ProducerController.Internal.Request(1, 20, true, false))

      consumerController ! sequencedMessage(producerId, 2, producerControllerProbe.ref)
      consumerProbe1.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(2)

      consumerController ! sequencedMessage(producerId, 3, producerControllerProbe.ref)
      consumerProbe1.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]].seqNr should ===(3)

      // restart consumer, before Confirmed(3)
      val consumerProbe2 = createTestProbe[ConsumerController.Delivery[TestConsumer.Job]]()
      consumerController ! ConsumerController.Start(consumerProbe2.ref)

      consumerProbe2.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]].seqNr should ===(3)
      consumerController ! ConsumerController.Confirmed(3)

      consumerController ! sequencedMessage(producerId, 4, producerControllerProbe.ref)
      consumerProbe2.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]].seqNr should ===(4)
      consumerController ! ConsumerController.Confirmed(4)

      testKit.stop(consumerController)
    }
  }

  // FIXME more tests for supportResend=false

}
