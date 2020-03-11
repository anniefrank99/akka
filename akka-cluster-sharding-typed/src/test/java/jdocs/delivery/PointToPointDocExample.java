/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.delivery;

// #imports
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.delivery.ProducerController;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import java.math.BigInteger;
import java.util.Optional;

// #imports

// #consumer
import akka.actor.typed.delivery.ConsumerController;

// #consumer

import akka.actor.typed.ActorSystem;
import java.util.UUID;

interface PointToPointDocExample {

  // #producer
  public class FibonacciProducer {

    interface Command {}

    private static class WrappedRequestNext implements Command {
      final ProducerController.RequestNext<FibonacciConsumer.Command> next;

      private WrappedRequestNext(ProducerController.RequestNext<FibonacciConsumer.Command> next) {
        this.next = next;
      }
    }

    private final ActorContext<Command> context;

    private FibonacciProducer(ActorContext<Command> context) {
      this.context = context;
    }

    public static Behavior<Command> create(
        ActorRef<ProducerController.Command<FibonacciConsumer.Command>> producerController) {
      return Behaviors.setup(
          context -> {
            ActorRef<ProducerController.RequestNext<FibonacciConsumer.Command>> requestNextAdapter =
                context.messageAdapter(
                    ProducerController.RequestNext.class, WrappedRequestNext::new);
            producerController.tell(new ProducerController.Start<>(requestNextAdapter));

            return new FibonacciProducer(context).fibonacci(0, BigInteger.ONE, BigInteger.ZERO);
          });
    }

    private Behavior<Command> fibonacci(long n, BigInteger b, BigInteger a) {
      return Behaviors.receive(Command.class)
          .onMessage(WrappedRequestNext.class, w -> onWrappedRequestNext(w, n, b, a))
          .build();
    }

    private Behavior<Command> onWrappedRequestNext(
        WrappedRequestNext w, long n, BigInteger b, BigInteger a) {
      context.getLog().info("Generated fibonacci {}: {}", n, a);
      w.next.sendNextTo().tell(new FibonacciConsumer.FibonacciNumber(n, a));

      if (n == 1000) return Behaviors.stopped();
      else return fibonacci(n + 1, a.add(b), b);
    }
  }
  // #producer

  // #consumer
  public class FibonacciConsumer {
    interface Command {}

    public static class FibonacciNumber implements Command {
      public final long n;
      public final BigInteger value;

      public FibonacciNumber(long n, BigInteger value) {
        this.n = n;
        this.value = value;
      }
    }

    private static class WrappedDelivery implements Command {
      final ConsumerController.Delivery<Command> delivery;

      private WrappedDelivery(ConsumerController.Delivery<Command> delivery) {
        this.delivery = delivery;
      }
    }

    public static Behavior<Command> create(
        ActorRef<ConsumerController.Command<FibonacciConsumer.Command>> consumerController) {
      return Behaviors.setup(
          context -> {
            ActorRef<ConsumerController.Delivery<FibonacciConsumer.Command>> deliveryAdapter =
                context.messageAdapter(ConsumerController.Delivery.class, WrappedDelivery::new);
            consumerController.tell(new ConsumerController.Start<>(deliveryAdapter));

            return Behaviors.receive(Command.class)
                .onMessage(
                    WrappedDelivery.class,
                    w -> {
                      FibonacciNumber number = (FibonacciNumber) w.delivery.message();
                      context.getLog().info("Processed fibonacci {}: {}", number.n, number.value);
                      w.delivery.confirmTo().tell(ConsumerController.confirmed());
                      return Behaviors.same();
                    })
                .build();
          });
    }
  }
  // #consumer

  public class Guardian {
    public static Behavior<Void> create() {
      return Behaviors.setup(
          context -> {
            // #connect
            ActorRef<ConsumerController.Command<FibonacciConsumer.Command>> consumerController =
                context.spawn(ConsumerController.create(), "consumerController");
            context.spawn(FibonacciConsumer.create(consumerController), "consumer");

            String producerId = "fibonacci-" + UUID.randomUUID();
            ActorRef<ProducerController.Command<FibonacciConsumer.Command>> producerController =
                context.spawn(
                    ProducerController.create(
                        FibonacciConsumer.Command.class, producerId, Optional.empty()),
                    "producerController");
            context.spawn(FibonacciProducer.create(producerController), "producer");

            consumerController.tell(
                new ConsumerController.RegisterToProducerController<>(producerController));
            // #connect

            return Behaviors.empty();
          });
    }
  }

  public static void main(String[] args) {
    ActorSystem.create(Guardian.create(), "FibonacciExample");
  }
}
