/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.Behaviors

// FIXME temporary instead of real sharding

object SimuatedSharding {
  final case class ShardingEnvelope[M](entityId: String, message: M)

  def apply[M](entityFactory: String => Behavior[M]): Behavior[ShardingEnvelope[M]] = {
    def next(entities: Map[String, ActorRef[M]]): Behavior[ShardingEnvelope[M]] = {
      Behaviors
        .receive[ShardingEnvelope[M]] { (context, command) =>
          command match {
            case ShardingEnvelope(entityId, message) =>
              entities.get(entityId) match {
                case Some(ref) =>
                  ref ! message
                  next(entities)
                case None =>
                  val ref = context.spawn(entityFactory(entityId), entityId)
                  context.watch(ref)
                  ref ! message
                  next(entities.updated(entityId, ref))
              }
          }
        }
        .receiveSignal {
          case (_, Terminated(ref)) =>
            next(entities - ref.path.name)
        }
    }

    next(Map.empty)

  }

}
