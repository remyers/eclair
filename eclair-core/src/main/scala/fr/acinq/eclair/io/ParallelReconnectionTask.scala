package fr.acinq.eclair.io

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Send
import akka.cluster.typed.Cluster
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.db.{NetworkDb, PeersDb}
import fr.acinq.eclair.io.Monitoring.Metrics
import fr.acinq.eclair.io.ParallelReconnectionTask._
import fr.acinq.eclair.io.ReconnectionTask.hostAndPort2InetSocketAddress

import java.net.InetSocketAddress
import scala.concurrent.duration.DurationInt

object ParallelReconnectionTask {

  // @formatter:off
  sealed trait Command
  case class WrappedPeerTransition(wrapped: Peer.Transition) extends Command
  case class WrappedPeerConnect(wrapped: Peer.Connect) extends Command
  // @formatter:on

  def apply(nodeParams: NodeParams, remoteNodeId: PublicKey): Behavior[Command] =
    Behaviors.setup { context =>
      new ParallelReconnectionTask(nodeParams, remoteNodeId, context).idle()
    }

  def getPeerAddressesFromDb(peersDb: PeersDb, networkDb: NetworkDb, remoteNodeId: PublicKey): Seq[InetSocketAddress] =
    (peersDb.getPeer(remoteNodeId).toSeq ++ networkDb.getNode(remoteNodeId).toSeq.flatMap(_.addresses)).map(_.socketAddress)

  def hostAndPort2InetSocketAddress(hostAndPort: HostAndPort): InetSocketAddress = new InetSocketAddress(hostAndPort.getHost, hostAndPort.getPort)

}

class ParallelReconnectionTask private(nodeParams: NodeParams,
                                       remoteNodeId: PublicKey,
                                       context: ActorContext[Command]) {

  def idle(): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedPeerTransition(Peer.Transition(_, nextPeerData: Peer.DisconnectedData)) =>
        if (nodeParams.autoReconnect && nextPeerData.channels.nonEmpty) { // we only reconnect if nodeParams explicitly instructs us to or there are existing channels
          context.log.info("reconnecting to node...")
          val addresses = getPeerAddressesFromDb(nodeParams.db.peers, nodeParams.db.network, remoteNodeId)
          val children = addresses.map { address =>
            context.spawnAnonymous(Behaviors.supervise(RecoTask.apply(remoteNodeId, address))
              .onFailure[RecoTask.ConnectionFailed](SupervisorStrategy
                .restartWithBackoff(minBackoff = 1 second, maxBackoff = 1 hour, randomFactor = 0.5)
                .withResetBackoffAfter(10 minutes)
                .withLoggingEnabled(false)
              )
            )
          }
          connecting(children)
        } else {
          context.log.info("auto reconnection is disabled")
          Behaviors.same
        }
      case WrappedPeerConnect(connect: Peer.Connect) => handlePeerConnect(connect)

    }

  def connecting(children: Seq[ActorRef[RecoTask.Command]]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedPeerTransition(Peer.Transition(_, _: Peer.ConnectedData)) =>
        context.log.info("connected, back to idle")
        children.foreach(context.stop)
        idle()

      case WrappedPeerConnect(connect: Peer.Connect) => handlePeerConnect(connect)
    }

  private def handlePeerConnect(connect: Peer.Connect): Behavior[Command] = {
    // manual connection requests happen completely independently of the automated reconnection process;
    // we initiate a connection but don't modify our state.
    // if we are already connecting/connected, the peer will kill any duplicate connections
    connect.address_opt
      .map(hostAndPort2InetSocketAddress)
      .orElse(getPeerAddressesFromDb(nodeParams.db.peers, nodeParams.db.network, remoteNodeId).headOption) match {
      case Some(address) =>
        context.spawnAnonymous(RecoTask.apply(remoteNodeId, address, replyTo_opt = Some(connect.replyTo)))
      case None =>
        connect.replyTo ! PeerConnection.ConnectionResult.NoAddressFound
    }
    Behaviors.same
  }
}

/**
 * This actor simply sends a connection request to the [[ClientSpawner.ConnectionRequest]] and crashes if the connection
 * attempt failed. This allows us to use a [[akka.actor.SupervisorStrategy]] to manage the reconnection logic.
 */
object RecoTask {

  // @formatter:off
  sealed trait Command
  private case class WrappedConnectionResult(wrapped: PeerConnection.ConnectionResult) extends Command
  // @formatter:on

  case class ConnectionFailed(failure: PeerConnection.ConnectionResult.Failure) extends RuntimeException(s"connection failure: $failure")

  def apply(remoteNodeId: PublicKey, address: InetSocketAddress, replyTo_opt: Option[akka.actor.ActorRef] = None): Behavior[Command] =
    Behaviors.setup { context =>
      context.log.info("connecting to address={}", address)
      val adapter = context.messageAdapter[PeerConnection.ConnectionResult](WrappedConnectionResult)
      val req = ClientSpawner.ConnectionRequest(address, remoteNodeId, adapter.toClassic, isPersistent = false)
      if (context.system.hasExtension(Cluster) && !req.address.getHostName.endsWith("onion")) {
        // activate the extension only on demand, so that tests pass
        lazy val mediator = DistributedPubSub(context.system).mediator
        mediator ! Send(path = "/user/client-spawner", msg = req, localAffinity = false)
      } else {
        context.system.eventStream ! EventStream.Publish(req)
      }
      Metrics.ReconnectionsAttempts.withoutTags().increment()
      Behaviors.receiveMessagePartial {
        case WrappedConnectionResult(success: PeerConnection.ConnectionResult.Success) =>
          replyTo_opt.foreach(_ ! success)
          Behaviors.stopped
        case WrappedConnectionResult(failure: PeerConnection.ConnectionResult.Failure) =>
          replyTo_opt.foreach(_ ! failure)
          throw ConnectionFailed(failure)
      }
    }

}