/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import java.util.concurrent.ThreadLocalRandom

import scala.reflect.ClassTag

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.StashBuffer

object WorkPullingProducerController {

  sealed trait InternalCommand

  sealed trait Command[A] extends InternalCommand

  final case class Start[A](producer: ActorRef[RequestNext[A]]) extends Command[A]

  final case class RequestNext[A](sendNextTo: ActorRef[A])

  final case class GetWorkerStats[A](replyTo: ActorRef[WorkerStats]) extends Command[A]

  final case class WorkerStats(numberOfWorkers: Int)

  private final case class WorkerRequestNext[A](next: ProducerController.RequestNext[A]) extends InternalCommand

  private case object RegisterConsumerDone extends InternalCommand

  private final case class OutState[A](
      producerController: ActorRef[ProducerController.Command[A]],
      consumerController: ActorRef[ConsumerController.Command[A]],
      seqNr: Long,
      unconfirmed: Vector[(Long, A)],
      sendNextTo: Option[ActorRef[A]])

  private final case class State[A](
      workers: Set[ActorRef[ConsumerController.Command[A]]],
      out: Map[String, OutState[A]],
      producerIdCount: Long,
      hasRequested: Boolean)

  // registration of workers via Receptionist
  private final case class CurrentWorkers[A](workers: Set[ActorRef[ConsumerController.Command[A]]])
      extends InternalCommand

  private final case class Msg[A](msg: A, wasStashed: Boolean) extends InternalCommand

  def apply[A: ClassTag](
      producerId: String,
      workerServiceKey: ServiceKey[ConsumerController.Command[A]]): Behavior[Command[A]] = {
    Behaviors
      .withStash[InternalCommand](1000) { stashBuffer => // FIXME stash config
        Behaviors.setup[InternalCommand] { context =>
          context.setLoggerName(classOf[WorkPullingProducerController[_]])
          val listingAdapter = context.messageAdapter[Receptionist.Listing](listing =>
            CurrentWorkers[A](listing.allServiceInstances(workerServiceKey)))
          context.system.receptionist ! Receptionist.Subscribe(workerServiceKey, listingAdapter)

          Behaviors.receiveMessage {
            case start: Start[A] @unchecked =>
              val msgAdapter: ActorRef[A] = context.messageAdapter(msg => Msg(msg, wasStashed = false))
              val requestNext = RequestNext(msgAdapter)
              val b = new WorkPullingProducerController(context, stashBuffer, producerId, start.producer, requestNext)
                .active(State(Set.empty, Map.empty, 0, hasRequested = false))
              stashBuffer.unstashAll(b)

            case other =>
              stashBuffer.stash(other)
              Behaviors.same
          }
        }
      }
      .narrow
  }

  // FIXME MessageWithConfirmation not implemented yet, see ProducerController

}

