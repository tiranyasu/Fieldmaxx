package com.fieldmaxx.server

import com.fieldmaxx.sharepoint.FakeDestination
import com.fieldmaxx.sharepoint.sharePointDestinations
import com.fieldmaxx.server.delivery.DeliveryWorker
import com.fieldmaxx.server.jobs.startJobMaintenance
import com.fieldmaxx.server.sync.ChangeLog
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.time.Clock
import okhttp3.OkHttpClient

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true") // the importer renders PDF pages
    val config = loadConfig()
    java.nio.file.Files.createDirectories(config.dataDir)
    val db = Db("jdbc:sqlite:${config.dataDir.resolve("fieldmaxx.db").toAbsolutePath()}")
    val destinations = when (config.destination) {
        "sharepoint" -> sharePointDestinations(OkHttpClient())
        "fake" -> { val fake = FakeDestination(); { _: String -> fake } }
        else -> error("FMX_DESTINATION must be sharepoint or fake, was '${config.destination}'")
    }
    val ctx = Ctx(config, db, Clock.systemUTC(), destinations, NoopChangeRecorder, NoopDeliveryKick)
    ctx.changes = ChangeLog(db, ctx.clock, ctx.hub)
    if (runCli(args, ctx)) { db.close(); return }
    val delivery = DeliveryWorker(ctx)
    ctx.delivery = delivery
    startJobMaintenance(ctx)
    delivery.start()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { fieldmaxx(ctx) }.start(wait = true)
}
