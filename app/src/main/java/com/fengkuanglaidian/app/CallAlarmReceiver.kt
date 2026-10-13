package com.fengkuanglaidian.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 定时触发假来电。
 *
 * 不直接发通知，而是让前台服务去拉起界面——前台服务的豁免能绕过
 * Android 10+ 的后台启动 Activity 限制，这样亮屏时也能直接弹出全屏通话界面。
 */
class CallAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        CallForegroundService.launchCall(context.applicationContext)
    }
}
