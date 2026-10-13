package com.fengkuanglaidian.app

import android.content.Context
import android.content.SharedPreferences

/** 假来电的可配置项，全部落在 SharedPreferences 里。 */
class CallSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 来电显示的名字 */
    var callerName: String
        get() = prefs.getString(KEY_NAME, "妈妈") ?: "妈妈"
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    /** 来电显示的头像文字（取名字首字） */
    var avatarText: String
        get() = prefs.getString(KEY_AVATAR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AVATAR, value).apply()

    /** 来电显示的号码，用于解析归属地与运营商 */
    var phoneNumber: String
        get() = prefs.getString(KEY_PHONE, "19506161512") ?: "19506161512"
        set(value) = prefs.edit().putString(KEY_PHONE, value).apply()

    /** 接通后要「说」的那句话（没有语音包文件时用系统语音念出来） */
    var voiceText: String
        get() = prefs.getString(KEY_VOICE_TEXT, "喂，我到楼下了，你下来吧")
            ?: "喂，我到楼下了，你下来吧"
        set(value) = prefs.edit().putString(KEY_VOICE_TEXT, value).apply()

    /** 皮肤 id，见 [CallThemes] */
    var themeId: String
        get() = prefs.getString(KEY_THEME, CallThemes.ANDROID.id) ?: CallThemes.ANDROID.id
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /** 延迟触发秒数，0 表示立即 */
    var delaySeconds: Int
        get() = prefs.getInt(KEY_DELAY, 0)
        set(value) = prefs.edit().putInt(KEY_DELAY, value).apply()

    /** 是否震动 */
    var vibrate: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATE, value).apply()

    /** 是否响铃 */
    var ring: Boolean
        get() = prefs.getBoolean(KEY_RING, true)
        set(value) = prefs.edit().putBoolean(KEY_RING, value).apply()

    /** 接听后是否自动挂断（秒），0 表示不自动挂断 */
    var autoHangupSeconds: Int
        get() = prefs.getInt(KEY_AUTO_HANGUP, 0)
        set(value) = prefs.edit().putInt(KEY_AUTO_HANGUP, value).apply()

    /** 自定义铃声 Uri，null 表示用系统默认来电铃声 */
    var ringtoneUri: String?
        get() = prefs.getString(KEY_RINGTONE, null)
        set(value) = prefs.edit().putString(KEY_RINGTONE, value).apply()

    fun resolvedTheme(): CallUiTheme = CallThemes.byId(themeId)

    fun effectiveAvatar(): String {
        val explicit = avatarText.trim()
        if (explicit.isNotEmpty()) return explicit.take(1)
        return callerName.trim().take(1).ifEmpty { "?" }
    }

    companion object {
        private const val PREFS = "crazy_call_settings"
        private const val KEY_NAME = "caller_name"
        private const val KEY_AVATAR = "avatar_text"
        private const val KEY_THEME = "theme_id"
        private const val KEY_DELAY = "delay_seconds"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_RING = "ring"
        private const val KEY_AUTO_HANGUP = "auto_hangup_seconds"
        private const val KEY_RINGTONE = "ringtone_uri"
        private const val KEY_PHONE = "phone_number"
        private const val KEY_VOICE_TEXT = "voice_text"
    }
}
