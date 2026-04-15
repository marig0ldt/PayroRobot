package com.example.payrorobot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import com.microsoft.cognitiveservices.speech.*
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

enum class RobotState { SLEEPING, LISTENING, BUSY }

class MainActivity : AppCompatActivity() {

    // ==========================================
    // 🔑 API AÇARLARI
    // ==========================================
    val groqApiKey = "SƏNİN_GROQ_AÇARIN_BURADA_OLACAQ"
    val azureApiKey = "SƏNİN_AZURE_AÇARIN_BURADA_OLACAQ"
    private val AZURE_REGION  = "westeurope"

    // ==========================================
    // 🔇 AKS SEDA HƏLLİ — MİKROFON SUSTURMA
    // ==========================================
    private val MIC_REOPEN_DELAY_MS = 800L
    private var isMicMuted = false

    // TTS timeout — 20 saniyə ərzində nə Completed nə Canceled gəlsə, açarı özü açır
    private val TTS_TIMEOUT_MS = 20_000L
    private val ttsTimeoutHandler = Handler(Looper.getMainLooper())
    private var ttsTimeoutRunnable: Runnable? = null

    // ==========================================
    // 🛑 SÖZ KƏSMƏ
    // ==========================================
    private val interruptWords = listOf("yox", "dayan", "səhv", "stop", "gözlə", "bəsdir")

    // ==========================================
    // ⏱ SÜKUT TAYMERİ
    // ==========================================
    private val silenceHandler  = Handler(Looper.getMainLooper())
    private var silenceRunnable: Runnable? = null

    private val SILENCE_SHORT_MS  = 1400L  // 1-2 söz: daha çox gözlə
    private val SILENCE_NORMAL_MS =  800L  // 3-4 söz
    private val SILENCE_LONG_MS   =  550L  // 5+ söz — cümlə bitmiş sayılır
    private val MIN_WORDS_TO_SEND = 2

    // ==========================================
    // 👁️ ÜZ VERİTABANI
    // ==========================================
    private val FACE_THRESHOLD = 0.18f
    private val MAX_FACES      = 100
    private val NO_FACE_LIMIT  = 25

    private val faceDatabase   = mutableListOf<Pair<String, FloatArray>>()
    private var lastKnownSig: FloatArray? = null
    private var currentFaceId: String?    = null
    private var isReturningCustomer       = false
    private var noFaceCount               = 0

    // ==========================================
    // UI / STATE
    // ==========================================
    private var conversationHistory = JSONArray()
    private lateinit var etInput: EditText
    private lateinit var tvResponse: TextView
    private lateinit var tvHeader: TextView
    private lateinit var visualizerContainer: LinearLayout
    private lateinit var cameraPreview: PreviewView
    private val voiceBars = mutableListOf<View>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechSynthesizer: SpeechSynthesizer? = null
    private lateinit var speechConfig: SpeechConfig

