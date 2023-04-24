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

package fr.acinq.eclair.channel

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream.Publish
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.channel.fsm.QuiesceChannel
import fr.acinq.eclair.channel.fsm.QuiesceChannel.{LocalFailure, RemoteFailure}
import fr.acinq.eclair.wire.protocol.{Stfu, UpdateAddHtlc}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshiLong, TestConstants, randomBytes32}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class QuiesceChannelSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val pendingHtlc: UpdateAddHtlc = UpdateAddHtlc(randomBytes32(), id = 0, 100 msat, randomBytes32(), CltvExpiry(0), TestConstants.emptyOnionPacket, None)
  val finalizedRemote: Commitments = CommitmentsSpec.makeCommitments(25_000_000 msat, 75_000_000 msat)
  val localHasChanges: Commitments = finalizedRemote.copy(changes = finalizedRemote.changes.copy(localChanges = LocalChanges(List(pendingHtlc), Nil, Nil)))
  val localHasChangesNotFunder: Commitments = localHasChanges.copy(params = finalizedRemote.params.copy(localParams = finalizedRemote.params.localParams.copy(isInitiator = false)))
  val channelId: ByteVector32 = finalizedRemote.channelId

  override protected def withFixture(test: OneArgTest): Outcome = {
    val channel = TestProbe[QuiesceChannel.Response]()
    val commitments = if (test.tags.contains("finalized-remote")) {
      finalizedRemote
    } else {
      if (test.tags.contains("non-funder")) {
        localHasChangesNotFunder
      } else {
        localHasChanges
      }
    }
    val initiator = !test.tags.contains("non-initiator")
    val quiesce = testKit.spawn(QuiesceChannel(channel.ref, channelId, initiator, commitments, 200 millis))

    withFixture(test.toNoArgTest(FixtureParam(channel, quiesce)))
  }

  case class FixtureParam(channel: TestProbe[QuiesceChannel.Response], quiesce: ActorRef[QuiesceChannel.Command])

  test("initiator with finalized commitments sends splice-init after stfu from remote", Tag("finalized-remote")) { f =>
    import f._

    assert(channel.expectMessageType[QuiesceChannel.SendStfu] == QuiesceChannel.SendStfu(initiator = true))
    quiesce ! QuiesceChannel.StfuReceived(Stfu(channelId, 0))
    channel.expectMessage(QuiesceChannel.SendSpliceInit)
    TestProbe().expectTerminated(quiesce.ref, 100 milli)
  }

  test("initiator sends splice-init after finalized commitments and stfu from remote") { f =>
    import f._

    channel.expectNoMessage(100 milli)
    system.eventStream ! Publish(ChannelSignatureReceived(channel.ref.toClassic, finalizedRemote))
    assert(channel.expectMessageType[QuiesceChannel.SendStfu] == QuiesceChannel.SendStfu(initiator = true))
    quiesce ! QuiesceChannel.StfuReceived(Stfu(channelId, 0))
    channel.expectMessage(QuiesceChannel.SendSpliceInit)
    TestProbe().expectTerminated(quiesce.ref, 100 milli)
  }

  test("fail if stfu received by initiator while waiting for their commitments to finalize") { f =>
    import f._

    channel.expectNoMessage(100 milli)
    quiesce ! QuiesceChannel.StfuReceived(Stfu(channelId, 0))
    assert(channel.expectMessageType[RemoteFailure].cause.getMessage.contains("non-initiator stfu from remote before we sent stfu as initiator"))
    TestProbe().expectTerminated(quiesce.ref, 100 milli)
  }

  test("fail if second stfu received by non-initiator while waiting for their commitments to finalize", Tag("non-initiator")) { f =>
    import f._

    channel.expectNoMessage(100 milli)
    quiesce ! QuiesceChannel.StfuReceived(Stfu(channelId, 0))
    assert(channel.expectMessageType[RemoteFailure].cause.getMessage.contains("received stfu from remote initiator twice"))
    TestProbe().expectTerminated(quiesce.ref, 100 milli)
  }

  test("fail if initiator receives splice-init before quiescent") { f =>
    import f._

    channel.expectNoMessage(100 milli)
    quiesce ! QuiesceChannel.SpliceInitReceived
    assert(channel.expectMessageType[RemoteFailure].cause.getMessage.contains("received splice-init from remote before quiescent"))
    TestProbe().expectTerminated(quiesce.ref, 100 milli)
  }

  test("fail if initiator does not finalize channels before the timeout") { f =>
    import f._

    assert(channel.expectMessageType[LocalFailure].cause.getMessage.contains("timed out waiting for local commitments to finalize"))
    TestProbe().expectTerminated(quiesce.ref, 100 milli)
  }

  test("fail if non-initiator does not finalize channels before the timeout", Tag("non-initiator")) { f =>
    import f._

    assert(channel.expectMessageType[LocalFailure].cause.getMessage.contains("timed out waiting for local commitments to finalize"))
    TestProbe().expectTerminated(quiesce.ref, 100 milli)
  }

  test("initiator does not send splice-init if remote sends stfu(1) first") { f =>
    import f._

    channel.expectNoMessage(100 milli)
    quiesce ! QuiesceChannel.StfuReceived(Stfu(channelId, 1))
    system.eventStream ! Publish(ChannelSignatureReceived(channel.ref.toClassic, finalizedRemote))
    channel.expectMessage(QuiesceChannel.SendStfu(initiator = false))
    quiesce ! QuiesceChannel.SpliceInitReceived
    channel.expectNoMessage(100 milli)
    TestProbe().expectTerminated(quiesce.ref, 10 milli)
  }

  test("non-initiator receives stfu from initiator twice", Tag("non-initiator")) { f =>
    import f._

    channel.expectNoMessage(100 milli)
    system.eventStream ! Publish(ChannelSignatureReceived(channel.ref.toClassic, finalizedRemote))
    channel.expectMessage(QuiesceChannel.SendStfu(initiator = false))
    quiesce ! QuiesceChannel.StfuReceived(Stfu(channelId, 1))
    assert(channel.expectMessageType[RemoteFailure].cause.getMessage.contains("received stfu from remote twice before splice-init"))
    TestProbe().expectTerminated(quiesce.ref, 100 milli)
  }

  test("non-initiator times out waiting for splice-init", Tag("non-initiator")) { f =>
    import f._

    channel.expectNoMessage(100 milli)
    system.eventStream ! Publish(ChannelSignatureReceived(channel.ref.toClassic, finalizedRemote))
    channel.expectMessage(QuiesceChannel.SendStfu(initiator = false))
    assert(channel.expectMessageType[RemoteFailure].cause.getMessage.contains("timed out waiting for splice-init"))
    TestProbe().expectTerminated(quiesce.ref, 100 milli)
  }

  test("initiator receives splice-init waiting for remote stfu") { f =>
    import f._

    channel.expectNoMessage(100 milli)
    system.eventStream ! Publish(ChannelSignatureReceived(channel.ref.toClassic, finalizedRemote))
    channel.expectMessage(QuiesceChannel.SendStfu(initiator = true))
    quiesce ! QuiesceChannel.SpliceInitReceived
    assert(channel.expectMessageType[RemoteFailure].cause.getMessage.contains("received splice-init from remote before remote stfu"))
    TestProbe().expectTerminated(quiesce.ref, 100 milli)
  }

  test("initiator times out waiting for remote stfu") { f =>
    import f._

    channel.expectNoMessage(100 milli)
    system.eventStream ! Publish(ChannelSignatureReceived(channel.ref.toClassic, finalizedRemote))
    channel.expectMessage(QuiesceChannel.SendStfu(initiator = true))
    assert(channel.expectMessageType[RemoteFailure].cause.getMessage.contains("timed out waiting for remote stfu"))
    TestProbe().expectTerminated(quiesce.ref, 100 milli)
  }

}