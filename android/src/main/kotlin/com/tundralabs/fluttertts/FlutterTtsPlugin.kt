package com.tundralabs.fluttertts

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import io.flutter.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.lang.reflect.Field
import java.util.Locale
import java.util.MissingResourceException
import java.util.UUID
import java.util.concurrent.Future;
import kotlin.text.MatchGroup
import kotlin.text.MatchGroupCollection


/** FlutterTtsPlugin  */
class FlutterTtsPlugin : MethodCallHandler, FlutterPlugin {
    private var handler: Handler? = null
    private var methodChannel: MethodChannel? = null
    private val synthesizing: Boolean get() = synthResult != null
    private val speaking: Boolean get() = speakResult != null
    private var speakResult: Result? = null
    private var synthUtteranceId: String? = null
    private var synthResult: Result? = null
    private var awaitSpeakCompletion = false
    private var awaitSynthCompletion = false
    private var context: Context? = null
    private var tts: TextToSpeech? = null
    private val tag = "TTS"
    private val pendingMethodCalls = ArrayList<Runnable>()
    private val utterances = HashMap<String, String>()
    private var bundle: Bundle? = null
    private var silencems = 0
    private var lastProgress = 0
    private var currentEngine: String? = null
    private var currentText: String? = null
    private var pauseText: String? = null
    private var isPaused: Boolean = false
    private var queueMode: Int = TextToSpeech.QUEUE_FLUSH
    private var ttsStatus: Int? = null
    private var engineResult: Result? = null
    private var isInitializing: Boolean = false
    private var cachedVoice: HashMap<String?, String>? = null
    private var cachedPitch: Float? = null
    private var cachedSpeechRate: Float? = null
    private var hasConfigurationError: Boolean = false
    private var initGeneration: Long = 0
    private val allowedInErrorState = setOf("setEngine", "getEngines", "stop", "isLanguageAvailable", "getCurrentEngine", "getVoices", "setVoice", "setSpeechRate", "setPitch")



    companion object {
        private const val SILENCE_PREFIX = "SIL_"
        private const val SYNTHESIZE_TO_FILE_PREFIX = "STF_"
        private const val INITIALIZATION_TIMEOUT_MILLIS = 15_000L
    }

    private fun initInstance(messenger: BinaryMessenger, context: Context) {
        Log.d(tag, "FlutterTts: initInstance")
        this.context = context
        methodChannel = MethodChannel(messenger, "flutter_tts")
        methodChannel!!.setMethodCallHandler(this)
        handler = Handler(Looper.getMainLooper())
        bundle = Bundle()
    }

