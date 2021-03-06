package sample

import akka.actor.Actor.actorOf
import akka.actor.Actor
import akka.actor.ActorRef
import akka.dispatch.Dispatchers
import akka.routing.CyclicIterator
import akka.routing.Routing
import akka.actor.PoisonPill
import akka.config.Supervision
import akka.actor.ReceiveTimeout

object Backend {

  case class TranslationRequest(text: String)
  case class TranslationResponse(text: String, words: Int)

  val backendDispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher("backend-dispatcher")
    .setCorePoolSize(8)
    .build

  val translationService = loadBalanced(10, actorOf[TranslationService])

  private def loadBalanced(poolSize: Int, actor: ⇒ ActorRef): ActorRef = {
    val workers = Vector.fill(poolSize)(actor.start())
    Routing.loadBalancerActor(CyclicIterator(workers)).start()
  }

  class TranslationService extends Actor {
    self.dispatcher = backendDispatcher

    val translator = actorOf[Translator].start()
    val counter = actorOf[Counter].start()

    def receive = {
      case TranslationRequest(text) ⇒
        for (replyTo ← self.sender) {
          val aggregator = actorOf(new Aggregator(replyTo)).start()
          translator.tell(text, aggregator)
          counter.tell(text, aggregator)
        }
    }
  }

  class Aggregator(replyTo: ActorRef) extends Actor {
    self.dispatcher = backendDispatcher
    self.lifeCycle = Supervision.Temporary
    self.receiveTimeout = Some(1000)

    var textResult: Option[String] = None
    var lengthResult: Option[Int] = None

    def receive = {
      case text: String ⇒
        textResult = Some(text)
        replyWhenDone()
      case length: Int ⇒
        lengthResult = Some(length)
        replyWhenDone()
      case ReceiveTimeout ⇒
        self.stop()
    }

    def replyWhenDone() {
      for (text ← textResult; length ← lengthResult) {
        replyTo.tell(TranslationResponse(text, length))
        self.stop()
      }

    }
  }

  class Translator extends Actor {
    self.dispatcher = backendDispatcher

    def receive = {
      case x: String ⇒
        // simulate some work
        Thread.sleep(100)
        val result = x.toUpperCase
        self.reply(result)
    }
  }

  class Counter extends Actor {
    self.dispatcher = backendDispatcher

    def receive = {
      case x: String ⇒
        // simulate some work
        Thread.sleep(100)
        val result = x.split(" ").length
        self.reply(result)
    }
  }

}