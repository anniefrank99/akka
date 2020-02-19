/*
 * Copyright (C) 2020-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.delivery

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.delivery.internal.ShardingConsumerControllerImpl
import com.typesafe.config.Config

object ShardingConsumerController {

  object Settings {

    /**
     * Scala API: Factory method from config `akka.reliable-delivery.sharding.consumer-controller`
     * of the `ActorSystem`.
     */
    def apply(system: ActorSystem[_]): Settings =
      apply(system.settings.config.getConfig("akka.reliable-delivery.sharding.consumer-controller"))

    /**
     * Scala API: Factory method from Config corresponding to
     * `akka.reliable-delivery.sharding.consumer-controller`.
     */
    def apply(config: Config): Settings = {
      new Settings(bufferSize = config.getInt("buffer-size"), ConsumerController.Settings(config))
    }

    /**
     * Java API: Factory method from config `akka.reliable-delivery.sharding.consumer-controller`
     * of the `ActorSystem`.
     */
    def create(system: ActorSystem[_]): Settings =
      apply(system)

    /**
     * java API: Factory method from Config corresponding to
     * `akka.reliable-delivery.sharding.consumer-controller`.
     */
    def create(config: Config): Settings =
      apply(config)
  }

  final class Settings private (val bufferSize: Int, val consumerControllerSettings: ConsumerController.Settings) {

    def withBufferSize(newBufferSize: Int): Settings =
      copy(bufferSize = newBufferSize)

    def withConsumerControllerSettings(newConsumerControllerSettings: ConsumerController.Settings): Settings =
      copy(consumerControllerSettings = newConsumerControllerSettings)

    /**
     * Private copy method for internal use only.
     */
    private def copy(
        bufferSize: Int = bufferSize,
        consumerControllerSettings: ConsumerController.Settings = consumerControllerSettings) =
      new Settings(bufferSize, consumerControllerSettings)

    override def toString: String =
      s"Settings($bufferSize,$consumerControllerSettings)"
  }

  def apply[A, B](consumerBehavior: ActorRef[ConsumerController.Start[A]] => Behavior[B])
      : Behavior[ConsumerController.SequencedMessage[A]] = {
    Behaviors.setup { context =>
      ShardingConsumerControllerImpl(consumerBehavior, Settings(context.system))
    }
  }

  // can't overload apply, loosing type inference
  def withSettings[A, B](settings: Settings)(consumerBehavior: ActorRef[ConsumerController.Start[A]] => Behavior[B])
      : Behavior[ConsumerController.SequencedMessage[A]] = {
    ShardingConsumerControllerImpl(consumerBehavior, settings)
  }

  // FIXME javadsl create

}
