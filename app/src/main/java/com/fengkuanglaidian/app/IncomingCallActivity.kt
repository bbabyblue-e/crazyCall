package com.fengkuanglaidian.app

import android.animation.ObjectAnimator
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 来电页。视觉对齐原生拨号器：大号号码 + 归属地运营商 + 模糊头像 + 底部接听/挂断。
 *
 * 锁屏弹出的三个条件（缺一不可）：
 *  1. 由带 full-screen intent 的通知拉起，或由前台服务直接 startActivity
 *  2. 本 Activity 声明 showWhenLocked / turnScreenOn
 *  3. 系统授予了 full-screen intent 权限（Android 14+）
 */
class IncomingCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALLER = "extra_caller"
        const val EXTRA_NUMBER = "extra_number"
        const val EXTRA_AUTO_ANSWER = "extra_auto_answer"
    }

    private lateinit var settings: CallSettings
    private var ringtone: Ringtone? = null
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = CallSettings(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguard?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_incoming_call)

        val caller = intent.getStringExtra(EXTRA_CALLER)?.takeIf { it.isNotBlank() }
            ?: settings.callerName
        val number = intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() }
            ?: settings.phoneNumber

        bindCallInfo(caller, number)
        applyTheme(settings.resolvedTheme())
        setupInteractions()

        // 返回键不来电：锁屏场景误触会直接"露馅"
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        startFeedback()

        if (intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)) {
            window.decorView.postDelayed({ answer() }, 1500)
        }
    }

    /** 号码与归属地信息，与原生拨号器排版一致。 */
    private fun bindCallInfo(caller: String, number: String) {
        val info = PhoneNumberResolver.resolve(this, number)
        findViewById<TextView>(R.id.nameLabel).apply {
            text = caller
            visibility = if (caller.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.numberLabel).text = info.display
        findViewById<TextView>(R.id.carrierLabel).text = info.subtitle
        findViewById<View>(R.id.smsReply).setOnClickListener {
            Toast.makeText(this, "短信回复（演示）", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.assistantIcon).setOnClickListener {
            Toast.makeText(this, "语音助手（演示）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyTheme(theme: CallUiTheme) {
        findViewById<TextView>(R.id.nameLabel).setTextColor(theme.primaryTextColor)
        findViewById<TextView>(R.id.numberLabel).setTextColor(theme.primaryTextColor)
        findViewById<TextView>(R.id.carrierLabel).setTextColor(theme.secondaryTextColor)

        // 圆形按钮现在是 FrameLayout（图标绝对居中）。
        // 注意：必须用 tint 而不是 setBackgroundColor——后者会把圆形 drawable 整个替换成纯色方块。
        findViewById<FrameLayout>(R.id.acceptButton).backgroundTintList =
            android.content.res.ColorStateList.valueOf(theme.acceptColor)
        findViewById<FrameLayout>(R.id.declineButton).backgroundTintList =
            android.content.res.ColorStateList.valueOf(theme.declineColor)

        val track = findViewById<FrameLayout>(R.id.slideTrack)
        val buttonRow = findViewById<View>(R.id.buttonRow)
        if (theme.slideToAnswer) {
            track.visibility = View.VISIBLE
            buttonRow.visibility = View.GONE
            track.background = pill(0x33FFFFFF)
            findViewById<TextView>(R.id.slideThumb).background = circle(theme.acceptColor)
            findViewById<TextView>(R.id.slideHint).text = theme.acceptLabel
        } else {
            track.visibility = View.GONE
            buttonRow.visibility = View.VISIBLE
            startFloatingButtons()
        }
    }

    /**
     * 底部两个按钮轻微上下浮动，错开相位。
     * 动画起止都是 0，结束后会自动回到原位，不影响点击。
     */
    private fun startFloatingButtons() {
        val decline = findViewById<View>(R.id.declineWrapper)
        val accept = findViewById<View>(R.id.acceptWrapper)
        decline.postDelayed({
            if (isFinishing) return@postDelayed
            decline.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.float_soft)
            )
        }, 260)
        accept.postDelayed({
            if (isFinishing) return@postDelayed
            accept.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.float_soft_alt)
            )
        }, 520)
    }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 200f
        setColor(color)
    }

    private fun setupInteractions() {
        findViewById<View>(R.id.acceptButton).setOnClickListener { answer() }
        findViewById<View>(R.id.declineButton).setOnClickListener { decline() }
        setupSwipe()
    }

    /** iOS 风格的滑动接听：拖到轨道末端即接听，松手未到位则回弹。 */
    private fun setupSwipe() {
        val track = findViewById<FrameLayout>(R.id.slideTrack)
        val thumb = findViewById<TextView>(R.id.slideThumb)
        var startX = 0f
        var maxTravel = 1f

        track.post {
            val lp = thumb.layoutParams as ViewGroup.MarginLayoutParams
            maxTravel = (track.width - thumb.width - lp.marginStart - lp.marginEnd)
                .coerceAtLeast(1).toFloat()
        }

        thumb.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = view.x - event.rawX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val target = (startX + event.rawX).coerceIn(0f, maxTravel)
                    view.x = target
                    view.alpha = 0.75f + 0.25f * (target / maxTravel)
                    if (target >= maxTravel - 2f) {
                        view.x = maxTravel
                        answer()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!answered) {
                        ObjectAnimator.ofFloat(view, "x", view.x, 0f)
                            .setDuration(180)
                            .apply { interpolator = LinearInterpolator(); start() }
                        view.alpha = 1f
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun answer() {
        if (answered) return
        answered = true
        stopFeedback()
        CallNotifier.cancelIncoming(this)
        CallForegroundService.stop(this)
        startActivity(
            Intent(this, InCallActivity::class.java)
                .putExtra(EXTRA_CALLER, findViewById<TextView>(R.id.nameLabel).text.toString())
                .putExtra(EXTRA_NUMBER, settings.phoneNumber)
        )
        finish()
    }

    private fun decline() {
        if (answered) return
        answered = true
        stopFeedback()
        CallNotifier.cancelIncoming(this)
        CallForegroundService.stop(this)
        finish()
    }

    private fun startFeedback() {
        CallNotifier.startFeedback(this, settings.vibrate)
        if (!settings.ring) return
        try {
            val uri = settings.ringtoneUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: return
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
        } catch (_: Exception) {
            // 没有可用铃声时静默降级
        }
    }

    private fun stopFeedback() {
        CallNotifier.stopFeedback()
        try {
            ringtone?.stop()
        } catch (_: Exception) {
            // ignore
        }
        ringtone = null
    }

    override fun onDestroy() {
        stopFeedback()
        super.onDestroy()
    }
}
