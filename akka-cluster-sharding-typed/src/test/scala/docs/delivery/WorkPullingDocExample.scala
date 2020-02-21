/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.delivery

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.typed.ActorRef
import com.github.ghik.silencer.silent

@silent("never used")
object WorkPullingDocExample {

  //#imports
  import akka.actor.typed.scaladsl.Behaviors
  import akka.actor.typed.Behavior
  //#imports

  //#consumer
  import akka.actor.typed.delivery.ConsumerController
  import akka.actor.typed.receptionist.ServiceKey

  object ImageConverter {
    sealed trait Command
    final case class ConversionJob(fromFormat: String, toFormat: String, image: Array[Byte])
    private case class WrappedDelivery(d: ConsumerController.Delivery[ConversionJob]) extends Command

    val serviceKey = ServiceKey[ConsumerController.Command[ConversionJob]]("ImageConverter")

    def apply(): Behavior[Command] = {
      Behaviors.setup { context =>
        val deliveryAdapter =
          context.messageAdapter[ConsumerController.Delivery[ConversionJob]](WrappedDelivery(_))
        val consumerController =
          context.spawn(ConsumerController(serviceKey), "consumerController")
        consumerController ! ConsumerController.Start(deliveryAdapter)

        Behaviors.receiveMessage {
          case WrappedDelivery(delivery) =>
            val image = delivery.msg.image
            val fromFormat = delivery.msg.fromFormat
            val toFormat = delivery.msg.toFormat
            // convert image...

            // and when completed confirm
            delivery.confirmTo ! ConsumerController.Confirmed(delivery.seqNr)

            Behaviors.same
        }

      }
    }

  }
  //#consumer

  //#producer
  import akka.actor.typed.delivery.WorkPullingProducerController
  import akka.actor.typed.scaladsl.ActorContext
  import akka.actor.typed.scaladsl.StashBuffer

  object ImageWorkManager {
    trait Command
    final case class Convert(fromFormat: String, toFormat: String, image: Array[Byte]) extends Command
    private case class WrappedRequestNext(r: WorkPullingProducerController.RequestNext[ImageConverter.ConversionJob])
        extends Command

    //#producer

    //#ask
    final case class ConvertRequest(
        fromFormat: String,
        toFormat: String,
        image: Array[Byte],
        replyTo: ActorRef[ConvertResponse])
        extends Command

    sealed trait ConvertResponse
    case object ConvertAccepted extends ConvertResponse
    case object ConvertTimedOut extends ConvertResponse

    private final case class AskReply(originalReplyTo: ActorRef[ConvertResponse], timeout: Boolean) extends Command
    //#ask

    //#producer
    def apply(): Behavior[Command] = {
      Behaviors.setup { context =>
        val requestNextAdapter =
          context.messageAdapter[WorkPullingProducerController.RequestNext[ImageConverter.ConversionJob]](
            WrappedRequestNext(_))
        val producerController = context.spawn(
          WorkPullingProducerController(
            producerId = "workManager",
            workerServiceKey = ImageConverter.serviceKey,
            durableQueueBehavior = None),
          "producerController")
        //#producer
        //#durable-queue
        import akka.persistence.typed.delivery.EventSourcedProducerQueue
        import akka.persistence.typed.PersistenceId

        val durableQueue =
          EventSourcedProducerQueue[ImageConverter.ConversionJob](PersistenceId.ofUniqueId("ImageWorkManager"))
        val durableProducerController = context.spawn(
          WorkPullingProducerController(
            producerId = "workManager",
            workerServiceKey = ImageConverter.serviceKey,
            durableQueueBehavior = Some(durableQueue)),
          "producerController")
        //#durable-queue
        //#producer
        producerController ! WorkPullingProducerController.Start(requestNextAdapter)

        Behaviors.withStash(1000) { stashBuffer =>
          new ImageWorkManager(context, stashBuffer).waitForNext()
        }
      }
    }

  }

  class ImageWorkManager(
      context: ActorContext[ImageWorkManager.Command],
      stashBuffer: StashBuffer[ImageWorkManager.Command]) {

    import ImageWorkManager._

    def waitForNext(): Behavior[Command] = {
      Behaviors.receiveMessage {
        case WrappedRequestNext(next) =>
          stashBuffer.unstashAll(active(next))
        case c: Convert =>
          stashBuffer.stash(c)
          Behaviors.same
      }
    }

    def active(next: WorkPullingProducerController.RequestNext[ImageConverter.ConversionJob]): Behavior[Command] = {
      Behaviors.receiveMessage {
        case Convert(from, to, image) =>
          next.sendNextTo ! ImageConverter.ConversionJob(from, to, image)
          waitForNext()
        case _: WrappedRequestNext =>
          throw new IllegalStateException("Unexpected RequestNext")
      }
    }
    //#producer

    //#ask
    import WorkPullingProducerController.MessageWithConfirmation
    import akka.util.Timeout

    implicit val askTimeout: Timeout = 3.seconds

    def waitForNext2(): Behavior[Command] = {
      Behaviors.receiveMessage {
        case WrappedRequestNext(next) =>
          stashBuffer.unstashAll(active(next))
        case c: Convert =>
          stashBuffer.stash(c)
          Behaviors.same
        case AskReply(originalReplyTo, timeout) =>
          val response = if (timeout) ConvertTimedOut else ConvertAccepted
          originalReplyTo ! response
          Behaviors.same
      }
    }

    def active2(next: WorkPullingProducerController.RequestNext[ImageConverter.ConversionJob]): Behavior[Command] = {
      Behaviors.receiveMessage {
        case ConvertRequest(from, to, image, originalReplyTo) =>
          context.ask[MessageWithConfirmation[ImageConverter.ConversionJob], Done](
            next.askNextTo,
            askReplyTo => MessageWithConfirmation(ImageConverter.ConversionJob(from, to, image), askReplyTo)) {
            case Success(done) => AskReply(originalReplyTo, timeout = false)
            case Failure(_)    => AskReply(originalReplyTo, timeout = true)
          }
          waitForNext()
        case AskReply(originalReplyTo, timeout) =>
          val response = if (timeout) ConvertTimedOut else ConvertAccepted
          originalReplyTo ! response
          Behaviors.same
        case _: WrappedRequestNext =>
          throw new IllegalStateException("Unexpected RequestNext")
      }
    }
    //#ask
    //#producer
  }
  //#producer

}
