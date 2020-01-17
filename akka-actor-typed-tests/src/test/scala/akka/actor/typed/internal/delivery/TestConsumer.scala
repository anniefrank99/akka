/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.internal.delivery.ConsumerController.SequencedMessage
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors

object TestConsumer {

  final case class Job(payload: String)
  sealed trait Command
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

  val defaultConsumerDelay: FiniteDuration = 10.millis

  def sequencedMessage(
      producerId: String,
      n: Long,
      producerController: ActorRef[ProducerController.Command[TestConsumer.Job]],
      ack: Boolean = false): SequencedMessage[TestConsumer.Job] = {
    ConsumerController.SequencedMessage(producerId, n, TestConsumer.Job(s"msg-$n"), first = n == 1, ack)(
      producerController.unsafeUpcast[ProducerController.InternalCommand])
  }

  def consumerEndCondition(seqNr: Long): TestConsumer.SomeAsyncJob => Boolean = {
    case TestConsumer.SomeAsyncJob(_, nr, _, _) => nr >= seqNr
  }

  def apply(
      delay: FiniteDuration,
      endSeqNr: Long,
      endReplyTo: ActorRef[CollectedProducerIds],
      controller: ActorRef[ConsumerController.Start[TestConsumer.Job]]): Behavior[Command] =
    apply(delay, consumerEndCondition(endSeqNr), endReplyTo, controller)

  def apply(
      delay: FiniteDuration,
      endCondition: SomeAsyncJob => Boolean,
      endReplyTo: ActorRef[CollectedProducerIds],
      controller: ActorRef[ConsumerController.Start[TestConsumer.Job]]): Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      new TestConsumer(ctx, delay, endCondition, endReplyTo, controller).active(Set.empty)
    }

}

class TestConsumer(
    ctx: ActorContext[TestConsumer.Command],
    delay: FiniteDuration,
    endCondition: TestConsumer.SomeAsyncJob => Boolean,
    endReplyTo: ActorRef[TestConsumer.CollectedProducerIds],
    controller: ActorRef[ConsumerController.Start[TestConsumer.Job]]) {
  import TestConsumer._

  ctx.setLoggerName("TestConsumer")

  private val deliverTo: ActorRef[ConsumerController.Delivery[Job]] =
    ctx.messageAdapter(d => JobDelivery(d.producerId, d.seqNr, d.msg, d.confirmTo))

  controller ! ConsumerController.Start(deliverTo)

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
      }
    }
  }

}
