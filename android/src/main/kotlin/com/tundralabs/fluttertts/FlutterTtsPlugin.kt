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



    companion object {
        private const val SILENCE_PREFIX = "SIL_"
        private const val SYNTHESIZE_TO_FILE_PREFIX = "STF_"
    }

    private fun initInstance(messenger: BinaryMessenger, context: Context) {
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
        Log.d(tag, "FlutterTts: onDetachedFromEngine")
        disposeTextToSpeech()
        context = null
        methodChannel!!.setMethodCallHandler(null)
        methodChannel = null
        isInitializing = false
    }

    private fun initTextToSpeech() {
        Log.e(tag, "Initalizing TextToSpeech")
        isInitializing = true
        if (tts != null) {
           disposeTextToSpeech()
        }
         tts = if (currentEngine != null) {
            TextToSpeech(context, onInitListener, currentEngine)
        } else {
            TextToSpeech(context, onInitListener)
        }
    }

    private fun disposeTextToSpeech() {
         ttsStatus = null
         isPaused = false
         pauseText = null
         stop()
         tts?.shutdown()
         tts = null
    }

    private val onInitListener: TextToSpeech.OnInitListener =
    TextToSpeech.OnInitListener { status ->
        // Handle pending method calls (sent while TTS was initializing)
        synchronized(this@FlutterTtsPlugin) {
            ttsStatus = status
            isInitializing = false
            for (call in pendingMethodCalls) {
                call.run()
            }
            pendingMethodCalls.clear()
        }

        if (status == TextToSpeech.SUCCESS && tts != null) {
            tts!!.setOnUtteranceProgressListener(utteranceProgressListener)
            Log.e(tag, "Successfully initialized TextToSpeech with status: $status")
            engineCompletion(1)
        } else {
            val errorMessage = "Failed to initialize TextToSpeech with status: $status"
            Log.e(tag, errorMessage)
            engineCompletion(0, errorMessage)
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
                    onProgress(utteranceId, 0, utterances[utteranceId]!!.length)
                }
            }

            override fun onDone(utteranceId: String) {
                if (utteranceId.startsWith(SILENCE_PREFIX)) return
                if (utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                    Log.d(tag, "Utterance ID has completed: $utteranceId")
                    if (synthesizing) synthCompletion(1)
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
                Log.d(
                    tag,
                    "Utterance ID has been stopped: $utteranceId. Interrupted: $interrupted"
                )
                if (synthesizing){
                  synthCompletion(0)
                }
                if (speaking){
                  speakCompletion(0)
                }
                if (isPaused) {
                    invokeMethod("speak.onPause", true)
                } else {
                    invokeMethod("speak.onCancel", true)
                }
            }

            private fun onProgress(utteranceId: String?, startAt: Int, endAt: Int) {
                if (utteranceId != null && !utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                    val text = utterances[utteranceId]
                    val data = HashMap<String, String?>()
                    data["text"] = text
                    data["start"] = startAt.toString()
                    data["end"] = endAt.toString()
                    data["word"] = text!!.substring(startAt, endAt)
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
                    if (synthesizing) synthCompletion(0)
                    invokeMethod("synth.onError", "Error from TextToSpeech (synth)")
                } else {
                    if (speaking) speakCompletion(0)
                    invokeMethod("speak.onError", "Error from TextToSpeech (speak)")
                }
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                if (utteranceId.startsWith(SYNTHESIZE_TO_FILE_PREFIX)) {
                    if (synthesizing) synthCompletion(0)
                    invokeMethod("synth.onError", "Error from TextToSpeech (synth) - $errorCode")
                } else {
                    if (speaking) speakCompletion(0)
                    invokeMethod("speak.onError", "Error from TextToSpeech (speak) - $errorCode")
                }
            }
        }

    fun speakCompletion(success: Int) {
        handler!!.post {
            speakResult?.success(success)
            speakResult = null
        }
    }

    fun synthCompletion(success: Int) {
        handler!!.post {
            synthResult?.success(success)
            synthResult = null
        }
    }
    fun engineCompletion(success: Int, error: String? = null) {
        if (engineResult != null) {
                if (error != null) { 
                    engineResult?.error("EngineError", error, null) 
                } else {
                    engineResult?.success(success)
                }
                engineResult = null
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
                val suspendedCall = Runnable { onMethodCall(call, result) }
                pendingMethodCalls.add(suspendedCall)
                return
            }
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
                        val suspendedCall = Runnable { onMethodCall(call, result) }
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
                awaitSpeakCompletion = java.lang.Boolean.parseBoolean(call.arguments.toString())
                result.success(1)
            }

            "awaitSynthCompletion" -> {
                awaitSynthCompletion = java.lang.Boolean.parseBoolean(call.arguments.toString())
                result.success(1)
            }

            "getMaxSpeechInputLength" -> {
                val res = maxSpeechInputLength
                result.success(res)
            }

            "synthesizeToFile" -> {
                if (synthesizing) {
                    stop() // Stop any ongoing synthesis
                }
                if (awaitSynthCompletion) {
                    synthResult = result 
                } else {
                    result.success(1)
                }
                val text: String? = call.argument("text")
                val fileName: String? = call.argument("fileName")
                synthesizeToFile(text!!, fileName!!)
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
                setSpeechRate(rate.toFloat() * 2.0f)
                result.success(1)
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

    private fun setSpeechRate(rate: Float) {
        tts!!.setSpeechRate(rate)
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
            for (v in tts!!.voices) {
                if (v.locale == locale && !v.isNetworkConnectionRequired) {
                    voiceToCheck = v
                    break
                }
            }
            if (voiceToCheck != null) {
                val features: Set<String> = voiceToCheck.features
                return (!features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
            }
        }
        return false
    }

    private fun setEngine(engine: String?, result: Result) {
        currentEngine = engine
        engineResult = result
        initTextToSpeech()
    }

    private fun setLanguage(language: String?, result: Result) {
        val locale: Locale = Locale.forLanguageTag(language!!)
        if (isLanguageAvailable(locale)) {
            tts!!.language = locale
            result.success(1)
        } else {
            result.success(0)
        }
    }

    private fun setVoice(voice: HashMap<String?, String>, result: Result) {
        for (ttsVoice in tts!!.voices) {
            if (ttsVoice.name == voice["name"] && ttsVoice.locale
                    .toLanguageTag() == voice["locale"]
            ) {
                tts!!.voice = ttsVoice
                result.success(1)
                return
            }
        }
        Log.d(tag, "Voice name not found: $voice")
        result.success(0)
    }

    private fun clearVoice(result: Result) {
        tts!!.voice = tts!!.defaultVoice
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
            tts!!.setPitch(pitch)
            result.success(1)
        } else {
            Log.d(tag, "Invalid pitch $pitch value - Range is from 0.5 to 2.0")
            result.success(0)
        }
    }

    private fun getVoices(result: Result) {
        val voices = ArrayList<HashMap<String, String>>()
        try {
            for (voice in tts!!.voices) {
                val voiceMap = HashMap<String, String>()
                voiceMap["name"] = voice.name
                voiceMap["locale"] = voice.locale.toLanguageTag()
                voices.add(voiceMap)
            }
            result.success(voices)
        } catch (e: NullPointerException) {
            Log.d(tag, "getVoices: " + e.message)
            result.success(null)
        }
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
        val engines = ArrayList<String>()
        try {
            for (engineInfo in tts!!.engines) {
                engines.add(engineInfo.name)
            }
        } catch (e: Exception) {
            Log.d(tag, "getEngines: " + e.message)
        }
        result.success(engines)
    }

    private fun getDefaultEngine(result: Result) {
        val defaultEngine: String? = tts!!.defaultEngine
        result.success(defaultEngine)
    }

    private fun getCurrentEngine(result: Result) {
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
        utterances[uuid] = text
        return if (ismServiceConnectionUsable(tts)) {
            if (silencems > 0) {
                tts!!.playSilentUtterance(
                    silencems.toLong(),
                    TextToSpeech.QUEUE_FLUSH,
                    SILENCE_PREFIX + uuid
                )
                tts!!.speak(text, TextToSpeech.QUEUE_ADD, bundle, uuid) == 0
            } else {
                tts!!.speak(text, queueMode, bundle, uuid) == 0
            }
        } else {
            initTextToSpeech() // Reinitialize TTS
            false
        }
    }

    private fun stop() {
        tts?.stop()
        if (speaking) speakCompletion(0)
        if (synthesizing) synthCompletion(0)
    }

    private val maxSpeechInputLength: Int
        get() = TextToSpeech.getMaxSpeechInputLength()


    private fun synthesizeToFile(text: String, fileName: String) {
        val fullPath: String
        val uuid: String = UUID.randomUUID().toString()
        bundle!!.putString(
            TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
            SYNTHESIZE_TO_FILE_PREFIX + uuid
        )
        val file = File(fileName)
        fullPath = file.path

        val result: Int = tts!!.synthesizeToFile(text, bundle!!, file!!, SYNTHESIZE_TO_FILE_PREFIX + uuid)

        if (result == TextToSpeech.SUCCESS) {
            Log.d(tag, "Successfully created file : $fullPath")
        } else {
            Log.d(tag, "Failed creating file : $fullPath")
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
