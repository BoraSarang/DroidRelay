package com.borasarang.droidrelay

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.borasarang.droidrelay.relay.ServerToggle

/** 퀵세팅 서버 토글 타일 (T-953, API 26+) */
@RequiresApi(Build.VERSION_CODES.N)
class ServerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        ServerToggle.toggle(this)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (ServerToggle.isRunning(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "DroidRelay"
        tile.updateTile()
    }
}
