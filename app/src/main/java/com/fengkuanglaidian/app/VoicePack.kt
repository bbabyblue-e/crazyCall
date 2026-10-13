package com.fengkuanglaidian.app

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/**
 * 接通后的「说话声」。
 *
 * 优先播放**你自己录的语音包**（真人的声音才是这个功能的重点）：
 *  1. 设置页录一段 5–15 秒的语音 → 存到 `Android/data/<包名>/files/voice/` 下的 m4a
 *  2. 接通后自动播放你选中的那条（没选就播第一条）
 *
 * 兜底：没有录音文件时，用系统离线 TTS 念一句中文（音色取决于手机语音引擎）。
 * 两者都没有就安静，不放人工噪音。
 */
object VoicePack {

    private const val START_OFFSET_MS = 300
    private const val TTS_UTTERANCE_ID = "crazy_call_voice"
    private const val PREF_SELECTED = "voice_selected_file"
    private const val PREF_NAME = "crazy_call_settings"

    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var pendingText: String? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null

    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus")

    /** 语音包目录（应用外部私有目录）。 */
    fun voiceDir(context: Context): File =
        File(context.getExternalFilesDir(null), "voice").apply { mkdirs() }

    /** 目录里所有可用音频，按名称排序。 */
    fun listVoiceFiles(context: Context): List<File> {
        val dir = voiceDir(context)
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXT }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /** 当前选中的语音包；没选或文件没了就退回第一条；都没有返回 null。 */
    fun selectedVoiceFile(context: Context): File? {
        val files = listVoiceFiles(context)
        if (files.isEmpty()) return null
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_SELECTED, null)
        if (name == null) return files.first()
        return files.firstOrNull { it.name == name } ?: files.first()
    }

    fun setSelectedVoiceFile(context: Context, file: File?) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_SELECTED, file?.name)
            .apply()
    }

    fun deleteVoiceFile(context: Context, file: File): Boolean {
        val selected = selectedVoiceFile(context)
        val ok = file.delete()
        if (ok && selected?.absolutePath == file.absolutePath) {
            setSelectedVoiceFile(context, listVoiceFiles(context).firstOrNull())
        }
        return ok
    }

    // ---------------- 播放 ----------------

    /** 接通后调用：优先放你的录音，没有就用 TTS 念 [fallbackText]。 */
    fun speak(context: Context, fallbackText: String) {
        stop()
        val file = selectedVoiceFile(context)
        if (file != null) {
            playFile(file)
            return
        }
        speakWithTts(context.applicationContext, fallbackText)
    }

    private fun playFile(file: File) {
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp ->
                    if (START_OFFSET_MS < mp.duration) mp.seekTo(START_OFFSET_MS)
                    mp.start()
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    private fun speakWithTts(appContext: Context, text: String) {
        if (text.isBlank()) return
        pendingText = text
        try {
            tts = TextToSpeech(appContext) { status ->
                val engine = tts
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    val zh = Locale.SIMPLIFIED_CHINESE
                    if (engine.isLanguageAvailable(zh) < TextToSpeech.LANG_AVAILABLE) {
                        releaseTts()
                        return@TextToSpeech
                    }
                    engine.language = zh
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) = releaseTts()
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) = releaseTts()
                        override fun onError(utteranceId: String?, errorCode: Int) = releaseTts()
                    })
                    engine.speak(pendingText ?: "", TextToSpeech.QUEUE_FLUSH, Bundle(), TTS_UTTERANCE_ID)
                } else {
                    releaseTts()
                }
            }
        } catch (_: Exception) {
            releaseTts()
        }
    }

    fun stop() {
        releasePlayer()
        releaseTts()
    }

    private fun releasePlayer() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    private fun releaseTts() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        pendingText = null
    }

    // ---------------- 录音 ----------------

    /** 录音输出文件：`voice/voice_yyyyMMdd_HHmmss.m4a`。 */
    fun newRecordingFile(context: Context): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        return File(voiceDir(context), "voice_$stamp.m4a")
    }

    /** 开始录音，返回输出文件；失败返回 null。 */
    fun startRecording(context: Context): File? {
        stopRecording()
        val out = newRecordingFile(context)
        return try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(out.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            recordingFile = out
            out
        } catch (_: Exception) {
            stopRecording()
            out.delete()
            null
        }
    }

    /** 停止录音，返回录好的文件；太短或失败返回 null。 */
    fun stopRecording(): File? {
        val rec = recorder
        val file = recordingFile
        recorder = null
        recordingFile = null
        if (rec == null) return null
        return try {
            rec.stop()
            rec.release()
            if (file != null && file.exists() && file.length() > 1024) file else {
                file?.delete()
                null
            }
        } catch (_: Exception) {
            try {
                rec.release()
            } catch (_: Exception) {
            }
            file?.delete()
            null
        }
    }

    fun cancelRecording() {
        val rec = recorder
        recorder = null
        val file = recordingFile
        recordingFile = null
        try {
            rec?.stop()
            rec?.release()
        } catch (_: Exception) {
        }
        file?.delete()
    }

    fun isRecording(): Boolean = recorder != null

    /** 试听某个文件。 */
    fun preview(context: Context, file: File) {
        stop()
        playFile(file)
    }

    /** 给界面用的一句话说明。 */
    fun describe(context: Context): String {
        val files = listVoiceFiles(context)
        if (files.isEmpty()) {
            return "还没有语音包：可以录一段，或用系统语音念一句中文"
        }
        val sel = selectedVoiceFile(context)
        return "共 ${files.size} 条语音包，当前使用：${sel?.name ?: "-"}"
    }
}
