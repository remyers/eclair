/*
 * Copyright 2023 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel.fsm

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.pubsub.Topic.Publish
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.Logs
import fr.acinq.eclair.channel.{ChannelException, ChannelSignatureReceived, Commitments}
import fr.acinq.eclair.wire.protocol._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/* This actor implements the quiescence protocol used to ensure that all pending channel changes have been propagated
 * to the remote peer so we can safely begin a change that relies on both parties having the same committed state. As
 * implemented here, the quiescence protocol is only used for splices.
 *
 * After each party confirms remote commitment signatures, they can safely send the `stfu` message to the other party.
 * After both parties have sent `stfu, the initiator should send `splice-init` to the non-initiator.
 *
 * In a race situation, the non-initiator may send `stfu` first, in which case they will become the initiator.
 * If both parties send `stfu` as the initiator simultaneously, the party that initiated the channel will send
 * `splice-init`.
 */

object QuiesceChannel {
  // @formatter:off
  sealed trait Command
  case class StfuReceived(stfu: Stfu) extends Command
  case object SpliceInitReceived extends Command
  private case class WrappedChannelSignatureReceived(csr: ChannelSignatureReceived) extends Command
  private case object Timeout extends Command

  sealed trait Response
  case class SendStfu(initiator: Boolean) extends Response
  case object SendSpliceInit extends Response
  sealed trait Failed extends Response { def cause: ChannelException }
  case class LocalFailure(cause: ChannelException) extends Failed
  case class RemoteFailure(cause: ChannelException) extends Failed
  // @formatter:on

  def apply(replyTo: ActorRef[Response], channelId: ByteVector32, initiator: Boolean, commitments: Commitments, timeout: FiniteDuration)(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup { context =>
      // commitments are finalized when there are no more remote changes pending on the channel
      val adapter = context.messageAdapter[ChannelSignatureReceived](WrappedChannelSignatureReceived)
      context.system.eventStream ! EventStream.Subscribe(adapter)
      Behaviors.withTimers { timers =>
        timers.startSingleTimer(Timeout, timeout)
        Behaviors.withMdc(Logs.mdc(channelId_opt = Some(channelId))) {
          val quiesceChannel = new QuiesceChannel(replyTo, channelId, commitments.params.localParams.isInitiator, context)
          // check if remote changes are already finalized
          context.self ! WrappedChannelSignatureReceived(ChannelSignatureReceived(replyTo.toClassic, commitments))
          quiesceChannel.waitForCommitmentsFinalized(initiator)
        }
      }
    }
}

private class QuiesceChannel(replyTo: ActorRef[QuiesceChannel.Response], channelId: ByteVector32, funder: Boolean, context: ActorContext[QuiesceChannel.Command]) {
  import QuiesceChannel._

  private val log = context.log

  def waitForCommitmentsFinalized(initiator: Boolean): Behavior[Command] = {
    Behaviors.receiveMessage {
      case WrappedChannelSignatureReceived(csr) =>
        if (csr.commitments.channelId == channelId && csr.commitments.changes.localChanges.all.isEmpty) {
          context.system.eventStream ! EventStream.Unsubscribe(context.messageAdapter[ChannelSignatureReceived](WrappedChannelSignatureReceived))
          replyTo ! SendStfu(initiator)
          if (initiator) {
            waitForRemoteStfu()
          } else {
            waitForSpliceInit()
          }
        } else {
          Behaviors.same
        }
      case StfuReceived(stfu) =>
        if (initiator) {
          if (stfu.initiator == 1) {
            // if we receive stfu from remote before we send it as an initiator, become the non-initiator
            waitForCommitmentsFinalized(initiator = false)
          } else {
            replyTo ! RemoteFailure(new ChannelException(channelId, "received non-initiator stfu from remote before we sent stfu as initiator"))
            Behaviors.stopped
          }
        } else {
          replyTo ! RemoteFailure(new ChannelException(channelId, "received stfu from remote initiator twice"))
          Behaviors.stopped
        }
      case SpliceInitReceived =>
        replyTo ! RemoteFailure(new ChannelException(channelId, "received splice-init from remote before quiescent"))
        Behaviors.stopped
      case Timeout =>
        replyTo ! LocalFailure(new ChannelException(channelId, "timed out waiting for local commitments to finalize"))
        Behaviors.stopped
    }
  }

  def waitForRemoteStfu(): Behavior[Command] = {
    Behaviors.receiveMessage {
      case StfuReceived(stfu) =>
        if (stfu.initiator == 0 || funder) {
          replyTo ! SendSpliceInit
          Behaviors.stopped
        } else {
          waitForSpliceInit()
        }
      case SpliceInitReceived =>
        replyTo ! RemoteFailure(new ChannelException(channelId, "received splice-init from remote before remote stfu"))
        Behaviors.stopped
      case Timeout =>
        replyTo ! RemoteFailure(new ChannelException(channelId, "timed out waiting for remote stfu"))
        Behaviors.stopped
      case WrappedChannelSignatureReceived(_) => Behaviors.same
    }
  }

  def waitForSpliceInit(): Behavior[Command] =
  Behaviors.receiveMessage {
    case SpliceInitReceived =>
      Behaviors.stopped
    case StfuReceived(_) =>
      replyTo ! RemoteFailure(new ChannelException(channelId, "received stfu from remote twice before splice-init"))
      Behaviors.stopped
    case Timeout =>
      replyTo ! RemoteFailure(new ChannelException(channelId, "timed out waiting for splice-init"))
      Behaviors.stopped
    case WrappedChannelSignatureReceived(_) => Behaviors.same
  }

}
