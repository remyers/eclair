/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.payment

import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.wire.protocol.OfferCodecs.invoiceTlvCodec
import fr.acinq.eclair.wire.protocol.Offers._
import fr.acinq.eclair.wire.protocol.TlvStream
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshi, TimestampSecond}
import scodec.bits.ByteVector


case class Bolt12Invoice(records: TlvStream[InvoiceTlv]) extends PaymentRequest {

  import Bolt12Invoice._

  val amount: MilliSatoshi = records.get[Amount].map(_.amount).get

  override val amount_opt: Option[MilliSatoshi] = Some(amount)

  override val nodeId: Crypto.PublicKey = records.get[NodeId].get.nodeId

  override val paymentHash: ByteVector32 = records.get[PaymentHash].get.hash

  override val paymentSecret: Option[ByteVector32] = None

  override val description: Either[String, ByteVector32] = records.get[Description].map(_.description).map(Left(_)).getOrElse(Right(paymentHash))

  override val routingInfo: Seq[Seq[ExtraHop]] = Seq.empty

  override val timestamp: TimestampSecond = records.get[CreatedAt].get.timestamp

  override val relativeExpiry: Long = records.get[RelativeExpiry].map(_.seconds).getOrElse(DEFAULT_EXPIRY_SECONDS)

  override val minFinalCltvExpiryDelta: Option[CltvExpiryDelta] = records.get[Cltv].map(_.minFinalCltvExpiry)

  override val features: Features = records.get[FeaturesTlv].map(_.features).getOrElse(Features.empty)

  override def write: String =
    invoiceTlvCodec.encode(records).require.toHex
}

object Bolt12Invoice {
  val DEFAULT_EXPIRY_SECONDS: Long = 7200

  def read(input: String): Bolt12Invoice = {
    Bolt12Invoice(invoiceTlvCodec.decode(ByteVector.fromHex(input).get.bits).require.value)
  }
}