class WorkPullingProducerController[A: ClassTag](
    context: ActorContext[WorkPullingProducerController.InternalCommand],
    stashBuffer: StashBuffer[WorkPullingProducerController.InternalCommand],
    producerId: String,
    producer: ActorRef[WorkPullingProducerController.RequestNext[A]],
    requestNext: WorkPullingProducerController.RequestNext[A]) {
  import WorkPullingProducerController._

  private val workerRequestNextAdapter: ActorRef[ProducerController.RequestNext[A]] =
    context.messageAdapter(WorkerRequestNext.apply)

  private def active(s: State[A]): Behavior[InternalCommand] = {
    Behaviors.receiveMessage {
      case curr: CurrentWorkers[A] @unchecked =>
        // FIXME adjust all logging, most should probably be debug
        val addedWorkers = curr.workers.diff(s.workers)
        val removedWorkers = s.workers.diff(curr.workers)

        val newState = addedWorkers.foldLeft(s) { (acc, c) =>
          context.log.info("Registered worker [{}]", c)
          val newProducerIdCount = acc.producerIdCount + 1
          val outKey = s"$producerId-$newProducerIdCount"
          // FIXME support DurableProducerQueue
          val p =
            context.spawnAnonymous(ProducerController[A](outKey, durableQueueBehavior = None))
          p ! ProducerController.Start(workerRequestNextAdapter)
          p ! ProducerController.RegisterConsumer(c)
          acc.copy(
            out = s.out.updated(outKey, OutState(p, c, 0L, Vector.empty, None)),
            producerIdCount = newProducerIdCount)
        }

        val newState2 = removedWorkers.foldLeft(newState) { (acc, c) =>
          context.log.info("Deregistered worker [{}]", c)
          acc.out.find { case (_, outState) => outState.consumerController == c } match {
            case Some((key, outState)) =>
              context.stop(outState.producerController)
              // resend the unconfirmed, sending to self since order of messages for WorkPulling doesn't matter anyway
              if (outState.unconfirmed.nonEmpty)
                context.log.infoN(
                  "Resending unconfirmed from deregistered worker [{}], from seqNr [{}] to [{}]",
                  c,
                  outState.unconfirmed.head._1,
                  outState.unconfirmed.last._1)
              outState.unconfirmed.foreach {
                case (_, msg) => context.self ! Msg(msg, wasStashed = true)
              }
              acc.copy(out = acc.out - key)

            case None => acc
          }
        }

        active(newState2.copy(workers = curr.workers))

      case w: WorkerRequestNext[A] =>
        val next = w.next
        val outKey = next.producerId
        s.out.get(outKey) match {
          case Some(outState) =>
            context.log.info("WorkerRequestNext from [{}]", w.next.producerId)
            val newUnconfirmed = outState.unconfirmed.dropWhile { case (seqNr, _) => seqNr <= w.next.confirmedSeqNr }
            val newOut =
              s.out.updated(
                outKey,
                outState
                  .copy(seqNr = w.next.currentSeqNr, unconfirmed = newUnconfirmed, sendNextTo = Some(next.sendNextTo)))

            if (stashBuffer.nonEmpty) {
              context.log.info("Unstash [{}]", stashBuffer.size)
              stashBuffer.unstashAll(active(s.copy(out = newOut)))
            } else if (s.hasRequested) {
              active(s.copy(out = newOut))
            } else {
              context.log.info("RequestNext after WorkerRequestNext from [{}]", w.next.producerId)
              producer ! requestNext
              active(s.copy(out = newOut, hasRequested = true))
            }

          case None =>
            // obsolete Next, ConsumerController already deregistered
            Behaviors.unhandled
        }

      case m @ Msg(msg: A, wasStashed) =>
        val consumersWithDemand = s.out.iterator.filter { case (_, out) => out.sendNextTo.isDefined }.toVector
        context.log.info2(
          "Received Msg [{}], consumersWithDemand [{}]",
          m,
          consumersWithDemand.map(_._1).mkString(", "))
        if (consumersWithDemand.isEmpty) {
          context.log.info("Stash [{}]", msg)
          stashBuffer.stash(m.copy(wasStashed = true))
          Behaviors.same
        } else {
          val i = ThreadLocalRandom.current().nextInt(consumersWithDemand.size)
          val (outKey, out) = consumersWithDemand(i)
          val newUnconfirmed = out.unconfirmed :+ (out.seqNr -> msg)
          val newOut = s.out.updated(outKey, out.copy(unconfirmed = newUnconfirmed, sendNextTo = None))
          out.sendNextTo.get ! msg

          val hasMoreDemand = consumersWithDemand.size >= 2
          val requested =
            if (hasMoreDemand && (!wasStashed || stashBuffer.isEmpty)) {
              context.log.info("RequestNext after Msg [{}]", m)
              producer ! requestNext
              true
            } else if (wasStashed) {
              s.hasRequested
            } else {
              false
            }
          active(s.copy(out = newOut, hasRequested = requested))
        }

      case GetWorkerStats(replyTo) =>
        replyTo ! WorkerStats(s.workers.size)
        Behaviors.same

      case RegisterConsumerDone =>
        Behaviors.same

    }
  }
}
