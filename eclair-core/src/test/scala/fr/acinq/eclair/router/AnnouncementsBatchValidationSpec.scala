/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.router

import akka.actor.ActorSystem
import akka.pattern.pipe
import akka.testkit.TestProbe
import sttp.client3.okhttp.OkHttpFutureBackend
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{Block, Satoshi, SatoshiLong, Script, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.ValidateResult
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCAuthMethod.UserPassword
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinCoreClient}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate}
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, Features, MilliSatoshiLong, RealShortChannelId, ShortChannelId, randomKey}
import org.json4s.JsonAST.JString
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
 * Created by PM on 31/05/2016.
 */

class AnnouncementsBatchValidationSpec extends AnyFunSuite {

  import AnnouncementsBatchValidationSpec._

  ignore("validate a batch of announcements") {
    import scala.concurrent.ExecutionContext.Implicits.global

    implicit val system = ActorSystem("test")
    implicit val sttpBackend = OkHttpFutureBackend()

    val bitcoinClient = new BitcoinCoreClient(new BasicBitcoinJsonRPCClient(Block.RegtestGenesisBlock.hash, rpcAuthMethod = UserPassword("foo", "bar"), host = "localhost", port = 18332))

    val channels = for (i <- 0 until 50) yield {
      // let's generate a block every 10 txs so that we can compute short ids
      if (i % 10 == 0) generateBlocks(1, bitcoinClient)
      simulateChannel(bitcoinClient)
    }
    generateBlocks(6, bitcoinClient)
    val announcements = channels.map(c => makeChannelAnnouncement(c, bitcoinClient))

    val sender = TestProbe()

    bitcoinClient.validate(announcements(0)).pipeTo(sender.ref)
    sender.expectMsgType[ValidateResult].fundingTx.isRight

    bitcoinClient.validate(announcements(1).copy(shortChannelId = RealShortChannelId(Long.MaxValue))).pipeTo(sender.ref) // invalid block height
    sender.expectMsgType[ValidateResult].fundingTx.isRight

    bitcoinClient.validate(announcements(2).copy(shortChannelId = RealShortChannelId(BlockHeight(500), 1000, 0))).pipeTo(sender.ref) // invalid tx index
    sender.expectMsgType[ValidateResult].fundingTx.isRight
  }

}

object AnnouncementsBatchValidationSpec {

  case class SimulatedChannel(node1Key: PrivateKey, node2Key: PrivateKey, node1FundingKey: PrivateKey, node2FundingKey: PrivateKey, amount: Satoshi, fundingTx: Transaction, fundingOutputIndex: Int)

  def generateBlocks(numBlocks: Int, bitcoinClient: BitcoinCoreClient)(implicit ec: ExecutionContext): Unit = {
    val generatedF = for {
      JString(address) <- bitcoinClient.rpcClient.invoke("getnewaddress")
      _ <- bitcoinClient.rpcClient.invoke("generatetoaddress", numBlocks, address)
    } yield ()
    Await.result(generatedF, 10 seconds)
  }

  def simulateChannel(bitcoinClient: BitcoinCoreClient)(implicit ec: ExecutionContext): SimulatedChannel = {
    val node1Key = randomKey()
    val node2Key = randomKey()
    val node1BitcoinKey = randomKey()
    val node2BitcoinKey = randomKey()
    val amount = 1000000 sat
    // first we publish the funding tx
    val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(node1BitcoinKey.publicKey, node2BitcoinKey.publicKey)))
    val fundingTxFuture = bitcoinClient.makeFundingTx(fundingPubkeyScript, amount, FeeratePerKw(10000 sat))
    val res = Await.result(fundingTxFuture, 10 seconds)
    Await.result(bitcoinClient.publishTransaction(res.fundingTx), 10 seconds)
    SimulatedChannel(node1Key, node2Key, node1BitcoinKey, node2BitcoinKey, amount, res.fundingTx, res.fundingTxOutputIndex)
  }

  def makeChannelAnnouncement(c: SimulatedChannel, bitcoinClient: BitcoinCoreClient)(implicit ec: ExecutionContext): ChannelAnnouncement = {
    val (blockHeight, txIndex) = Await.result(bitcoinClient.getTransactionShortId(c.fundingTx.txid), 10 seconds)
    val shortChannelId = RealShortChannelId(blockHeight, txIndex, c.fundingOutputIndex)
    val witness = Announcements.generateChannelAnnouncementWitness(Block.RegtestGenesisBlock.hash, shortChannelId, c.node1Key.publicKey, c.node2Key.publicKey, c.node1FundingKey.publicKey, c.node2FundingKey.publicKey, Features.empty)
    val channelAnnNodeSig1 = Announcements.signChannelAnnouncement(witness, c.node1Key)
    val channelAnnBitcoinSig1 = Announcements.signChannelAnnouncement(witness, c.node1FundingKey)
    val channelAnnNodeSig2 = Announcements.signChannelAnnouncement(witness, c.node2Key)
    val channelAnnBitcoinSig2 = Announcements.signChannelAnnouncement(witness, c.node2FundingKey)
    val channelAnnouncement = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, shortChannelId, c.node1Key.publicKey, c.node2Key.publicKey, c.node1FundingKey.publicKey, c.node2FundingKey.publicKey, channelAnnNodeSig1, channelAnnNodeSig2, channelAnnBitcoinSig1, channelAnnBitcoinSig2)
    channelAnnouncement
  }

  def makeChannelUpdate(c: SimulatedChannel, shortChannelId: ShortChannelId): ChannelUpdate =
    Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, c.node1Key, c.node2Key.publicKey, shortChannelId, CltvExpiryDelta(10), 1000 msat, 10 msat, 100, 500000000 msat)

}