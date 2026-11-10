package com.hdcontest.ecocoach.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hdcontest.ecocoach.BuildConfig
import java.util.Locale

/**
 * Pleos TextToSpeech SDK 래퍼.
 * 클라이언트 ID/Secret 이 있으면 HYBRID, 없으면 ON_DEVICE 로 동작한다.
 * 말하는 도중 새 문장이 오면 끝난 뒤 이어서 읽는다(최대 [maxQueue]개, 넘치면 오래된 것부터 버림).
 */
class VoiceCoach(context: Context, private val maxQueue: Int = 2) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val hasCredentials =
        BuildConfig.PLEOS_CLIENT_ID.isNotBlank() && BuildConfig.PLEOS_CLIENT_SECRET.isNotBlank()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var speaking = false
    private val queue = ArrayDeque<String>()

    private val eventListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit

        override fun onDone() {
            main.post { onFinished() }
        }

        override fun onStop() {
            main.post { onFinished() }
        }

        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "TTS error: $errorType")
            main.post { onFinished() }
        }
    }

    fun initialize() {
        if (tts != null) return
        val engine = TextToSpeech(appContext, if (hasCredentials) Mode.HYBRID else Mode.ON_DEVICE)
        tts = engine
        engine.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                engine.addEventListener(eventListener)
                engine.setLocale(Locale.KOREA)
                if (!hasCredentials) {
                    main.post { markReady() }
                    return
                }
                engine.registerApp(
                    BuildConfig.PLEOS_CLIENT_ID,
                    BuildConfig.PLEOS_CLIENT_SECRET,
                    object : OnServerConnectionListener {
                        override fun onConnected() {
                            main.post { markReady() }
                        }

                        override fun onFailed(errorType: ErrorType) {
                            // HYBRID 모드는 온디바이스 엔진으로 자동 전환된다.
                            Log.w(TAG, "TTS server connection failed: $errorType")
                            main.post { markReady() }
                        }
                    },
                )
            }

            override fun onFailure(errorType: ErrorType) {
                Log.e(TAG, "TTS init failed: $errorType")
            }
        })
    }

    fun say(text: String) {
        queue.addLast(text)
        while (queue.size > maxQueue) queue.removeFirst()
        speakNext()
    }

    private fun markReady() {
        ready = true
        speakNext()
    }

    private fun speakNext() {
        val engine = tts ?: return
        if (!ready || speaking) return
        val next = queue.removeFirstOrNull() ?: return
        speaking = true
        engine.speak(next, true)
    }

    private fun onFinished() {
        speaking = false
        speakNext()
    }

    fun release() {
        queue.clear()
        tts?.run {
            stop()
            removeEventListener(eventListener)
            release()
        }
        tts = null
        ready = false
        speaking = false
    }

    private companion object {
        const val TAG = "VoiceCoach"
    }
}
