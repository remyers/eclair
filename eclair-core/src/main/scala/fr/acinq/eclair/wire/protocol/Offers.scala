/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, LexicographicalOrdering}
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.wire.protocol.OfferCodecs.offerTlvCodec
import fr.acinq.eclair.wire.protocol.TlvCodecs.genericTlv
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshi, TimestampSecond}
import scodec.Attempt.{Failure, Successful}
import scodec.bits.ByteVector
import scodec.codecs.vector
import scodec.{Codec, DecodeResult}

object Offers {

  sealed trait OfferTlv extends Tlv

  sealed trait InvoiceRequestTlv extends Tlv

  sealed trait InvoiceTlv extends Tlv

  sealed trait InvoiceErrorTlv extends Tlv

  case class Chains(chains: Seq[ByteVector32]) extends OfferTlv

  case class Currency(iso4217: String) extends OfferTlv

  case class Amount(amount: MilliSatoshi) extends OfferTlv with InvoiceRequestTlv with InvoiceTlv

  case class Description(description: String) extends OfferTlv with InvoiceTlv

  case class FeaturesTlv(features: Features) extends OfferTlv with InvoiceRequestTlv with InvoiceTlv

  case class AbsoluteExpiry(absoluteExpiry: Long) extends OfferTlv

  case class Paths(paths: Seq[BlindedRoute]) extends OfferTlv with InvoiceTlv

  case class Issuer(issuer: String) extends OfferTlv with InvoiceTlv

  case class QuantityMin(min: Long) extends OfferTlv

  case class QuantityMax(max: Long) extends OfferTlv

  case class NodeId(nodeId: PublicKey) extends OfferTlv with InvoiceTlv

  case class SendInvoice() extends OfferTlv with InvoiceTlv

  case class RefundFor(refundedPaymentHash: ByteVector32) extends OfferTlv with InvoiceTlv

  case class Signature(signature: ByteVector64) extends OfferTlv with InvoiceRequestTlv with InvoiceTlv

  case class Chain(hash: ByteVector32) extends InvoiceRequestTlv with InvoiceTlv

  case class OfferId(offerId: ByteVector32) extends InvoiceRequestTlv with InvoiceTlv

  case class Quantity(quantity: Long) extends InvoiceRequestTlv with InvoiceTlv

  case class PayerKey(key: ByteVector32) extends InvoiceRequestTlv with InvoiceTlv

  case class PayerNote(note: String) extends InvoiceRequestTlv with InvoiceTlv

  case class PayerInfo(info: ByteVector) extends InvoiceRequestTlv with InvoiceTlv

  case class ReplaceInvoice(paymentHash: ByteVector32) extends InvoiceRequestTlv with InvoiceTlv

  case class PayInfo(feeBase: MilliSatoshi, feeProportionalMillionths: Long, cltvExpiryDelta: CltvExpiryDelta, features: Features)

  case class BlindedPay(payInfos: Seq[PayInfo]) extends InvoiceTlv

  case class BlindedCapacities(capacities: Seq[MilliSatoshi]) extends InvoiceTlv

  case class CreatedAt(timestamp: TimestampSecond) extends InvoiceTlv

  case class PaymentHash(hash: ByteVector32) extends InvoiceTlv

  case class RelativeExpiry(seconds: Long) extends InvoiceTlv

  case class Cltv(minFinalCltvExpiry: CltvExpiryDelta) extends InvoiceTlv

  case class FallbackAddress(version: Byte, value: ByteVector)

  case class Fallbacks(addresses: Seq[FallbackAddress]) extends InvoiceTlv

  case class RefundSignature(signature: ByteVector64) extends InvoiceTlv

  case class ErroneousField(tag: Long) extends InvoiceErrorTlv

  case class SuggestedValue(value: ByteVector) extends InvoiceErrorTlv

  case class Error(message: String) extends InvoiceErrorTlv

  case class Offer(records: TlvStream[OfferTlv]) {
    val offerId: ByteVector32 = rootHash(records, offerTlvCodec).get

    val nodeId: PublicKey = records.get[NodeId].get.nodeId

    val contact: Either[OnionMessages.Recipient, BlindedRoute] = records.get[Paths].flatMap(_.paths.headOption).map(Right(_)).getOrElse(Left(OnionMessages.Recipient(nodeId, None, None)))
  }

  def rootHash[T <: Tlv](tlvs: TlvStream[T], codec: Codec[TlvStream[T]]): Option[ByteVector32] = {
    codec.encode(tlvs) match {
      case Failure(_) =>
        println("Can't encode")
        None
      case Successful(encoded) => vector(genericTlv).decode(encoded) match {
        case Failure(f) =>
          println(s"Can't decode ${encoded.toHex}: $f")
          None
        case Successful(DecodeResult(tlvVector, _)) =>

          def hash(tag: ByteVector, msg: ByteVector): ByteVector32 = {
            val tagHash = Crypto.sha256(tag)
            Crypto.sha256(tagHash ++ tagHash ++ msg)
          }

          def previousPowerOfTwo(n : Int) : Int = {
            var p = 1
            while (p < n) {
              p = p << 1
            }
            p >> 1
          }

          def merkleTree(i: Int, j: Int): ByteVector32 = {
            val (a, b) = if (j - i == 1) {
              val tlv = genericTlv.encode(tlvVector(i)).require.bytes
              (hash(ByteVector("LnLeaf".getBytes), tlv), hash(ByteVector("LnAll".getBytes) ++ encoded.bytes, tlv))
            } else {
              val k = i + previousPowerOfTwo(j - i)
              (merkleTree(i, k), merkleTree(k, j))
            }
            if (LexicographicalOrdering.isLessThan(a, b)) {
              hash(ByteVector("LnBranch".getBytes), a ++ b)
            } else {
              hash(ByteVector("LnBranch".getBytes), b ++ a)
            }
          }

          Some(merkleTree(0, tlvVector.length))
      }
    }
  }

}

