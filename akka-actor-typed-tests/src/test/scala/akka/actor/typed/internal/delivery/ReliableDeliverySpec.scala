/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.BehaviorInterceptor
import akka.actor.typed.Terminated
import akka.actor.typed.TypedActorContext
import akka.actor.typed.internal.delivery.ConsumerController.SequencedMessage
import akka.actor.typed.internal.delivery.SimuatedSharding.ShardingEnvelope
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object ReliableDeliverySpec {
  private val config = ConfigFactory.parseString("""
  """)

  object TestProducer {

    trait Command
    final case class RequestNext(sendTo: ActorRef[TestConsumer.Job]) extends Command
    private final case object Tick extends Command

    def apply(
        delay: FiniteDuration,
        producerController: ActorRef[ProducerController.Start[TestConsumer.Job]]): Behavior[Command] = {
      Behaviors.setup { context =>
        val requestNextAdapter: ActorRef[ProducerController.RequestNext[TestConsumer.Job]] =
          context.messageAdapter(req => RequestNext(req.sendNextTo))
        producerController ! ProducerController.Start(requestNextAdapter)

        if (delay == Duration.Zero)
          activeNoDelay(1) // simulate fast producer
        else {
          Behaviors.withTimers { timers ⇒
            timers.startTimerWithFixedDelay(Tick, Tick, delay)
            idle(0)
          }
        }
      }
    }

    private def idle(n: Int): Behavior[Command] = {
      Behaviors.receiveMessage {
        case Tick ⇒ Behaviors.same
        case RequestNext(sendTo) ⇒ active(n + 1, sendTo)
      }
    }

    private def active(n: Int, sendTo: ActorRef[TestConsumer.Job]): Behavior[Command] = {
      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case Tick ⇒
            sendMessage(n, sendTo, ctx)
            idle(n)

          case RequestNext(_) ⇒
            throw new IllegalStateException("Unexpected RequestNext, already got one.")
        }
      }
    }

    private def activeNoDelay(n: Int): Behavior[Command] = {
      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case RequestNext(sendTo) ⇒
            sendMessage(n, sendTo, ctx)
            activeNoDelay(n + 1)
        }
      }
    }

    private def sendMessage(n: Int, sendTo: ActorRef[TestConsumer.Job], ctx: ActorContext[Command]): Unit = {
      val msg = s"msg-$n"
      ctx.log.info("sent {}", msg)
      sendTo ! TestConsumer.Job(msg)
    }
  }

  object TestConsumer {

    final case class Job(payload: String)
    trait Command
    private final case class JobDelivery(
        producerId: String,
        seqNr: Long,
        msg: Job,
        confirmTo: ActorRef[ConsumerController.Confirmed])
        extends Command
    final case class SomeAsyncJob(
        producerId: String,
        seqNr: Long,
        msg: Job,
        confirmTo: ActorRef[ConsumerController.Confirmed])
        extends Command

    final case class CollectedProducerIds(producerIds: Set[String])

    final case class AddConsumerController(controller: ActorRef[ConsumerController.Start[TestConsumer.Job]])
        extends Command

    def apply(
        delay: FiniteDuration,
        endCondition: SomeAsyncJob => Boolean,
        endReplyTo: ActorRef[CollectedProducerIds],
        controller: ActorRef[ConsumerController.Start[TestConsumer.Job]]): Behavior[Command] =
      Behaviors.setup { ctx ⇒
        val deliverTo: ActorRef[ConsumerController.Delivery[Job]] =
          ctx.messageAdapter(d => JobDelivery(d.producerId, d.seqNr, d.msg, d.confirmTo))
        ctx.self ! AddConsumerController(controller)
        (new TestConsumer(delay, endCondition, endReplyTo, deliverTo)).active(Set.empty)
      }

    // dynamically adding ConsumerController via message AddConsumerController
    def apply(
        delay: FiniteDuration,
        endCondition: SomeAsyncJob => Boolean,
        endReplyTo: ActorRef[CollectedProducerIds]): Behavior[Command] =
      Behaviors.setup { ctx ⇒
        val deliverTo: ActorRef[ConsumerController.Delivery[Job]] =
          ctx.messageAdapter(d => JobDelivery(d.producerId, d.seqNr, d.msg, d.confirmTo))
        (new TestConsumer(delay, endCondition, endReplyTo, deliverTo)).active(Set.empty)
      }
  }

  class TestConsumer(
      delay: FiniteDuration,
      endCondition: TestConsumer.SomeAsyncJob => Boolean,
      endReplyTo: ActorRef[TestConsumer.CollectedProducerIds],
      deliverTo: ActorRef[ConsumerController.Delivery[TestConsumer.Job]]) {
    import TestConsumer._

    private def active(processed: Set[(String, Long)]): Behavior[Command] = {
      Behaviors.receive { (ctx, m) =>
        m match {
          case JobDelivery(producerId, seqNr, msg, confirmTo) ⇒
            // confirmation can be later, asynchronously
            if (delay == Duration.Zero)
              ctx.self ! SomeAsyncJob(producerId, seqNr, msg, confirmTo)
            else
              // schedule to simulate slow consumer
              ctx.scheduleOnce(10.millis, ctx.self, SomeAsyncJob(producerId, seqNr, msg, confirmTo))
            Behaviors.same

          case job @ SomeAsyncJob(producerId, seqNr, _, confirmTo) ⇒
            // when replacing producer the seqNr may start from 1 again
            val cleanProcessed =
              if (seqNr == 1L) processed.filterNot { case (pid, _) => pid == producerId } else processed

            if (cleanProcessed((producerId, seqNr)))
              throw new RuntimeException(s"Received duplicate [($producerId,$seqNr)]")
            ctx.log.info("processed [{}] from [{}]", seqNr, producerId)
            confirmTo ! ConsumerController.Confirmed(seqNr)

            if (endCondition(job)) {
              endReplyTo ! CollectedProducerIds(processed.map(_._1))
              Behaviors.stopped
            } else
              active(cleanProcessed + (producerId -> seqNr))

          case AddConsumerController(controller) =>
            controller ! ConsumerController.Start(deliverTo)
            Behaviors.same
        }
      }
    }

  }

  object TestProducerWithConfirmation {

    trait Command
    final case class RequestNext(askTo: ActorRef[ProducerController.MessageWithConfirmation[TestConsumer.Job]])
        extends Command
    private case object Tick extends Command
    private final case class Confirmed(seqNr: Long) extends Command
    private case object AskTimeout extends Command

    private implicit val askTimeout: Timeout = 10.seconds

    def apply(
        delay: FiniteDuration,
        replyProbe: ActorRef[Long],
        producerController: ActorRef[ProducerController.Start[TestConsumer.Job]]): Behavior[Command] = {
      Behaviors.setup { context =>
        val requestNextAdapter: ActorRef[ProducerController.RequestNext[TestConsumer.Job]] =
          context.messageAdapter(req => RequestNext(req.askNextTo))
        producerController ! ProducerController.Start(requestNextAdapter)

        Behaviors.withTimers { timers ⇒
          timers.startTimerWithFixedDelay(Tick, Tick, delay)
          idle(0, replyProbe)
        }
      }
    }

    private def idle(n: Int, replyProbe: ActorRef[Long]): Behavior[Command] = {
      Behaviors.receiveMessage {
        case Tick ⇒ Behaviors.same
        case RequestNext(sendTo) ⇒ active(n + 1, replyProbe, sendTo)
        case Confirmed(seqNr) =>
          replyProbe ! seqNr
          Behaviors.same
      }
    }

    private def active(
        n: Int,
        replyProbe: ActorRef[Long],
        sendTo: ActorRef[ProducerController.MessageWithConfirmation[TestConsumer.Job]]): Behavior[Command] = {
      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case Tick ⇒
            val msg = s"msg-$n"
            ctx.log.info("sent {}", msg)
            ctx.ask(
              sendTo,
              (askReplyTo: ActorRef[Long]) =>
                ProducerController.MessageWithConfirmation(TestConsumer.Job(msg), askReplyTo)) {
              case Success(seqNr) => Confirmed(seqNr)
              case Failure(_)     => AskTimeout
            }
            idle(n, replyProbe)

          case RequestNext(_) ⇒
            throw new IllegalStateException("Unexpected RequestNext, already got one.")

          case Confirmed(seqNr) =>
            ctx.log.info("Reply Confirmed [{}]", seqNr)
            replyProbe ! seqNr
            Behaviors.same

          case AskTimeout =>
            ctx.log.warn("Timeout")
            Behaviors.same
        }
      }
    }

  }

  object TestProducerWorkPulling {

    trait Command
    final case class RequestNext(sendTo: ActorRef[TestConsumer.Job]) extends Command
    private final case object Tick extends Command

    def apply(
        delay: FiniteDuration,
        producerController: ActorRef[WorkPullingProducerController.Start[TestConsumer.Job]]): Behavior[Command] = {
      Behaviors.setup { context =>
        val requestNextAdapter: ActorRef[WorkPullingProducerController.RequestNext[TestConsumer.Job]] =
          context.messageAdapter(req => RequestNext(req.sendNextTo))
        producerController ! WorkPullingProducerController.Start(requestNextAdapter)

        Behaviors.withTimers { timers ⇒
          timers.startTimerWithFixedDelay(Tick, Tick, delay)
          idle(0)
        }
      }
    }

    private def idle(n: Int): Behavior[Command] = {
      Behaviors.receiveMessage {
        case Tick ⇒ Behaviors.same
        case RequestNext(sendTo) ⇒ active(n + 1, sendTo)
      }
    }

    private def active(n: Int, sendTo: ActorRef[TestConsumer.Job]): Behavior[Command] = {
      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case Tick ⇒
            val msg = s"msg-$n"
            ctx.log.info("sent {}", msg)
            sendTo ! TestConsumer.Job(msg)
            idle(n)

          case RequestNext(_) ⇒
            throw new IllegalStateException("Unexpected RequestNext, already got one.")
        }
      }
    }

  }

  object TestShardingProducer {

    trait Command
    final case class RequestNext(sendToRef: ActorRef[ShardingEnvelope[TestConsumer.Job]]) extends Command

    private final case object Tick extends Command

    def apply(producerController: ActorRef[ShardingProducerController.Start[TestConsumer.Job]]): Behavior[Command] = {
      Behaviors.setup { context =>
        val requestNextAdapter: ActorRef[ShardingProducerController.RequestNext[TestConsumer.Job]] =
          context.messageAdapter(req => RequestNext(req.sendNextTo))
        producerController ! ShardingProducerController.Start(requestNextAdapter)

        // simulate fast producer
        Behaviors.withTimers { timers ⇒
          timers.startTimerWithFixedDelay(Tick, Tick, 20.millis)
          idle(0)
        }
      }
    }

    private def idle(n: Int): Behavior[Command] = {
      Behaviors.receiveMessage {
        case Tick ⇒ Behaviors.same
        case RequestNext(sendTo) ⇒ active(n + 1, sendTo)
      }
    }

    private def active(n: Int, sendTo: ActorRef[ShardingEnvelope[TestConsumer.Job]]): Behavior[Command] = {
      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case Tick ⇒
            val msg = s"msg-$n"
            val entityId = s"entity-${n % 3}"
            ctx.log.info2("sent {} to {}", msg, entityId)
            sendTo ! ShardingEnvelope(entityId, TestConsumer.Job(msg))
            idle(n)

          case RequestNext(_) ⇒
            throw new IllegalStateException("Unexpected RequestNext, already got one.")
        }
      }
    }

  }

  // FIXME this should also become part of Akka
  object TestShardingConsumer {
    def apply(
        delay: FiniteDuration,
        endCondition: TestConsumer.SomeAsyncJob => Boolean,
        endReplyTo: ActorRef[TestConsumer.CollectedProducerIds])
        : Behavior[ConsumerController.SequencedMessage[TestConsumer.Job]] = {
      Behaviors.setup { context =>
        val consumer = context.spawn(TestConsumer(delay, endCondition, endReplyTo), name = "consumer")
        // if consumer terminates this actor will also terminate
        context.watch(consumer)
        active(consumer, Map.empty)
      }
    }

    private def active(
        consumer: ActorRef[TestConsumer.Command],
        controllers: Map[String, ActorRef[ConsumerController.Command[TestConsumer.Job]]])
        : Behavior[ConsumerController.SequencedMessage[TestConsumer.Job]] = {
      Behaviors
        .receive[ConsumerController.SequencedMessage[TestConsumer.Job]] { (ctx, msg) =>
          controllers.get(msg.producerId) match {
            case Some(c) =>
              c ! msg
              Behaviors.same
            case None =>
              val c = ctx.spawn(
                ConsumerController[TestConsumer.Job](resendLost = true),
                s"consumerController-${msg.producerId}")
              // FIXME watch msg.producerController to cleanup terminated producers
              consumer ! TestConsumer.AddConsumerController(c)
              c ! msg
              active(consumer, controllers.updated(msg.producerId, c))
          }
        }
        .receiveSignal {
          case (_, Terminated(_)) =>
            Behaviors.stopped
        }
    }
  }

  object RandomFlakyNetwork {
    def apply[T](dropProbability: Any => Double): BehaviorInterceptor[T, T] =
      new RandomFlakyNetwork(dropProbability).asInstanceOf[BehaviorInterceptor[T, T]]
  }

  class RandomFlakyNetwork(dropProbability: Any => Double) extends BehaviorInterceptor[Any, Any] {
    override def aroundReceive(
        ctx: TypedActorContext[Any],
        msg: Any,
        target: BehaviorInterceptor.ReceiveTarget[Any]): Behavior[Any] = {
      if (ThreadLocalRandom.current().nextDouble() < dropProbability(msg)) {
        ctx.asScala.log.info("dropped {}", msg)
        Behaviors.same
      } else {
        target(ctx, msg)
      }
    }

  }

}

