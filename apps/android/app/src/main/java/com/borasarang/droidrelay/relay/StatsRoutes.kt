package com.borasarang.droidrelay.relay

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.json.JSONArray
import org.json.JSONObject

/** 트래픽 통계 API (v0.37 summary/daily + v0.39 P2 extended) */
internal fun Route.statsRoutes(context: Context, serverRef: RelayServer) {
    get("/api/stats/summary") {
        val s = TrafficLedger.summary()
        call.respondText(
            JSONObject().apply {
                put("today", bucketJson(s.today))
                put("month", bucketJson(s.month))
                put("total", bucketJson(s.total))
            }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/stats/daily") {
        val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 400) ?: 30
        val arr = JSONArray()
        TrafficLedger.daily(days).forEach { d ->
            arr.put(JSONObject().apply {
                put("date", d.date)
                put("down", d.downTotal())
                put("up", d.upTotal())
                put("downHttp", d.downHttp)
                put("downVideo", d.downVideo)
                put("downTorrent", d.downTorrent)
                put("upServe", d.upServe)
                put("upTorrent", d.upTorrent)
                put("maxDownBps", d.maxDownBps)
                put("maxUpBps", d.maxUpBps)
                put("done", d.doneTotal())
                put("doneHttp", d.doneHttp)
                put("doneVideo", d.doneVideo)
                put("doneTorrent", d.doneTorrent)
                put("failCount", d.failCount)
            })
        }
        call.respondText(
            JSONObject().apply { put("days", arr) }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/stats/extended") {
        val stor = StatsSnapshots.storage()
        val lastStor = stor.lastOrNull()
        call.respondText(
            JSONObject().apply {
                put("peers", JSONObject().apply {
                    put("seeds", StatsSnapshots.lastSeeds)
                    put("peers", StatsSnapshots.lastPeers)
                    put("peakSeeds", StatsSnapshots.peakSeeds)
                    put("peakPeers", StatsSnapshots.peakPeers)
                })
                put("uptime", JSONObject().apply {
                    put("bootCount", StatsSnapshots.bootCount)
                    put("firstBootAt", StatsSnapshots.firstBootAt)
                    put("lastBootAt", StatsSnapshots.lastBootAt)
                    put("appUptimeMs", System.currentTimeMillis() - RelayApp.startTime)
                })
                put("storage", JSONObject().apply {
                    put("dirSize", lastStor?.dirSize ?: 0L)
                    put("quotaMovedTotal", stor.sumOf { it.quotaMoved })
                    put("snapshots", stor.size)
                })
                put("net", JSONObject().apply {
                    put("lossCount", StatsSnapshots.netLossCount())
                })
                put("throttle", JSONObject().apply {
                    put("throttleCount", StatsSnapshots.throttleCount())
                })
            }.toString(),
            ContentType.Application.Json
        )
    }
}

private fun bucketJson(b: TrafficBucket): JSONObject = JSONObject().apply {
    put("down", b.downTotal())
    put("up", b.upTotal())
    put("downHttp", b.downHttp)
    put("downVideo", b.downVideo)
    put("downTorrent", b.downTorrent)
    put("upServe", b.upServe)
    put("upTorrent", b.upTorrent)
    put("maxDownBps", b.maxDownBps)
    put("maxUpBps", b.maxUpBps)
    put("done", b.doneTotal())
    put("doneHttp", b.doneHttp)
    put("doneVideo", b.doneVideo)
    put("doneTorrent", b.doneTorrent)
    put("failCount", b.failCount)
}
