package com.fengkuanglaidian.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 「延迟来电」期间驻留的前台服务。
 *
 * 为什么需要它：Android 10 起禁止后台应用直接启动 Activity，所以定时到点时
 * 只能弹一个通知，用户还得点一下（这就是"亮屏时只出现消息通知"的原因）。
 * 只要进程里有正在运行的前台服务，系统就允许该应用启动 Activity——
 * 于是到点后可以直接把全屏来电界面拉起来，和真实来电一致。
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (intent?.action == ACTION_LAUNCH) {
            launchIncomingCall()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager?.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "来电预备", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "延迟来电期间的静默常驻通知"
                    setShowBadge(false)
                }
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("等待延迟来电…")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 拉起全屏来电界面。
     *
     * 顺序很关键：**先发带 full-screen intent 的通知，再尝试直接启动 Activity**。
     *  - 锁屏/息屏：系统的 full-screen intent 会把来电页直接盖到锁屏上（最可靠的一条）
     *  - 亮屏解锁：后台启动 Activity 在 Android 10+ 受限，前台服务的豁免通常允许；
     *    即使被拦，通知也已经挂上了，用户点一下就能进界面
     *    —— 不会再像以前那样"屏幕亮着时什么都没发生"
     */
    private fun launchIncomingCall() {
        val settings = CallSettings(this)
        val caller = settings.callerName
        val number = settings.phoneNumber

        // 1. 无条件先发全屏通知
        CallNotifier.showIncoming(this, caller, number)

        // 2. 亮屏时再尝试直接弹全屏界面（省掉用户点击）
        if (!isScreenOn()) return
        try {
            startActivity(
                Intent(this, IncomingCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    putExtra(IncomingCallActivity.EXTRA_CALLER, caller)
                    putExtra(IncomingCallActivity.EXTRA_NUMBER, number)
                }
            )
        } catch (_: Exception) {
            // 被系统拦截也没关系，通知已经在通知栏里等待点击
        }
    }

    private fun isScreenOn(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            power.isInteractive
        } else {
            @Suppress("DEPRECATION")
            power.isScreenOn
        }
    }

    companion object {
        private const val CHANNEL_ID = "crazy_call_pending"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_LAUNCH = "com.fengkuanglaidian.app.action.LAUNCH_CALL"

        fun start(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // 后台启动前台服务被限制时忽略：退化成通知模式
            }
        }

        fun launchCall(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).setAction(ACTION_LAUNCH)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                CallNotifier.showIncoming(context, CallSettings(context).callerName)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CallForegroundService::class.java))
            } catch (_: Exception) {
                // ignore
            }
        }
    }
}
