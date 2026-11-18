package app.mjolnir.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val db = System.getenv("MJOLNIR_DB") ?: "./mjolnir.db"
    val deps = RelayDeps(store = Store("jdbc:sqlite:$db"))
    embeddedServer(Netty, port = port, host = "0.0.0.0") { relayModule(deps) }.start(wait = true)
}