    private var currentState      = RobotState.SLEEPING
    private var currentCommand    = ""
    private var isInConversation  = false
    private var isCustomerSmiling = false
    private var wakeWordTriggered = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var cameraExecutor: ExecutorService

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val audio  = perms[Manifest.permission.RECORD_AUDIO] ?: false
        val camera = perms[Manifest.permission.CAMERA] ?: false
        if (audio && camera) { initAzureEar(); startCamera() }
        else updateUI("🚨 MİKROFON VƏ YA KAMERA İCAZƏSİ YOXDUR!")
    }

    // ==========================================
    // 🧠 SİSTEM PROMPTU
    // ==========================================
    private fun buildSystemMessage(): JSONObject = JSONObject().apply {
        put("role", "system")
        put("content", """
            Sən "Payro" adlı ağıllı texnologiya satış məsləhətçisisən. Müasir texnologiya mağazalarında müştərilərə xidmət edirsən. Sənin liderın PAyro kamandasidir .

            ════════════════════════════════════
            🎯 ƏSAS MİSSİYAN
            ════════════════════════════════════
            Müştərinin ehtiyacını dəqiq anlamaq, ona ən uyğun məhsulu tapmaq və alış qərarına kömək etmək.
            Sən yalnız bir növ texnologiya deyil — BÜTÜN texnoloji sahələr üzrə məsləhət verirsən:
            Smartfon, noutbuk, planşet, ağıllı saat, qulaqlıq, televizor, oyun konsolları, kamera, dron,
            ağıllı ev cihazları (smart home), printer, proyektor, xarici disk, router, klaviatura, siçan,
            monitor, səs sistemi, və sair bütün texnoloji məhsullar.

            ════════════════════════════════════
            💰 QİYMƏT VƏ BÜDCƏ MƏNTİQİ
            ════════════════════════════════════
            - Heç vaxt özündən qiymət UYDURMA. Qiyməti dəqiq bilmirsənsə — demə.
            - Yalnız müştəri büdcə aralığını söylədikdə: "Bu büdcəyə uyğun variantlar var" kimi istiqamətləndir.
            - Qiymət soruşulduqda yalnız əmindiksə: "Təxminən X-Y AZN aralığındadır" de.
            - Büdcə aralığı verildikdə həmin aralıqda 2-3 real variant göstər.

            ════════════════════════════════════
            🧠 SATIŞ MƏSLƏHƏTÇİSİ METODU
            ════════════════════════════════════
            1. EHTİYACI KƏŞFİ — İLK ADDIM:
               Müştəri nə istədiyini desə belə, 1 sual ilə ehtiyacı dəqiqləşdir:
               "Bu cihazı daha çox nə üçün işlədəcəksiniz?"
               Texniki savadını da anla: "Texniki detallara baxmaq istərsinizmi, yoxsa sadə izah kifayətdir?"

            2. FAYDANı SAT — PARAMETRİ DEYİL:
               ❌ Pis: "12 GB yaddaşı var"
               ✅ Yaxşı: "Eyni anda çox tətbiq açsanız belə donmayacaq"
               ❌ Pis: "50 MP kamera"
               ✅ Yaxşı: "Gecə şəkilləri belə kristal aydın çıxır"

            3. VARIANT TƏKLİFİ:
               Həmişə 2-3 variant müqayisəli göstər. Hər variantın 1 güclü, 1 zəif cəhətini dürüst bildir.

            4. ÇARPAZ SATIŞ (müştəri məhsul seçəndən sonra):
               Aksesuar tövsiyə et: qoruyucu, qulaqlıq, şarj, çanta.

            5. SATIŞ BAĞLAMA QAYDASI:
               Müştəri "alıram" deməyincə — "mübarəkdir" DEMƏ.
               Aldıqda: "Mübarəkdir, mükəmməl seçimdir!"
               Almadan gedirsə: "Gülə-gülə, istənilən vaxt buyurun."

            ════════════════════════════════════
            🗣️ DİL VƏ ÜSLUB — ƏN VACİB BÖLMƏ
            ════════════════════════════════════
            QADAĞAN EDİLMİŞ İFADƏLƏR (heç vaxt işlətmə):
            - "Əlbəttə ki", "Sizi dinləyirəm", "Mən Payroyam", "Kömək etməkdən məmnunam"
            - "Gəlin baxaq", "Sualınıza gəlincə", "Birinci olaraq"
            - İngilis sözlər: "okay", "sure", "nice", "wow", "cool"

            TÖVSİYƏ EDİLƏN AÇILIŞ TƏRZİ — sözə birbaşa mövzudan başla:
            ✅ "Smartfonlar arasında bu büdcəyə ən yaxşı iki variant var..."
            ✅ "Oyun üçün noutbuk seçərkən ən vacib 3 şeyə baxmaq lazımdır..."

            CÜMLƏ UZUNLUĞU: Maksimum 2-3 qısa cümlə. Robot danışır — uzun mətn yorucu olur.

            ════════════════════════════════════
            🤖 HƏRƏKƏT KODLARI (cavabın SONUNA)
            ════════════════════════════════════
            [F]=İrəli  [B]=Geri  [R]=Sağa  [L]=Sola  [S]=Dayan
        """.trimIndent())
    }

    // ==========================================
    // LIFECYCLE
    // ==========================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etInput             = findViewById(R.id.etInput)
        tvResponse          = findViewById(R.id.tvResponse)
        tvHeader            = findViewById(R.id.tvHeader)
        visualizerContainer = findViewById(R.id.voiceVisualizerContainer)
        cameraPreview       = findViewById(R.id.cameraPreview)
        val btnMic          = findViewById<Button>(R.id.btnMic)

        cameraExecutor = Executors.newSingleThreadExecutor()

        loadFaceDatabase()
        initConversationHistory()
        createVoiceVisualizerBars(5)

        val perms = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            initAzureEar(); startCamera()
        } else {
            permLauncher.launch(perms)
        }

        btnMic.setOnClickListener { resetToSleep() }
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
        ttsTimeoutRunnable?.let { ttsTimeoutHandler.removeCallbacks(it) }
        muteAndStopRecognizer()
        speechSynthesizer?.StopSpeakingAsync()
        speechSynthesizer?.close()
        super.onDestroy()
    }

    // ==========================================
    // 🔇 MİKROFON İDARƏSİ
    // ==========================================
    private fun muteAndStopRecognizer() {
        if (isMicMuted) return
        isMicMuted = true
        try {
            speechRecognizer?.stopContinuousRecognitionAsync()
            Log.d("PayroMic", "🔇 Mikrofon susturuldu")
        } catch (e: Exception) {
            Log.e("PayroMic", "Susturma xətası: ${e.message}")
        }
    }

    private fun unmuteAndRestartRecognizer() {
        // TTS timeout-u ləğv et
        ttsTimeoutRunnable?.let { ttsTimeoutHandler.removeCallbacks(it) }
        ttsTimeoutRunnable = null

        mainHandler.postDelayed({
            isMicMuted = false
            try {
                speechRecognizer?.startContinuousRecognitionAsync()
                Log.d("PayroMic", "🎙️ Mikrofon açıldı")
            } catch (e: Exception) {
                Log.e("PayroMic", "Açma xətası: ${e.message}")
            }
            wakeUpAndListen()
        }, MIC_REOPEN_DELAY_MS)
    }

    // ==========================================
    // 👁️ ÜZ VERİTABANI
    // ==========================================
    private fun loadFaceDatabase() {
        try {
            val prefs = getSharedPreferences("payro_face_db", Context.MODE_PRIVATE)
            val json  = prefs.getString("faces", null) ?: return
            val arr   = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id  = obj.getString("id")
                val sig = obj.getJSONArray("sig")
                faceDatabase.add(Pair(id, FloatArray(sig.length()) { sig.getDouble(it).toFloat() }))
            }
        } catch (e: Exception) { Log.e("FaceDB", "Yükləmə xətası: ${e.message}") }
    }

    private fun saveFaceDatabase() {
        try {
            val arr = JSONArray()
            for ((id, sig) in faceDatabase) {
                val obj = JSONObject()
                val sigArr = JSONArray()
                sig.forEach { sigArr.put(it.toDouble()) }
                obj.put("id", id); obj.put("sig", sigArr); arr.put(obj)
            }
            getSharedPreferences("payro_face_db", Context.MODE_PRIVATE)
                .edit().putString("faces", arr.toString()).apply()
        } catch (e: Exception) { Log.e("FaceDB", "Saxlama xətası: ${e.message}") }
    }

    private fun addOrUpdateFace(id: String, sig: FloatArray) {
        val idx = faceDatabase.indexOfFirst { it.first == id }
        if (idx >= 0) {
            val old = faceDatabase[idx].second
            faceDatabase[idx] = Pair(id, FloatArray(sig.size) { old[it] * 0.7f + sig[it] * 0.3f })
        } else {
            if (faceDatabase.size >= MAX_FACES) faceDatabase.removeAt(0)
            faceDatabase.add(Pair(id, sig))
        }
        saveFaceDatabase()
    }

    private fun findMatchingFace(sig: FloatArray): String? {
        var bestId = ""; var bestDist = Float.MAX_VALUE
        for ((id, stored) in faceDatabase) {
            val d = euclidean(sig, stored)
            if (d < bestDist) { bestDist = d; bestId = id }
        }
        return if (bestDist < FACE_THRESHOLD) bestId else null
    }

    private fun euclidean(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        var s = 0f
        for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
        return sqrt(s)
    }

    private fun extractSignature(face: Face): FloatArray? {
        val box = face.boundingBox
        val w = box.width().toFloat(); val h = box.height().toFloat()
        if (w <= 0 || h <= 0) return null
        val lmIds = listOf(
            FaceLandmark.NOSE_BASE, FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE,
            FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT,
            FaceLandmark.LEFT_CHEEK, FaceLandmark.RIGHT_CHEEK,
            FaceLandmark.LEFT_EAR, FaceLandmark.RIGHT_EAR
        )
        val features = mutableListOf<Float>()
        for (lmId in lmIds) {
            val pt = face.getLandmark(lmId)?.position
            if (pt != null) { features.add((pt.x - box.left) / w); features.add((pt.y - box.top) / h) }
            else { features.add(0f); features.add(0f) }
        }
        return if (features.any { it != 0f }) features.toFloatArray() else null
    }

    // ==========================================
    // 📷 KAMERA
    // ==========================================
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview  = Preview.Builder().build().also { it.setSurfaceProvider(cameraPreview.surfaceProvider) }
            val opts = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
            val detector = FaceDetection.getClient(opts)
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(cameraExecutor) { proxy -> processImageProxy(detector, proxy) } }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analyzer)
            } catch (e: Exception) { Log.e("PayroCamera", "Kamera xətası", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(detector: FaceDetector, proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) { proxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        detector.process(image).addOnSuccessListener { faces ->
            if (faces.isEmpty()) {
                noFaceCount++
                if (noFaceCount >= NO_FACE_LIMIT) onCustomerLeft()
            } else {
                noFaceCount = 0
                val face = faces.maxByOrNull { it.boundingBox.width() } ?: return@addOnSuccessListener
                val sig = extractSignature(face)
                if (sig != null) {
                    lastKnownSig = sig
                    if (currentFaceId == null) {
                        val matchId = findMatchingFace(sig)
                        if (matchId != null) {
                            currentFaceId = matchId; isReturningCustomer = true
                            runOnUiThread { tvHeader.text = "P A Y R O  👋" }
                        } else {
                            currentFaceId = "f_${System.currentTimeMillis()}"; isReturningCustomer = false
                        }
                    }
                }
                val smiling = (face.smilingProbability ?: 0f) > 0.55f
                if (smiling != isCustomerSmiling) {
                    isCustomerSmiling = smiling
                    runOnUiThread { tvHeader.text = "P A Y R O  ${if (smiling) "😊" else "😐"}" }
                }
            }
        }.addOnCompleteListener { proxy.close() }
    }

    private fun onCustomerLeft() {
        val sig = lastKnownSig ?: return
        val id  = currentFaceId ?: return
        addOrUpdateFace(id, sig)
        lastKnownSig = null; currentFaceId = null
        isReturningCustomer = false; noFaceCount = 0
        runOnUiThread { tvHeader.text = "P A Y R O  😐" }
    }

    // ==========================================
    // YARDIMÇI
    // ==========================================
    private fun initConversationHistory() {
        conversationHistory = JSONArray()
        conversationHistory.put(buildSystemMessage())
    }

    private fun createVoiceVisualizerBars(count: Int) {
        visualizerContainer.removeAllViews(); voiceBars.clear()
        repeat(count) {
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(16, 10).apply { setMargins(8, 0, 8, 0) }
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bar_bg)
            }
            visualizerContainer.addView(bar); voiceBars.add(bar)
        }
    }

    private fun animateVisualizerRandomly() {
        if (currentState != RobotState.LISTENING) return
        runOnUiThread { voiceBars.forEach { val p = it.layoutParams; p.height = (20..130).random(); it.layoutParams = p } }
    }

    private fun updateUI(s: String) = runOnUiThread { tvResponse.text = s }
    private fun hasWakeWord(t: String)   = t.contains("payro") || t.contains("peyro") || t.contains("pajro")
    private fun stripWakeWord(t: String) = t.replace(Regex("(hey\\s+)?(payro|peyro|pajro)"), "").trim()

    private fun silenceDelayFor(text: String): Long {
        val wordCount = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        return when {
            wordCount <= 2 -> SILENCE_SHORT_MS
            wordCount <= 4 -> SILENCE_NORMAL_MS
            else           -> SILENCE_LONG_MS
        }
    }

    private fun isEnoughWords(text: String): Boolean {
        return text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size >= MIN_WORDS_TO_SEND
    }

    // ==========================================
    // 👂 AZURE STT
    // ==========================================
    private fun initAzureEar() {
        try {
            updateUI("Sistem hazırlanır...")

            speechConfig = SpeechConfig.fromSubscription(AZURE_API_KEY, AZURE_REGION).apply {
                speechRecognitionLanguage = "az-AZ"
                setProperty(PropertyId.SpeechServiceConnection_InitialSilenceTimeoutMs, "5000")
                setProperty(PropertyId.SpeechServiceConnection_EndSilenceTimeoutMs,     "3000")
                speechSynthesisLanguage  = "az-AZ"
                speechSynthesisVoiceName = "az-AZ-BabekNeural"
            }

            // ✅ TTS: AudioConfig.fromDefaultSpeakerOutput() — dinamikin açıq olduğunu təmin edir
            val speakerConfig = AudioConfig.fromDefaultSpeakerOutput()
            speechSynthesizer = SpeechSynthesizer(speechConfig, speakerConfig)

            speechSynthesizer?.SynthesisCompleted?.addEventListener { _, e ->
                Log.d("PayroTTS", "✅ TTS tamamlandı, audio uzunluğu: ${e.result.audioLength}")
                unmuteAndRestartRecognizer()
            }
            speechSynthesizer?.SynthesisCanceled?.addEventListener { _, e ->
                val detail = SpeechSynthesisCancellationDetails.fromResult(e.result)
                Log.e("PayroTTS", "❌ TTS ləğv edildi: ${detail.reason} | ${detail.errorDetails}")
                unmuteAndRestartRecognizer()
            }
            speechSynthesizer?.SynthesisStarted?.addEventListener { _, _ ->
                Log.d("PayroTTS", "▶️ TTS başladı")
            }

            buildRecognizer()
            speechRecognizer?.startContinuousRecognitionAsync()
            resetToSleep()

        } catch (ex: Exception) {
            updateUI("🚨 Azure Xətası: ${ex.message}")
        }
    }

    private fun buildRecognizer() {
        try { speechRecognizer?.close() } catch (_: Exception) {}

        val audioConfig = AudioConfig.fromDefaultMicrophoneInput()
        speechRecognizer = SpeechRecognizer(speechConfig, audioConfig)

        val phraseList = PhraseListGrammar.fromRecognizer(speechRecognizer!!)
        phraseList.addPhrase("Payro")
        phraseList.addPhrase("Hey Payro")
        phraseList.addPhrase("Peyro")
        phraseList.addPhrase("Pajro")

        speechRecognizer?.recognizing?.addEventListener { _, e ->
            if (isMicMuted) return@addEventListener
            val text = e.result.text.lowercase().trim()
            if (text.isEmpty()) return@addEventListener

            if (currentState == RobotState.BUSY) {
                if (interruptWords.any { text.contains(it) }) {
                    speechSynthesizer?.StopSpeakingAsync()
                    ttsTimeoutRunnable?.let { ttsTimeoutHandler.removeCallbacks(it) }
                    muteAndStopRecognizer()
                    mainHandler.postDelayed({
                        isMicMuted = false
                        speechRecognizer?.startContinuousRecognitionAsync()
                        wakeUpAndListen()
                    }, 300L)
                }
                return@addEventListener
            }

            animateVisualizerRandomly()
            silenceRunnable?.let { silenceHandler.removeCallbacks(it) }

            when (currentState) {
                RobotState.SLEEPING -> {
                    if (hasWakeWord(text) && !wakeWordTriggered) {
                        val cmd = stripWakeWord(text)
                        if (cmd.isNotEmpty()) {
                            wakeWordTriggered = true
                            currentCommand = cmd
                            startSilenceTimer(cmd)
                        } else {
                            wakeWordTriggered = true
                            wakeUpAndGreet()
                        }
                    }
                }
                RobotState.LISTENING -> {
                    if (hasWakeWord(text)) {
                        val cmd = stripWakeWord(text)
                        if (cmd.isNotEmpty()) { currentCommand = cmd; startSilenceTimer(cmd) }
                        else wakeUpAndGreet()
                    } else {
                        currentCommand = text
                        runOnUiThread { etInput.setText(currentCommand) }
                        startSilenceTimer(text)
                    }
                }
                else -> {}
            }
        }

        speechRecognizer?.recognized?.addEventListener { _, e ->
            if (isMicMuted || currentState == RobotState.BUSY) return@addEventListener
            if (e.result.reason != ResultReason.RecognizedSpeech) return@addEventListener
            val text = e.result.text.lowercase().trim()
            if (silenceRunnable != null) return@addEventListener

            when (currentState) {
                RobotState.SLEEPING -> {
                    if (hasWakeWord(text) && !wakeWordTriggered) {
                        wakeWordTriggered = true
                        val cmd = stripWakeWord(text)
                        if (cmd.isNotEmpty() && isEnoughWords(cmd)) { currentCommand = cmd; processCommand() }
                        else if (cmd.isEmpty()) wakeUpAndGreet()
                    }
                }
                RobotState.LISTENING -> {
                    if (hasWakeWord(text)) {
                        val cmd = stripWakeWord(text)
                        if (cmd.isNotEmpty()) { currentCommand = cmd; startNewConversationAndProcess() }
                        else wakeUpAndGreet()
                    } else if (isEnoughWords(text)) {
                        currentCommand = text.trim(); processCommand()
                    }
                }
                else -> {}
            }
        }

        speechRecognizer?.canceled?.addEventListener { _, e ->
            if (e.reason == CancellationReason.Error) {
                runOnUiThread { Toast.makeText(this, "Azure Xəta: İnternet yoxdur!", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ==========================================
    // ⏱ SÜKUT TAYMERİ
    // ==========================================
    private fun startSilenceTimer(currentText: String) {
        silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
        val delay = silenceDelayFor(currentText)

        val runnable = Runnable {
            silenceRunnable = null
            val cmd = currentCommand.trim()
            if (cmd.isNotEmpty() && isEnoughWords(cmd) &&
                (currentState == RobotState.LISTENING || currentState == RobotState.SLEEPING)) {
                val cleanCmd = stripWakeWord(cmd)
                if (cleanCmd.isNotEmpty()) {
                    currentCommand = cleanCmd
                    if (isInConversation) processCommand() else startNewConversationAndProcess()
                } else {
                    wakeUpAndGreet()
                }
            }
        }
        silenceRunnable = runnable
        silenceHandler.postDelayed(runnable, delay)
    }

    // ==========================================
    // REJİMLƏR
    // ==========================================
    private fun resetToSleep() {
        currentState      = RobotState.SLEEPING
        isInConversation  = false
        currentCommand    = ""
        wakeWordTriggered = false
        silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
        silenceRunnable = null
        ttsTimeoutRunnable?.let { ttsTimeoutHandler.removeCallbacks(it) }
        ttsTimeoutRunnable = null
        speechSynthesizer?.StopSpeakingAsync()
        runOnUiThread {
            tvResponse.text = "Gözləyirəm... ('Hey Payro' deyin)"
            visualizerContainer.visibility = View.GONE
            etInput.text.clear()
        }
    }

    private fun wakeUpAndGreet() {
        if (currentState == RobotState.BUSY) return
        currentState     = RobotState.BUSY
        isInConversation = true
        initConversationHistory()
        silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
        silenceRunnable = null

        val greeting = if (isReturningCustomer)
            "Yenidən xoş gəldiniz! Necə kömək edə bilərəm?"
        else
            "Buyrun, necə kömək edə bilərəm?"

        runOnUiThread { tvResponse.text = greeting; visualizerContainer.visibility = View.GONE }
        generateCloudVoice(greeting)
    }

    private fun startNewConversationAndProcess() {
        currentState     = RobotState.BUSY
        isInConversation = true
        initConversationHistory()
        silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
        silenceRunnable = null
        runOnUiThread { tvResponse.text = "Payro düşünür... 🧠"; visualizerContainer.visibility = View.GONE }
        askPayroBrain(currentCommand)
    }

    private fun wakeUpAndListen() {
        currentState      = RobotState.LISTENING
        currentCommand    = ""
        wakeWordTriggered = false
        silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
        silenceRunnable = null
        runOnUiThread {
            tvResponse.text = "Payro sizi dinləyir... 🎧"
            visualizerContainer.visibility = View.VISIBLE
        }
    }

    private fun processCommand() {
        currentState = RobotState.BUSY
        silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
        silenceRunnable = null
        val cmd = if (currentCommand.isEmpty()) "Salam" else stripWakeWord(currentCommand)
        runOnUiThread { tvResponse.text = "Payro düşünür... 🧠"; visualizerContainer.visibility = View.GONE }
        askPayroBrain(cmd)
    }

    // ==========================================
    // 🧠 BEYİN (GROQ)
    // ==========================================
    private fun askPayroBrain(question: String) {
        val ctx = buildString {
            if (isReturningCustomer) append("[Bu müştəri əvvəl mağazaya gəlib] ")
            if (isCustomerSmiling)   append("[Müştəri gülümsəyir] ")
        }
        conversationHistory.put(JSONObject().apply { put("role", "user"); put("content", ctx + question) })

        val body = JSONObject().apply {
            put("model", "llama-3.1-8b-instant")
            put("messages", conversationHistory)
            put("temperature", 0.6)
            put("max_tokens", 180)
        }

        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $GROQ_API_KEY")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(applicationContext, "İnternet Yoxdur!", Toast.LENGTH_SHORT).show()
                    resumeListening()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val data = response.body?.string()
                if (!response.isSuccessful || data == null) { runOnUiThread { resumeListening() }; return }
                try {
                    var answer = JSONObject(data)
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()

                    var motorCmd = ""
                    val m = Regex("\\[([FBLRS])\\]").find(answer)
                    if (m != null) {
                        motorCmd = m.groupValues[1]
                        answer = answer.replace(Regex("\\[([FBLRS])\\]"), "").trim()
                    }

                    conversationHistory.put(JSONObject().apply { put("role", "assistant"); put("content", answer) })

                    runOnUiThread {
                        tvResponse.text = answer
                        if (motorCmd.isNotEmpty())
                            Toast.makeText(this@MainActivity, "🤖 Arduino: '$motorCmd'", Toast.LENGTH_SHORT).show()
                    }

                    if (answer.isNotEmpty()) generateCloudVoice(answer)
                    else runOnUiThread { resumeListening() }

                } catch (e: Exception) {
                    Log.e("PayroBrain", "JSON parse xətası: ${e.message}")
                    runOnUiThread { resumeListening() }
                }
            }
        })
    }

    // ==========================================
    // 🔤 TTS MƏTNİ — Markalar Azərbaycanca
    // ==========================================
    private fun preprocessTTS(text: String): String = text
        .replace("&", "və").replace("%", "faiz")
        .replace(Regex("(?i)iphone (\\d+) pro max"), "Ay fon $1 pro maks")
        .replace(Regex("(?i)iphone (\\d+) pro"),     "Ay fon $1 pro")
        .replace(Regex("(?i)iphone (\\d+)"),         "Ay fon $1")
        .replace(Regex("(?i)iphone"),                "Ay fon")
        .replace(Regex("(?i)ipad pro"),              "Ay ped pro")
        .replace(Regex("(?i)ipad"),                  "Ay ped")
        .replace(Regex("(?i)macbook pro"),           "Mek buk pro")
        .replace(Regex("(?i)macbook air"),           "Mek buk er")
        .replace(Regex("(?i)macbook"),               "Mek buk")
        .replace(Regex("(?i)airpods pro"),           "Er pods pro")
        .replace(Regex("(?i)airpods"),               "Er pods")
        .replace(Regex("(?i)apple watch"),           "Epl voç")
        .replace(Regex("(?i)imac"),                  "Ay mek")
        .replace(Regex("(?i)apple"),                 "Epl")
        .replace(Regex("(?i)samsung galaxy"),        "Samsunq qalaksi")
        .replace(Regex("(?i)samsung"),               "Samsunq")
        .replace(Regex("(?i)xiaomi"),                "Şaomi")
        .replace(Regex("(?i)redmi"),                 "Redmi")
        .replace(Regex("(?i)poco"),                  "Poko")
        .replace(Regex("(?i)huawei"),                "Huavey")
        .replace(Regex("(?i)honor"),                 "Honor")
        .replace(Regex("(?i)oneplus"),               "Van plas")
        .replace(Regex("(?i)realme"),                "Rilmi")
        .replace(Regex("(?i)oppo"),                  "Oppo")
        .replace(Regex("(?i)google pixel"),          "Quql piksel")
        .replace(Regex("(?i)motorola"),              "Motorola")
        .replace(Regex("(?i)nokia"),                 "Nokia")
        .replace(Regex("(?i)lenovo"),                "Lenovo")
        .replace(Regex("(?i)asus"),                  "Asus")
        .replace(Regex("(?i)acer"),                  "Eyser")
        .replace(Regex("(?i)microsoft surface"),     "Maykrosoft sörfis")
        .replace(Regex("(?i)microsoft"),             "Maykrosoft")
        .replace(Regex("(?i)dell"),                  "Del")
        .replace(Regex("(?i)sony"),                  "Soni")
        .replace(Regex("(?i)\\blg\\b"),              "El ci")
        .replace(Regex("(?i)\\bjbl\\b"),             "Cey bi el")
        .replace(Regex("(?i)bose"),                  "Boz")
        .replace(Regex("(?i)philips"),               "Filips")
        .replace(Regex("(?i)google"),                "Quql")
        .replace(Regex("(?i)android"),               "Endroid")
        .replace(Regex("(?i)windows (\\d+)"),        "Vindovs $1")
        .replace(Regex("(?i)windows"),               "Vindovs")
        .replace(Regex("(?i)wi-fi|wifi"),            "Vay fay")
        .replace(Regex("(?i)bluetooth"),             "Bluetut")
        .replace(Regex("(?i)usb-c"),                 "yu es bi si")
        .replace(Regex("(?i)usb"),                   "yu es bi")
        .replace(Regex("(?i)hdmi"),                  "eych di em ay")
        .replace(Regex("(?i)oled"),                  "o led")
        .replace(Regex("(?i)amoled"),                "amoled")
        .replace(Regex("(?i)\\bram\\b"),             "rem")
        .replace(Regex("(?i)\\bgpu\\b"),             "ci pi yu")
        .replace(Regex("(?i)\\bcpu\\b"),             "si pi yu")
        .replace(Regex("(?i)\\bhp\\b"),              "eyç pi")
        .replace(Regex("(?i)youtube"),               "Yutub")
        .replace(Regex("(?i)netflix"),               "Netfliks")
        .replace(Regex("(?i)instagram"),             "İnstaqram")
        .replace(Regex("(?i)whatsapp"),              "Vatsap")

    // ==========================================
    // 🔊 AZURE TTS — DÜZƏLDİLMİŞ
    // ==========================================
    private fun generateCloudVoice(text: String) {
        val processed = preprocessTTS(text)

        // ✅ 1. Mikrofonu TAM sustur (aks seda üçün)
        muteAndStopRecognizer()

        // ✅ 2. Sadə SSML — mstts:express-as YOX (az-AZ-BabekNeural dəstəkləmir, səssizcə uğursuz olurdu)
        val ssml = """
            <speak version='1.0' xml:lang='az-AZ'>
              <voice name='az-AZ-BabekNeural'>
                <prosody rate='+10%' pitch='+2%' volume='x-loud'>
                  $processed
                </prosody>
              </voice>
            </speak>
        """.trimIndent()

        // ✅ 3. Timeout qoru — 20 saniyədə TTS cavab verməsə mikrofonu özü açır
        ttsTimeoutRunnable?.let { ttsTimeoutHandler.removeCallbacks(it) }
        ttsTimeoutRunnable = Runnable {
            Log.w("PayroTTS", "⚠️ TTS timeout — mikrofon məcburi açılır")
            unmuteAndRestartRecognizer()
        }
        ttsTimeoutHandler.postDelayed(ttsTimeoutRunnable!!, TTS_TIMEOUT_MS)

        // ✅ 4. Async çağır, nəticəni ayrı thread-də yoxla
        try {
            val future = speechSynthesizer?.SpeakSsmlAsync(ssml)

            Thread {
                try {
                    val result = future?.get()   // blocking — nəticəni gözlə
                    when (result?.reason) {
                        ResultReason.SynthesizingAudioCompleted -> {
                            Log.d("PayroTTS", "✅ Audio uğurla sintez edildi")
                            // SynthesisCompleted eventi artıq unmuteAndRestartRecognizer çağırır
                        }
                        ResultReason.Canceled -> {
                            val detail = SpeechSynthesisCancellationDetails.fromResult(result)
                            Log.e("PayroTTS", "❌ Sintez uğursuz: ${detail.reason} | ${detail.errorDetails}")
                            // SynthesisCanceled eventi artıq işləyir
                        }
                        else -> {
                            Log.w("PayroTTS", "⚠️ Naməlum nəticə: ${result?.reason}")
                            runOnUiThread { unmuteAndRestartRecognizer() }
                        }
                    }
                } catch (ex: Exception) {
                    Log.e("PayroTTS", "Future.get() xətası: ${ex.message}")
                    runOnUiThread { unmuteAndRestartRecognizer() }
                }
            }.start()

        } catch (e: Exception) {
            Log.e("PayroTTS", "SpeakSsmlAsync xətası: ${e.message}")
            ttsTimeoutRunnable?.let { ttsTimeoutHandler.removeCallbacks(it) }
            isMicMuted = false
            runOnUiThread { resumeListening() }
        }
    }

    private fun resumeListening() {
        if (isInConversation) wakeUpAndListen()
        else resetToSleep()
    }
}