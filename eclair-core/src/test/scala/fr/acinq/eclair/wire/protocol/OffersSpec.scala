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

package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.{MilliSatoshi, randomBytes32, randomKey}
import fr.acinq.eclair.wire.protocol.OfferCodecs.invoiceRequestTlvCodec
import fr.acinq.eclair.wire.protocol.Offers.{Amount, InvoiceRequestTlv, OfferId, PayerKey, Signature, rootHash}
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv.InvoiceRequest
import fr.acinq.secp256k1.Secp256k1JvmKt
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

class OffersSpec extends AnyFunSuite {

  test("test") {
    val payerKey = randomKey()
    val r = randomBytes32()
    val requestTlvs: Seq[InvoiceRequestTlv] = Seq(OfferId(randomBytes32()), Amount(MilliSatoshi(1000000000)), PayerKey(ByteVector32(payerKey.publicKey.value.drop(1))))
    val signature = ByteVector64(ByteVector(Secp256k1JvmKt.getSecpk256k1.signSchnorr(rootHash(TlvStream(requestTlvs), invoiceRequestTlvCodec).get.toArray, payerKey.value.toArray, r.toArray)))
    val request = InvoiceRequest(TlvStream(requestTlvs :+ Signature(signature)))

  }

}
