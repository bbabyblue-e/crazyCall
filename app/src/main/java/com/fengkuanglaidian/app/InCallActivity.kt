package com.fengkuanglaidian.app

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 通话中页。布局对齐原生拨号器：
 *  号码 / 归属地运营商 / 状态 / 模糊头像 / 两行功能键 / 底部免提-挂断-键盘
 *
 * 接通 [VOICE_DELAY_MS] 毫秒后播放「对方说话声」（语音包文件或系统语音合成）。
 */
class InCallActivity : AppCompatActivity() {

    private lateinit var settings: CallSettings
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var muted = false
    private var speakerOn = false

    private val ticker = object : Runnable {
        override fun run() {
            val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            findViewById<TextView>(R.id.timer).text = format(seconds)
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = CallSettings(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_in_call)

        val caller = intent.getStringExtra(IncomingCallActivity.EXTRA_CALLER)?.takeIf { it.isNotBlank() }
            ?: settings.callerName
        val number = intent.getStringExtra(IncomingCallActivity.EXTRA_NUMBER)?.takeIf { it.isNotBlank() }
            ?: settings.phoneNumber
        val info = PhoneNumberResolver.resolve(this, number)

        findViewById<TextView>(R.id.nameLabel).apply {
            text = caller
            visibility = if (caller.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.numberLabel).text = info.display
        findViewById<TextView>(R.id.carrierLabel).text = info.subtitle

        buildFunctionGrid()
        setupBottomControls()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        // 先「正在拨号」，短暂之后转「通话中」并开始计时——和真实拨号过程一致
        findViewById<TextView>(R.id.statusLabel).text = getString(R.string.status_dialing)
        handler.postDelayed({ connect() }, CONNECT_DELAY_MS)
        handler.postDelayed({
            if (!isFinishing) VoicePack.speak(this, settings.voiceText)
        }, VOICE_DELAY_MS)

        val auto = settings.autoHangupSeconds
        if (auto > 0) {
            handler.postDelayed({ hangUp() }, auto * 1000L)
        }
    }

    /** 「正在拨号」→「通话中」：状态行收起，计时行出现（与原生一致）。 */
    private fun connect() {
        if (isFinishing) return
        findViewById<TextView>(R.id.statusLabel).visibility = View.GONE
        findViewById<TextView>(R.id.timer).text = "00:00"
        startedAt = System.currentTimeMillis()
        handler.post(ticker)
    }

    /** 两行功能键，纯装饰但都可点（点了给个轻提示），为了"像真的"。 */
    private fun buildFunctionGrid() {
        val row1 = findViewById<LinearLayout>(R.id.functionRow1)
        val row2 = findViewById<LinearLayout>(R.id.functionRow2)

        addFunction(row1, R.drawable.ic_call_video, "视频模式") { toast("视频模式（演示）") }
        addFunction(row1, R.drawable.ic_call_record, "录音") { toast("录音（演示）") }
        addFunction(row1, R.drawable.ic_call_note, "笔记") { toast("笔记（演示）") }

        addFunction(row2, R.drawable.ic_call_mute, "静音") {
            muted = !muted
            toast(if (muted) "已静音" else "已取消静音")
        }
        addFunction(row2, R.drawable.ic_call_pause, "暂停通话") { toast("暂停通话（演示）") }
        addFunction(row2, R.drawable.ic_call_add, "添加通话") { toast("添加通话（演示）") }
    }

    private fun addFunction(
        row: LinearLayout,
        iconRes: Int,
        label: String,
        onClick: () -> Unit
    ) {
        val item = LayoutInflater.from(this).inflate(R.layout.item_call_function, row, false)
        item.findViewById<android.widget.ImageView>(R.id.functionIcon).setImageResource(iconRes)
        item.findViewById<TextView>(R.id.functionLabel).text = label
        item.findViewById<FrameLayout>(R.id.functionButton).setOnClickListener { onClick() }
        item.setOnClickListener { onClick() }
        row.addView(item)
    }

    private fun setupBottomControls() {
        // 免提：白底深色图标 ↔ 半透明底白色图标
        val speaker = findViewById<FrameLayout>(R.id.speakerButton)
        speaker.setOnClickListener {
            speakerOn = !speakerOn
            speaker.setBackgroundResource(
                if (speakerOn) R.drawable.bg_func_circle else R.drawable.bg_speaker_circle
            )
            speaker.findViewById<android.widget.ImageView>(R.id.speakerIcon)?.setImageResource(
                if (speakerOn) R.drawable.ic_call_speaker else R.drawable.ic_speaker_dark
            )
            toast(if (speakerOn) "已开启免提" else "已关闭免提")
        }

        findViewById<FrameLayout>(R.id.hangupButton).apply {
            // tint 而不是 setBackgroundColor：保住圆形
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF3B30.toInt())
            setOnClickListener { hangUp() }
        }

        findViewById<FrameLayout>(R.id.keypadButton).apply {
            setOnClickListener { toast("键盘（演示）") }
        }

        findViewById<View>(R.id.assistantIcon).setOnClickListener { toast("语音助手（演示）") }
    }

    private fun format(total: Int): String {
        val m = total / 60
        val s = total % 60
        return "%02d:%02d".format(m, s)
    }

    private fun hangUp() {
        handler.removeCallbacksAndMessages(null)
        VoicePack.stop()
        CallNotifier.cancelIncoming(this)
        CallForegroundService.stop(this)
        finish()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        VoicePack.stop()
        super.onDestroy()
    }

    companion object {
        private const val VOICE_DELAY_MS = 1600L
        private const val CONNECT_DELAY_MS = 1200L

        fun intent(context: Context, caller: String, number: String): Intent =
            Intent(context, InCallActivity::class.java)
                .putExtra(IncomingCallActivity.EXTRA_CALLER, caller)
                .putExtra(IncomingCallActivity.EXTRA_NUMBER, number)
    }
}
