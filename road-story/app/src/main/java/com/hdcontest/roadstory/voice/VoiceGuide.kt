package com.hdcontest.roadstory.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hdcontest.roadstory.BuildConfig
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitializeListener
import ai.pleos.playground.stt.listener.OnServerConnectionListener as SttServerConnectionListener
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode

/**
 * Pleos TextToSpeech / SpeechToText SDK 래퍼.
 *
 * - 클라이언트 ID/Secret 이 있으면 HYBRID(서버 우선, 실패 시 온디바이스), 없으면 ON_DEVICE 로 동작한다.
 * - SDK 콜백은 바인더 스레드에서 올 수 있으므로 모두 메인 스레드로 옮겨 처리한다.
 * - 한 번에 하나의 발화/인식만 진행한다.
 */
class VoiceGuide(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hasCredentials =
        BuildConfig.PLEOS_CLIENT_ID.isNotBlank() && BuildConfig.PLEOS_CLIENT_SECRET.isNotBlank()

    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    private var ttsReady = false
    private var sttReady = false

    private var onSpeakDone: (() -> Unit)? = null
    /** TTS 준비 전이거나, 이전 발화를 끊는 중일 때 대기하는 문장 */
    private var pendingSpeech: Pair<String, (() -> Unit)?>? = null
    private var interrupting = false

    private var onListenResult: ((String?) -> Unit)? = null
    private var lastPartial: String? = null
    private var listenTimeout: Runnable? = null

    var isSpeaking = false
        private set
    var isListening = false
        private set

    private val ttsListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit

        override fun onDone() {
            mainHandler.post { finishSpeaking() }
        }

        override fun onStop() {
            mainHandler.post { finishSpeaking() }
        }

        override fun onError(errorType: TtsErrorType) {
            Log.w(TAG, "TTS error: $errorType")
            mainHandler.post { finishSpeaking() }
        }
    }

    private val sttListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit

        override fun onUpdated(stt: String, completed: Boolean) {
            mainHandler.post {
                lastPartial = stt
                if (completed) finishListening(stt)
            }
        }

        override fun onEndedRecognition() {
            // 최종 결과 없이 끝난 경우 마지막 중간 결과라도 사용한다.
            mainHandler.post { finishListening(lastPartial) }
        }

        override fun onError(errorType: SttErrorType) {
            Log.w(TAG, "STT error: $errorType")
            mainHandler.post { finishListening(null) }
        }
    }

    fun initialize() {
        if (tts != null) return
        initTts()
        initStt()
    }

    private fun initTts() {
        val mode = if (hasCredentials) TtsMode.HYBRID else TtsMode.ON_DEVICE
        val engine = TextToSpeech(appContext, mode)
        tts = engine
        engine.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                engine.addEventListener(ttsListener)
                engine.setLocale(Locale.KOREA)
                if (!hasCredentials) {
                    mainHandler.post { onTtsReady() }
                    return
                }
                engine.registerApp(
                    BuildConfig.PLEOS_CLIENT_ID,
                    BuildConfig.PLEOS_CLIENT_SECRET,
                    object : OnServerConnectionListener {
                        override fun onConnected() {
                            mainHandler.post { onTtsReady() }
                        }

                        override fun onFailed(errorType: TtsErrorType) {
                            // HYBRID 모드는 온디바이스 엔진으로 자동 전환되므로 계속 진행한다.
                            Log.w(TAG, "TTS server connection failed: $errorType")
                            mainHandler.post { onTtsReady() }
                        }
                    },
                )
            }

            override fun onFailure(errorType: TtsErrorType) {
                Log.e(TAG, "TTS init failed: $errorType")
            }
        })
    }

    private fun initStt() {
        val mode = if (hasCredentials) SttMode.HYBRID else SttMode.ON_DEVICE
        val engine = SpeechToText(appContext, mode)
        stt = engine
        engine.initialize(object : SttInitializeListener {
            override fun onSuccess() {
                engine.addListener(sttListener)
                engine.setLocale(Locale.KOREA)
                if (!hasCredentials) {
                    mainHandler.post { sttReady = true }
                    return
                }
                engine.registerApp(
                    BuildConfig.PLEOS_CLIENT_ID,
                    BuildConfig.PLEOS_CLIENT_SECRET,
                    object : SttServerConnectionListener {
                        override fun onConnected() {
                            mainHandler.post { sttReady = true }
                        }

                        override fun onFailed(errorType: SttErrorType) {
                            Log.w(TAG, "STT server connection failed: $errorType")
                            mainHandler.post { sttReady = true }
                        }
                    },
                )
            }

            override fun onFailure(errorType: SttErrorType) {
                Log.e(TAG, "STT init failed: $errorType")
            }
        })
    }

    private fun onTtsReady() {
        ttsReady = true
        pendingSpeech?.let { (text, done) ->
            pendingSpeech = null
            speak(text, done)
        }
    }

    /**
     * 문장을 읽는다. 이미 말하는 중이면 기존 발화를 멈춘 뒤(onStop 수신 후) 새 문장을 읽는다.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val engine = tts
        if (engine == null || !ttsReady || interrupting) {
            pendingSpeech = text to onDone
            return
        }
        cancelListening()
        if (isSpeaking) {
            pendingSpeech = text to onDone
            onSpeakDone = null
            interrupting = true
            engine.stop()
            return
        }
        onSpeakDone = onDone
        isSpeaking = true
        engine.speak(text, true)
    }

    /**
     * 짧은 음성 응답을 듣는다. [timeoutMs] 안에 결과가 없으면 null 로 완료한다.
     */
    fun listen(timeoutMs: Long = 6_000L, onResult: (String?) -> Unit) {
        val engine = stt
        if (engine == null || !sttReady || isSpeaking) {
            onResult(null)
            return
        }
        cancelListening()
        onListenResult = onResult
        lastPartial = null
        isListening = true
        engine.request()
        listenTimeout = Runnable {
            engine.stop()
            finishListening(lastPartial)
        }.also { mainHandler.postDelayed(it, timeoutMs) }
    }

    fun stopAll() {
        pendingSpeech = null
        onSpeakDone = null
        if (isSpeaking) {
            interrupting = true
            tts?.stop()
        }
        cancelListening()
    }

    private fun finishSpeaking() {
        isSpeaking = false
        interrupting = false
        val callback = onSpeakDone
        onSpeakDone = null
        callback?.invoke()
        pendingSpeech?.let { (text, done) ->
            pendingSpeech = null
            speak(text, done)
        }
    }

    private fun finishListening(result: String?) {
        if (!isListening) return
        isListening = false
        listenTimeout?.let { mainHandler.removeCallbacks(it) }
        listenTimeout = null
        val callback = onListenResult
        onListenResult = null
        callback?.invoke(result)
    }

    private fun cancelListening() {
        if (!isListening) return
        isListening = false
        listenTimeout?.let { mainHandler.removeCallbacks(it) }
        listenTimeout = null
        onListenResult = null
        stt?.stop()
    }

    fun release() {
        stopAll()
        tts?.run {
            removeEventListener(ttsListener)
            release()
        }
        stt?.run {
            removeListener(sttListener)
            release()
        }
        tts = null
        stt = null
        ttsReady = false
        sttReady = false
        interrupting = false
        isSpeaking = false
    }

    private companion object {
        const val TAG = "VoiceGuide"
    }
}
