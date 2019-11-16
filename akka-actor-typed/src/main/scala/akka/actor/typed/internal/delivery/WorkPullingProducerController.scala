/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import java.util.concurrent.ThreadLocalRandom

import scala.reflect.ClassTag

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors

object WorkPullingProducerController {

  sealed trait InternalCommand

  sealed trait Command[A] extends InternalCommand

  final case class Start[A](producer: ActorRef[RequestNext[A]]) extends Command[A]

  final case class RequestNext[A](sendNextTo: ActorRef[A])

  private final case class WrappedRequestNext[A](next: ProducerController.RequestNext[A]) extends InternalCommand

  private case object RegisterConsumerDone extends InternalCommand

  private final case class OutState[A](
      producerController: ActorRef[ProducerController.Command[A]],
      consumerController: ActorRef[ConsumerController.Command[A]],
      sendNextTo: Option[ActorRef[A]])

  private final case class State[A](out: Map[String, OutState[A]], producerIdCount: Long, hasRequested: Boolean)

  // TODO Now the workers have to be registered explicitly/manually.
  // We could support automatic registration via Receptionist, similar to how routers work.

  final case class RegisterWorker[A](
      consumerController: ActorRef[ConsumerController.Command[A]],
      replyTo: ActorRef[Done])
      extends Command[A]

  private final case class Msg[A](msg: A) extends InternalCommand

  def apply[A: ClassTag](producerId: String): Behavior[Command[A]] = {
    Behaviors
      .setup[InternalCommand] { context =>
        Behaviors.withStash[InternalCommand](Int.MaxValue) { buffer =>
          Behaviors.receiveMessagePartial {
            case reg: RegisterWorker[A] @unchecked =>
              buffer.stash(reg)
              Behaviors.same
            case start: Start[A] @unchecked =>
              val msgAdapter: ActorRef[A] = context.messageAdapter(msg ⇒ Msg(msg))
              val requestNext = RequestNext(msgAdapter)
              val b = new WorkPullingProducerController(context, producerId, start.producer, requestNext)
                .active(State(Map.empty, 0, hasRequested = false))
              buffer.unstashAll(b)
          }
        }
      }
      .narrow
  }

  // FIXME MessageWithConfirmation not implemented yet, see ProducerController

}

class WorkPullingProducerController[A: ClassTag](
    context: ActorContext[WorkPullingProducerController.InternalCommand],
    producerId: String,
    producer: ActorRef[WorkPullingProducerController.RequestNext[A]],
    requestNext: WorkPullingProducerController.RequestNext[A]) {
  import WorkPullingProducerController._

  private val requestNextAdapter: ActorRef[ProducerController.RequestNext[A]] =
    context.messageAdapter(WrappedRequestNext.apply)

  private def active(s: State[A]): Behavior[InternalCommand] = {
    Behaviors.receiveMessage {
      case RegisterWorker(c: ActorRef[ConsumerController.Command[A]] @unchecked, replyTo) =>
        // FIXME adjust all logging, most should probably be debug
        context.log.info("Registered worker [{}]", c)
        val newProducerIdCount = s.producerIdCount + 1
        val outKey = s"$producerId-$newProducerIdCount"
        val p = context.spawnAnonymous(ProducerController[A](outKey, seqMsg => c ! seqMsg))
        p ! ProducerController.Start(requestNextAdapter)
        p ! ProducerController.RegisterConsumer(c)
        replyTo ! Done
        // FIXME watch and deregistration not implemented yet
        active(s.copy(out = s.out.updated(outKey, OutState(p, c, None)), producerIdCount = newProducerIdCount))

      case w: WrappedRequestNext[A] =>
        val next = w.next
        val outKey = next.producerId
        s.out.get(outKey) match {
          case Some(p) =>
            val newOut = s.out.updated(outKey, p.copy(sendNextTo = Some(next.sendNextTo)))
            if (s.hasRequested)
              active(s.copy(newOut))
            else {
              producer ! requestNext
              active(s.copy(newOut, hasRequested = true))
            }

          case None =>
            // obsolete Next, ConsumerController already deregistered
            Behaviors.unhandled
        }

      case Msg(msg: A) =>
        val consumersWithDemand = s.out.iterator.filter { case (_, out) => out.sendNextTo.isDefined }.toVector
        if (consumersWithDemand.isEmpty) {
          // FIXME all consumers have deregistered, need buffering or something
          throw new IllegalStateException(s"Deregister of consumers not supported yet. Message not handled: $msg")
        } else {
          val i = ThreadLocalRandom.current().nextInt(consumersWithDemand.size)
          val (outKey, out) = consumersWithDemand(i)
          val newOut = s.out.updated(outKey, out.copy(sendNextTo = None))
          out.sendNextTo.get ! msg
          val hasMoreDemand = consumersWithDemand.size > 1
          if (hasMoreDemand)
            producer ! requestNext
          active(s.copy(newOut, hasRequested = hasMoreDemand))
        }

      case RegisterConsumerDone =>
        Behaviors.same

    }
  }
}
