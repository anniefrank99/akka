/*
 * Copyright (C) 2020-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.delivery

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.ConsumerController
import akka.cluster.sharding.typed.delivery.internal.ShardingConsumerControllerImpl

object ShardingConsumerController {
  def apply[A, B](consumerBehavior: ActorRef[ConsumerController.Start[A]] => Behavior[B])
      : Behavior[ConsumerController.SequencedMessage[A]] = {
    ShardingConsumerControllerImpl(consumerBehavior)
  }

  // FIXME javadsl create

}
