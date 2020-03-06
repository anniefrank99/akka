/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal.delivery

import akka.actor.ExtendedActorSystem
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.delivery.DurableProducerQueue
import akka.actor.typed.delivery.ProducerController
import akka.actor.typed.delivery.internal.ProducerControllerImpl
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.serialization.SerializationExtension
import org.scalatest.wordspec.AnyWordSpecLike

class ReliableDeliverySerializerSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {

  private val classicSystem = system.toClassic
  private val serializer = new ReliableDeliverySerializer(classicSystem.asInstanceOf[ExtendedActorSystem])
  private val ref = spawn(Behaviors.empty[Any])

  "ReliableDeliverySerializer" must {

    Seq(
      "SequencedMessage-1" -> ConsumerController.SequencedMessage("prod-1", 17L, "msg17", false, false)(ref),
      "SequencedMessage-2" -> ConsumerController.SequencedMessage("prod-1", 1L, "msg01", true, true)(ref),
      "Ack" -> ProducerControllerImpl.Ack(5L),
      "Request" -> ProducerControllerImpl.Request(5L, 25L, true, true),
      "Resend" -> ProducerControllerImpl.Resend(5L),
      "RegisterConsumer" -> ProducerController.RegisterConsumer(ref),
      "DurableProducerQueue.MessageSent-1" -> DurableProducerQueue.MessageSent(3L, "msg03", false, ""),
      "DurableProducerQueue.MessageSent-2" -> DurableProducerQueue.MessageSent(3L, "msg03", true, "q1"),
      "DurableProducerQueue.Confirmed" -> DurableProducerQueue.Confirmed(3L, "q2"),
      "DurableProducerQueue.State-1" -> DurableProducerQueue.State(3L, 2L, Map.empty, Vector.empty),
      "DurableProducerQueue.State-2" -> DurableProducerQueue.State(
        3L,
        2L,
        Map("" -> 2L),
        Vector(DurableProducerQueue.MessageSent(3L, "msg03", false, ""))),
      "DurableProducerQueue.State-3" -> DurableProducerQueue.State(
        17L,
        12L,
        Map("q1" -> 5L, "q2" -> 7L, "q3" -> 12L, "q4" -> 14L),
        Vector(
          DurableProducerQueue.MessageSent(15L, "msg15", true, "q4"),
          DurableProducerQueue.MessageSent(16L, "msg16", true, "q4")))).foreach {
      case (scenario, item) =>
        s"resolve serializer for $scenario" in {
          val serializer = SerializationExtension(classicSystem)
          serializer.serializerFor(item.getClass).getClass should be(classOf[ReliableDeliverySerializer])
        }

        s"serialize and de-serialize $scenario" in {
          verifySerialization(item)
        }
    }
  }

  def verifySerialization(msg: AnyRef): Unit = {
    serializer.fromBinary(serializer.toBinary(msg), serializer.manifest(msg)) should be(msg)
  }

}
