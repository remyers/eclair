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

package fr.acinq.eclair.payment.send

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.message.OnionMessages.Recipient
import fr.acinq.eclair.message.Postman.{OnionMessageResponse, SendMessage}
import fr.acinq.eclair.message.{OnionMessages, Postman}
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.PreimageReceived
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.payment.{Bolt12Invoice, PaymentFailed, PaymentSent}
import fr.acinq.eclair.wire.protocol.OfferCodecs.{invoiceRequestTlvCodec, offerTlvCodec}
import fr.acinq.eclair.wire.protocol.Offers.{Amount, InvoiceRequestTlv, Offer, OfferId, PayerKey, Signature, rootHash}
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv.{Invoice, InvoiceRequest, ReplyPath}
import fr.acinq.eclair.wire.protocol.{OnionMessage, TlvStream}
import fr.acinq.eclair.{MilliSatoshi, NodeParams, randomBytes32, randomKey}
import fr.acinq.secp256k1.Secp256k1JvmKt
import scodec.bits.ByteVector

import java.util.UUID

object OfferPayment {
  sealed trait Result
  case class Success(invoice: Bolt12Invoice, paymentPreimage: ByteVector32) extends Result
  case class FailedPayment(paymentFailed: PaymentFailed) extends Result

  sealed trait Command
  case class PayOffer(replyTo: typed.ActorRef[Result]) extends Command
  case class WrappedMessageResponse(response: OnionMessageResponse) extends Command
  case class WrappedPaymentId(paymentId: UUID) extends Command
  case class WrappedPreimageReceived(preimageReceived: PreimageReceived) extends Command
  case class WrappedPaymentSent(paymentSent: PaymentSent) extends Command
  case class WrappedPaymentFailed(paymentFailed: PaymentFailed) extends Command

  def paymentResultWrapper(x: Any) : Command = {
    x match {
      case paymentId: UUID => WrappedPaymentId(paymentId)
      case preimageReceived: PreimageReceived => WrappedPreimageReceived(preimageReceived)
      case paymentSent: PaymentSent => WrappedPaymentSent(paymentSent)
      case paymentFailed: PaymentFailed => WrappedPaymentFailed(paymentFailed)
    }
  }

  def buildRequest(nodeParams: NodeParams, request: InvoiceRequest, destination: Either[OnionMessages.Recipient, BlindedRoute]):(ByteVector32, PublicKey, OnionMessage) = {
    val pathId = randomBytes32()
    val replyPath = ReplyPath(OnionMessages.buildRoute(randomKey(), Seq.empty, Left(Recipient(nodeParams.nodeId, Some(pathId.bytes)))))
    val (nextNodeId, message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(), destination, Seq(replyPath, request))
    (pathId, nextNodeId, message)
  }

  def apply(nodeParams: NodeParams,
            postman: typed.ActorRef[Postman.Command],
            paymentInitiator: ActorRef,
            offer: Offer,
            amount: MilliSatoshi): Behavior[Command] = {
    Behaviors.receivePartial {
      case (context, PayOffer(replyTo)) =>
        val payerKey = randomKey()
        val requestTlvs: Seq[InvoiceRequestTlv] = Seq(OfferId(offer.offerId), Amount(amount), PayerKey(ByteVector32(payerKey.publicKey.value.drop(1))))
        val signature = ByteVector64(ByteVector(Secp256k1JvmKt.getSecpk256k1.signSchnorr(rootHash(TlvStream(requestTlvs), invoiceRequestTlvCodec).get.toArray, payerKey.value.toArray, randomBytes32().toArray)))
        val request = InvoiceRequest(TlvStream(requestTlvs :+ Signature(signature)))
        val (pathId, nextNodeId, message) = buildRequest(nodeParams, request, offer.contact)
        postman ! SendMessage(nextNodeId, message, Some(pathId), context.messageAdapter(WrappedMessageResponse), nodeParams.onionMessageConfig.timeout)
        waitForInvoice(nodeParams, postman, paymentInitiator, offer, request, replyTo)
    }
  }

  def waitForInvoice(nodeParams: NodeParams,
                     postman: typed.ActorRef[Postman.Command],
                     paymentInitiator: ActorRef,
                     offer: Offer,
                     request: InvoiceRequest,
                     replyTo: typed.ActorRef[Result]): Behavior[Command] = {
    Behaviors.receivePartial {
      case (context, WrappedMessageResponse(Postman.Response(payload))) if payload.invoice.nonEmpty =>
        val invoice = payload.invoice.get.invoice
        // TODO: check invoice
        val recipientAmount = invoice.amount
        paymentInitiator ! SendPaymentToNode(context.messageAdapter(paymentResultWrapper).toClassic, recipientAmount, invoice, maxAttempts = ???, fallbackFinalExpiryDelta = ???, externalId = ???, assistedRoutes = ???, routeParams = ???, userCustomTlvs = ???, blockUntilComplete = ???)
        waitForPayment(offer, invoice, replyTo)
      // TODO: case message doesn't have invoice
      case (context, WrappedMessageResponse(_)) =>
        // We didn't get an invoice, let's retry.
        val (pathId, nextNodeId, message) = buildRequest(nodeParams, request, offer.contact)
        postman ! SendMessage(nextNodeId, message, Some(pathId), context.messageAdapter(WrappedMessageResponse), nodeParams.onionMessageConfig.timeout)
        waitForInvoice(nodeParams, postman, paymentInitiator, offer, request, replyTo)
    }
  }

  def waitForPayment(offer: Offer, invoice: Bolt12Invoice, replyTo: typed.ActorRef[Result]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedPaymentId(_) =>
        Behaviors.same
      case WrappedPreimageReceived(preimageReceived) =>
        replyTo ! Success(invoice, preimageReceived.paymentPreimage)
        Behaviors.stopped
      case WrappedPaymentSent(paymentSent) =>
        replyTo ! Success(invoice, paymentSent.paymentPreimage)
        Behaviors.stopped
      case WrappedPaymentFailed(failed) =>
        replyTo ! FailedPayment(failed)
        Behaviors.stopped
    }
  }
}


