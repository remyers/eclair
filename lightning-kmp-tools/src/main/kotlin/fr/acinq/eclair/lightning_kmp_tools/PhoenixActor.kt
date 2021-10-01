package fr.acinq.eclair.lightning_kmp_tools

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.javadsl.AbstractBehavior
import akka.actor.typed.javadsl.ActorContext
import akka.actor.typed.javadsl.Behaviors
import akka.actor.typed.javadsl.Receive
import com.typesafe.config.Config
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.*
import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.blockchain.electrum.ElectrumWatcher
import fr.acinq.lightning.blockchain.fee.FeerateTolerance
import fr.acinq.lightning.blockchain.fee.OnChainFeeConf
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.db.Databases
import fr.acinq.lightning.db.InMemoryChannelsDb
import fr.acinq.lightning.db.InMemoryPaymentsDb
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.ReceivePayment
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.payment.PaymentRequest
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.CoroutineContext

interface PhoenixRequest {}
interface PhoenixResponse{}
data class PingRequest(val data: String, val replyTo: ActorRef<PhoenixResponse>) : PhoenixRequest
data class PongResponse(val data: String) : PhoenixResponse
data class CreateInvoiceRequest(val amount: Long?, val description: String?, val replyTo: ActorRef<PhoenixResponse>) : PhoenixRequest
data class CreateInvoiceResponse(val invoice: String): PhoenixResponse


class PhoenixActor(nodeParams: NodeParams, walletParams: WalletParams, electrumServerAddress: ServerAddress, private val ctx: ActorContext<PhoenixRequest>): AbstractBehavior<PhoenixRequest>(ctx), CoroutineScope {
    private val job = Job()
    private val scope = CoroutineScope(this.context.executionContext.asCoroutineDispatcher() + job)
    override val coroutineContext: CoroutineContext = scope.coroutineContext

    val electrum = ElectrumClient(TcpSocket.Builder(), this).apply { connect(electrumServerAddress) }
    val watcher = ElectrumWatcher(electrum, this)
    val db = object : Databases {
        override val channels = InMemoryChannelsDb()
        override val payments = InMemoryPaymentsDb()
    }
    val peer = Peer(nodeParams, walletParams, watcher, db, TcpSocket.Builder(), this)

    init {
        this.scope.launch {
            peer.connect()
        }
    }

