/*
 * Copyright 2025 ACINQ SAS
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

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{Satoshi, SatoshiLong}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.wire.protocol._
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime

/**
 * Created by remyers on 14/05/2025.
 */

class SplicesInteropSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  override def withFixture(test: OneArgTest): Outcome = {
    val tags = test.tags + ChannelStateTestsTags.DualFunding
    val setup = init(tags = tags)
    import setup._
    reachNormal(setup, tags)
    alice2bob.ignoreMsg { case _: ChannelUpdate => true }
    bob2alice.ignoreMsg { case _: ChannelUpdate => true }
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    withFixture(test.toNoArgTest(setup))
  }

  private def spliceIn(s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe, amount: Satoshi, exchangeCommitSigs: Boolean = true, exchangeTxSignatures: Boolean = true): Unit = {
    val sender = TestProbe()
    s ! CMD_SPLICE(sender.ref, Some(SpliceIn(amount)), None, None)
    exchangeStfu(s, r, s2r, r2s)
    s2r.expectMsgType[SpliceInit]
    s2r.forward(r)
    r2s.expectMsgType[SpliceAck]
    r2s.forward(s)
    s2r.expectMsgType[TxAddInput]
    s2r.forward(r)
    r2s.expectMsgType[TxComplete]
    r2s.forward(s)
    s2r.expectMsgType[TxAddInput]
    s2r.forward(r)
    r2s.expectMsgType[TxComplete]
    r2s.forward(s)
    s2r.expectMsgType[TxAddOutput]
    s2r.forward(r)
    r2s.expectMsgType[TxComplete]
    r2s.forward(s)
    s2r.expectMsgType[TxAddOutput]
    s2r.forward(r)
    r2s.expectMsgType[TxComplete]
    r2s.forward(s)
    s2r.expectMsgType[TxComplete]
    s2r.forward(r)
    if (exchangeCommitSigs) {
      s2r.expectMsgType[CommitSig]
      s2r.forward(r)
      r2s.expectMsgType[CommitSig]
      r2s.forward(s)
    }
    if (exchangeTxSignatures) {
      r2s.expectMsgType[TxSignatures]
      r2s.forward(s)
      s2r.expectMsgType[TxSignatures]
      s2r.forward(r)
    }
  }

  private def exchangeStfu(s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    s2r.expectMsgType[Stfu]
    s2r.forward(r)
    r2s.expectMsgType[Stfu]
    r2s.forward(s)
  }

  private def disconnect(f: FixtureParam): Unit = {
    import f._

    alice ! INPUT_DISCONNECTED
    bob ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
  }

  private def reconnect(f: FixtureParam, sendReestablish: Boolean = true): (ChannelReestablish, ChannelReestablish) = {
    import f._

    val aliceInit = Init(alice.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.params.localParams.initFeatures)
    val bobInit = Init(bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.params.localParams.initFeatures)
    alice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
    val channelReestablishAlice = alice2bob.expectMsgType[ChannelReestablish]
    if (sendReestablish) alice2bob.forward(bob)
    val channelReestablishBob = bob2alice.expectMsgType[ChannelReestablish]
    if (sendReestablish) bob2alice.forward(alice)
    (channelReestablishAlice, channelReestablishBob)
  }

  test("Disconnection with one side sending commit_sig") { f =>
    import f._
    // alice                    bob
    //   |         ...           |
    //   |    <interactive-tx>   |
    //   |<----- tx_complete ----|
    //   |------ tx_complete --X |
    //   |------ commit_sig ---X |
    //   |      <disconnect>     |
    //   |      <reconnect>      |
    //   | <channel_reestablish> |
    //   |<------ tx_abort ------|
    //   |------- tx_abort ----->|

    val sender = TestProbe()
    alice ! CMD_SPLICE(sender.ref, Some(SpliceIn(15_001 sat)), None, None)
    exchangeStfu(alice, bob, alice2bob, bob2alice)
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    //alice2bob.expectMsgType[TxComplete]
    //alice2bob.expectMsgType[CommitSig]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus.isInstanceOf[SpliceStatus.SpliceWaitingForSigs])

    // Bob doesn't receive Alice's tx_complete or commit_sig before disconnecting.
    disconnect(f)
    reconnect(f)

    // Bob will send tx_abort and Alice will acknowledge it.
    bob2alice.expectMsgType[TxAbort]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAbort]
    bob2alice.forward(bob)
  }

  test("Disconnection with both sides sending commit_sig") { f =>
    import f._
    // alice                    bob
    //   |         ...           |
    //   |    <interactive-tx>   |
    //   |<----- tx_complete ----|
    //   |------ tx_complete --->|
    //   |------ commit_sig ---X |
    //   |<------ commit_sig ----|
    //   |      <disconnect>     |
    //   |      <reconnect>      |
    //   | <channel_reestablish> |
    //   |------ commit_sig ---->|
    //   |<---- tx_signatures ---|
    //   |----- tx_signatures -->|

    spliceIn(alice, bob, alice2bob, bob2alice, 15_002 sat, exchangeCommitSigs = false, exchangeTxSignatures = false)
    //alice2bob.expectMsgType[CommitSig]
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus.isInstanceOf[SpliceStatus.SpliceWaitingForSigs])

    // Bob does not receive Alice's commit_sig before disconnecting.
    disconnect(f)
    reconnect(f)

    // Alice will send their commit_sig.
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    // Bob has the lowest input amount, so must transmit its tx_signatures first.
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    bob2alice.expectNoMessage(100 millis)
  }

  test("Disconnection with one side sending tx_signatures") { f =>
    import f._
    // alice                    bob
    //   |         ...           |
    //   |    <interactive-tx>   |
    //   |<----- tx_complete ----|
    //   |------ tx_complete --->|
    //   |------ commit_sig ---->|
    //   |<------ commit_sig ----|
    //   |X---- tx_signatures ---|
    //   |      <disconnect>     |
    //   |      <reconnect>      |
    //   | <channel_reestablish> |
    //   |<---- tx_signatures ---|
    //   |----- tx_signatures -->|

    spliceIn(alice, bob, alice2bob, bob2alice, 15_003 sat, exchangeTxSignatures = false)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    // alice2bob.expectMsgType[TxSignatures]
    bob2alice.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)

    // Alice will ignore Bob's tx_signatures before disconnecting.
    disconnect(f)
    reconnect(f)

    // Bob has the lowest input amount, so must transmit its tx_signatures first.
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    bob2alice.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)
  }

  test("Disconnection with both sides sending tx_signatures") { f =>
    import f._
    // alice                    bob
    //   |         ...           |
    //   |    <interactive-tx>   |
    //   |<----- tx_complete ----|
    //   |------ tx_complete --->|
    //   |------ commit_sig ---->|
    //   |<------ commit_sig ----|
    //   |<---- tx_signatures ---|
    //   |----- tx_signatures --X|
    //   |      <disconnect>     |
    //   |      <reconnect>      |
    //   | <channel_reestablish> |
    //   |----- tx_signatures -->|
    //   |--- update_add_htlc -->|
    //   |------ commit_sig ---->| batch_size = 2, funding_txid = FundingTx
    //   |------ commit_sig ---->| batch_size = 2, funding_txid = SpliceFundingTx
    //   |<--- revoke_and_ack ---|
    //   |<----- commit_sig -----|
    //   |<----- commit_sig -----|
    //   |---- revoke_and_ack -->|

    alice.underlyingActor.reconnectCount = 1 // splice_reestablish2.sh script expects this after one reconnect
    spliceIn(alice, bob, alice2bob, bob2alice, 15_004 sat, exchangeTxSignatures = false)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    // alice2bob.expectMsgType[TxSignatures]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)

    // Bob will not receive Alice's tx_signatures before disconnecting.
    disconnect(f)
    reconnect(f)

    // Alice must retransmit her tx_signatures, but not Bob.
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    bob2alice.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)
  }

  test("Disconnection with both sides sending tx_signatures and channel updates") { f =>
    import f._
    // alice                    bob
    //   |         ...           |
    //   |    <interactive-tx>   |
    //   |<----- tx_complete ----|
    //   |------ tx_complete --->|
    //   |------ commit_sig ---->|
    //   |<------ commit_sig ----|
    //   |<---- tx_signatures ---|
    //   |----- tx_signatures --X|
    //   |--- update_add_htlc --X|
    //   |------ commit_sig ----X| batch_size = 2, funding_txid = FundingTx
    //   |------ commit_sig ----X| batch_size = 2, funding_txid = SpliceFundingTx
    //   |      <disconnect>     |
    //   |      <reconnect>      |
    //   | <channel_reestablish> |
    //   |----- tx_signatures -->|
    //   |--- update_add_htlc -->|
    //   |------ commit_sig ---->| batch_size = 2, funding_txid = FundingTx
    //   |------ commit_sig ---->| batch_size = 2, funding_txid = SpliceFundingTx
    //   |<--- revoke_and_ack ---|
    //   |<----- commit_sig -----|
    //   |<----- commit_sig -----|
    //   |---- revoke_and_ack -->|

    spliceIn(alice, bob, alice2bob, bob2alice, 15_005 sat, exchangeTxSignatures = false)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    // alice2bob.expectMsgType[TxSignatures]

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    val (_, cmd) = makeCmdAdd(15005.sat.toMilliSatoshi, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight)
    alice ! cmd.copy(commit = true)
    // alice2bob.expectMsgType[UpdateAddHtlc]
    // alice2bob.expectMsgType[CommitSig]
    // alice2bob.expectMsgType[CommitSig]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // Bob will not receive Alice's tx_signatures, update_add_htlc or commit_sigs before disconnecting.
    disconnect(f)
    reconnect(f)

    // Alice must retransmit her tx_signatures, update_add_htlc and commit_sigs first.
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    bob2alice.expectNoMessage(100 millis)
  }

  test("Disconnection with concurrent splice_locked") { f =>
    import f._
    // alice                    bob
    //   |         ...           |
    //   |    <interactive-tx>   |
    //   |<----- tx_complete ----|
    //   |------ tx_complete --->|
    //   |------ commit_sig ---->|
    //   |<------ commit_sig ----|
    //   |<---- tx_signatures ---|
    //   |----- tx_signatures -->|
    //   |----- splice_locked --X|
    //   |      <disconnect>     |
    //   |      <reconnect>      |
    //   | <channel_reestablish> |
    //   |----- splice_locked -->|
    //   |X---- splice_locked ---|
    //   |      <disconnect>     |
    //   |      <reconnect>      |
    //   | <channel_reestablish> |
    //   |--- update_add_htlc -->|
    //   |------ commit_sig ---->|
    //   |<---- splice_locked ---|
    //   |<--- revoke_and_ack ---|
    //   |<----- commit_sig -----|
    //   |---- revoke_and_ack -->|

    spliceIn(alice, bob, alice2bob, bob2alice, 15_006 sat)

    // wait for splice negotiation to end
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    val spliceTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get

    // confirm the splice transaction for Alice
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, spliceTx)
    // alice2bob.expectMsgType[SpliceLocked]

    // Bob will not receive Alice's splice_locked before disconnecting.
    disconnect(f)
    reconnect(f)

    // Alice must retransmit her splice_locked.
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)

    // confirm the splice transaction for Bob
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, spliceTx)
    // bob2alice.expectMsgType[SpliceLocked]

    // Alice will not receive Bob's splice_locked before disconnecting.
    disconnect(f)
    reconnect(f)

    // Alice does not need to send her splice_locked, but sends update_add_htlc concurrently with Bob's splice_locked.

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    val (_, cmd) = makeCmdAdd(15006.sat.toMilliSatoshi, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight)
    alice ! cmd.copy(commit = true)
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    bob2alice.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)
  }

  test("Disconnection after exchanging tx_signatures and one side sends commit_sig for channel update") { f =>
    import f._
    // alice                    bob
    //   |         ...           |
    //   |    <interactive-tx>   |
    //   |<----- tx_complete ----|
    //   |------ tx_complete --->|
    //   |------ commit_sig ---->|
    //   |<------ commit_sig ----|
    //   |<---- tx_signatures ---|
    //   |----- tx_signatures -->|
    //   |--- update_add_htlc -->|
    //   |------ commit_sig ----X| batch_size = 2, funding_txid = FundingTx
    //   |------ commit_sig ----X| batch_size = 2, funding_txid = SpliceFundingTx
    //   |      <disconnect>     |
    //   |      <reconnect>      |
    //   | <channel_reestablish> |
    //   |--- update_add_htlc -->|
    //   |------ commit_sig ---->| batch_size = 2, funding_txid = FundingTx
    //   |------ commit_sig ---->| batch_size = 2, funding_txid = SpliceFundingTx
    //   |<--- revoke_and_ack ---|
    //   |<----- commit_sig -----|
    //   |<----- commit_sig -----|
    //   |---- revoke_and_ack -->|

    spliceIn(alice, bob, alice2bob, bob2alice, 15_007 sat)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    val (_, cmd) = makeCmdAdd(15007.sat.toMilliSatoshi, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight)
    alice ! cmd.copy(commit = true)
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    // alice2bob.expectMsgType[CommitSig]
    // alice2bob.expectMsgType[CommitSig]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // Bob will not receive Alice's commit_sigs before disconnecting.
    disconnect(f)
    reconnect(f)

    // Alice must retransmit update_add_htlc and commit_sigs first.
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    bob2alice.expectNoMessage(100 millis)
  }

  test("Disconnection after exchanging tx_signatures and both sides send commit_sig for channel update") { f =>
    import f._
    // alice                    bob
    //   |         ...           |
    //   |    <interactive-tx>   |
    //   |<----- tx_complete ----|
    //   |------ tx_complete --->|
    //   |------ commit_sig ---->|
    //   |<------ commit_sig ----|
    //   |<---- tx_signatures ---|
    //   |----- tx_signatures -->|
    //   |--- update_add_htlc -->|
    //   |------ commit_sig ---->| batch_size = 2, funding_txid = FundingTx
    //   |------ commit_sig ---->| batch_size = 2, funding_txid = SpliceFundingTx
    //   |<--- revoke_and_ack ---|
    //   |X----- commit_sig -----|
    //   |X----- commit_sig -----|
    //   |      <disconnect>     |
    //   |      <reconnect>      | next_commitment_number = 1 (alice), 2 (bob)
    //   | <channel_reestablish> | next_revocation_number = 1 (alice) and 0 (bob)
    //   |<----- commit_sig -----| batch_size = 2, funding_txid = FundingTx
    //   |<----- commit_sig -----| batch_size = 2, funding_txid = SpliceFundingTx
    //   |---- revoke_and_ack -->|

    spliceIn(alice, bob, alice2bob, bob2alice, 15_008 sat)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    val (_, cmd) = makeCmdAdd(100000.sat.toMilliSatoshi, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight)
    alice ! cmd.copy(commit = true)
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // Alice will not receive Bob's commit_sigs before disconnecting.
    disconnect(f)
    val (aliceReestablish, bobReestablish) = reconnect(f)
    assert(aliceReestablish.nextLocalCommitmentNumber == 1)
    assert(aliceReestablish.nextRemoteRevocationNumber == 1)
    assert(bobReestablish.nextLocalCommitmentNumber == 2)
    assert(bobReestablish.nextRemoteRevocationNumber == 0)

    // Bob must retransmit his commit_sigs first.
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
  }

  test("Disconnection after exchanging tx_signatures and both sides send commit_sig for channel update; revoke_and_ack not received") { f =>
    import f._
    // alice                    bob
    //   |         ...           |
    //   |    <interactive-tx>   |
    //   |<----- tx_complete ----|
    //   |------ tx_complete --->|
    //   |------ commit_sig ---->|
    //   |<------ commit_sig ----|
    //   |<---- tx_signatures ---|
    //   |----- tx_signatures -->|
    //   |--- update_add_htlc -->|
    //   |------ commit_sig ---->| batch_size = 2, funding_txid = FundingTx
    //   |------ commit_sig ---->| batch_size = 2, funding_txid = SpliceFundingTx
    //   |X--- revoke_and_ack ---|
    //   |X----- commit_sig -----|
    //   |X----- commit_sig -----|
    //   |      <disconnect>     |
    //   |      <reconnect>      |
    //   | <channel_reestablish> | next_revocation_number = 0 (for both alice and bob)
    //   |<--- revoke_and_ack ---|
    //   |<----- commit_sig -----| batch_size = 2, funding_txid = FundingTx
    //   |<----- commit_sig -----| batch_size = 2, funding_txid = SpliceFundingTx
    //   |---- revoke_and_ack -->|

    spliceIn(alice, bob, alice2bob, bob2alice, 15_009 sat)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    val (_, cmd) = makeCmdAdd(100000.sat.toMilliSatoshi, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight)
    alice ! cmd.copy(commit = true)
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice) // ignored
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice) // ignored
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice) // ignored
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // Alice will not receive Bob's commit_sigs before disconnecting.
    disconnect(f)
    val (aliceReestablish, bobReestablish) = reconnect(f)
    assert(aliceReestablish.nextRemoteRevocationNumber == 0)
    assert(bobReestablish.nextRemoteRevocationNumber == 0)

    // Bob must retransmit his commit_sigs first.
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
  }

}
