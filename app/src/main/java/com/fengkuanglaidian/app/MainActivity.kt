package com.fengkuanglaidian.app

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** 设置页：配置来电内容，自检锁屏权限，并提供立即/延迟触发。 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: CallSettings

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast("没给通知权限，锁屏不会弹出")
        checkLockscreenPermissions()
    }

    /** 系统铃声选择器。 */
    private val ringtonePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.getParcelableExtra<android.net.Uri>(
            android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI
        ) ?: return@registerForActivityResult
        settings.ringtoneUri = uri.toString()
        refreshRingtoneLabel()
        toast("铃声已保存")
    }

    /** 录音权限。 */
    private val recordPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showRecordDialog() else toast("没有录音权限，无法录制语音包")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = CallSettings(this)
        setContentView(R.layout.activity_main)
        CallNotifier.ensureChannel(this)
        VoicePack.voiceDir(this).mkdirs()

        val inputName = findViewById<EditText>(R.id.inputName)
        val inputPhone = findViewById<EditText>(R.id.inputPhone)
        val labelPhoneInfo = findViewById<TextView>(R.id.labelPhoneInfo)
        val spinnerTheme = findViewById<Spinner>(R.id.spinnerTheme)
        val seekDelay = findViewById<SeekBar>(R.id.seekDelay)
        val labelDelay = findViewById<TextView>(R.id.labelDelay)
        val switchVibrate = findViewById<Switch>(R.id.switchVibrate)
        val switchRing = findViewById<Switch>(R.id.switchRing)
        val seekAutoHangup = findViewById<SeekBar>(R.id.seekAutoHangup)
        val labelAutoHangup = findViewById<TextView>(R.id.labelAutoHangup)
        val inputVoiceText = findViewById<EditText>(R.id.inputVoiceText)
        val labelVoiceStatus = findViewById<TextView>(R.id.labelVoiceStatus)

        spinnerTheme.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            CallThemes.ALL.map { it.displayName }
        )

        // 回填
        inputName.setText(settings.callerName)
        inputPhone.setText(settings.phoneNumber)
        inputVoiceText.setText(settings.voiceText)
        spinnerTheme.setSelection(CallThemes.ALL.indexOfFirst { it.id == settings.themeId }.coerceAtLeast(0))
        seekDelay.progress = settings.delaySeconds
        labelDelay.text = "延迟触发（秒）：${settings.delaySeconds}"
        seekAutoHangup.progress = settings.autoHangupSeconds
        labelAutoHangup.text = "接听后自动挂断（秒）：${settings.autoHangupSeconds}"

        // 号码解析实时预览
        fun updatePhonePreview(text: String) {
            val info = PhoneNumberResolver.resolve(this, text)
            labelPhoneInfo.text = buildString {
                append("显示为 ${info.display}\n")
                append("归属地：${info.region ?: "未识别"}    运营商：${info.carrier ?: "未识别"}")
            }
        }
        updatePhonePreview(settings.phoneNumber)
        inputPhone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updatePhonePreview(s?.toString().orEmpty())
        })

        // 号码库在后台线程加载，加载完再刷一次预览（首帧可能还没就绪）
        Thread {
            PhoneNumberResolver.preload(applicationContext)
            runOnUiThread {
                if (!isFinishing) updatePhonePreview(inputPhone.text.toString())
            }
        }.start()

        seekDelay.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                labelDelay.text = "延迟触发（秒）：$progress"
            }
        })
        seekAutoHangup.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                labelAutoHangup.text = "接听后自动挂断（秒）：$progress"
            }
        })

        fun persist() {
            settings.callerName = inputName.text.toString().trim().ifEmpty { "妈妈" }
            settings.phoneNumber = inputPhone.text.toString().filter { it.isDigit() || it == '+' }
                .ifEmpty { "19506161512" }
            settings.voiceText = inputVoiceText.text.toString().trim()
            settings.themeId = CallThemes.ALL[spinnerTheme.selectedItemPosition].id
            settings.delaySeconds = seekDelay.progress
            settings.vibrate = switchVibrate.isChecked
            settings.ring = switchRing.isChecked
            settings.autoHangupSeconds = seekAutoHangup.progress
        }

        refreshRingtoneLabel()
        refreshVoiceStatus()

        findViewById<Button>(R.id.buttonRingtone).setOnClickListener {
            persist()
            pickRingtone()
        }

        findViewById<Button>(R.id.buttonVoiceRecord).setOnClickListener {
            persist()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                showRecordDialog()
            } else {
                recordPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        findViewById<Button>(R.id.buttonVoiceManage).setOnClickListener {
            persist()
            showVoiceManageDialog()
        }

        findViewById<Button>(R.id.buttonVoiceDir).setOnClickListener {
            persist()
            val dir = VoicePack.voiceDir(this)
            dir.mkdirs()
            val path = dir.absolutePath
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("voice dir", path))
            AlertDialog.Builder(this)
                .setTitle("语音包目录")
                .setMessage(
                    "$path\n\n" +
                        "路径已复制到剪贴板。把 mp3 / m4a / wav 放进去，接通后会自动播放第一条（约 10 秒）。\n\n" +
                        "如果文件管理器进不去这个目录，可以用数据线：\n" +
                        "adb push 你的录音.mp3 $path/ \n\n" +
                        "当前状态：" + VoicePack.describe(this)
                )
                .setPositiveButton("知道了") { _, _ -> labelVoiceStatus.text = VoicePack.describe(this) }
                .show()
        }

        findViewById<Button>(R.id.buttonTriggerNow).setOnClickListener {
            persist()
            triggerCallNow()
        }

        findViewById<Button>(R.id.buttonTriggerDelayed).setOnClickListener {
            persist()
            val seconds = settings.delaySeconds
            if (seconds <= 0) {
                toast("延迟设为 0，已改为立即来电")
                triggerCallNow()
            } else {
                // 先把前台服务拉起来，到点时才能直接弹全屏界面（而不是只给一条通知）
                CallForegroundService.start(this)
                scheduleAlarm(seconds)
                toast("$seconds 秒后来电，把屏幕锁上试试")
            }
        }

        findViewById<Button>(R.id.buttonPermission).setOnClickListener {
            persist()
            preflight()
        }
    }

    // ---------------- 触发 ----------------

    /**
     * 立即来电。
     * 与延迟触发保持一致：**先发全屏通知**（锁屏时系统会直接盖到锁屏上），
     * 亮屏时再直接启动全屏界面。
     */
    private fun triggerCallNow() {
        CallNotifier.showIncoming(this, settings.callerName, settings.phoneNumber)
        if (!isScreenOn()) return
        try {
            startActivity(
                Intent(this, IncomingCallActivity::class.java)
                    .putExtra(IncomingCallActivity.EXTRA_CALLER, settings.callerName)
                    .putExtra(IncomingCallActivity.EXTRA_NUMBER, settings.phoneNumber)
            )
        } catch (_: Exception) {
            // 通知已发出，用户可以从通知栏进入
        }
    }

    private fun isScreenOn(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return power.isInteractive
    }

    // ---------------- 铃声 ----------------

    private fun pickRingtone() {
        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                android.media.RingtoneManager.TYPE_RINGTONE
            )
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "选择来电铃声")
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                settings.ringtoneUri?.let { android.net.Uri.parse(it) }
            )
        }
        try {
            ringtonePicker.launch(intent)
        } catch (_: Exception) {
            toast("没找到系统铃声选择器")
        }
    }

    private fun refreshRingtoneLabel() {
        val label = findViewById<TextView>(R.id.labelRingtone)
        val uri = settings.ringtoneUri
        val name = if (uri == null) {
            "系统默认"
        } else {
            runCatching {
                android.media.RingtoneManager.getRingtone(this, android.net.Uri.parse(uri))
                    ?.getTitle(this)
            }.getOrNull() ?: "已自定义"
        }
        label.text = getString(R.string.label_ringtone) + "：" + name
    }

    // ---------------- 语音包 ----------------

    /** 刷新语音包状态文字与「立即来电」按钮文案。 */
    private fun refreshVoiceStatus() {
        val label = findViewById<TextView>(R.id.labelVoiceStatus)
        label.text = VoicePack.describe(this)
        val record = findViewById<Button>(R.id.buttonVoiceRecord)
        val recording = VoicePack.isRecording()
        record.text = if (recording) "停止并保存录音" else getString(R.string.action_voice_record)
    }

    /** 录制语音包：开始 / 停止 / 试听。 */
    private fun showRecordDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_record_voice, null)
        val timer = view.findViewById<TextView>(R.id.recordTimer)
        val toggle = view.findViewById<Button>(R.id.recordToggle)
        val play = view.findViewById<Button>(R.id.recordPlay)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var seconds = 0
        var recorded: java.io.File? = null

        val ticker = object : Runnable {
            override fun run() {
                seconds++
                timer.text = "已录制 %d 秒".format(seconds)
                if (seconds >= 20) {
                    toggle.performClick()
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }

        fun stopEverything() {
            handler.removeCallbacks(ticker)
            VoicePack.stop()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.record_title)
            .setView(view)
            .setPositiveButton("完成") { _, _ ->
                stopEverything()
                VoicePack.stopRecording()
                refreshVoiceStatus()
            }
            .setOnDismissListener {
                stopEverything()
                VoicePack.stopRecording()
                refreshVoiceStatus()
            }
            .create()

        toggle.setOnClickListener {
            if (VoicePack.isRecording()) {
                recorded = VoicePack.stopRecording()
                handler.removeCallbacks(ticker)
                toggle.text = "开始录制"
                play.isEnabled = recorded != null
                timer.text = if (recorded != null) {
                    "已保存 ${recorded!!.name}（%d 秒）".format(seconds)
                } else {
                    "录得太短，没保存"
                }
                if (recorded != null) VoicePack.setSelectedVoiceFile(this, recorded)
            } else {
                val file = VoicePack.startRecording(this)
                if (file == null) {
                    toast("录音启动失败，检查麦克风权限")
                    return@setOnClickListener
                }
                seconds = 0
                timer.text = "已录制 0 秒"
                toggle.text = "停止并保存"
                play.isEnabled = false
                handler.postDelayed(ticker, 1000)
            }
        }

        play.setOnClickListener {
            val file = recorded ?: VoicePack.selectedVoiceFile(this)
            if (file == null) {
                toast("还没有可试听的录音")
            } else {
                VoicePack.preview(this, file)
                toast("试听中…")
            }
        }

        dialog.show()
    }

    /** 管理已有语音包：选用 / 试听 / 删除。 */
    private fun showVoiceManageDialog() {
        val files = VoicePack.listVoiceFiles(this)
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("还没有语音包")
                .setMessage(
                    "先点「录制我的语音包」录一段 5–15 秒的语音。\n\n" +
                        "也可以用数据线把现成的 mp3 推进来：\n" +
                        "adb push 你的录音.mp3 " + VoicePack.voiceDir(this).absolutePath + "/"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val current = VoicePack.selectedVoiceFile(this)
        val names = files.map { file ->
            val mark = if (file.absolutePath == current?.absolutePath) "✓ " else "　"
            "$mark${file.name}   (${file.length() / 1024} KB)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择要播放的语音包")
            .setItems(names) { _, which ->
                val file = files[which]
                AlertDialog.Builder(this)
                    .setTitle(file.name)
                    .setItems(arrayOf("设为当前使用", "试听", "删除")) { _, action ->
                        when (action) {
                            0 -> {
                                VoicePack.setSelectedVoiceFile(this, file)
                                refreshVoiceStatus()
                                toast("已设为当前语音包")
                            }
                            1 -> {
                                VoicePack.preview(this, file)
                                toast("试听中…")
                            }
                            else -> {
                                if (VoicePack.deleteVoiceFile(this, file)) {
                                    refreshVoiceStatus()
                                    toast("已删除")
                                } else {
                                    toast("删除失败")
                                }
                            }
                        }
                    }
                    .show()
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    // ---------------- 权限自检 ----------------

    private fun preflight() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkLockscreenPermissions()
        }
    }

    private fun checkLockscreenPermissions() {
        val problems = mutableListOf<String>()

        if (!CallNotifier.notificationsEnabled(this)) {
            problems += "• 通知总开关被关掉了，锁屏上不会出现任何东西"
        }
        if (!CallNotifier.canUseFullScreenIntent(this)) {
            problems += "• 没有「全屏通知」权限（Android 14+ 需要手动放行）"
        }

        if (problems.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("权限检查通过")
                .setMessage(
                    "应用侧该有的权限都有了。\n\n" +
                        "如果锁屏仍然不弹出，基本就是 ROM 的后台限制，需要在系统设置里再放行两项：\n" +
                        "1. 自启动 / 后台启动\n" +
                        "2. 电池 → 后台高耗电（或省电策略改为「无限制」）\n\n" +
                        "国产 ROM（小米澎湃、vivo OriginOS、OPPO ColorOS）通常还需要在最近任务里下拉锁定本应用。"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("还缺这些权限")
            .setMessage(problems.joinToString("\n") + "\n\n按提示逐项放行后再试。")
            .setPositiveButton("去设置") { _, _ -> openMissingSettings() }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun openMissingSettings() {
        val intents = mutableListOf<Intent>()
        CallNotifier.fullScreenIntentSettingsIntent(this)?.let { intents += it }
        CallNotifier.notificationSettingsIntent(this)?.let { intents += it }
        intents += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
        }

        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // 试下一个
            }
        }
        toast("没找到可跳转的设置页，请手动到「设置 → 应用 → 疯狂来电」里放行")
    }

    // ---------------- 定时 ----------------

    private fun scheduleAlarm(seconds: Int) {
        val manager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, CallAlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            this,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + seconds * 1000L
        val canExact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
        try {
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (_: SecurityException) {
            manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** SeekBar 的空实现。 */
    private abstract class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }
}
