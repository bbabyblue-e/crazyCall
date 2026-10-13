package com.fengkuanglaidian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 假来电的通知 / 铃声 / 震动。
 *
 * 锁屏能不能弹出，取决三件事：
 *  1. 通知是否带 full-screen intent
 *  2. 是否拿到 USE_FULL_SCREEN_INTENT 权限（Android 14+ 需手动放行）
 *  3. 系统是否允许锁屏显示通知（国产 ROM 多为独立开关）
 */
object CallNotifier {

    const val CHANNEL_ID = "crazy_call_incoming"
    private const val NOTIFICATION_ID = 1001

    /** 创建通知渠道。反复调用无副作用。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "假来电",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "用于显示假来电的锁屏全屏界面"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 发出带 full-screen intent 的来电通知。
     * 锁屏时系统会把 [IncomingCallActivity] 直接拉到锁屏之上。
     *
     * 注意：通知的 contentIntent 也指向来电页，这样即使系统只显示了通知（亮屏、
     * 未授予全屏通知权限等情形），用户点一下就直接进通话界面，而不是再展开一层。
     */
    fun showIncoming(context: Context, callerName: String, phoneNumber: String? = null) {
        ensureChannel(context)

        val number = phoneNumber ?: CallSettings(context).phoneNumber
        val contentText = PhoneNumberResolver.resolve(number).subtitle

        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(IncomingCallActivity.EXTRA_CALLER, callerName)
            putExtra(IncomingCallActivity.EXTRA_NUMBER, number)
        }
        val fullScreenPending = PendingIntent.getActivity(
            context,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(callerName)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // 通知权限被拒时静默失败，界面本身仍可前台弹出
        }
    }

    fun cancelIncoming(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (_: SecurityException) {
            // ignore
        }
    }

    /** Android 14+ 是否还能使用 full-screen intent；低版本一律 true。 */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }

    /** 跳转到本应用的「全屏通知」系统设置页，让用户手动放行。 */
    fun fullScreenIntentSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 34) return null
        return Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /** 通知总开关是否打开（Android 13+ 用户可能整体关掉）。 */
    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 系统设置里跳转到本应用的通知设置。 */
    fun notificationSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    // ---------------- 铃声与震动 ----------------

    private var vibrator: Vibrator? = null
    private const val RING_PATTERN_OFF = 2000L
    private const val RING_PATTERN_ON = 1000L

    fun startFeedback(context: Context, vibrate: Boolean) {
        if (!vibrate) return
        val v = obtainVibrator(context) ?: return
        if (!v.hasVibrator()) return
        // 模仿系统来电节奏：响 1 秒、停 2 秒
        val pattern = longArrayOf(0, RING_PATTERN_ON, RING_PATTERN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, 0)
        }
    }

    fun stopFeedback() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun obtainVibrator(context: Context): Vibrator? {
        vibrator?.let { return it }
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator = v
        return v
    }

    /** 默认来电铃声的 Uri（留给后续自定义铃声用）。 */
    fun defaultRingtoneUri(): Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    /** 让铃声走「铃声」通道而不是媒体通道。 */
    fun ringtoneAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}
