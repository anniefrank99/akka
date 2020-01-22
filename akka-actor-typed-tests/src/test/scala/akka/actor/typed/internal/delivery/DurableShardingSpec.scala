/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._

import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.internal.delivery.ConsumerController.SequencedMessage
import akka.actor.typed.internal.delivery.DurableProducerQueue.MessageSent
import akka.actor.typed.internal.delivery.SimuatedSharding.ShardingEnvelope
import org.scalatest.WordSpecLike

class DurableShardingSpec extends ScalaTestWithActorTestKit with WordSpecLike with LogCapturing {

  private var idCount = 0
  private def nextId(): Int = {
    idCount += 1
    idCount
  }

  private def producerId: String = s"p-$idCount"

  "ReliableDelivery with sharding and durable queue" must {

    "load initial state and resend unconfirmed" in {
      nextId()

      val durable = TestDurableProducerQueue[ShardingEnvelope[TestConsumer.Job]](
        Duration.Zero,
        DurableProducerQueue.State(
          currentSeqNr = 5,
          confirmedSeqNr = 2,
          unconfirmed = Vector(
            DurableProducerQueue.MessageSent(3, ShardingEnvelope("entity-1", TestConsumer.Job("msg-3")), false),
            DurableProducerQueue.MessageSent(4, ShardingEnvelope("entity-1", TestConsumer.Job("msg-4")), false))))

      val shardingProbe =
        createTestProbe[ShardingEnvelope[SequencedMessage[TestConsumer.Job]]]()
      val shardingProducerController =
        spawn(
          ShardingProducerController[TestConsumer.Job](producerId, shardingProbe.ref, Some(durable)),
          s"shardingController-$idCount")
      val producerProbe = createTestProbe[ShardingProducerController.RequestNext[TestConsumer.Job]]()
      shardingProducerController ! ShardingProducerController.Start(producerProbe.ref)

      val next1 = producerProbe.receiveMessage()
      next1.entitiesWithDemand should ===(Set.empty)
      next1.bufferedForEntitiesWithoutDemand should ===(Map("entity-1" -> 2))
      producerProbe.expectNoMessage()

      val seq3 = shardingProbe.receiveMessage().message
      seq3.msg should ===(TestConsumer.Job("msg-3"))
      seq3.producer ! ProducerController.Internal.Request(confirmedSeqNr = 1L, upToSeqNr = 5, true, false)

      val seq4 = shardingProbe.receiveMessage().message
      seq4.msg should ===(TestConsumer.Job("msg-4"))

      val next5 = producerProbe.receiveMessage()
      next5.entitiesWithDemand should ===(Set("entity-1"))

      testKit.stop(shardingProducerController)
    }

    "reply to MessageWithConfirmation after storage" in {
      import ShardingProducerController.MessageWithConfirmation
      nextId()
      val durable =
        TestDurableProducerQueue[ShardingEnvelope[TestConsumer.Job]](
          Duration.Zero,
          DurableProducerQueue.State.empty[ShardingEnvelope[TestConsumer.Job]])
      val shardingProbe =
        createTestProbe[ShardingEnvelope[SequencedMessage[TestConsumer.Job]]]()
      val shardingProducerController =
        spawn(
          ShardingProducerController[TestConsumer.Job](producerId, shardingProbe.ref, Some(durable)),
          s"shardingController-$idCount")
      val producerProbe = createTestProbe[ShardingProducerController.RequestNext[TestConsumer.Job]]()
      shardingProducerController ! ShardingProducerController.Start(producerProbe.ref)

      val replyProbe = createTestProbe[Done]()
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(
        "entity-1",
        TestConsumer.Job("msg-1"),
        replyProbe.ref)
      // reply after storage, doesn't wait for ack from consumer
      replyProbe.receiveMessage()

      val seq1 = shardingProbe.receiveMessage().message
      seq1.msg should ===(TestConsumer.Job("msg-1"))
      seq1.producer ! ProducerController.Internal.Request(confirmedSeqNr = 1L, upToSeqNr = 5, true, false)

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(
        "entity-1",
        TestConsumer.Job("msg-2"),
        replyProbe.ref)
      replyProbe.receiveMessage()
      shardingProbe.receiveMessage()

      val next = producerProbe.receiveMessage()
      next.askNextTo ! MessageWithConfirmation("entity-2", TestConsumer.Job("msg-3"), replyProbe.ref)
      // send immediately to exercise buffering
      next.askNextTo ! MessageWithConfirmation("entity-2", TestConsumer.Job("msg-4"), replyProbe.ref)
      next.askNextTo ! MessageWithConfirmation("entity-2", TestConsumer.Job("msg-5"), replyProbe.ref)
      replyProbe.receiveMessage()
      replyProbe.receiveMessage()
      replyProbe.receiveMessage()

      val seq2 = shardingProbe.receiveMessage().message
      seq2.msg should ===(TestConsumer.Job("msg-3"))
      seq2.producer ! ProducerController.Internal.Request(confirmedSeqNr = 1L, upToSeqNr = 5, true, false)

      shardingProbe.receiveMessage().message
      val seq5 = shardingProbe.receiveMessage().message
      seq5.msg should ===(TestConsumer.Job("msg-5"))
      seq5.producer ! ProducerController.Internal.Ack(seq5.seqNr)

      testKit.stop(shardingProducerController)
    }

    "store confirmations" in {
      nextId()

      val stateHolder =
        new AtomicReference[DurableProducerQueue.State[ShardingEnvelope[TestConsumer.Job]]](
          DurableProducerQueue.State.empty)
      val durable = TestDurableProducerQueue[ShardingEnvelope[TestConsumer.Job]](
        Duration.Zero,
        stateHolder,
        (_: DurableProducerQueue.Command[_]) => false)

      val shardingProbe =
        createTestProbe[ShardingEnvelope[SequencedMessage[TestConsumer.Job]]]()
      val shardingProducerController =
        spawn(
          ShardingProducerController[TestConsumer.Job](producerId, shardingProbe.ref, Some(durable)),
          s"shardingController-$idCount")
      val producerProbe = createTestProbe[ShardingProducerController.RequestNext[TestConsumer.Job]]()
      shardingProducerController ! ShardingProducerController.Start(producerProbe.ref)

      producerProbe.receiveMessage().sendNextTo ! ShardingEnvelope("entity-1", TestConsumer.Job("msg-1"))
      producerProbe.awaitAssert {
        stateHolder.get() should ===(
          DurableProducerQueue
            .State(2, 0, Vector(MessageSent(1, ShardingEnvelope("entity-1", TestConsumer.Job("msg-1")), ack = false))))
      }

      val seq1 = shardingProbe.receiveMessage().message
      seq1.msg should ===(TestConsumer.Job("msg-1"))
      seq1.producer ! ProducerController.Internal.Request(confirmedSeqNr = 1L, upToSeqNr = 5, true, false)
      producerProbe.awaitAssert {
        stateHolder.get() should ===(DurableProducerQueue.State(2, 1, Vector.empty))
      }

      producerProbe.receiveMessage().sendNextTo ! ShardingEnvelope("entity-2", TestConsumer.Job("msg-2"))
      val seq2 = shardingProbe.receiveMessage().message
      seq2.msg should ===(TestConsumer.Job("msg-2"))
      seq2.producer ! ProducerController.Internal.Request(confirmedSeqNr = 1L, upToSeqNr = 5, true, false)
      producerProbe.awaitAssert {
        stateHolder.get() should ===(DurableProducerQueue.State(3, 2, Vector.empty))
      }

      producerProbe.receiveMessage().sendNextTo ! ShardingEnvelope("entity-1", TestConsumer.Job("msg-3"))
      shardingProbe.receiveMessage().message
      producerProbe.receiveMessage().sendNextTo ! ShardingEnvelope("entity-2", TestConsumer.Job("msg-4"))
      val seq4 = shardingProbe.receiveMessage().message
      producerProbe.receiveMessage().sendNextTo ! ShardingEnvelope("entity-1", TestConsumer.Job("msg-5"))
      shardingProbe.receiveMessage().message
      producerProbe.receiveMessage().sendNextTo ! ShardingEnvelope("entity-2", TestConsumer.Job("msg-6"))
      shardingProbe.receiveMessage().message

      producerProbe.awaitAssert {
        stateHolder.get().currentSeqNr should ===(7)
        stateHolder.get().confirmedSeqNr should ===(2)
      }

      seq4.producer ! ProducerController.Internal.Ack(seq4.seqNr)
      producerProbe.awaitAssert {
        stateHolder.get() should ===(
          DurableProducerQueue.State(
            7,
            4,
            Vector(
              // FIXME BUG msg-3 shouldn't be confirmed by this, different entity
              //MessageSent(5, ShardingEnvelope("entity-1", TestConsumer.Job("msg-5")), ack = false),
              MessageSent(5, ShardingEnvelope("entity-1", TestConsumer.Job("msg-5")), ack = false),
              MessageSent(6, ShardingEnvelope("entity-2", TestConsumer.Job("msg-6")), ack = false))))
      }

      testKit.stop(shardingProducerController)
    }

  }

}