    /** Android Plugin APIs  */
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Log.d(tag, "FlutterTts: onAttachedToEngine")
        initInstance(binding.binaryMessenger, binding.applicationContext)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Log.w(tag, "FlutterTts: onDetachedFromEngine")
        disposeTextToSpeech()
        context = null
        methodChannel!!.setMethodCallHandler(null)
        methodChannel = null
    }

    @Synchronized
    private fun initTextToSpeech() {
        Log.d(tag, "Initalizing TextToSpeech (initTextToSpeech)")
        if (tts != null) {
            disposeTextToSpeech()
        }

        val generation: Long
        synchronized(this@FlutterTtsPlugin) {
            generation = ++initGeneration
            isInitializing = true
            ttsStatus = null
            hasConfigurationError = false
        }
        val onInitListener = TextToSpeech.OnInitListener { status ->
            handler!!.post {
                handleInitializationResult(generation, status)
            }
        }
        handler?.postDelayed(
            { handleInitializationTimeout(generation) },
            INITIALIZATION_TIMEOUT_MILLIS)
        tts = if (currentEngine != null) {
            TextToSpeech(context, onInitListener, currentEngine)
        } else {
            TextToSpeech(context, onInitListener)
        }
    }

    private fun disposeTextToSpeech() {
        val ttsToDispose = tts

        synchronized(this@FlutterTtsPlugin) {
            ++initGeneration
            isInitializing = false
            tts = null
            ttsStatus = null
        }
        isPaused = false
        pauseText = null

        speakCompletion(0)
        synthCompletion(0)
        utterances.clear()

        if (ttsToDispose != null) {
            try {
                ttsToDispose.stop()
            } catch (e: Throwable) {
                Log.e(tag, "Error during tts.stop(): ${e.message}")
            }

            try {
                ttsToDispose.shutdown()
                Log.d(tag, "TTS Engine shutdown successfully")
            } catch (e: Throwable) {
                Log.e(tag, "Error during tts.shutdown(): ${e.message}")
            }
        }
    }

    private fun handleInitializationResult(generation: Long, status: Int) {
        val callsToProcess: List<Runnable>
        val errorMessage: String?
        synchronized(this@FlutterTtsPlugin) {
            if (generation != initGeneration || !isInitializing) {
                Log.d(tag, "Ignoring stale TextToSpeech initialization callback")
                return
            }

            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts!!.setOnUtteranceProgressListener(utteranceProgressListener)
                errorMessage = restoreConfiguration()
                if (errorMessage == null) {
                    ttsStatus = TextToSpeech.SUCCESS
                } else {
                    hasConfigurationError = true
                    ttsStatus = TextToSpeech.ERROR
                }
            } else {
                errorMessage = "Failed to initialize TextToSpeech with status: $status"
                ttsStatus = status
            }

            isInitializing = false
            callsToProcess = ArrayList(pendingMethodCalls)
            pendingMethodCalls.clear()
        }

        if (errorMessage == null) {
            Log.d(tag, "Successfully initialized TextToSpeech engine with status: $status")
            engineCompletion(1)
        } else {
            Log.e(tag, errorMessage)
            engineCompletion(0, errorMessage)
        }
        processPendingMethodCalls(callsToProcess)
    }

    private fun handleInitializationTimeout(generation: Long) {
        val errorMessage = "TextToSpeech initialization timed out."
        val callsToProcess: List<Runnable>
        synchronized(this@FlutterTtsPlugin) {
            if (generation != initGeneration || !isInitializing) return

            ++initGeneration
            ttsStatus = TextToSpeech.ERROR
            isInitializing = false
            callsToProcess = ArrayList(pendingMethodCalls)
            pendingMethodCalls.clear()
        }
        Log.e(tag, errorMessage)
        engineCompletion(0, errorMessage)
        processPendingMethodCalls(callsToProcess)
    }

    private fun processPendingMethodCalls(callsToProcess: List<Runnable>) {
        for (call in callsToProcess) {
            call.run()
        }
    }

    private fun createSuspendedMethodCall(call: MethodCall, result: Result) = Runnable {
        try {
            onMethodCall(call, result)
        } catch (e: RuntimeException) {
            Log.e(tag, "Failed to process pending TTS method call", e)
            result.error("error", e.message, Log.getStackTraceString(e))
        }
    }


    private val utteranceProgressListener: UtteranceProgressListener =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                if (utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                    invokeMethod("synth.onStart", true)
                } else {
                    if (isPaused) {
                        invokeMethod("speak.onContinue", true)
                        isPaused = false
                    } else {
                        Log.d(tag, "Utterance ID has started: $utteranceId")
                        invokeMethod("speak.onStart", true)
                    }
                }
                if (Build.VERSION.SDK_INT < 26) {
                    val text = utterances[utteranceId] ?: return
                    onProgress(utteranceId, 0, text.length)
                }
            }

            override fun onDone(utteranceId: String) {
                if (utteranceId.startsWith(SILENCE_PREFIX)) return

                if (utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                    Log.d(tag, "Utterance ID has completed: $utteranceId")
                    synthCompletion(1, utteranceId)
                    invokeMethod("synth.onComplete", true)
                } else {
                    Log.d(tag, "Utterance ID has completed: $utteranceId")
                    if (speaking && queueMode == TextToSpeech.QUEUE_FLUSH) {
                        speakCompletion(1)
                    }
                    invokeMethod("speak.onComplete", true)
                }
                lastProgress = 0
                pauseText = null
                utterances.remove(utteranceId)
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                Log.d(tag, "Utterance ID has been stopped: $utteranceId. Interrupted: $interrupted")

                // Unconditionally complete the active request if it's a synthesis task.
                // Our manual stop() method is the main way to cancel, but this handles
                // cancellations from the engine itself.
                if (utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                    if(synthCompletion(0, utteranceId)) {
                        invokeMethod("speak.onCancel", true)
                    }
                } else if (speaking) {
                    speakCompletion(0)
                    invokeMethod("speak.onCancel", true)
                }

                if (isPaused) {
                    invokeMethod("speak.onPause", true)
                }
                utterances.remove(utteranceId)
            }

            private fun onProgress(utteranceId: String?, startAt: Int, endAt: Int) {
                if (utteranceId != null && !utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                    val text = utterances[utteranceId] ?: return
                    if (startAt < 0 || endAt < startAt || endAt > text.length) return

                    val data = HashMap<String, String?>()
                    data["text"] = text
                    data["start"] = startAt.toString()
                    data["end"] = endAt.toString()
                    data["word"] = text.substring(startAt, endAt)
                    invokeMethod("speak.onProgress", data)
                }
            }

            // Requires Android 26 or later
            override fun onRangeStart(utteranceId: String, startAt: Int, endAt: Int, frame: Int) {
                if (!utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                    lastProgress = startAt
                    super.onRangeStart(utteranceId, startAt, endAt, frame)
                    onProgress(utteranceId, startAt, endAt)
                }
            }

            @Deprecated("")
            override fun onError(utteranceId: String) {
                if (utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                    // Only invoke the method if the completion was successful.
                    if (synthCompletion(-1, utteranceId)) {
                        Log.e(tag, "Error from TextToSpeech (synth)")
                        invokeMethod("synth.onError", "Error from TextToSpeech (synth)")
                    }
                } else {
                    if (speaking) speakCompletion(-1)
                    invokeMethod("speak.onError", "Error from TextToSpeech (speak)")
                }
                utterances.remove(utteranceId)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                if (utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                    // Only invoke the method if the completion was successful.
                    if (synthCompletion(errorCode, utteranceId)) {
                        Log.e(tag, "Error from TextToSpeech (synth) - code $errorCode")
                        invokeMethod("synth.onError", "Error from TextToSpeech (synth) - $errorCode")
                    }
                } else {
                    if (speaking) speakCompletion(errorCode)
                    invokeMethod("speak.onError", "Error from TextToSpeech (speak) - $errorCode")
                }
                utterances.remove(utteranceId)
            }
        }


    fun speakCompletion(result: Int) {
        val resultToComplete = speakResult
        if (resultToComplete != null) {
            speakResult = null
            handler!!.post {
                resultToComplete.success(result)
            }
        }
    }


    fun synthCompletion(result: Int, utteranceId: String? = null): Boolean {
        val resultToComplete = synthResult
        if (resultToComplete != null) {
            // Proceed only if it's an unconditional stop OR the ID matches the active request.
            if (utteranceId == null || utteranceId == this.synthUtteranceId) {
                synthResult = null
                synthUtteranceId = null
                handler!!.post {
                    resultToComplete.success(result)
                }
                return true
            } else {
                Log.d(tag, "Ignoring completion for stale/mismatched utterance ID: $utteranceId")
                return false
            }
        }
        // There was no active result to complete.
        return false
    }


    fun engineCompletion(success: Int, error: String? = null) {
        val resultToComplete = engineResult
        if (resultToComplete != null) {
            engineResult = null
            if (error != null) {
                resultToComplete.error("EngineError", error, null)
            } else {
                resultToComplete.success(success)
            }
        }
    }


    override fun onMethodCall(call: MethodCall, result: Result) {
        // If TTS is still loading
        synchronized(this@FlutterTtsPlugin) {
            if (ttsStatus == null) {
                if (!isInitializing) {
                    // Start initialization if not already started
                    initTextToSpeech()
                }
                // Suspend method call until the TTS engine is ready
                val suspendedCall = createSuspendedMethodCall(call, result)
                pendingMethodCalls.add(suspendedCall)
                return
            }
        }

        if (ttsStatus == TextToSpeech.ERROR && call.method !in allowedInErrorState) {
            result.error("EngineError", "TTS engine failed to initialize.",null)
            return
        }


        when (call.method) {
            "speak" -> {
                var text: String = call.arguments.toString()
                if (pauseText == null) {
                    pauseText = text
                    currentText = pauseText!!
                }
                if (isPaused) {
                    // Ensure the text hasn't changed
                    if (currentText == text) {
                        text = pauseText!!
                    } else {
                        pauseText = text
                        currentText = pauseText!!
                        lastProgress = 0
                    }
                }
                if (speaking) {
                    // If TTS is set to queue mode, allow the utterance to be queued up rather than discarded
                    if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                        result.success(0)
                        return
                    }
                }
                val b = speak(text)
                if (!b) {
                    synchronized(this@FlutterTtsPlugin) {
                        val suspendedCall = createSuspendedMethodCall(call, result)
                        pendingMethodCalls.add(suspendedCall)
                    }
                    return
                }
                // Only use await speak completion if queueMode is set to QUEUE_FLUSH
                if (awaitSpeakCompletion && queueMode == TextToSpeech.QUEUE_FLUSH) {
                    speakResult = result
                } else {
                    result.success(1)
                }
            }

            "awaitSpeakCompletion" -> {
                awaitSpeakCompletion =
                    java.lang.Boolean.parseBoolean(call.arguments.toString())
                result.success(1)
            }

            "awaitSynthCompletion" -> {
                awaitSynthCompletion =
                    java.lang.Boolean.parseBoolean(call.arguments.toString())
                result.success(1)
            }

            "getMaxSpeechInputLength" -> {
                val res = maxSpeechInputLength
                result.success(res)
            }

            "synthesizeToFile" -> {
                val text: String = call.argument("text")!!
                val fileName: String = call.argument("fileName")!!

                if (synthesizing) {
                    stop() // Stop any ongoing synthesis
                }
                if (awaitSynthCompletion) {
                    synthResult = result
                } else {
                    result.success(1)
                }

                synthesizeToFile(text, fileName)
            }

            "pause" -> {
                isPaused = true
                if (pauseText != null) {
                    pauseText = pauseText!!.substring(lastProgress)
                }
                stop()
                result.success(1)
            }

            "stop" -> {
                stop()
                lastProgress = 0
                result.success(1)
            }


            "setEngine" -> {
                val engine: String = call.arguments.toString()
                setEngine(engine, result)
            }

            "setSpeechRate" -> {
                val rate: String = call.arguments.toString()
                // To make the FlutterTts API consistent across platforms,
                // Android 1.0 is mapped to flutter 0.5.
                setSpeechRate(rate.toFloat() * 2.0f, result)
            }

            "setVolume" -> {
                val volume: String = call.arguments.toString()
                setVolume(volume.toFloat(), result)
            }

            "setPitch" -> {
                val pitch: String = call.arguments.toString()
                setPitch(pitch.toFloat(), result)
            }

            "setLanguage" -> {
                val language: String = call.arguments.toString()
                setLanguage(language, result)
            }

            "getLanguages" -> getLanguages(result)
            "getVoices" -> getVoices(result)
            "getSpeechRateValidRange" -> getSpeechRateValidRange(result)
            "getEngines" -> getEngines(result)
            "getDefaultEngine" -> getDefaultEngine(result)
            "getCurrentEngine" -> getCurrentEngine(result)
            "getDefaultVoice" -> getDefaultVoice(result)
            "setVoice" -> {
                val voice: HashMap<String?, String>? = call.arguments()
                setVoice(voice!!, result)
            }

            "clearVoice" -> clearVoice(result)

            "isLanguageAvailable" -> {
                val language: String = call.arguments.toString()
                val locale: Locale = Locale.forLanguageTag(language)
                result.success(isLanguageAvailable(locale))
            }

            "setSilence" -> {
                val silencems: String = call.arguments.toString()
                this.silencems = silencems.toInt()
            }

            "setSharedInstance" -> result.success(1)
            "isLanguageInstalled" -> {
                val language: String = call.arguments.toString()
                result.success(isLanguageInstalled(language))
            }

            "areLanguagesInstalled" -> {
                val languages: List<String?>? = call.arguments()
                result.success(areLanguagesInstalled(languages!!))
            }

            "setQueueMode" -> {
                val queueMode: String = call.arguments.toString()
                this.queueMode = queueMode.toInt()
                result.success(1)
            }

            else -> result.notImplemented()
        }

    }

    private fun setSpeechRate(rate: Float, result: Result) {
        if (ttsStatus == TextToSpeech.ERROR && !hasConfigurationError) {
            result.error("EngineError", "TTS engine failed to initialize.", null)
            return
        }
        if (tts!!.setSpeechRate(rate) == TextToSpeech.SUCCESS) {
            cachedSpeechRate = rate
            if (hasConfigurationError) {
                val configurationError = verifyRemainingConfiguration(
                    tts!!,
                    verifySpeechRate = false,
                    verifyPitch = true)
                if (configurationError != null) {
                    result.error("SET_SPEECH_RATE_ERROR", configurationError, null)
                    return
                }
                hasConfigurationError = false
                ttsStatus = TextToSpeech.SUCCESS
            }
            result.success(1)
        } else {
            result.error(
                "SET_SPEECH_RATE_ERROR",
                "Failed to apply the requested TTS speech rate.",
                null)
        }
    }

    private fun isLanguageAvailable(locale: Locale?): Boolean {
        return tts!!.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
    }

    private fun areLanguagesInstalled(languages: List<String?>): Map<String?, Boolean> {
        val result: MutableMap<String?, Boolean> = HashMap()
        for (language in languages) {
            result[language] = isLanguageInstalled(language)
        }
        return result
    }

    private fun isLanguageInstalled(language: String?): Boolean {
        val locale: Locale = Locale.forLanguageTag(language!!)
        if (isLanguageAvailable(locale)) {
            var voiceToCheck: Voice? = null
            val voices = getVoicesOrNull() ?: return false
            for (v in voices) {
                if (v.locale == locale && !v.isNetworkConnectionRequired) {
                    voiceToCheck = v
                    break
                }
            }
            if (voiceToCheck != null) {
                val features: Set<String> = voiceToCheck.features ?: return false
                return (!features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
            }
        }
        return false
    }

    private fun setEngine(engine: String?, result: Result) {
        engineResult = result

        try {
            if (engine != currentEngine) {
                cachedVoice = null
            }
            currentEngine = engine
            initTextToSpeech()
        } catch (e: Throwable) {
            Log.e(tag, "An exception occurred in setEngine: " + e.message)
            engineCompletion(0, "An exception occurred in setEngine: " + e.message)
        }
    }

    private fun setLanguage(language: String?, result: Result) {
        val locale: Locale = Locale.forLanguageTag(language!!)
        if (isLanguageAvailable(locale)) {
            if (tts!!.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE) {
                cachedVoice = null
                result.success(1)
            } else {
                result.success(0)
            }
        } else {
            result.success(0)
        }
    }

    private fun setVoice(voice: HashMap<String?, String>, result: Result) {
        if (ttsStatus == TextToSpeech.ERROR && !hasConfigurationError) {
            cachedVoice = HashMap(voice)
            if (!isInitializing) {
                initTextToSpeech()
            }
            result.error(
                "SET_VOICE_ERROR",
                "TTS engine initialization failed; initialization was restarted.",
                null)
            return
        }

        val voices = getVoicesOrNull()
        if (voices == null) {
            cachedVoice = HashMap(voice)
            if (!isInitializing) {
                initTextToSpeech()
            }
            result.error(
                "SET_VOICE_ERROR",
                "TTS voices are temporarily unavailable; initialization was restarted.",
                null)
            return
        }
        for (ttsVoice in voices) {
            if (ttsVoice.name == voice["name"] && ttsVoice.locale
                    .toLanguageTag() == voice["locale"]
            ) {
                if (tts!!.setVoice(ttsVoice) == TextToSpeech.SUCCESS) {
                    cachedVoice = HashMap(voice)
                    if (hasConfigurationError) {
                        val configurationError = restoreSpeechRateAndPitch(tts!!)
                        if (configurationError != null) {
                            result.error("SET_VOICE_ERROR", configurationError, null)
                            return
                        }
                        hasConfigurationError = false
                        ttsStatus = TextToSpeech.SUCCESS
                    }
                    result.success(1)
                } else {
                    result.error(
                        "SET_VOICE_ERROR",
                        "Failed to apply the requested TTS voice.",
                        null)
                }
                return
            }
        }
        Log.d(tag, "Voice name not found: $voice")
        result.error(
            "SET_VOICE_ERROR",
            "Requested TTS voice was not found.",
            null)
    }

    private fun clearVoice(result: Result) {
        tts!!.voice = tts!!.defaultVoice
        cachedVoice = null
        result.success(1)
    }

    private fun setVolume(volume: Float, result: Result) {
        if (volume in (0.0f..1.0f)) {
            bundle!!.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            result.success(1)
        } else {
            Log.d(tag, "Invalid volume $volume value - Range is from 0.0 to 1.0")
            result.success(0)
        }
    }

    private fun setPitch(pitch: Float, result: Result) {
        if (pitch in (0.5f..2.0f)) {
            if (ttsStatus == TextToSpeech.ERROR && !hasConfigurationError) {
                result.error("EngineError", "TTS engine failed to initialize.", null)
                return
            }
            if (tts!!.setPitch(pitch) == TextToSpeech.SUCCESS) {
                cachedPitch = pitch
                if (hasConfigurationError) {
                    val configurationError = verifyRemainingConfiguration(
                        tts!!,
                        verifySpeechRate = true,
                        verifyPitch = false)
                    if (configurationError != null) {
                        result.error("SET_PITCH_ERROR", configurationError, null)
                        return
                    }
                    hasConfigurationError = false
                    ttsStatus = TextToSpeech.SUCCESS
                }
                result.success(1)
            } else {
                result.error(
                    "SET_PITCH_ERROR",
                    "Failed to apply the requested TTS pitch.",
                    null)
            }
        } else {
            Log.d(tag, "Invalid pitch $pitch value - Range is from 0.5 to 2.0")
            result.success(0)
        }
    }

    private fun getVoicesOrNull(): Set<Voice>? {
        return try {
            tts!!.voices
        } catch (e: NullPointerException) {
            Log.d(tag, "TTS voices are unavailable: ${e.message}")
            null
        }
    }

    private fun restoreConfiguration(): String? {
        val textToSpeech = tts ?: return "TextToSpeech became unavailable during initialization."

        return verifyRemainingConfiguration(textToSpeech, true, true)
    }

    private fun verifyRemainingConfiguration(
        textToSpeech: TextToSpeech,
        verifySpeechRate: Boolean,
        verifyPitch: Boolean
    ): String? {
        if (verifySpeechRate) {
            cachedSpeechRate?.let {
                if (textToSpeech.setSpeechRate(it) != TextToSpeech.SUCCESS) {
                    return "Cached TTS speech rate could not be restored."
                }
            }
        }
        if (verifyPitch) {
            cachedPitch?.let {
                if (textToSpeech.setPitch(it) != TextToSpeech.SUCCESS) {
                    return "Cached TTS pitch could not be restored."
                }
            }
        }

        val voiceToRestore = cachedVoice ?: return null
        val voices = getVoicesOrNull()
            ?: return "Cached TTS voice cannot be restored because voices are unavailable."
        val matchingVoice = voices.firstOrNull {
            it.name == voiceToRestore["name"] &&
                it.locale.toLanguageTag() == voiceToRestore["locale"]
        } ?: return "Cached TTS voice is not available in the initialized engine."
        if (textToSpeech.setVoice(matchingVoice) != TextToSpeech.SUCCESS) {
            return "Cached TTS voice could not be applied to the initialized engine."
        }
        return null
    }

    private fun restoreSpeechRateAndPitch(textToSpeech: TextToSpeech): String? {
        cachedSpeechRate?.let {
            if (textToSpeech.setSpeechRate(it) != TextToSpeech.SUCCESS) {
                return "Cached TTS speech rate could not be restored."
            }
        }
        cachedPitch?.let {
            if (textToSpeech.setPitch(it) != TextToSpeech.SUCCESS) {
                return "Cached TTS pitch could not be restored."
            }
        }
        return null
    }

    private fun getVoices(result: Result) {
        if (ttsStatus == TextToSpeech.ERROR && !hasConfigurationError) {
            result.error("GET_VOICES_ERROR", "TTS engine failed to initialize.", null)
            return
        }
        val voices = ArrayList<HashMap<String, String>>()
        val ttsVoices = getVoicesOrNull()
        if (ttsVoices == null) {
            if (ttsStatus == TextToSpeech.SUCCESS && !hasConfigurationError && !isInitializing) {
                initTextToSpeech()
                result.error(
                    "GET_VOICES_ERROR",
                    "TTS voices were unavailable; initialization was restarted.",
                    null)
                return
            }
            result.error(
                "GET_VOICES_ERROR",
                "Failed to retrieve TTS voices.",
                null)
            return
        }
        for (voice in ttsVoices) {
            voices.add(hashMapOf("name" to voice.name, "locale" to voice.locale.toLanguageTag()))
        }
        result.success(voices)
    }

    private fun getLanguages(result: Result) {
        val locales = ArrayList<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // While this method was introduced in API level 21, it seems that it
                // has not been implemented in the speech service side until API Level 23.
                for (locale in tts!!.availableLanguages) {
                    locales.add(locale.toLanguageTag())
                }
            } else {
                for (locale in Locale.getAvailableLocales()) {
                    if (locale.variant.isEmpty() && isLanguageAvailable(locale)) {
                        locales.add(locale.toLanguageTag())
                    }
                }
            }
        } catch (e: MissingResourceException) {
            Log.d(tag, "getLanguages: " + e.message)
        } catch (e: NullPointerException) {
            Log.d(tag, "getLanguages: " + e.message)
        }
        result.success(locales)
    }


    private fun getEngines(result: Result) {
        val engines = ArrayList<HashMap<String, String>>()
        try {
            for (engineInfo in tts!!.engines) {
                if (engineInfo.name.startsWith("com.samsung") && Build.VERSION.SDK_INT >= 35) {
                    continue // On Android 15 (API 35) and newer, Samsung blocks its TTS engine from third-party use.
                }
                engines.add(hashMapOf("name" to engineInfo.name, "label" to engineInfo.label))
            }
            result.success(engines)
        } catch (e: Exception) {
            Log.d(tag, "getEngines: " + e.message)
            result.error(
                "EngineError",
                "Failed to retrieve TTS engines.",
                e.message)

        }
    }

    private fun getDefaultEngine(result: Result) {
        val defaultEngine: String? = tts!!.defaultEngine
        result.success(defaultEngine)
    }

    private fun getCurrentEngine(result: Result) {
        if (ttsStatus != TextToSpeech.SUCCESS || tts == null) {
            result.success(null)
            return
        }
        if (currentEngine != null) {
            result.success(currentEngine)
            return
        }
        return getDefaultEngine(result)
    }

    private fun getDefaultVoice(result: Result) {
        val defaultVoice: Voice? = tts!!.defaultVoice
        val voice = HashMap<String, String>()
        if (defaultVoice != null) {
            voice["name"] = defaultVoice.name
            voice["locale"] = defaultVoice.locale.toLanguageTag()
        }
        result.success(voice)
    }


    private fun getSpeechRateValidRange(result: Result) {
        // Valid values available in the android documentation.
        // https://developer.android.com/reference/android/speech/tts/TextToSpeech#setSpeechRate(float)
        // To make the FlutterTts API consistent across platforms,
        // we map Android 1.0 to flutter 0.5 and so on.
        val data = HashMap<String, String>()
        data["min"] = "0"
        data["normal"] = "0.5"
        data["max"] = "1.5"
        data["platform"] = "android"
        result.success(data)
    }

    private fun speak(text: String): Boolean {
        val uuid: String = UUID.randomUUID().toString()
        return if (ismServiceConnectionUsable(tts)) {
            utterances[uuid] = text
            val result = if (silencems > 0) {
                tts!!.playSilentUtterance(
                    silencems.toLong(),
                    TextToSpeech.QUEUE_FLUSH,
                    SILENCE_PREFIX + uuid
                )
                tts!!.speak(text, TextToSpeech.QUEUE_ADD, bundle, uuid)
            } else {
                tts!!.speak(text, queueMode, bundle, uuid)
            }
            if (result != TextToSpeech.SUCCESS) {
                utterances.remove(uuid)
            }
            result == TextToSpeech.SUCCESS
        } else {
            initTextToSpeech() // Reinitialize TTS
            false
        }
    }

    private fun stop() {
        if (speaking) speakCompletion(0)
        if (synthesizing) synthCompletion(0)
        tts?.stop()
    }

    private val maxSpeechInputLength: Int
        get() = TextToSpeech.getMaxSpeechInputLength()


    private fun synthesizeToFile(text: String, fileName: String) {
        val utteranceId = SYNTHESIZE_TO_FILE_PREFIX + UUID.randomUUID().toString()
        this.synthUtteranceId = utteranceId

        try {
            bundle!!.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            val file = File(fileName)
            val result: Int = tts!!.synthesizeToFile(text, bundle!!, file, utteranceId)

            if (result != TextToSpeech.SUCCESS) {
                synthCompletion(result, utteranceId)
                Log.e(tag, "Failed creating file (result: $result) : Path: ${file.path}")
            } else {
                Log.d(tag, "Successfully started synthesis to file : ${file.path}")
            }
        } catch (e: Throwable) {
            synthCompletion(-1, utteranceId)
            Log.e(tag, "An exception occurred in synthesizeToFile: " + e.message)
        }
    }

    private fun invokeMethod(method: String, arguments: Any) {
        handler!!.post {
            if (methodChannel != null) methodChannel!!.invokeMethod(
                method,
                arguments
            )
        }
    }

    private fun ismServiceConnectionUsable(tts: TextToSpeech?): Boolean {
        var isBindConnection = true
        if (tts == null) {
            return false
        }
        val fields: Array<Field> = tts.javaClass.declaredFields
        for (j in fields.indices) {
            fields[j].isAccessible = true
            if ("mServiceConnection" == fields[j].name && "android.speech.tts.TextToSpeech\$Connection" == fields[j].type.name) {
                try {
                    if (fields[j][tts] == null) {
                        isBindConnection = false
                        Log.e(tag, "*******TTS -> mServiceConnection == null*******")
                    }
                } catch (e: IllegalArgumentException) {
                    e.printStackTrace()
                } catch (e: IllegalAccessException) {
                    e.printStackTrace()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return isBindConnection
    }
}
