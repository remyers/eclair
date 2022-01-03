package fr.acinq.eclair.io

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior, SupervisorStrategy, Terminated}
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

object RecoTask {

  // @formatter:off
  sealed trait Command
  private case object Die extends Command
  // @formatter:on

  def apply(): Behavior[Command] =
    Behaviors.setup { context =>
      context.log.info("connecting...")
      Behaviors.withTimers { timers =>
        timers.startSingleTimer(Die, 3 seconds)
        Behaviors.receiveMessage {
          case Die =>
            context.log.info("dying")
            throw new RuntimeException("oops")
        }
      }
    }

}

object Manager {

  // @formatter:off
  sealed trait Command
  case object Spawn extends Command
  // @formatter:on

  def apply(): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case Spawn =>
          context.log.info("spawning connection task")
          context.spawnAnonymous(Behaviors.supervise(RecoTask.apply())
            .onFailure[RuntimeException](SupervisorStrategy
              .restartWithBackoff(minBackoff = 1 second, maxBackoff = 30 seconds, randomFactor = 0.1)
              .withResetBackoffAfter(10 minutes)
              .withLoggingEnabled(false)
            )
          )
          Behaviors.same
      }
    }

}

object Guardian {
  def apply(): Behavior[NotUsed] =
    Behaviors.setup { context =>
      val manager = context.spawn(Manager(), "manager")
      context.watch(manager)
      manager ! Manager.Spawn

      Behaviors.receiveSignal {
        case (_, Terminated(_)) =>
          Behaviors.stopped
      }
    }
}

class NewRecoSpec extends AnyFunSuite {

  test("new reconnection task") {

    val system = ActorSystem(Guardian(), "system")
    Thread.sleep(Long.MaxValue)
  }

}
