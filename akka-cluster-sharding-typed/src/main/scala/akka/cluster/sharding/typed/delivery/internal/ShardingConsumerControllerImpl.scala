/*
 * Copyright (C) 2020-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.delivery.internal

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ShardingConsumerControllerImpl {
  def apply[A, B](consumerBehavior: ActorRef[ConsumerController.Start[A]] => Behavior[B])
      : Behavior[ConsumerController.SequencedMessage[A]] = {
    Behaviors
      .setup[ConsumerController.Command[A]] { context =>
        context.setLoggerName("akka.cluster.sharding.typed.delivery.ShardingConsumerController")
        val consumer = context.spawn(consumerBehavior(context.self), name = "consumer")
        context.watch(consumer)
        waitForStart(context)
      }
      .narrow
  }

  private def waitForStart[A](
      context: ActorContext[ConsumerController.Command[A]]): Behavior[ConsumerController.Command[A]] = {
    Behaviors.withStash(10000) { stashBuffer => // FIXME buffer size
      Behaviors
        .receiveMessage[ConsumerController.Command[A]] {
          case start: ConsumerController.Start[A] =>
            stashBuffer.unstashAll(new ShardingConsumerControllerImpl[A](context, start.deliverTo).active(Map.empty))
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

private class ShardingConsumerControllerImpl[A](
    context: ActorContext[ConsumerController.Command[A]],
    deliverTo: ActorRef[ConsumerController.Delivery[A]]) {

  def active(
      controllers: Map[String, ActorRef[ConsumerController.Command[A]]]): Behavior[ConsumerController.Command[A]] = {

    Behaviors
      .receiveMessagePartial[ConsumerController.Command[A]] {
        case msg: ConsumerController.SequencedMessage[A] =>
          controllers.get(msg.producerId) match {
            case Some(c) =>
              c ! msg
              Behaviors.same
            case None =>
              val c = context.spawn(ConsumerController[A](), s"consumerController-${msg.producerId}")
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
