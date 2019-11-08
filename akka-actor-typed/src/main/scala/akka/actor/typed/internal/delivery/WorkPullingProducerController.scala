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

  private final case class Next[A](sendNextTo: ActorRef[A], belongsTo: ActorRef[ConsumerController.Command[A]])
      extends InternalCommand

  private final case class OutState[A](
      producerController: ActorRef[ProducerController.Command[A]],
      sendNextTo: Option[ActorRef[A]])

  private final case class State[A](
      consumers: Map[ActorRef[ConsumerController.Command[A]], OutState[A]],
      hasRequested: Boolean)

  // TODO Now the workers have to be registered explicitly/manually.
  // We could support automatic registration via Receptionist, similar to how routers work.

  final case class RegisterWorker[A](
      consumerController: ActorRef[ConsumerController.Command[A]],
      replyTo: ActorRef[Done])
      extends Command[A]

  private final case class Msg[A](msg: A) extends InternalCommand

  def apply[A: ClassTag, RequestNext](
      producerId: String,
      requestNextFactory: ActorRef[A] ⇒ RequestNext,
      producer: ActorRef[RequestNext]): Behavior[Command[A]] = {
    Behaviors
      .setup[InternalCommand] { context =>
        val msgAdapter: ActorRef[A] = context.messageAdapter(msg ⇒ Msg(msg))
        val requestNext = requestNextFactory(msgAdapter)
        new WorkPullingProducerController(context, producerId, requestNext, producer).active(
          State(Map.empty, hasRequested = false))
      }
      .narrow
  }

  // FIXME withConfirmation not implemented yet, see ProducerController.withConfirmation

}

class WorkPullingProducerController[A: ClassTag, RequestNext](
    context: ActorContext[WorkPullingProducerController.InternalCommand],
    producerId: String,
    requestNext: RequestNext,
    producer: ActorRef[RequestNext]) {
  import WorkPullingProducerController._

  private def active(s: State[A]): Behavior[InternalCommand] = {
    Behaviors.receiveMessage {
      case RegisterWorker(c: ActorRef[ConsumerController.Command[A]] @unchecked, replyTo) =>
        // FIXME adjust all logging, most should probably be debug
        context.log.info("Registered worker {}", c)
        val p =
          context.spawnAnonymous(
            ProducerController[A, Next[A]](
              producerId,
              // FIXME confirmedSeqNr in RequestNextParam not handled yet
              (nextParam: ProducerController.RequestNextParam[A]) => Next(nextParam.sendNextTo, c),
              context.self.narrow,
              seqMsg => c ! seqMsg))
        replyTo ! Done
        // FIXME watch and deregistration not implemented yet
        active(s.copy(consumers = s.consumers.updated(c, OutState(p, None))))

      case next: Next[A] @unchecked =>
        s.consumers.get(next.belongsTo) match {
          case Some(p) =>
            val newConsumers = s.consumers.updated(next.belongsTo, p.copy(sendNextTo = Some(next.sendNextTo)))
            if (s.hasRequested)
              active(s.copy(newConsumers))
            else {
              producer ! requestNext
              active(s.copy(newConsumers, hasRequested = true))
            }

          case None =>
            // obsolete Next, ConsumerController already deregistered
            Behaviors.unhandled
        }

      case Msg(msg: A) =>
        val consumersWithDemand = s.consumers.iterator.filter { case (_, out) => out.sendNextTo.isDefined }.toVector
        if (consumersWithDemand.isEmpty) {
          // FIXME all consumers have deregistered, need buffering or something
          throw new IllegalStateException(s"Deregister of consumers not supported yet. Message not handled: $msg")
        } else {
          val i = ThreadLocalRandom.current().nextInt(consumersWithDemand.size)
          val (c, out) = consumersWithDemand(i)
          val newConsumers = s.consumers.updated(c, out.copy(sendNextTo = None))
          out.sendNextTo.get ! msg
          val hasMoreDemand = consumersWithDemand.size > 1
          if (hasMoreDemand)
            producer ! requestNext
          active(s.copy(newConsumers, hasRequested = hasMoreDemand))
        }

    }
  }
}
