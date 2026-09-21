package com.borasarang.droidrelay.relay

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.json.JSONArray
import org.json.JSONObject

/** 트래픽 통계 API (v0.37) — 일별·이번달·누적 up/down + breakdown */
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
            })
        }
        call.respondText(
            JSONObject().apply { put("days", arr) }.toString(),
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
}
