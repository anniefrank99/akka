/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Random
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
import akka.actor.typed.internal.delivery.ProducerController.MessageWithConfirmation
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
          Behaviors.withTimers { timers =>
            timers.startTimerWithFixedDelay(Tick, Tick, delay)
            idle(0)
          }
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
            sendMessage(n, sendTo, ctx)
            idle(n)

          case RequestNext(_) =>
            throw new IllegalStateException("Unexpected RequestNext, already got one.")
        }
      }
    }

    private def activeNoDelay(n: Int): Behavior[Command] = {
      Behaviors.receive { (ctx, msg) =>
        msg match {
          case RequestNext(sendTo) =>
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
      Behaviors.setup { ctx =>
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
      Behaviors.setup { ctx =>
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
          case JobDelivery(producerId, seqNr, msg, confirmTo) =>
            // confirmation can be later, asynchronously
            if (delay == Duration.Zero)
              ctx.self ! SomeAsyncJob(producerId, seqNr, msg, confirmTo)
            else
              // schedule to simulate slow consumer
              ctx.scheduleOnce(10.millis, ctx.self, SomeAsyncJob(producerId, seqNr, msg, confirmTo))
            Behaviors.same

          case job @ SomeAsyncJob(producerId, seqNr, _, confirmTo) =>
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

        Behaviors.withTimers { timers =>
          timers.startTimerWithFixedDelay(Tick, Tick, delay)
          idle(0, replyProbe)
        }
      }
    }

    private def idle(n: Int, replyProbe: ActorRef[Long]): Behavior[Command] = {
      Behaviors.receiveMessage {
        case Tick                => Behaviors.same
        case RequestNext(sendTo) => active(n + 1, replyProbe, sendTo)
        case Confirmed(seqNr) =>
          replyProbe ! seqNr
          Behaviors.same
      }
    }

    private def active(
        n: Int,
        replyProbe: ActorRef[Long],
        sendTo: ActorRef[ProducerController.MessageWithConfirmation[TestConsumer.Job]]): Behavior[Command] = {
      Behaviors.receive { (ctx, msg) =>
        msg match {
          case Tick =>
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

          case RequestNext(_) =>
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
    def apply[T](rnd: Random, dropProbability: Any => Double): BehaviorInterceptor[T, T] =
      new RandomFlakyNetwork(rnd, dropProbability).asInstanceOf[BehaviorInterceptor[T, T]]
  }

  class RandomFlakyNetwork(rnd: Random, dropProbability: Any => Double) extends BehaviorInterceptor[Any, Any] {
    override def aroundReceive(
        ctx: TypedActorContext[Any],
        msg: Any,
        target: BehaviorInterceptor.ReceiveTarget[Any]): Behavior[Any] = {
      if (rnd.nextDouble() < dropProbability(msg)) {
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

  private def sequencedMessage(
      n: Long,
      producerController: ActorRef[ProducerController.Command[TestConsumer.Job]],
      ack: Boolean = false): SequencedMessage[TestConsumer.Job] = {
    ConsumerController.SequencedMessage(s"p-$idCount", n, TestConsumer.Job(s"msg-$n"), first = n == 1, ack)(
      producerController.unsafeUpcast[ProducerController.InternalCommand])
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
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController-${idCount}")
      val producer = spawn(TestProducer(defaultProducerDelay, producerController), name = s"producer-${idCount}")

      consumerController ! ConsumerController.RegisterToProducerController(producerController)

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
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController-${idCount}")
      val producer =
        spawn(
          TestProducerWithConfirmation(defaultProducerDelay, replyProbe.ref, producerController),
          name = s"producer-${idCount}")

      consumerController ! ConsumerController.RegisterToProducerController(producerController)

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
      spawn(
        TestConsumer(defaultConsumerDelay, consumerEndCondition(42), consumerEndProbe.ref, consumerController),
        s"consumer1-${idCount}")

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController-${idCount}")
      val producer = spawn(TestProducer(defaultProducerDelay, producerController), name = s"producer-${idCount}")

      consumerController ! ConsumerController.RegisterToProducerController(producerController)

      consumerEndProbe.receiveMessage(5.seconds)

      val consumerEndProbe2 = createTestProbe[TestConsumer.CollectedProducerIds]()
      val consumerController2 =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"consumerController2-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, consumerEndCondition(42), consumerEndProbe2.ref, consumerController2),
        s"consumer2-${idCount}")
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
        TestConsumer(defaultConsumerDelay, consumerEndCondition(42), consumerEndProbe.ref, consumerController),
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

    "work with flaky network" in {
      nextId()

      val rndSeed = System.currentTimeMillis()
      val rnd = new Random(rndSeed)
      val consumerDropProbability = 0.1 + rnd.nextDouble() * 0.4
      val producerDropProbability = 0.1 + rnd.nextDouble() * 0.3
      val consumerDelay = rnd.nextInt(40).millis
      val producerDelay = rnd.nextInt(40).millis
      system.log.infoN(
        "Random seed [{}], consumerDropProbability [{}], producerDropProbability [{}], " +
        "consumerDelay [{}], producerDelay [{}]",
        rndSeed,
        consumerDropProbability,
        producerDropProbability,
        consumerDelay,
        producerDelay)

      // RandomFlakyNetwork to simulate lost messages from producerController to consumerController
      val consumerDrop: Any => Double = {
        case _: ConsumerController.SequencedMessage[_] => consumerDropProbability
        case _                                         => 0.0
      }

      val consumerEndProbe = createTestProbe[TestConsumer.CollectedProducerIds]()
      val consumerController =
        spawn(
          Behaviors.intercept(() =>
            RandomFlakyNetwork[ConsumerController.Command[TestConsumer.Job]](rnd, consumerDrop))(
            ConsumerController[TestConsumer.Job](resendLost = true)),
          s"consumerController-${idCount}")
      spawn(
        TestConsumer(consumerDelay, consumerEndCondition(63), consumerEndProbe.ref, consumerController),
        name = s"destination-${idCount}")

      // RandomFlakyNetwork to simulate lost messages from consumerController to producerController
      val producerDrop: Any => Double = {
        case _: ProducerController.Internal.Request    => producerDropProbability
        case _: ProducerController.Internal.Resend     => producerDropProbability
        case _: ProducerController.RegisterConsumer[_] => producerDropProbability
        case _                                         => 0.0
      }

      val producerController = spawn(
        Behaviors.intercept(() => RandomFlakyNetwork[ProducerController.Command[TestConsumer.Job]](rnd, producerDrop))(
          ProducerController[TestConsumer.Job](s"p-${idCount}", None)),
        s"producerController-${idCount}")
      val producer = spawn(TestProducer(producerDelay, producerController), name = s"producer-${idCount}")

      consumerController ! ConsumerController.RegisterToProducerController(producerController)

      consumerEndProbe.receiveMessage(120.seconds)

      testKit.stop(producer)
      testKit.stop(producerController)
      testKit.stop(consumerController)

    }

  }

  // FIXME move this unit test to separate file
  "ProducerController" must {

    "resend lost initial SequencedMessage" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController-${idCount}")
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      val sendTo = producerProbe.receiveMessage().sendNextTo
      sendTo ! TestConsumer.Job("msg-1")

      consumerControllerProbe.expectMessage(sequencedMessage(1, producerController))

      // the ConsumerController will send initial `Request` back, but if that is lost or if the first
      // `SequencedMessage` is lost the ProducerController will resend the SequencedMessage
      consumerControllerProbe.expectMessage(sequencedMessage(1, producerController))

      val internalProducerController = producerController.unsafeUpcast[ProducerController.InternalCommand]
      internalProducerController ! ProducerController.Internal.Request(1L, 10L, true, false)
      consumerControllerProbe.expectNoMessage(1100.millis)

      testKit.stop(producerController)
    }

    "resend lost SequencedMessage when receiving Resend" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      consumerControllerProbe.expectMessage(sequencedMessage(1, producerController))

      producerController ! ProducerController.Internal.Request(1L, 10L, true, false)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-2")
      consumerControllerProbe.expectMessage(sequencedMessage(2, producerController))

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-3")
      consumerControllerProbe.expectMessage(sequencedMessage(3, producerController))
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-4")
      consumerControllerProbe.expectMessage(sequencedMessage(4, producerController))

      // let's say 3 is lost, when 4 is received the ConsumerController detects the gap and sends Resend(3)
      producerController ! ProducerController.Internal.Resend(3)

      consumerControllerProbe.expectMessage(sequencedMessage(3, producerController))
      consumerControllerProbe.expectMessage(sequencedMessage(4, producerController))

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-5")
      consumerControllerProbe.expectMessage(sequencedMessage(5, producerController))

      testKit.stop(producerController)
    }

    "resend last lost SequencedMessage when receiving Request" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      consumerControllerProbe.expectMessage(sequencedMessage(1, producerController))

      producerController ! ProducerController.Internal.Request(1L, 10L, true, false)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-2")
      consumerControllerProbe.expectMessage(sequencedMessage(2, producerController))

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-3")
      consumerControllerProbe.expectMessage(sequencedMessage(3, producerController))
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-4")
      consumerControllerProbe.expectMessage(sequencedMessage(4, producerController))

      // let's say 3 and 4 are lost, and new more messages are sent from producer
      // ConsumerController will resend Request periodically
      producerController ! ProducerController.Internal.Request(2L, 10L, true, true)

      consumerControllerProbe.expectMessage(sequencedMessage(3, producerController))
      consumerControllerProbe.expectMessage(sequencedMessage(4, producerController))

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-5")
      consumerControllerProbe.expectMessage(sequencedMessage(5, producerController))

      testKit.stop(producerController)
    }

    "support registration of new ConsumerController" in {
      nextId()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      val consumerControllerProbe1 = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()
      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe1.ref)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      consumerControllerProbe1.expectMessage(sequencedMessage(1, producerController))

      producerController ! ProducerController.Internal.Request(1L, 10L, true, false)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-2")
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-3")

      val consumerControllerProbe2 = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()
      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe2.ref)

      consumerControllerProbe2.expectMessage(
        sequencedMessage(2, producerController).copy(first = true)(producerController))
      consumerControllerProbe2.expectNoMessage(100.millis)
      // if no Request confirming the first (seqNr=2) it will resend it
      consumerControllerProbe2.expectMessage(
        sequencedMessage(2, producerController).copy(first = true)(producerController))

      producerController ! ProducerController.Internal.Request(2L, 10L, true, false)
      // then the other unconfirmed should be resent
      consumerControllerProbe2.expectMessage(sequencedMessage(3, producerController))

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-4")
      consumerControllerProbe2.expectMessage(sequencedMessage(4, producerController))

      testKit.stop(producerController)
    }

    "reply to MessageWithConfirmation" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      val replyTo = createTestProbe[Long]()

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-1"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(1, producerController, ack = true))
      producerController ! ProducerController.Internal.Request(1L, 10L, true, false)
      replyTo.expectMessage(1L)

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-2"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(2, producerController, ack = true))
      producerController ! ProducerController.Internal.Ack(2L)
      replyTo.expectMessage(2L)

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-3"), replyTo.ref)
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-4"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(3, producerController, ack = true))
      consumerControllerProbe.expectMessage(sequencedMessage(4, producerController, ack = true))
      // Ack(3 lost, but Ack(4) triggers reply for 3 and 4
      producerController ! ProducerController.Internal.Ack(4L)
      replyTo.expectMessage(3L)
      replyTo.expectMessage(4L)

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-5"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(5, producerController, ack = true))
      // Ack(5) lost, but eventually a Request will trigger the reply
      producerController ! ProducerController.Internal.Request(5L, 15L, true, false)
      replyTo.expectMessage(5L)

      testKit.stop(producerController)
    }

    "allow restart of producer" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](s"p-${idCount}", None), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe1 = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe1.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      producerProbe1.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      consumerControllerProbe.expectMessage(sequencedMessage(1, producerController))
      producerController ! ProducerController.Internal.Request(1L, 10L, true, false)

      producerProbe1.receiveMessage().sendNextTo ! TestConsumer.Job("msg-2")
      consumerControllerProbe.expectMessage(sequencedMessage(2, producerController))

      producerProbe1.receiveMessage().currentSeqNr should ===(3)

      // restart producer, new Start
      val producerProbe2 = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe2.ref)

      producerProbe2.receiveMessage().sendNextTo ! TestConsumer.Job("msg-3")
      consumerControllerProbe.expectMessage(sequencedMessage(3, producerController))

      producerProbe2.receiveMessage().sendNextTo ! TestConsumer.Job("msg-4")
      consumerControllerProbe.expectMessage(sequencedMessage(4, producerController))

      testKit.stop(producerController)
    }

  }

  // FIXME move this unit test to separate file
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

      consumerController ! sequencedMessage(1, producerControllerProbe.ref)

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
        consumerController ! sequencedMessage(n, producerControllerProbe.ref)
      }

      producerControllerProbe.expectMessage(ProducerController.Internal.Request(0, windowSize, true, false))
      (1 until windowSize / 2).foreach { n =>
        consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
        consumerController ! ConsumerController.Confirmed(n)
        if (n == 1)
          producerControllerProbe.expectMessage(ProducerController.Internal.Request(1, windowSize, true, false))
      }

      producerControllerProbe.expectNoMessage()

      consumerController ! sequencedMessage(windowSize / 2, producerControllerProbe.ref)

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

      consumerController ! sequencedMessage(1, producerControllerProbe.ref)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(1)

      producerControllerProbe.expectMessage(ProducerController.Internal.Request(0, 20, true, false))
      producerControllerProbe.expectMessage(ProducerController.Internal.Request(1, 20, true, false))

      consumerController ! sequencedMessage(2, producerControllerProbe.ref)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(2)

      consumerController ! sequencedMessage(5, producerControllerProbe.ref)
      producerControllerProbe.expectMessage(ProducerController.Internal.Resend(3))

      consumerController ! sequencedMessage(3, producerControllerProbe.ref)
      consumerController ! sequencedMessage(4, producerControllerProbe.ref)
      consumerController ! sequencedMessage(5, producerControllerProbe.ref)

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

      consumerController ! sequencedMessage(1, producerControllerProbe.ref)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(1)

      producerControllerProbe.expectMessage(ProducerController.Internal.Request(0, 20, true, false))
      producerControllerProbe.expectMessage(ProducerController.Internal.Request(1, 20, true, false))

      consumerController ! sequencedMessage(2, producerControllerProbe.ref)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(2)

      consumerController ! sequencedMessage(3, producerControllerProbe.ref)
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

      consumerController ! sequencedMessage(1, producerControllerProbe.ref, ack = true)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(1)

      producerControllerProbe.expectMessage(ProducerController.Internal.Request(0, 20, true, false))
      producerControllerProbe.expectMessage(ProducerController.Internal.Request(1, 20, true, false))

      consumerController ! sequencedMessage(2, producerControllerProbe.ref, ack = true)
      consumerProbe.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(2)
      producerControllerProbe.expectMessage(ProducerController.Internal.Ack(2))

      consumerController ! sequencedMessage(3, producerControllerProbe.ref, ack = true)
      consumerController ! sequencedMessage(4, producerControllerProbe.ref, ack = false)
      consumerController ! sequencedMessage(5, producerControllerProbe.ref, ack = true)

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

      consumerController ! sequencedMessage(1, producerControllerProbe.ref)
      consumerProbe1.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(1)

      producerControllerProbe.expectMessage(ProducerController.Internal.Request(0, 20, true, false))
      producerControllerProbe.expectMessage(ProducerController.Internal.Request(1, 20, true, false))

      consumerController ! sequencedMessage(2, producerControllerProbe.ref)
      consumerProbe1.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]]
      consumerController ! ConsumerController.Confirmed(2)

      consumerController ! sequencedMessage(3, producerControllerProbe.ref)
      consumerProbe1.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]].seqNr should ===(3)

      // restart consumer, before Confirmed(3)
      val consumerProbe2 = createTestProbe[ConsumerController.Delivery[TestConsumer.Job]]()
      consumerController ! ConsumerController.Start(consumerProbe2.ref)

      consumerProbe2.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]].seqNr should ===(3)
      consumerController ! ConsumerController.Confirmed(3)

      consumerController ! sequencedMessage(4, producerControllerProbe.ref)
      consumerProbe2.expectMessageType[ConsumerController.Delivery[TestConsumer.Job]].seqNr should ===(4)
      consumerController ! ConsumerController.Confirmed(4)

      testKit.stop(consumerController)
    }
  }

  // FIXME more tests for supportResend=false

}