    companion object {
        @JvmStatic
        fun create(nodeParams: NodeParams, walletParams: WalletParams, electrumServerAddress: ServerAddress) : Behavior<PhoenixRequest> {
            return Behaviors.setup { ctx -> PhoenixActor(nodeParams, walletParams, electrumServerAddress, ctx)}
        }

        @JvmStatic
        fun create(datadir: File) : Behavior<PhoenixRequest> {
            return create(datadir, Node.loadConfiguration(datadir))
        }

        @JvmStatic
        fun create(datadir: File, config: Config) : Behavior<PhoenixRequest> {
            val seed = Node.getSeed(datadir)
            val chain = config.getString("phoenix.chain")
            val chainHash: ByteVector32 = when (chain) {
                "regtest" -> Block.RegtestGenesisBlock.hash
                "testnet" -> Block.TestnetGenesisBlock.hash
                else -> error("invalid chain $chain")
            }
            val chaindir = File(datadir, chain)
            chaindir.mkdirs()
            val nodeUri = config.getString("phoenix.trampoline-node-uri")
            val (nodeId, nodeAddress, nodePort) = Node.parseUri(nodeUri)
            val electrumServerAddress = Node.parseElectrumServerAddress(config.getString("phoenix.electrum-server"))
            val keyManager = LocalKeyManager(seed, chainHash)
            val trampolineFees = listOf(
                TrampolineFees(0.sat, 0, CltvExpiryDelta(576)),
                TrampolineFees(1.sat, 100, CltvExpiryDelta(576)),
                TrampolineFees(3.sat, 100, CltvExpiryDelta(576)),
                TrampolineFees(5.sat, 500, CltvExpiryDelta(576)),
                TrampolineFees(5.sat, 1000, CltvExpiryDelta(576)),
                TrampolineFees(5.sat, 1200, CltvExpiryDelta(576))
            )
            val walletParams = WalletParams(NodeUri(nodeId, nodeAddress, nodePort), trampolineFees, InvoiceDefaultRoutingFees(1_000.msat, 100, CltvExpiryDelta(144)))

            val nodeParams = NodeParams(
                keyManager = keyManager,
                alias = "phoenix",
                features = Features(
                    Feature.OptionDataLossProtect to FeatureSupport.Mandatory,
                    Feature.VariableLengthOnion to FeatureSupport.Mandatory,
                    Feature.PaymentSecret to FeatureSupport.Mandatory,
                    Feature.BasicMultiPartPayment to FeatureSupport.Optional,
                    Feature.Wumbo to FeatureSupport.Optional,
                    Feature.StaticRemoteKey to FeatureSupport.Optional,
                    Feature.AnchorOutputs to FeatureSupport.Optional,
                    Feature.TrampolinePayment to FeatureSupport.Optional,
                    Feature.ZeroReserveChannels to FeatureSupport.Optional,
                    Feature.ZeroConfChannels to FeatureSupport.Optional,
                    Feature.WakeUpNotificationClient to FeatureSupport.Optional,
                    Feature.PayToOpenClient to FeatureSupport.Optional,
                    Feature.TrustedSwapInClient to FeatureSupport.Optional,
                    Feature.ChannelBackupClient to FeatureSupport.Optional,
                ),
                dustLimit = 546.sat,
                maxRemoteDustLimit = 600.sat,
                onChainFeeConf = OnChainFeeConf(
                    closeOnOfflineMismatch = true,
                    updateFeeMinDiffRatio = 0.1,
                    // these values mean that we will basically accept whatever feerate our peer is using.
                    // there is not much else we can do and this is mitigated by the fact that :
                    // - we only use the anchor_outputs format
                    // - we never fund channels (we're always "fundee" in LN parlance)
                    feerateTolerance = FeerateTolerance(ratioLow = 0.05, ratioHigh = 10.0)
                ),
                maxHtlcValueInFlightMsat = 5000000000L,
                maxAcceptedHtlcs = 30,
                expiryDeltaBlocks = CltvExpiryDelta(144),
                fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(24),
                checkHtlcTimeoutAfterStartupDelaySeconds = 15,
                htlcMinimum = 1.msat,
                minDepthBlocks = 3,
                toRemoteDelayBlocks = CltvExpiryDelta(2016),
                maxToLocalDelayBlocks = CltvExpiryDelta(2016),
                feeBase = 1000.msat,
                feeProportionalMillionth = 100,
                reserveToFundingRatio = 0.01, // note: not used (overridden below)
                maxReserveToFundingRatio = 0.05,
                revocationTimeoutSeconds = 20,
                authTimeoutSeconds = 10,
                initTimeoutSeconds = 10,
                pingIntervalSeconds = 30,
                pingTimeoutSeconds = 10,
                pingDisconnect = true,
                autoReconnect = false,
                initialRandomReconnectDelaySeconds = 5,
                maxReconnectIntervalSeconds = 3600,
                chainHash = chainHash,
                channelFlags = 0,
                paymentRequestExpirySeconds = 3600,
                multiPartPaymentExpirySeconds = 60,
                minFundingSatoshis = 100000.sat,
                maxFundingSatoshis = 16777215.sat,
                maxPaymentAttempts = 5,
                enableTrampolinePayment = true
            )
            return Behaviors.setup { ctx -> PhoenixActor(nodeParams, walletParams, electrumServerAddress, ctx)}
        }
    }

    override fun createReceive(): Receive<PhoenixRequest> {
        return newReceiveBuilder()
            .onMessage(PingRequest::class.java) { request -> onPing(request) }
            .onMessage(CreateInvoiceRequest::class.java) { request -> onCreateInvoice(request)}
            .build()
    }

    private fun onPing(pingRequest: PingRequest) : Behavior<PhoenixRequest> {
        println(pingRequest)
        Thread.sleep(2000)
        pingRequest.replyTo.tell(PongResponse(pingRequest.data))
        return this
    }

    private fun onCreateInvoice(createInvoiceRequest: CreateInvoiceRequest) : Behavior<PhoenixRequest> {
        val paymentPreimage = Lightning.randomBytes32()
        val amount = MilliSatoshi(createInvoiceRequest.amount ?: 50000L)
        val result = CompletableDeferred<PaymentRequest>()
        launch {
            peer.send(ReceivePayment(paymentPreimage, amount, createInvoiceRequest.description.orEmpty(), null, result))
            createInvoiceRequest.replyTo.tell(CreateInvoiceResponse(result.await().write()))
        }
        return this
    }
}
