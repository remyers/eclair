package fr.acinq.eclair.integration

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.{actorRefAdapter, ClassicActorSystemOps}
import akka.testkit.TestProbe
import com.google.common.io.Files
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.lightning_kmp_tools.{CreateInvoiceRequest, PhoenixActor}

import scala.jdk.CollectionConverters.MapHasAsJava
import scala.concurrent.duration._

class LightningKmpIntegrationSpec extends IntegrationSpec {

    test("start electrumx") {
        val config = ConfigFactory.parseMap(
            Map(
                "phoenix.chain" -> "regtest",
                "phoenix.electrum-server" -> "localhost:51001:tcp",
                "phoenix.trampoline-node-uri" -> "039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585@localhost:48001"
            ).asJava
        )
        val phoenix = system.spawn(PhoenixActor.create(Files.createTempDir(), config), "phoenix")

        val probe = TestProbe()
        phoenix ! new CreateInvoiceRequest(10000, "test", probe.ref)
        val foo = probe.receiveOne(5 seconds)
        println(foo)
    }

    def startElectrumX(): Unit = {
        import sys.process._
        s"docker run --detach --network host --name electrumx -e DAEMON_URL=http://foo:bar@localhost:$bitcoindRpcPort -e NET=regtest -e COIN=BitcoinSegwit -e SERVICES=tcp://:51001,rpc://:8100 -p 51001:51001 lukechilds/electrumx:v1.15.0"!!
    }

    def stopElectrumX(): Unit = {
        import sys.process._
        "docker rm -f electrumx"!!
    }

    def checkElectrumX() = {
        import sys.process._
        "docker exec electrumx electrumx_rpc -p 8100 getinfo"!!
    }

    override def beforeAll(): Unit = {
        super.beforeAll()

        startElectrumX()
        checkElectrumX()
    }

    override def afterAll(): Unit = {
        stopElectrumX()
        super.afterAll()
    }

}
