package com.fengkuanglaidian.app

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.os.Build

/**
 * 下拉快捷开关：点一下立刻来电，不用先打开 App。
 * 需要在「编辑快捷开关」里把它拖到控制中心。
 */
class QuickCallTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = "点一下立刻来电"
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()

        // 点磁贴时系统会把本应用短暂带到前台，此时启动 Activity 不会被拦
        try {
            startActivityAndCollapse(
                android.content.Intent(this, IncomingCallActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(
                        IncomingCallActivity.EXTRA_CALLER,
                        CallSettings(this@QuickCallTileService).callerName
                    )
                }
            )
        } catch (_: Exception) {
            // 老版本没有 startActivityAndCollapse(Intent)，退回通知
            CallNotifier.showIncoming(this, CallSettings(this).callerName)
        }

        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    @Suppress("unused")
    private fun tileIcon(): Icon? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Icon.createWithResource(this, R.drawable.ic_call_tile)
        } else {
            null
        }
}
