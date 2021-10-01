package fr.acinq.lightning_kmp_tools

import akka.actor.testkit.typed.javadsl.TestKitJunitResource
import akka.actor.testkit.typed.javadsl.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.lightning_kmp_tools.*
import fr.acinq.lightning.*
import fr.acinq.lightning.blockchain.fee.FeerateTolerance
import fr.acinq.lightning.blockchain.fee.OnChainFeeConf
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import org.junit.ClassRule
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoenixActorSpec {

    @Test
    fun `reply to ping`() {
        val datadir = File(System.getProperty("phoenix.datadir", System.getProperty("user.home") + "/.phoenix"))
        datadir.mkdirs()
        val config = ConfigFactory.parseMap(mapOf(
            "phoenix.chain" to "regtest",
            "phoenix.electrum-server" to "localhost:51001:tcp",
            "phoenix.trampoline-node-uri" to "039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585@localhost:48001"
        ))

        var probe: TestProbe<PhoenixResponse> = testKit.createTestProbe(PhoenixResponse::class.java)
        var phoenix = testKit.spawn(PhoenixActor.create(datadir, config))

        phoenix.tell(PingRequest("test", probe.ref))
        var response = probe.expectMessageClass(PongResponse::class.java, Duration.ofSeconds(10))
        assertEquals("test", response.data)

        phoenix.tell(CreateInvoiceRequest(10000, "this is a test", probe.ref))
        val response1 = probe.expectMessageClass(CreateInvoiceResponse::class.java, Duration.ofSeconds(10))
        println(response1)
    }

    companion object {
        @get:ClassRule
        @JvmStatic
        val testKit = TestKitJunitResource()
    }
}