class ReliableDeliverySpec
    extends ScalaTestWithActorTestKit(ReliableDeliverySpec.config)
    with WordSpecLike
    with LogCapturing {
  import ReliableDeliverySpec._

  private var idCount = 0
  private def nextId(): Int = {
    idCount += 1
    idCount
  }

  private val defaultProducerDelay = 20.millis
  private val defaultConsumerDelay = 10.millis

  private def consumerEndCondition(seqNr: Long): TestConsumer.SomeAsyncJob => Boolean = {
    case TestConsumer.SomeAsyncJob(_, nr, _, _) => nr == seqNr
  }

  "ReliableDelivery" must {

    "illustrate point-to-point usage" in {
      nextId()
      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, consumerEndCondition(42), consumerEndProbe.ref, consumerController),
        name = s"destination-${idCount}")

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}"), s"producerController-${idCount}")
      val producer = spawn(TestProducer(defaultProducerDelay, producerController), name = s"producer-${idCount}")

      val registerDoneProbe = createTestProbe[Done]()
      producerController ! ProducerController.RegisterConsumer(consumerController, registerDoneProbe.ref)
      registerDoneProbe.expectMessage(Done)

      consumerEndProbe.receiveMessage(5.seconds)

      testKit.stop(producer)
      testKit.stop(producerController)
      testKit.stop(consumerController)
    }

    "illustrate point-to-point usage with confirmations" in {
      nextId()
      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, consumerEndCondition(42), consumerEndProbe.ref, consumerController),
        name = s"destination-${idCount}")

      val replyProbe = createTestProbe[Long]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}"), s"producerController-${idCount}")
      val producer =
        spawn(
          TestProducerWithConfirmation(defaultProducerDelay, replyProbe.ref, producerController),
          name = s"producer-${idCount}")

      val registerDoneProbe = createTestProbe[Done]()
      producerController ! ProducerController.RegisterConsumer(consumerController, registerDoneProbe.ref)
      registerDoneProbe.expectMessage(Done)

      consumerEndProbe.receiveMessage(5.seconds)

      replyProbe.receiveMessages(42, 5.seconds).toSet should ===((1L to 42L).toSet)

      testKit.stop(producer)
      testKit.stop(producerController)
      testKit.stop(consumerController)
    }

    "illustrate work-pulling usage" in {
      nextId()
      val workPullingController =
        spawn(WorkPullingProducerController[TestConsumer.Job](s"p-${idCount}"), s"workPullingController-${idCount}")
      val jobProducer =
        spawn(TestProducerWorkPulling(defaultProducerDelay, workPullingController), name = s"jobProducer-${idCount}")

      val consumerEndProbe1 = createTestProbe[TestConsumer.CollectedProducerIds]()
      val workerController1 =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"workerController1-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, consumerEndCondition(42), consumerEndProbe1.ref, workerController1),
        name = s"worker1-${idCount}")

      val consumerEndProbe2 = createTestProbe[TestConsumer.CollectedProducerIds]()
      val workerController2 =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"workerController2-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, consumerEndCondition(42), consumerEndProbe2.ref, workerController2),
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

    "illustrate sharding usage" in {
      nextId()
      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val sharding: ActorRef[ShardingEnvelope[SequencedMessage[TestConsumer.Job]]] =
        spawn(
          SimuatedSharding(
            _ => TestShardingConsumer(defaultConsumerDelay, consumerEndCondition(42), consumerEndProbe.ref)),
          s"sharding-${idCount}")

      val shardingController =
        spawn(ShardingProducerController[TestConsumer.Job](s"p-${idCount}", sharding), s"shardingController-${idCount}")
      val producer = spawn(TestShardingProducer(shardingController), name = s"shardingProducer-${idCount}")

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
            _ => TestShardingConsumer(defaultConsumerDelay, consumerEndCondition(42), consumerEndProbe.ref)),
          s"sharding-${idCount}")

      val shardingController1 =
        spawn(
          ShardingProducerController[TestConsumer.Job](
            s"p1-${idCount}", // note different producerId
            sharding),
          s"shardingController1-${idCount}")
      val producer1 = spawn(TestShardingProducer(shardingController1), name = s"shardingProducer1-${idCount}")

      val shardingController2 =
        spawn(
          ShardingProducerController[TestConsumer.Job](
            s"p2-${idCount}", // note different producerId
            sharding),
          s"shardingController2-${idCount}")
      val producer2 = spawn(TestShardingProducer(shardingController2), name = s"shardingProducer2-${idCount}")

      // expecting 3 end messages, one for each entity: "entity-0", "entity-1", "entity-2"
      val endMessages = consumerEndProbe.receiveMessages(3, 5.seconds)
      // verify that they received messages from both producers
      endMessages.flatMap(_.producerIds).toSet should ===(
        Set(
          s"p1-${idCount}-entity-0",
          s"p1-${idCount}-entity-1",
          s"p1-${idCount}-entity-2",
          s"p2-${idCount}-entity-0",
          s"p2-${idCount}-entity-1",
          s"p2-${idCount}-entity-2"))

      testKit.stop(producer1)
      testKit.stop(producer2)
      testKit.stop(shardingController1)
      testKit.stop(shardingController2)
      testKit.stop(sharding)
    }

    def testWithDelays(producerDelay: FiniteDuration, consumerDelay: FiniteDuration): Unit = {
      nextId()
      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
      spawn(
        TestConsumer(consumerDelay, consumerEndCondition(42), consumerEndProbe.ref, consumerController),
        name = s"destination-${idCount}")

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}"), s"producerController-${idCount}")
      val producer = spawn(TestProducer(producerDelay, producerController), name = s"producer-${idCount}")

      val registerDoneProbe = createTestProbe[Done]()
      producerController ! ProducerController.RegisterConsumer(consumerController, registerDoneProbe.ref)
      registerDoneProbe.expectMessage(Done)

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
      spawn(
        TestConsumer(defaultConsumerDelay, consumerEndCondition(42), consumerEndProbe.ref, consumerController),
        s"consumer1-${idCount}")

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}"), s"producerController-${idCount}")
      val producer = spawn(TestProducer(defaultProducerDelay, producerController), name = s"producer-${idCount}")

      val registerDoneProbe = createTestProbe[Done]()
      producerController ! ProducerController.RegisterConsumer(consumerController, registerDoneProbe.ref)
      registerDoneProbe.expectMessage(Done)

      consumerEndProbe.receiveMessage(5.seconds)

      val consumerEndProbe2 = createTestProbe[TestConsumer.CollectedProducerIds]()
      val consumerController2 =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController2-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, consumerEndCondition(42), consumerEndProbe2.ref, consumerController2),
        s"consumer2-${idCount}")
      producerController ! ProducerController.RegisterConsumer(consumerController2, registerDoneProbe.ref)
      registerDoneProbe.expectMessage(Done)

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
        TestConsumer(defaultConsumerDelay, consumerEndCondition(42), consumerEndProbe.ref, consumerController),
        name = s"destination-${idCount}")

      val producerController1 =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}"), s"producerController1-${idCount}")
      val producer1 = spawn(TestProducer(defaultProducerDelay, producerController1), name = s"producer1-${idCount}")

      val registerDoneProbe = createTestProbe[Done]()
      producerController1 ! ProducerController.RegisterConsumer(consumerController, registerDoneProbe.ref)
      registerDoneProbe.expectMessage(Done)

      // FIXME better way of testing this
      Thread.sleep(300)
      testKit.stop(producer1)
      testKit.stop(producerController1)

      val producerController2 =
        spawn(
          ProducerController[TestConsumer.Job](s"p-${idCount}" // must keep the same producerId
          ),
          s"producerController2-${idCount}")
      val producer2 = spawn(TestProducer(defaultProducerDelay, producerController2), name = s"producer2-${idCount}")

      producerController2 ! ProducerController.RegisterConsumer(consumerController, registerDoneProbe.ref)
      registerDoneProbe.expectMessage(Done)

      consumerEndProbe.receiveMessage(5.seconds)

      testKit.stop(producer2)
      testKit.stop(producerController2)
      testKit.stop(consumerController)
    }

    "work with flaky network" in {
      nextId()
      // RandomFlakyNetwork to simulate lost messages from producerController to consumerController
      val consumerDrop: Any => Double = {
        case _: ConsumerController.SequencedMessage[_] => 0.1
        case _                                         => 0.0
      }

      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val consumerController =
        spawn(
          Behaviors.intercept(() => RandomFlakyNetwork[ConsumerController.Command[TestConsumer.Job]](consumerDrop))(
            ConsumerController[TestConsumer.Job](resendLost = true)),
          s"consumerController-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, consumerEndCondition(42), consumerEndProbe.ref, consumerController),
        name = s"destination-${idCount}")

      // RandomFlakyNetwork to simulate lost messages from consumerController to producerController
      val producerDrop: Any => Double = {
        case _: ProducerController.Internal.Request => 0.3
        case _: ProducerController.Internal.Resend  => 0.3
        case _                                      => 0.0
      }

      val producerController = spawn(
        Behaviors.intercept(() => RandomFlakyNetwork[ProducerController.Command[TestConsumer.Job]](producerDrop))(
          ProducerController[TestConsumer.Job](s"p-${idCount}")),
        s"producerController-${idCount}")
      val producer = spawn(TestProducer(defaultProducerDelay, producerController), name = s"producer-${idCount}")

      val registerDoneProbe = createTestProbe[Done]()
      producerController ! ProducerController.RegisterConsumer(consumerController, registerDoneProbe.ref)
      registerDoneProbe.expectMessage(Done)

      consumerEndProbe.receiveMessage(30.seconds)

      testKit.stop(producer)
      testKit.stop(producerController)
      testKit.stop(consumerController)

    }

    "deliver for normal scenario" in {
      nextId()
      val consumerController =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController-${idCount}")
      val consumerProbe = createTestProbe[ConsumerController.Delivery[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}"), s"producerController-${idCount}")
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      val registerDoneProbe = createTestProbe[Done]()
      producerController ! ProducerController.RegisterConsumer(consumerController, registerDoneProbe.ref)
      registerDoneProbe.expectMessage(Done)

      // initial RequestNext
      val sendTo = producerProbe.receiveMessage().sendNextTo

      consumerController ! ConsumerController.Start(consumerProbe.ref)
      sendTo ! TestConsumer.Job("msg-1")

      val delivery1 = consumerProbe.receiveMessage()
      delivery1.seqNr should ===(1)
      delivery1.msg should ===(TestConsumer.Job("msg-1"))
      delivery1.confirmTo ! ConsumerController.Confirmed(delivery1.seqNr)

      sendTo ! TestConsumer.Job("msg-2")
      producerProbe.receiveMessage() // RequestNext immediately
      sendTo ! TestConsumer.Job("msg-3")
      producerProbe.receiveMessage()

      val delivery2 = consumerProbe.receiveMessage()
      delivery2.seqNr should ===(2)
      delivery2.msg should ===(TestConsumer.Job("msg-2"))
      // msg-3 isn't delivered before msg-2 has been confirmed (but we could allow more in flight)
      consumerProbe.expectNoMessage()
      delivery2.confirmTo ! ConsumerController.Confirmed(delivery2.seqNr)

      val delivery3 = consumerProbe.receiveMessage()
      delivery3.seqNr should ===(3)
      delivery3.msg should ===(TestConsumer.Job("msg-3"))
      delivery3.confirmTo ! ConsumerController.Confirmed(delivery3.seqNr)

      testKit.stop(producerController)
      testKit.stop(consumerController)
    }

    "resend initial SequencedMessage if lost" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}"), s"producerController-${idCount}")
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      val registerDoneProbe = createTestProbe[Done]()
      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref, registerDoneProbe.ref)
      registerDoneProbe.expectMessage(Done)

      val sendTo = producerProbe.receiveMessage().sendNextTo
      sendTo ! TestConsumer.Job("msg-1")

      val internalProducerController = producerController.unsafeUpcast[ProducerController.InternalCommand]

      consumerControllerProbe.expectMessage(
        ConsumerController.SequencedMessage(s"p-${idCount}", 1L, TestConsumer.Job("msg-1"), true)(
          internalProducerController))

      // the ConsumerController will send initial `Request` back, but if that is lost or if the first
      // `SequencedMessage` is lost the ProducerController will resend the SequencedMessage
      consumerControllerProbe.expectMessage(
        ConsumerController.SequencedMessage(s"p-${idCount}", 1L, TestConsumer.Job("msg-1"), true)(
          internalProducerController))

      internalProducerController ! ProducerController.Internal.Request(1L, 10L, true, false)
      consumerControllerProbe.expectNoMessage(1100.millis)

      testKit.stop(producerController)

      // FIXME more tests of resend first,
      // * for supportResend=false
      // * for registration of new ConsumerController
      // * when replacing producer
    }

  }

}
