/*
 * Copyright (C) 2020-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors

object ShardingConsumerController {
  def apply[A, B](
      // FIXME EntityContext parameter in consumerBehavior factory
      consumerBehavior: ActorRef[ConsumerController.Start[A]] => Behavior[B],
      resendLost: Boolean): Behavior[ConsumerController.SequencedMessage[A]] = {
    Behaviors
      .setup[ConsumerController.Command[A]] { context =>
        context.setLoggerName(classOf[ShardingConsumerController[_]])
        val consumer = context.spawn(consumerBehavior(context.self), name = "consumer")
        context.watch(consumer)
        waitForStart(context, resendLost)
      }
      .narrow
  }

  def waitForStart[A](
      context: ActorContext[ConsumerController.Command[A]],
      resendLost: Boolean): Behavior[ConsumerController.Command[A]] = {
    Behaviors.withStash(10000) { stashBuffer => // FIXME buffer size
      Behaviors
        .receiveMessage[ConsumerController.Command[A]] {
          case start: ConsumerController.Start[A] @unchecked =>
            stashBuffer.unstashAll(
              new ShardingConsumerController[A](context, resendLost, start.deliverTo).active(Map.empty))
          case other =>
            stashBuffer.stash(other)
            Behaviors.same
        }
        .receiveSignal {
          case (_, Terminated(_)) =>
            Behaviors.stopped
        }
    }
  }

}

class ShardingConsumerController[A](
    context: ActorContext[ConsumerController.Command[A]],
    resendLost: Boolean,
    deliverTo: ActorRef[ConsumerController.Delivery[A]]) {

  def active(
      controllers: Map[String, ActorRef[ConsumerController.Command[A]]]): Behavior[ConsumerController.Command[A]] = {

    Behaviors
      .receiveMessagePartial[ConsumerController.Command[A]] {
        case msg: ConsumerController.SequencedMessage[A] @unchecked =>
          controllers.get(msg.producerId) match {
            case Some(c) =>
              c ! msg
              Behaviors.same
            case None =>
              val c = context.spawn(ConsumerController[A](resendLost), s"consumerController-${msg.producerId}")
              // FIXME watch msg.producerController to cleanup terminated producers
              c ! ConsumerController.Start(deliverTo)
              c ! msg
              active(controllers.updated(msg.producerId, c))
          }
      }
      .receiveSignal {
        case (_, Terminated(_)) =>
          Behaviors.stopped
      }

  }

}
