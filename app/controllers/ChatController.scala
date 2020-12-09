package controllers

import java.util.NoSuchElementException

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.Materializer
import javax.inject._
import play.api.libs.json.JsValue.jsValueToJsLookup
import play.api.libs.json.Json
import play.api.libs.streams.ActorFlow
import play.api.mvc._

import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt}
import mutable.{MutableList => List}


@Singleton
class ChatController @Inject()(cc: ControllerComponents)(implicit system: ActorSystem, mat: Materializer) extends AbstractController(cc) {
  def chat = Action {
    Ok(views.html.chat())
  }

  val messages = List[Message]()

  def socket = WebSocket.accept[String, String] { request =>
    println("New connection to the chat")
    ActorFlow.actorRef { out =>
      ChatActor.props(system, out, messages)
    }
  }
}

object ChatActor {
  def props(system:ActorSystem, out: ActorRef, messages: List[Message]) = Props(new ChatActor(system, out, messages))
}

case class Message(time: Long, value: String)

class ChatActor(system: ActorSystem, out: ActorRef, messages: List[Message]) extends Actor {

  final val Tick = "tick"
  var lastChecked: Long = System.currentTimeMillis()
  override def preStart(): Unit = {
    import system.dispatcher
    // Trigger this actor every second with no initial delay to check for new messages
    system.scheduler.scheduleAtFixedRate(Duration.Zero, 1.second, self, Tick)
  }

  def handle(msg: String): Unit = {
    println("Got message " + msg)
    val json = Json.parse(msg)
    val messageType = (json \ "type").as[String]
    if (messageType == "new_message" ) {
      messages += Message(System.currentTimeMillis(), msg)
    }
    else if (messageType == "connected") {
      // Send all previous messages
      for (m <- messages) {
        out ! m.value
      }
    }
  }

  def checkForMessages(): Unit = {

    var q = messages.reverse
    // If there are new messages
    try {
      if (q.head.time > lastChecked) {
        // Push message to listening WebSocket
        out ! q.head.value
        // Move to the next one
        q = q.tail
      }
    }
    catch {
      case _: NoSuchElementException =>
    }
    lastChecked = System.currentTimeMillis()
  }

  def receive = {
    case Tick =>
      checkForMessages()
    case msg: String =>
      handle(msg)
  }
}