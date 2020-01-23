/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import scala.concurrent.duration._

import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.internal.delivery.ConsumerController.SequencedMessage
import akka.actor.typed.internal.delivery.SimuatedSharding.ShardingEnvelope
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import org.scalatest.wordspec.AnyWordSpecLike

object ReliableDeliveryShardingSpec {

  object TestShardingProducer {

    trait Command
    final case class RequestNext(sendToRef: ActorRef[ShardingEnvelope[TestConsumer.Job]]) extends Command

    private final case object Tick extends Command

    def apply(producerController: ActorRef[ShardingProducerController.Start[TestConsumer.Job]]): Behavior[Command] = {
      Behaviors.setup { context =>
        context.setLoggerName("TestShardingProducer")
        val requestNextAdapter: ActorRef[ShardingProducerController.RequestNext[TestConsumer.Job]] =
          context.messageAdapter(req => RequestNext(req.sendNextTo))
        producerController ! ShardingProducerController.Start(requestNextAdapter)

        // simulate fast producer
        Behaviors.withTimers { timers =>
          timers.startTimerWithFixedDelay(Tick, Tick, 20.millis)
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

    private def active(n: Int, sendTo: ActorRef[ShardingEnvelope[TestConsumer.Job]]): Behavior[Command] = {
      Behaviors.receive { (ctx, msg) =>
        msg match {
          case Tick =>
            val msg = s"msg-$n"
            val entityId = s"entity-${n % 3}"
            ctx.log.info2("sent {} to {}", msg, entityId)
            sendTo ! ShardingEnvelope(entityId, TestConsumer.Job(msg))
            idle(n)

          case RequestNext(_) =>
            // already active
            Behaviors.same
        }
      }
    }

  }

}

class ReliableDeliveryShardingSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {
  import ReliableDeliveryShardingSpec._
  import TestConsumer.defaultConsumerDelay

  private var idCount = 0
  private def nextId(): Int = {
    idCount += 1
    idCount
  }

  private def producerId: String = s"p-$idCount"

  "ReliableDelivery with sharding" must {

    "illustrate sharding usage" in {
      nextId()
      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val sharding: ActorRef[ShardingEnvelope[SequencedMessage[TestConsumer.Job]]] =
        spawn(
          SimuatedSharding(
            _ =>
              ShardingConsumerController[TestConsumer.Job, TestConsumer.Command](
                c => TestConsumer(defaultConsumerDelay, 42, consumerEndProbe.ref, c),
                resendLost = true)),
          s"sharding-$idCount")

      val shardingController =
        spawn(ShardingProducerController[TestConsumer.Job](producerId, sharding, None), s"shardingController-$idCount")
      val producer = spawn(TestShardingProducer(shardingController), name = s"shardingProducer-$idCount")

      // expecting 3 end messages, one for each entity: "entity-0", "entity-1", "entity-2"
      consumerEndProbe.receiveMessages(3, 5.seconds)

      testKit.stop(producer)
      testKit.stop(shardingController)
      testKit.stop(sharding)
    }

    "illustrate sharding usage with several producers" in {
      nextId()
      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val sharding: ActorRef[ShardingEnvelope[SequencedMessage[TestConsumer.Job]]] =
        spawn(
          SimuatedSharding(
            _ =>
              ShardingConsumerController[TestConsumer.Job, TestConsumer.Command](
                c => TestConsumer(defaultConsumerDelay, 42, consumerEndProbe.ref, c),
                resendLost = true)),
          s"sharding-$idCount")

      val shardingController1 =
        spawn(
          ShardingProducerController[TestConsumer.Job](
            s"p1-$idCount", // note different producerId
            sharding,
            None),
          s"shardingController1-$idCount")
      val producer1 = spawn(TestShardingProducer(shardingController1), name = s"shardingProducer1-$idCount")

      val shardingController2 =
        spawn(
          ShardingProducerController[TestConsumer.Job](
            s"p2-$idCount", // note different producerId
            sharding,
            None),
          s"shardingController2-$idCount")
      val producer2 = spawn(TestShardingProducer(shardingController2), name = s"shardingProducer2-$idCount")

      // expecting 3 end messages, one for each entity: "entity-0", "entity-1", "entity-2"
      val endMessages = consumerEndProbe.receiveMessages(3, 5.seconds)
      // verify that they received messages from both producers
      endMessages.flatMap(_.producerIds).toSet should ===(
        Set(
          s"p1-$idCount-entity-0",
          s"p1-$idCount-entity-1",
          s"p1-$idCount-entity-2",
          s"p2-$idCount-entity-0",
          s"p2-$idCount-entity-1",
          s"p2-$idCount-entity-2"))

      testKit.stop(producer1)
      testKit.stop(producer2)
      testKit.stop(shardingController1)
      testKit.stop(shardingController2)
      testKit.stop(sharding)
    }

    "reply to MessageWithConfirmation" in {
      nextId()
      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val sharding: ActorRef[ShardingEnvelope[SequencedMessage[TestConsumer.Job]]] =
        spawn(
          SimuatedSharding(
            _ =>
              ShardingConsumerController[TestConsumer.Job, TestConsumer.Command](
                c => TestConsumer(defaultConsumerDelay, 3, consumerEndProbe.ref, c),
                resendLost = true)),
          s"sharding-$idCount")

      val shardingController =
        spawn(ShardingProducerController[TestConsumer.Job](producerId, sharding, None), s"shardingController-$idCount")

      val producerProbe = createTestProbe[ShardingProducerController.RequestNext[TestConsumer.Job]]()
      shardingController ! ShardingProducerController.Start(producerProbe.ref)

      val replyProbe = createTestProbe[Done]()
      producerProbe.receiveMessage().askNextTo ! ShardingProducerController.MessageWithConfirmation(
        "entity-0",
        TestConsumer.Job("msg-1"),
        replyProbe.ref)
      producerProbe.receiveMessage().askNextTo ! ShardingProducerController.MessageWithConfirmation(
        "entity-0",
        TestConsumer.Job("msg-2"),
        replyProbe.ref)
      producerProbe.receiveMessage().askNextTo ! ShardingProducerController.MessageWithConfirmation(
        "entity-1",
        TestConsumer.Job("msg-3"),
        replyProbe.ref)
      producerProbe.receiveMessage().askNextTo ! ShardingProducerController.MessageWithConfirmation(
        "entity-0",
        TestConsumer.Job("msg-4"),
        replyProbe.ref)

      consumerEndProbe.receiveMessage() // entity-0 received 3 messages
      consumerEndProbe.expectNoMessage()

      producerProbe.receiveMessage().askNextTo ! ShardingProducerController.MessageWithConfirmation(
        "entity-1",
        TestConsumer.Job("msg-5"),
        replyProbe.ref)
      producerProbe.receiveMessage().askNextTo ! ShardingProducerController.MessageWithConfirmation(
        "entity-1",
        TestConsumer.Job("msg-6"),
        replyProbe.ref)
      consumerEndProbe.receiveMessage() // entity-0 received 3 messages

      testKit.stop(shardingController)
      testKit.stop(sharding)
    }

    "include demand information in RequestNext" in {
      nextId()

      val shardingProbe =
        createTestProbe[ShardingEnvelope[SequencedMessage[TestConsumer.Job]]]()
      val shardingProducerController =
        spawn(
          ShardingProducerController[TestConsumer.Job](producerId, shardingProbe.ref, None),
          s"shardingController-$idCount")
      val producerProbe = createTestProbe[ShardingProducerController.RequestNext[TestConsumer.Job]]()
      shardingProducerController ! ShardingProducerController.Start(producerProbe.ref)

      val next1 = producerProbe.receiveMessage()
      next1.entitiesWithDemand should ===(Set.empty)
      next1.bufferedForEntitiesWithoutDemand should ===(Map.empty)

      next1.sendNextTo ! ShardingEnvelope("entity-1", TestConsumer.Job("msg-1"))
      // for the first message no RequestNext until initial roundtrip
      producerProbe.expectNoMessage()

      val seq1 = shardingProbe.receiveMessage().message
      seq1.msg should ===(TestConsumer.Job("msg-1"))
      seq1.producer ! ProducerController.Internal.Request(confirmedSeqNr = 0L, upToSeqNr = 5, true, false)

      val next2 = producerProbe.receiveMessage()
      next2.entitiesWithDemand should ===(Set("entity-1"))
      next2.bufferedForEntitiesWithoutDemand should ===(Map.empty)

      next2.sendNextTo ! ShardingEnvelope("entity-1", TestConsumer.Job("msg-2"))
      val next3 = producerProbe.receiveMessage()
      // could be sent immediately since had demand, and Request(upToSeqNr-5)
      next3.entitiesWithDemand should ===(Set("entity-1"))
      next3.bufferedForEntitiesWithoutDemand should ===(Map.empty)

      next3.sendNextTo ! ShardingEnvelope("entity-1", TestConsumer.Job("msg-3"))
      val next4 = producerProbe.receiveMessage()
      next4.entitiesWithDemand should ===(Set("entity-1"))
      next4.bufferedForEntitiesWithoutDemand should ===(Map.empty)

      next4.sendNextTo ! ShardingEnvelope("entity-1", TestConsumer.Job("msg-4"))
      val next5 = producerProbe.receiveMessage()
      next5.entitiesWithDemand should ===(Set("entity-1"))
      next5.bufferedForEntitiesWithoutDemand should ===(Map.empty)

      next5.sendNextTo ! ShardingEnvelope("entity-1", TestConsumer.Job("msg-5"))
      // no more demand Request(upToSeqNr-5)
      producerProbe.expectNoMessage()
      // but we can anyway send more, which will be buffered
      next5.sendNextTo ! ShardingEnvelope("entity-1", TestConsumer.Job("msg-6"))

      shardingProbe.receiveMessage()
      shardingProbe.receiveMessage()
      shardingProbe.receiveMessage()
      val seq5 = shardingProbe.receiveMessage().message
      seq5.msg should ===(TestConsumer.Job("msg-5"))

      val next6 = producerProbe.receiveMessage()
      next6.entitiesWithDemand should ===(Set.empty)
      next6.bufferedForEntitiesWithoutDemand should ===(Map("entity-1" -> 1))

      // and we can send to another entity
      next6.sendNextTo ! ShardingEnvelope("entity-2", TestConsumer.Job("msg-7"))
      producerProbe.expectNoMessage()
      val seq7 = shardingProbe.receiveMessage().message
      seq7.msg should ===(TestConsumer.Job("msg-7"))
      seq7.producer ! ProducerController.Internal.Request(confirmedSeqNr = 0L, upToSeqNr = 5, true, false)

      val next8 = producerProbe.receiveMessage()
      next8.entitiesWithDemand should ===(Set("entity-2"))
      next8.bufferedForEntitiesWithoutDemand should ===(Map("entity-1" -> 1))

      // when new demand the buffered messages will be be sent
      seq5.producer ! ProducerController.Internal.Request(confirmedSeqNr = 5L, upToSeqNr = 10, true, false)
      val seq6 = shardingProbe.receiveMessage().message
      seq6.msg should ===(TestConsumer.Job("msg-6"))

      val next9 = producerProbe.receiveMessage()
      next9.entitiesWithDemand should ===(Set("entity-1", "entity-2"))
      next9.bufferedForEntitiesWithoutDemand should ===(Map.empty)

      testKit.stop(shardingProducerController)
    }

  }

}
