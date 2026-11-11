package com.naffith.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.naffith.app.core.ArabicText
import com.naffith.app.core.Command
import com.naffith.app.core.ChoiceMemory
import com.naffith.app.core.CommandParser
import com.naffith.app.core.Contact
import com.naffith.app.core.ContactMatcher
import com.naffith.app.core.MatchResult
import com.naffith.app.core.Places
import com.naffith.app.core.ScreenWords
import kotlinx.coroutines.launch

/**
 * الشاشة الرئيسية. [VoicePopupActivity] يستخدم نفس المنطق بس كنافذة صغيرة تطلع لما
 * تنادي «يا مرسال» أو تضغط زر المساعد.
 */
open class MainActivity : AppCompatActivity() {

    /** نافذة المساعد الصغيرة؟ (ترد بالصوت وتسكّر نفسها بعد التنفيذ). */
    protected open val isPopup = false
    protected open val layoutRes = R.layout.activity_main

    private lateinit var transcriptText: TextView
    private lateinit var statusText: TextView
    private lateinit var micButton: ImageButton
    /** الكرة الصوتية (تتحرك مع الاستماع والتفكير والكلام). */
    private var orb: VoiceOrbView? = null
    private var answerText: TextView? = null

    /** كم مرة ما فهمنا في نافذة المساعد (نوقف بعد مرتين). */
    private var unclearCount = 0

    private lateinit var launcher: AppLauncher
    private lateinit var contactsRepository: ContactsRepository
    private lateinit var choiceMemory: ChoiceMemory

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    /** أي محرك تعرف نستخدم (من [VoicePrefs.recognizerCandidates])؛ يزيد إذا المحرك ما يدعم العربي. */
    private var recognizerIndex = 0

    /** إذا سألنا المستخدم سؤال (مثل «على مين تتصل؟») فالجواب الجاي يروح هنا بدل المحلل. */
    private var followUp: ((String) -> Command)? = null

    /** جينا من «يا مرسال»: نرد ونسأل بالصوت. */
    private var voiceMode = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<String, (() -> Unit)?>? = null
    private val utteranceCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    // ---------- الأذونات: نطلبها وقت الحاجة فقط ----------

    private var permissionCallback: ((Boolean) -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cb = permissionCallback
            permissionCallback = null
            cb?.invoke(granted)
        }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun withPermission(permission: String, onDenied: () -> Unit, onGranted: () -> Unit) {
        if (hasPermission(permission)) {
            onGranted()
            return
        }
        permissionCallback = { granted -> if (granted) onGranted() else onDenied() }
        permissionLauncher.launch(permission)
    }

    /** بعد الرفض: إذا النظام ما عاد يعرض نافذة الإذن، نوجّه المستخدم للإعدادات. */
    private fun handleDenied(permission: String, softMessage: Int, permanentMessage: Int) {
        setStatus(getString(softMessage))
        if (!shouldShowRequestPermissionRationale(permission)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perm_permanent_title)
                .setMessage(permanentMessage)
                .setPositiveButton(R.string.open_settings) { _, _ -> openAppSettings() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    // ---------- دورة حياة الشاشة ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (shouldOpenAsAssistant(intent)) {
            // أيقونة «مرسال» (أو زر الجنب) تفتح نافذة المساعد على طول.
            startActivity(Intent(this, VoicePopupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }
        setContentView(layoutRes)

        transcriptText = findViewById(R.id.transcriptText)
        statusText = findViewById(R.id.statusText)
        micButton = findViewById(R.id.micButton)

        launcher = AppLauncher(this)
        contactsRepository = ContactsRepository(this)
        choiceMemory = ChoiceMemory(PrefsStore(getSharedPreferences("choices", MODE_PRIVATE)))

        findViewById<android.view.View?>(R.id.settingsButton)?.setOnClickListener {
            if (isPopup) {
                // من النافذة: نفتح التطبيق الكامل (فيه الإعدادات) ونسكّر النافذة.
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .putExtra(EXTRA_FULL_APP, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            } else {
                openingInternalScreen = true
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        answerText = findViewById(R.id.answerText)
        // زر «اسأل Gemini»: يحاول يفتح نافذة Gemini الصوتية، وضغطة مطوّلة تفتح تطبيق Gemini بسؤالك الأخير.
        findViewById<android.view.View?>(R.id.geminiButton)?.apply {
            setOnClickListener {
                stopListeningNow()
                if (!launcher.openGeminiAssistant()) setStatus(getString(R.string.cannot_open))
            }
            setOnLongClickListener {
                stopListeningNow()
                if (!launcher.openGemini(lastQuestion)) setStatus(getString(R.string.cannot_open))
                true
            }
        }
        // زر «اسأل عن الشاشة»: تقول سؤالك، ويروح مع صورة الشاشة اللي كنت فيها.
        findViewById<android.view.View?>(R.id.screenButton)?.setOnClickListener {
            stopListeningNow()
            if (ScreenContext.fresh() == null) {
                showScreenshotHelp()
                return@setOnClickListener
            }
            askAboutScreen = true
            askFollowUp(getString(R.string.ask_screen_prompt)) { Command.Unknown(it) }
        }

        // نجهّز الصوت من بدري عشان الرد يطلع على طول.
        if (isPopup) initTts()

        // أندرويد 15 يرسم التطبيق تحت شريط الحالة والتنقل؛ نبعد المحتوى عنهم عشان ما ينقص.
        val root = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        micButton.setOnClickListener { onMicClicked() }
        orb = findViewById<VoiceOrbView?>(R.id.orbView)?.also { it.setOnClickListener { onMicClicked() } }
    }

    /** فتح من الأيقونة والخيار مفعّل (وما طلب التطبيق الكامل من زر ⚙️ في النافذة). */
    private fun shouldOpenAsAssistant(intent: Intent?): Boolean =
        !isPopup && VoicePrefs.openAsAssistant(this) &&
            intent?.action == Intent.ACTION_MAIN &&
            intent.getBooleanExtra(EXTRA_FULL_APP, false).not()

    /** فتحنا شاشة داخلية (الإعدادات)؛ ما نسكّر الشاشة الرئيسية وراها. */
    private var openingInternalScreen = false

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (isFinishing) return
        if (savedInstanceState == null) handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // التحديثات: فحص في الخلفية كل ٦ ساعات (إشعار)، وكل ما تفتح مرسال نشيك على طول.
        Updater.scheduleBackgroundCheck(this)
        askNotificationsOnce()
        if (isPopup) {
            // في النافذة ما نقطع عليك برسالة؛ لو فيه تحديث يطلع إشعار.
            if (Updater.shouldAutoCheck(this)) lifecycleScope.launch { Updater.notifyIfAvailable(this@MainActivity) }
        } else {
            UpdateDialogs.autoCheck(this)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isListening) recognizer?.cancel()
        if (!awaitingUnlock) cloudJob?.cancel()
        setListening(false)
    }

    override fun onStop() {
        super.onStop()
        if (isPopup) {
            // النافذة الصغيرة تسكّر بعد ما فتحنا التطبيق المطلوب (أو لما يطلع منها المستخدم)،
            // إلا وقت فتح القفل (شاشة البصمة/الرمز تغطيها مؤقتاً).
            // وإلا وقت خريطة تأكيد الموقع (نستنى النتيجة).
            if (!isChangingConfigurations && !awaitingUnlock && !sequenceRunning && onLocationPicked == null) finish()
            return
        }
        // إذا «افتح كنافذة مساعد» مفعّل، نسكّر الشاشة الكاملة لما تطلع منها، عشان المرة الجاية
        // الأيقونة/زر الجنب يفتح النافذة مو هذي الشاشة.
        if (VoicePrefs.openAsAssistant(this) && !isChangingConfigurations && !openingInternalScreen) finish()
        openingInternalScreen = false
        showOverLockScreen(false)
        voiceMode = false
    }

    /** أوامر جاية من وضع «يا مرسال» أو من زر الأوامر الصوتية في السماعة. */
    private fun handleLaunchIntent(intent: Intent?) {
        intent ?: return
        if (isPopup) {
            // النافذة الصغيرة: نرد «هلا بيك، أمرني» بالصوت وبعدها نسمع.
            voiceMode = true
            unclearCount = 0
            showOverLockScreen(true)
            transcriptText.text = ""
            if (!VoicePrefs.speakReplies(this)) {
                // بدون صوت: نسمع على طول، تقدر تنطق الأمر مباشرة.
                setStatus(getString(R.string.status_listening))
                if (!isListening) startListeningWithPermission()
                return
            }
            setStatus(getString(R.string.hf_greeting))
            say(getString(R.string.hf_greeting)) { if (!isListening) startListeningWithPermission() }
            return
        }
        val commands = intent.getStringArrayListExtra(EXTRA_COMMANDS)
        when {
            !commands.isNullOrEmpty() -> {
                intent.removeExtra(EXTRA_COMMANDS)
                followUp = null
                handleSpeech(commands)
            }
            intent.getBooleanExtra(EXTRA_START_LISTENING, false) ||
                intent.action == Intent.ACTION_VOICE_COMMAND || intent.action == Intent.ACTION_ASSIST -> {
                intent.removeExtra(EXTRA_START_LISTENING)
                intent.action = null
                micButton.postDelayed({ if (!isListening) startListeningWithPermission() }, 400)
            }
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ---------- الرد بالصوت (وضع «يا مرسال») ----------

    /** ننطق النص (لو جينا من «يا مرسال»)، وبعد ما نخلص ننفذ [then]. */
    private fun say(text: String, then: (() -> Unit)? = null) {
        if (!voiceMode || !VoicePrefs.speakReplies(this)) {
            then?.let { micButton.postDelayed(it, 400) }
            return
        }
        val engine = tts
        if (engine == null || !ttsReady) {
            pendingSpeech = text to then
            if (engine == null) initTts()
            // إذا محرك الصوت تأخر، ما نخلي المستخدم ينتظر.
            micButton.postDelayed({
                pendingSpeech?.let { (_, cb) -> pendingSpeech = null; cb?.invoke() }
            }, 2500)
            return
        }
        val id = "u" + System.nanoTime()
        utteranceCallbacks[id] = {
            if (!isListening) orb?.state = VoiceOrbView.State.IDLE
            then?.invoke()
        }
        orb?.state = VoiceOrbView.State.SPEAKING
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            // أوضح وأطبع صوت عربي متوفر (أو اللي اختاره المستخدم من الإعدادات).
            VoicePrefs.applyBestVoice(this, engine)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    val cb = utteranceCallbacks.remove(utteranceId) ?: return
                    runOnUiThread(cb)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = onDone(utteranceId)
            })
            ttsReady = true
            pendingSpeech?.let { (text, cb) ->
                pendingSpeech = null
                say(text, cb)
            }
        }
    }

    // ---------- التعرف على الصوت ----------

    private fun onMicClicked() {
        if (cloudJob?.isActive == true) {
            cloudJob?.cancel()
            setListening(false)
            return
        }
        if (isListening) {
            recognizer?.stopListening()
            return
        }
        followUp = null
        startListeningWithPermission()
    }

    private fun startListeningWithPermission() {
        // الجوال مقفول ومعنا مفتاح Gemini: نسمع بنفسنا ونخلي Gemini يفهم، لأن محرك Google
        // ما يسمع فوق القفل.
        if (isKeyguardLocked() && GeminiClient.apiKey(this).isNotBlank() &&
            hasPermission(Manifest.permission.RECORD_AUDIO)
        ) {
            startCloudListening()
            return
        }
        // نافذة طلب الإذن ما تطلع فوق القفل (تنرفض لحالها)، فإذا الإذن يحتاج طلب والجوال مقفول
        // نطلب فتح القفل أول. يصير هذا إذا الإذن «اسأل في كل مرة».
        if (!hasPermission(Manifest.permission.RECORD_AUDIO) && isKeyguardLocked() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            setStatus(getString(R.string.unlock_to_allow_mic))
            awaitingUnlock = true
            (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        awaitingUnlock = false
                        micButton.postDelayed({ if (!isFinishing) startListeningWithPermission() }, 300)
                    }
                    override fun onDismissCancelled() {
                        awaitingUnlock = false
                        setStatus(getString(R.string.unlock_cancelled))
                    }
                    override fun onDismissError() {
                        awaitingUnlock = false
                        setStatus(getString(R.string.unlock_cancelled))
                    }
                }
            )
            return
        }
        withPermission(
            Manifest.permission.RECORD_AUDIO,
            onDenied = {
                handleDenied(Manifest.permission.RECORD_AUDIO, R.string.perm_mic_denied, R.string.perm_permanent_mic)
            },
            onGranted = { startListening() }
        )
    }

    private fun startListening() {
        if (!VoicePrefs.isRecognitionAvailable(this)) {
            setStatus(getString(R.string.speech_unavailable))
            return
        }
        val sr = recognizer ?: VoicePrefs.createRecognizer(this, recognizerIndex).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // نعطيه وقت يفكر: ما ينهي الاستماع على أول سكتة.
            when (VoicePrefs.patience(this@MainActivity)) {
                1 -> {
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                }
                2 -> {
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2500L)
                }
            }
        }
        setListening(true)
        if (followUp == null && continuation == null) transcriptText.text = ""
        sr.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = setStatusIfIdle(getString(R.string.status_listening))
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val scale = 1f + (rmsdB.coerceIn(0f, 10f) / 50f)
            micButton.scaleX = scale
            micButton.scaleY = scale
            orb?.setLevel(rmsdB)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() = setStatus(getString(R.string.status_processing))
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
            transcriptText.text = getString(R.string.heard_format, partial)
        }

        override fun onResults(results: Bundle?) {
            setListening(false)
            val heardNow = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            // كان يكمّل جملة قبل: نلصق الكلام الجديد على القديم.
            val prefix = continuation
            continuation = null
            val alternatives = if (prefix != null) {
                val more = heardNow.filter { it.isNotBlank() }
                if (more.isEmpty()) prefix else prefix.map { p -> "$p ${more.first()}" } + more.drop(1).map { "${prefix.first()} $it" }
            } else heardNow
            if (alternatives.none { it.isNotBlank() }) {
                setStatus(getString(R.string.err_no_match))
                closePopupSoon()
                return
            }
            // وقف في نص الجملة («… و» / «… على»)؟ نستناه يكمّل بدل ما ننفّذ نص أمر.
            if (prefix == null && followUp == null && looksUnfinished(alternatives.first())) {
                continuation = alternatives
                transcriptText.text = getString(R.string.heard_format, alternatives.first())
                setStatus(getString(R.string.continue_speaking))
                startListening()
                return
            }
            handleSpeech(alternatives)
        }

        override fun onError(error: Int) {
            setListening(false)
            // كنا نستناه يكمّل وسكت: ننفّذ اللي قاله.
            continuation?.let { saved ->
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    continuation = null
                    handleSpeech(saved)
                    return
                }
                continuation = null
            }
            // المحرك ما يدعم العربي؟ نجرّب المحرك اللي بعده بصمت.
            if ((error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE) &&
                recognizerIndex + 1 < VoicePrefs.recognizerCandidates(this@MainActivity).size
            ) {
                recognizerIndex++
                recognizer?.destroy()
                recognizer = null
                startListening()
                return
            }
            // «ما عندك إذن» وإذن المايك موجود = الجوال مقفول، ومحرك Google ما يسمع فوق القفل.
            if (error in setOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE) &&
                hasPermission(Manifest.permission.RECORD_AUDIO) && isKeyguardLocked()
            ) {
                handleLockedRecognizer()
                return
            }
            setStatus(errorMessage(error))
            closePopupSoon()
        }
    }

    // ---------- السماع عن طريق Gemini (والجوال مقفول) ----------

    private var cloudJob: kotlinx.coroutines.Job? = null

    /** نسجّل كلامك لين تسكت، ونرسله لـ Gemini يحوّله نص، وبعدها ننفذه مثل أي أمر. */
    private fun startCloudListening() {
        if (cloudJob?.isActive == true) return
        recognizer?.cancel()
        setListening(true)
        if (followUp == null) transcriptText.text = ""
        cloudJob = lifecycleScope.launch {
            val wav = try {
                CloudListener.record { level -> runOnUiThread { orb?.setLevel(level * 12f - 2f) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            setListening(false)
            if (wav == null) {
                setStatus(getString(R.string.err_no_match))
                closePopupSoon()
                return@launch
            }
            setStatus(getString(R.string.status_processing))
            orb?.state = VoiceOrbView.State.THINKING
            GeminiClient.transcribe(this@MainActivity, wav)
                .onSuccess { text ->
                    orb?.state = VoiceOrbView.State.IDLE
                    if (text.isBlank()) {
                        setStatus(getString(R.string.err_no_match))
                        closePopupSoon()
                    } else {
                        handleSpeech(listOf(text))
                    }
                }
                .onFailure { e ->
                    orb?.state = VoiceOrbView.State.IDLE
                    val err = e as? GeminiClient.ChatError
                    setStatus(getString(err?.userMessage ?: R.string.chat_failed))
                    closePopupSoon()
                }
        }
    }

    /** جرّبنا محركات التعرف الثانية فوق القفل في هالمحاولة؟ */
    private var lockedFallbackTried = false

    private fun isKeyguardLocked(): Boolean =
        (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /**
     * الجوال مقفول: نجرّب أول محرك تعرف ثاني (بعضها، مثل سامسونج، يسمع فوق القفل)، وإذا كلها
     * رفضت نطلب فتح القفل (بصمة/وجه) ونسمع على طول بعده.
     */
    private fun handleLockedRecognizer() {
        if (GeminiClient.apiKey(this).isNotBlank()) {
            startCloudListening()
            return
        }
        val candidates = VoicePrefs.recognizerCandidates(this)
        if (!lockedFallbackTried && recognizerIndex + 1 < candidates.size) {
            recognizerIndex++
            if (recognizerIndex + 1 >= candidates.size) lockedFallbackTried = true
            recognizer?.destroy()
            recognizer = null
            startListening()
            return
        }
        lockedFallbackTried = false
        recognizerIndex = 0
        recognizer?.destroy()
        recognizer = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            setStatus(getString(R.string.unlock_to_listen))
            return
        }
        setStatus(getString(R.string.unlock_to_listen))
        say(getString(R.string.unlock_to_listen_short))
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        awaitingUnlock = true
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                awaitingUnlock = false
                // انفتح القفل: نسمعك على طول.
                micButton.postDelayed({ if (!isListening && !isFinishing) startListeningWithPermission() }, 300)
            }
            override fun onDismissCancelled() {
                awaitingUnlock = false
                setStatus(getString(R.string.unlock_cancelled))
            }
            override fun onDismissError() {
                awaitingUnlock = false
                setStatus(getString(R.string.unlock_cancelled))
            }
        })
    }

    /** ننتظر المستخدم يفتح القفل؛ النافذة ما تسكّر نفسها خلالها. */
    private var awaitingUnlock = false

    /** النافذة الصغيرة ما تبقى معلّقة إذا ما قلت شي (إلا إذا فيها جواب تقرأه). */
    private fun closePopupSoon() {
        if (!isPopup || answerShown) return
        micButton.postDelayed({
            if (!isListening && followUp == null && !isFinishing) finish()
        }, 4000)
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.err_no_match)
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> getString(R.string.err_network)
        SpeechRecognizer.ERROR_AUDIO -> getString(R.string.err_audio)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> getString(R.string.err_busy)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> getString(R.string.perm_mic_denied)
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> getString(R.string.err_language)
        SpeechRecognizer.ERROR_CLIENT -> getString(R.string.status_ready)
        else -> getString(R.string.err_generic, error)
    }

    /** جملة وقفت في نصها: كمّلها. */
    private var continuation: List<String>? = null

    private fun looksUnfinished(text: String): Boolean {
        val words = ArabicText.normalize(text).split(" ").filter { it.isNotEmpty() }
        val last = words.lastOrNull() ?: return false
        val hanging = setOf("و", "على", "الى", "ل", "عن", "في", "من", "اللي", "الي", "ثم", "وبعدين", "بعدين", "مع", "عند", "حق", "يا", "او", "بس", "لين")
        return last in hanging || (VoicePrefs.patience(this) == 2 && words.size <= 2 && CommandParser.parse(text) is Command.Unknown)
    }

    // ---------- تنفيذ الأوامر ----------

    private fun handleSpeech(alternatives: List<String>) {
        val pending = followUp
        followUp = null
        // «لا، قصدي …» بعد أمر: ننفّذ الصح ونتعلّمه للمرة الجاية.
        if (pending == null && handleCorrection(alternatives)) return
        // اختصارات المستخدم أول («تقويم أرامكو» ← صورة التقويم).
        if (pending == null) {
            val shortcuts = ShortcutStore(this).all()
            if (shortcuts.isNotEmpty()) {
                for (alt in alternatives.filter { it.isNotBlank() }) {
                    val sc = com.naffith.app.core.ShortcutMatcher.find(alt, shortcuts) ?: continue
                    transcriptText.text = getString(R.string.heard_format, alt.trim())
                    showAnswer(null)
                    remember(alt)
                    runShortcut(sc)
                    return
                }
            }
        }
        val (heard, command) = if (pending != null) {
            val text = alternatives.first { it.isNotBlank() }.trim()
            text to pending(text)
        } else {
            CommandParser.parseBest(alternatives)
        }
        transcriptText.text = getString(R.string.heard_format, heard)
        // ما فهمنا (أو «اتصل عليه» يرجع لشي قبل): Gemini يفهمها ويحوّلها لأوامرنا.
        if (pending == null && needsSmart(command) && GeminiClient.apiKey(this).isNotBlank()) {
            smartHandle(heard, command)
            return
        }
        // أمر جديد (مو سؤال): نشيل جواب السوالف القديم.
        if (command !is Command.Unknown) showAnswer(null)
        if (pending == null && command !is Command.Unknown) remember(heard)
        execute(command)
    }

    private val pronouns = setOf("عليه", "عليها", "عليهم", "له", "لها", "هو", "هي", "اياه", "ياه", "فيه", "هناك", "معه", "معها", "هذا", "هذي", "هاذا")

    /** الاسم ضمير بس («عليه») أو ناقص، ومعنا سياق من قبل. */
    private fun vagueNames(names: List<String>) =
        names.isEmpty() || names.all { n -> ArabicText.normalize(n).split(" ").all { it in pronouns } }

    private fun needsSmart(command: Command): Boolean = when (command) {
        is Command.Unknown -> command.heard.isNotBlank()
        is Command.Call -> SmartIntent.hasContext() && vagueNames(command.contactNames) &&
            (lastPlaceQuery == null || System.currentTimeMillis() - lastPlaceAt > 10 * 60_000)
        is Command.WhatsApp -> SmartIntent.hasContext() && vagueNames(command.contactNames) && command.nameAndMessage.isBlank()
        is Command.CallLater -> SmartIntent.hasContext() && vagueNames(command.contactNames)
        is Command.ShareLocation -> SmartIntent.hasContext() && !command.copyOnly && !command.toLastCaller && vagueNames(command.contactNames)
        else -> false
    }

    /** نسأل Gemini وش يقصد، وننفّذ أوامرنا اللي طلعت. لو ما ضبط: نرجع للي كنا بنسويه. */
    private fun smartHandle(heard: String, original: Command) {
        setStatus(getString(R.string.smart_thinking))
        orb?.state = VoiceOrbView.State.THINKING
        lifecycleScope.launch {
            val plan = SmartIntent.plan(this@MainActivity, heard)
            orb?.state = VoiceOrbView.State.IDLE
            val fallback = {
                if (original !is Command.Unknown) remember(heard)
                execute(original)
            }
            when {
                plan == null -> fallback()
                plan.callPlace.isNotBlank() -> {
                    remember(heard)
                    callPlace(plan.callPlace)
                }
                plan.isQuestion || plan.commands.isEmpty() -> fallback()
                else -> {
                    val cmds = plan.commands.map { CommandParser.parse(it) }.filter { it !is Command.Unknown }
                    if (cmds.isEmpty()) {
                        fallback()
                    } else {
                        showAnswer(null)
                        remember(heard)
                        transcriptText.text = getString(R.string.heard_format, heard) + "\n→ " + plan.commands.joinToString(" · ")
                        execute(if (cmds.size == 1) cmds.first() else Command.Sequence(cmds))
                    }
                }
            }
        }
    }

    /** «اتصل على العبير للاتصالات»: نلقى رقم المكان، ونتأكد منك قبل الاتصال. */
    private fun callPlace(place: String) {
        SmartIntent.remember("اتصال على محل/مكان: $place")
        if (GeminiClient.apiKey(this).isBlank()) {
            // بدون مفتاح Gemini ما نقدر ندوّر الرقم: نفتح المحل في الخرائط وتتصل من صفحته.
            execute(Command.OpenMaps(place))
            setStatus(getString(R.string.smart_no_phone, place))
            return
        }
        setStatus(getString(R.string.smart_finding_phone, place))
        orb?.state = VoiceOrbView.State.THINKING
        // موقعك التقريبي (إذا فيه إذن) عشان يلقى أقرب فرع.
        val near = if (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) || hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            runCatching {
                val lm = getSystemService(android.location.LocationManager::class.java)
                listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER)
                    .firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                    ?.let { String.format(java.util.Locale.US, "%.4f,%.4f", it.latitude, it.longitude) }
            }.getOrNull()
        } else null
        lifecycleScope.launch {
            val phone = SmartIntent.findPhone(this@MainActivity, place, near)
            orb?.state = VoiceOrbView.State.IDLE
            if (isFinishing) return@launch
            if (phone == null) {
                execute(Command.OpenMaps(place))
                setStatus(getString(R.string.smart_no_phone, place))
                return@launch
            }
            val title = getString(R.string.smart_call_place_title, phone.name)
            setStatus(title)
            SmartIntent.remember("لقيت رقم ${phone.name}: ${phone.number}")
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(title)
                .setMessage(getString(R.string.smart_call_place_msg, phone.number))
                .setPositiveButton(R.string.smart_call) { _, _ -> placeCall(Contact("", phone.name, phone.number)) }
                .setNeutralButton(R.string.smart_open_maps) { _, _ -> execute(Command.OpenMaps(place)) }
                .setNegativeButton(R.string.cancel) { _, _ -> setStatus(getString(R.string.status_ready)) }
                .show()
            if (voiceMode && VoicePrefs.speakReplies(this@MainActivity)) say(title)
        }
    }

    /** وصف قصير للأمر عشان السياق («فتحت الخرائط على العبير»). */
    private fun describe(command: Command): String = when (command) {
        is Command.OpenMaps -> (if (command.navigate) "ملاحة إلى: " else "فتح الخرائط على: ") + command.query
        is Command.Call -> "اتصال على: " + command.contactNames.firstOrNull().orEmpty()
        is Command.WhatsApp -> "واتساب: " + command.contactNames.firstOrNull().orEmpty() + " " + command.message
        is Command.SearchYouTube -> "يوتيوب: " + command.query
        is Command.OpenApp -> "فتح تطبيق: " + command.appName + " " + command.query
        is Command.CallLater -> "تذكير اتصال على: " + command.contactNames.firstOrNull().orEmpty()
        is Command.ShareLocation -> "إرسال الموقع"
        is Command.Device -> "تحكم في الجوال: " + command.action.name + " " + command.label
        is Command.Remind -> "تذكير: " + command.label
        is Command.Unknown -> "سؤال: " + command.heard
        else -> command.javaClass.simpleName
    }

    /** نتذكر آخر أمر نفّذناه عشان لو قال بعده «لا قصدي …» نعرف وش نصحّح. */
    private fun remember(heard: String) {
        lastHeard = heard.trim()
        lastAt = System.currentTimeMillis()
    }

    /** يرجع true إذا الكلام تصحيح لآخر أمر (وتعامل معه). */
    private fun handleCorrection(alternatives: List<String>): Boolean {
        val previous = lastHeard ?: return false
        if (System.currentTimeMillis() - lastAt > CORRECTION_WINDOW_MS) return false
        val text = alternatives.firstOrNull { it.isNotBlank() }?.trim() ?: return false
        val corrected = com.naffith.app.core.Correction.parse(text) ?: return false
        // «لا» لحالها ممكن تكون بداية أمر عادي («لا تزعجني»): نخليه أمر.
        if (!com.naffith.app.core.Correction.isStrong(text) && corrected.isNotEmpty() &&
            CommandParser.parse(text) !is Command.Unknown
        ) return false
        transcriptText.text = getString(R.string.heard_format, text)
        showAnswer(null)
        if (corrected.isEmpty()) {
            // قال «غلط» بس: نسأله وش كان يقصد ونتعلّم من جوابه.
            askFollowUp(getString(R.string.correction_ask)) { answer ->
                learnCorrection(previous, answer)
                CommandParser.parseBest(listOf(answer)).second
            }
            return true
        }
        learnCorrection(previous, corrected)
        val (_, command) = CommandParser.parseBest(listOf(corrected))
        execute(command)
        return true
    }

    /** نحفظ «اللي قاله» ← «اللي يقصده» كاختصار يشتغل بس على نفس الجملة، ويتعدّل من الإعدادات. */
    private fun learnCorrection(said: String, meant: String) {
        val m = meant.trim()
        if (m.isEmpty() || ArabicText.normalize(m) == ArabicText.normalize(said)) return
        val store = ShortcutStore(this)
        val key = ArabicText.normalize(said).replace(" ", "")
        val existing = store.all().firstOrNull { sc ->
            sc.exact && sc.triggers.any { ArabicText.normalize(it).replace(" ", "") == key }
        }
        store.save(
            com.naffith.app.core.Shortcut(
                id = existing?.id ?: store.newId(),
                triggers = existing?.triggers ?: listOf(said),
                type = com.naffith.app.core.ShortcutType.COMMANDS,
                value = m,
                title = getString(R.string.correction_title),
                exact = true,
            )
        )
        // الأمر الجديد هو اللي ينصحّح لو غلطنا مرة ثانية.
        lastHeard = said
        lastAt = System.currentTimeMillis()
        Toast.makeText(this, getString(R.string.correction_learned, said, m), Toast.LENGTH_LONG).show()
    }

    private fun execute(command: Command) {
        if (command !is Command.Sequence) SmartIntent.remember(describe(command))
        if (command is Command.OpenMaps && command.query.isNotBlank()) {
            lastPlaceQuery = command.query
            lastPlaceAt = System.currentTimeMillis()
        }
        // «اتصل عليه» بعد ما فتحنا محل في الخرائط = اتصل بالمحل.
        val recentPlace = lastPlaceQuery?.takeIf { System.currentTimeMillis() - lastPlaceAt < 10 * 60_000 }
        if (command is Command.Call && CommandParser.isVagueName(command.contactNames) && recentPlace != null) {
            callPlace(recentPlace)
            return
        }
        when (command) {
            is Command.OpenMaps -> if (command.navigate && command.query.isNotBlank()) {
                // «وجهني الى …»: الملاحة تبدأ على طول، و«المنزل» يروح للبيت المحفوظ.
                val target = when (Places.navigationTarget(command.query)) {
                    "Home" -> SavedPlaces.destination(this, SavedPlaces.Kind.HOME).ifBlank { null }
                    "Work" -> SavedPlaces.destination(this, SavedPlaces.Kind.WORK).ifBlank { null }
                    else -> command.query
                }
                if (target == null) {
                    // البيت/الدوام مو محفوظ: نطلب منه يحفظه بدل ما نبحث عن كلمة «منزل».
                    val msg = getString(R.string.places_missing, command.query)
                    setStatus(msg)
                    say(msg)
                } else {
                    setStatus(getString(R.string.navigating_to, command.query))
                    runUnlocked { if (!launcher.navigate(target)) setStatus(getString(R.string.cannot_open)) }
                }
            } else {
                setStatus(
                    if (command.query.isBlank()) getString(R.string.opening_maps)
                    else getString(R.string.searching_maps, command.query)
                )
                runUnlocked { if (!launcher.openMaps(command.query)) setStatus(getString(R.string.cannot_open)) }
            }

            is Command.SearchYouTube ->
                if (command.play && command.query.isNotBlank()) {
                    // «شغل …»: ندوّر وأول نتيجة نشغّلها على طول.
                    setStatus(getString(R.string.playing_youtube, command.query))
                    lifecycleScope.launch {
                        val videoId = YouTubeSearch.firstVideoId(command.query)
                        runUnlocked {
                            val ok = if (videoId != null) launcher.playYouTubeVideo(videoId)
                            else launcher.openYouTube(command.query)
                            if (!ok) setStatus(getString(R.string.cannot_open))
                        }
                    }
                } else {
                    setStatus(
                        if (command.query.isBlank()) getString(R.string.opening_youtube)
                        else getString(R.string.searching_youtube, command.query)
                    )
                    runUnlocked { if (!launcher.openYouTube(command.query)) setStatus(getString(R.string.cannot_open)) }
                }

            is Command.WhatsApp -> when {
                // «اكتب رسالة واتساب محمد الحفة هلا»: نفصل الاسم عن الرسالة حسب جهات الاتصال.
                command.nameAndMessage.isNotBlank() -> withContacts { contacts ->
                    // الأسماء الثانية المحفوظة («روحي» ← أم محمد) تدخل في الفصل بعد.
                    val aliases = choiceMemory.all().map { Contact("", it.spoken, it.number) }
                    val (name, message) = ContactMatcher.splitNameAndMessage(command.nameAndMessage, contacts + aliases)
                    execute(command.copy(contactNames = listOf(name), message = message, nameAndMessage = ""))
                }

                command.contactNames.isEmpty() ->
                    askFollowUp(getString(R.string.ask_whatsapp_name)) {
                        command.copy(contactNames = CommandParser.parseName(it))
                    }

                else -> {
                    val retry = { text: String -> command.copy(contactNames = CommandParser.parseName(text)) }
                    resolveContact(command.contactNames, retry = retry) { contact ->
                        openWhatsApp(contact, command.message, command.send)
                    }
                }
            }

            is Command.Call ->
                if (command.contactNames.isEmpty()) {
                    askFollowUp(getString(R.string.ask_call_name)) { Command.Call(CommandParser.parseName(it)) }
                } else {
                    resolveContact(command.contactNames, retry = { Command.Call(CommandParser.parseName(it)) }) { contact ->
                        placeCall(contact)
                    }
                }

            is Command.OpenApp -> openApp(command)

            is Command.Device -> runDevice(command)

            is Command.Sequence -> runSequence(command.steps)

            is Command.Remind -> scheduleReminder(command)

            is Command.ShareLocation -> shareLocation(command)

            is Command.CallPlace -> callPlace(command.query)

            is Command.LastCaller -> lastCaller(command)

            is Command.CallLater ->
                if (command.contactNames.isEmpty()) {
                    askFollowUp(getString(R.string.ask_call_name)) { command.copy(contactNames = CommandParser.parseName(it)) }
                } else {
                    resolveContact(command.contactNames, retry = { command.copy(contactNames = CommandParser.parseName(it)) }) { contact ->
                        scheduleCallLater(contact, command)
                    }
                }

            is Command.SendScreenshot ->
                resolveContact(command.contactNames, retry = { Command.SendScreenshot(CommandParser.parseName(it)) }) { contact ->
                    sendScreenshotTo(contact)
                }

            // مو أمر؟ سؤال أو سالفة: يجاوبه Gemini (داخل مرسال أو تطبيقه) أو Google، حسب الإعدادات.
            is Command.Unknown -> answerQuestion(command.heard)
        }
    }

    // ---------- الموقع وسجل المكالمات ----------

    private var onLocationPicked: ((Double, Double) -> Unit)? = null
    private val locationPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val cb = onLocationPicked
            onLocationPicked = null
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                cb?.invoke(data.getDoubleExtra(LocationPickActivity.EXTRA_LAT, 0.0), data.getDoubleExtra(LocationPickActivity.EXTRA_LNG, 0.0))
            } else {
                setStatus(getString(R.string.loc_cancelled))
                closePopupSoon()
            }
        }

    /** إذن الموقع (الدقيق، أو التقريبي إذا هذا اللي سمح فيه). */
    private fun withLocation(onGranted: () -> Unit) {
        if (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) || hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            onGranted()
            return
        }
        withPermission(Manifest.permission.ACCESS_FINE_LOCATION, onDenied = {
            if (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) onGranted()
            else handleDenied(Manifest.permission.ACCESS_FINE_LOCATION, R.string.perm_location_denied, R.string.perm_location_denied)
        }, onGranted = onGranted)
    }

    /** نفتح الخريطة للتأكيد، وبعدها [then] بالإحداثيات. */
    private fun pickLocation(title: String, button: String, then: (Double, Double) -> Unit) {
        withLocation {
            onLocationPicked = then
            setStatus(getString(R.string.loc_confirm_status))
            locationPicker.launch(
                Intent(this, LocationPickActivity::class.java)
                    .putExtra(LocationPickActivity.EXTRA_TITLE, title)
                    .putExtra(LocationPickActivity.EXTRA_BUTTON, button)
            )
        }
    }

    private fun locationMessage(lat: Double, lng: Double) =
        getString(R.string.loc_message, LocationPickActivity.link(lat, lng))

    private fun shareLocation(command: Command.ShareLocation) {
        when {
            command.copyOnly -> pickLocation(getString(R.string.loc_title_copy), getString(R.string.loc_copy)) { lat, lng ->
                val clip = getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(android.content.ClipData.newPlainText("location", LocationPickActivity.link(lat, lng)))
                reply(getString(R.string.loc_copied))
            }
            command.toLastCaller -> withLastCall { last ->
                val who = last.name.ifBlank { last.number }
                pickLocation(getString(R.string.loc_title_to, who), getString(R.string.loc_send)) { lat, lng ->
                    openWhatsApp(Contact("", who, last.number), locationMessage(lat, lng), send = true)
                }
            }
            command.contactNames.isEmpty() ->
                askFollowUp(getString(R.string.loc_ask_who)) { Command.ShareLocation(CommandParser.parseName(it)) }
            else -> resolveContact(command.contactNames, retry = { Command.ShareLocation(CommandParser.parseName(it)) }) { contact ->
                pickLocation(getString(R.string.loc_title_to, contact.name), getString(R.string.loc_send)) { lat, lng ->
                    openWhatsApp(contact, locationMessage(lat, lng), send = true)
                }
            }
        }
    }

    /** نقرأ آخر رقم من سجل المكالمات (نطلب الإذن أول مرة). */
    private fun withLastCall(then: (CallLogHelper.LastCall) -> Unit) {
        withPermission(Manifest.permission.READ_CALL_LOG, onDenied = {
            handleDenied(Manifest.permission.READ_CALL_LOG, R.string.perm_calllog_denied, R.string.perm_calllog_denied)
        }) {
            val last = CallLogHelper.last(this)
            if (last == null) reply(getString(R.string.last_none)) else then(last)
        }
    }

    private fun lastCaller(command: Command.LastCaller) = withLastCall { last ->
        val who = last.name.ifBlank { last.number }
        when (command.action) {
            com.naffith.app.core.LastCallerAction.SHOW ->
                reply(getString(R.string.last_show, who, CallLogHelper.ago(last.time)))
            com.naffith.app.core.LastCallerAction.CALL -> placeCall(Contact("", who, last.number))
            com.naffith.app.core.LastCallerAction.WHATSAPP ->
                openWhatsApp(Contact("", who, last.number), command.message, command.send)
            com.naffith.app.core.LastCallerAction.SAVE -> when {
                last.name.isNotBlank() && command.name.isBlank() -> reply(getString(R.string.last_already_saved, last.name))
                command.name.isBlank() -> askFollowUp(getString(R.string.last_ask_name, last.number, CallLogHelper.ago(last.time))) {
                    Command.LastCaller(com.naffith.app.core.LastCallerAction.SAVE, name = it.trim())
                }
                else -> withPermission(Manifest.permission.WRITE_CONTACTS, onDenied = {
                    // بدون إذن: نفتح شاشة إضافة جهة اتصال جاهزة بالاسم والرقم.
                    runCatching {
                        startActivity(
                            Intent(android.provider.ContactsContract.Intents.Insert.ACTION)
                                .setType(android.provider.ContactsContract.RawContacts.CONTENT_TYPE)
                                .putExtra(android.provider.ContactsContract.Intents.Insert.NAME, command.name)
                                .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, last.number)
                        )
                    }
                }) {
                    if (CallLogHelper.saveContact(this, command.name, last.number)) {
                        reply(getString(R.string.last_saved, last.number, command.name))
                    } else {
                        reply(getString(R.string.cannot_open))
                    }
                }
            }
        }
    }

    /** نعرض الرد (ونقوله في وضع الصوت)، وفي النافذة نسكّر بعده. */
    private fun reply(msg: String) {
        setStatus(msg)
        orb?.state = VoiceOrbView.State.IDLE
        if (isPopup) {
            if (voiceMode && VoicePrefs.speakReplies(this)) say(msg) { if (!isFinishing) finish() }
            else micButton.postDelayed({ if (!isFinishing) finish() }, 2600)
        } else if (voiceMode && VoicePrefs.speakReplies(this)) say(msg)
    }

    private var reminderDialog: androidx.appcompat.app.AlertDialog? = null

    /** «ذكرني …»: خيارين، إشعار فوق أو شاشة كاملة. يرد بالصوت أو يضغط. */
    private fun askReminderMode(label: String, atMinutes: Int) {
        val options = arrayOf(getString(R.string.rem_mode_notif), getString(R.string.rem_mode_full))
        reminderDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.rem_mode_title, label))
            .setItems(options) { _, which ->
                stopListeningNow()
                execute(Command.Remind(label, atMinutes, fullScreen = which == 1))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> stopListeningNow(); setStatus(getString(R.string.status_ready)) }
            .show()
        askFollowUp(getString(R.string.rem_mode_ask)) { answer ->
            Command.Remind(label, atMinutes, fullScreen = com.naffith.app.core.ReminderMode.parse(answer) ?: false)
        }
    }

    private fun scheduleReminder(command: Command.Remind) {
        reminderDialog?.dismiss()
        reminderDialog = null
        val doIt = {
            Reminders.scheduleAt(applicationContext, command.label, command.atMinutes, command.fullScreen)
            val time = CallReminder.describe(null, command.atMinutes)
            val msg = getString(
                if (command.fullScreen) R.string.rem_set_full else R.string.rem_set_notif, command.label, time
            )
            setStatus(msg)
            orb?.state = VoiceOrbView.State.IDLE
            if (command.fullScreen && !Reminders.canShowFullScreen(this)) {
                // أندرويد ١٤: الشاشة الكاملة تحتاج إذن (أو خدمة مرسال في إمكانية الوصول).
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.rem_full_perm_title)
                    .setMessage(R.string.rem_full_perm_text)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        runCatching { startActivity(Reminders.fullScreenSettings(this)) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else if (isPopup) {
                if (voiceMode && VoicePrefs.speakReplies(this)) say(msg) { if (!isFinishing) finish() }
                else micButton.postDelayed({ if (!isFinishing) finish() }, 2200)
            } else if (voiceMode && VoicePrefs.speakReplies(this)) say(msg)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            withPermission(Manifest.permission.POST_NOTIFICATIONS, onDenied = { setStatus(getString(R.string.call_later_no_notif)) }) { doIt() }
        } else doIt()
    }

    /** نجهّز إشعار «اتصل / إلغاء» في الوقت اللي قاله. */
    private fun scheduleCallLater(contact: Contact, command: Command.CallLater) {
        val doIt = {
            CallReminder.schedule(applicationContext, contact.name, contact.number, command.delaySeconds, command.atMinutes, command.note)
            val msg = getString(R.string.call_later_set, contact.name, CallReminder.describe(command.delaySeconds, command.atMinutes))
            setStatus(msg)
            orb?.state = VoiceOrbView.State.IDLE
            if (isPopup) {
                if (voiceMode && VoicePrefs.speakReplies(this)) say(msg) { if (!isFinishing) finish() }
                else micButton.postDelayed({ if (!isFinishing) finish() }, 2200)
            } else if (voiceMode && VoicePrefs.speakReplies(this)) say(msg)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            withPermission(Manifest.permission.POST_NOTIFICATIONS, onDenied = { setStatus(getString(R.string.call_later_no_notif)) }) { doIt() }
        } else doIt()
    }

    // ---------- أكثر من أمر ----------

    /** نخلي النافذة مفتوحة لين تخلص كل الأوامر. */
    private var sequenceRunning = false

    /**
     * ننفذ أوامر التحكم أول (صامت، منبه، إضاءة…)، وبعدها اللي يفتح تطبيقات، وآخر شي الاتصال
     * أو الواتساب. بين كل أمر يفتح شي والثاني ثانية ونص.
     */
    private fun runSequence(steps: List<Command>) {
        val app = applicationContext
        val messages = mutableListOf<String>()
        val later = mutableListOf<Command>()
        for (step in steps) {
            if (step is Command.Device && step.action != com.naffith.app.core.DeviceAction.SCREENSHOT &&
                step.action != com.naffith.app.core.DeviceAction.LOCK_SCREEN
            ) {
                messages += DeviceController(app).execute(step).message
            } else {
                later += step
            }
        }
        val needsContacts = { c: Command -> c is Command.Call || c is Command.WhatsApp || c is Command.SendScreenshot }
        val ordered = later.filterNot(needsContacts) + later.filter(needsContacts)
        if (messages.isNotEmpty()) setStatus(messages.joinToString("، "))
        if (ordered.isEmpty()) {
            val summary = messages.joinToString("، ")
            if (isPopup) {
                if (voiceMode && VoicePrefs.speakReplies(this)) say(summary) { if (!isFinishing) finish() }
                else micButton.postDelayed({ if (!isFinishing) finish() }, 2000)
            } else {
                say(summary)
            }
            return
        }
        sequenceRunning = true
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        ordered.forEachIndexed { i, step ->
            handler.postDelayed({ if (!isDestroyed) execute(step) }, i * 1500L)
        }
        // بعد آخر أمر بشوي، النافذة ترجع تتصرف عادي (تسكّر لما تختفي).
        handler.postDelayed({
            sequenceRunning = false
            if (isPopup && !hasWindowFocus() && !isFinishing) finish()
        }, ordered.size * 1500L + 6000L)
    }

    /** «صور الشاشة وارسلها لفلان»: نخفي مرسال، نصوّر الشاشة اللي وراه، ونرسلها بالواتساب. */
    private fun sendScreenshotTo(contact: Contact) {
        setStatus(getString(R.string.shot_sending, contact.name))
        val app = applicationContext
        if (isPopup) finish() else moveTaskToBack(true)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            ScreenshotSender.captureAndSend(app, contact)
        }, 900)
    }

    /** أندرويد 13+: نطلب إذن الإشعارات مرة وحدة (عشان إشعار التحديثات). */
    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        withPermission(Manifest.permission.POST_NOTIFICATIONS, onDenied = {}, onGranted = {})
    }

    // ---------- الاختصارات ----------

    private fun runShortcut(sc: com.naffith.app.core.Shortcut) {
        val name = sc.triggers.firstOrNull().orEmpty()
        when (sc.type) {
            com.naffith.app.core.ShortcutType.IMAGE, com.naffith.app.core.ShortcutType.FILE -> {
                setStatus(getString(R.string.sc_opening, name))
                runUnlocked {
                    val uri = Uri.parse(sc.value)
                    val type = runCatching { contentResolver.getType(uri) }.getOrNull()
                        ?: if (sc.type == com.naffith.app.core.ShortcutType.IMAGE) "image/*" else "*/*"
                    val ok = runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.isSuccess
                    if (!ok) setStatus(getString(R.string.sc_file_missing))
                }
            }
            com.naffith.app.core.ShortcutType.APP -> {
                setStatus(getString(R.string.opening_app, sc.title.ifBlank { name }))
                runUnlocked {
                    val intent = packageManager.getLaunchIntentForPackage(sc.value)
                    if (intent == null || runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
                        setStatus(getString(R.string.cannot_open))
                    }
                }
            }
            com.naffith.app.core.ShortcutType.LINK -> {
                setStatus(getString(R.string.sc_opening, name))
                runUnlocked {
                    val url = if (sc.value.contains("://")) sc.value else "https://" + sc.value
                    if (runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
                        setStatus(getString(R.string.cannot_open))
                    }
                }
            }
            com.naffith.app.core.ShortcutType.CALL -> placeCall(Contact("", sc.title.ifBlank { name }, sc.value))
            com.naffith.app.core.ShortcutType.PLACE -> {
                setStatus(getString(R.string.navigating_to, name))
                runUnlocked { if (!launcher.navigate(sc.value)) setStatus(getString(R.string.cannot_open)) }
            }
            com.naffith.app.core.ShortcutType.TEXT -> {
                showAnswer(sc.value)
                setStatus(name)
                say(sc.value)
            }
            com.naffith.app.core.ShortcutType.COMMANDS -> runRoutine(sc.value.lines().map { it.trim() }.filter { it.isNotEmpty() })
            com.naffith.app.core.ShortcutType.MACRO -> {
                val rec = Macro.decode(sc.value)
                val service = WhatsAppSendService.instance
                when {
                    rec == null -> setStatus(getString(R.string.cannot_open))
                    service == null -> {
                        setStatus(getString(R.string.dev_need_a11y))
                        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    }
                    else -> {
                        setStatus(getString(R.string.macro_playing, name))
                        val app = applicationContext
                        runUnlocked {
                            if (isPopup) finish()
                            Macro.play(service, rec) { ok, learned ->
                                if (!ok) android.widget.Toast.makeText(app, R.string.cannot_open, android.widget.Toast.LENGTH_SHORT).show()
                                // حفظ اللي تعلّمناه (خطوات صارت «بالمكان») عشان المرة الجاية أسرع.
                                else if (learned != rec) ShortcutStore(app).save(sc.copy(value = Macro.encode(learned)))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * روتين: أوامر التحكم (صامت، منبه، إضاءة…) تتنفذ كلها ورا بعض، وآخر أمر يفتح شي
     * (تطبيق، خرائط…) يتنفذ في الأخير.
     */
    private fun runRoutine(lines: List<String>) {
        val app = applicationContext
        val messages = mutableListOf<String>()
        var opener: Command? = null
        for (line in lines) {
            when (val cmd = CommandParser.parse(line)) {
                is Command.Device -> messages += DeviceController(app).execute(cmd).message
                is Command.Unknown -> {}
                else -> opener = cmd
            }
        }
        val summary = messages.joinToString("، ").ifBlank { getString(R.string.sc_routine_done) }
        setStatus(summary)
        val next = opener
        if (next != null) {
            execute(next)
        } else if (isPopup) {
            if (voiceMode && VoicePrefs.speakReplies(this)) say(summary) { if (!isFinishing) finish() }
            else micButton.postDelayed({ if (!isFinishing) finish() }, 2000)
        } else {
            say(summary)
        }
    }

    /** التحكم في الجوال: منبه، صوت، إضاءة، كشاف… ونرد بجملة قصيرة. */
    private fun runDevice(command: Command.Device) {
        // «ذكرني …»: نسأل إشعار ولا شاشة كاملة.
        if (command.action == com.naffith.app.core.DeviceAction.ALARM && command.label.isNotBlank() && command.value != null) {
            askReminderMode(command.label, command.value)
            return
        }
        val app = applicationContext
        // القفل ولقطة الشاشة: نسكّر النافذة أول عشان ما تطلع في الصورة.
        if (command.action == com.naffith.app.core.DeviceAction.SCREENSHOT ||
            command.action == com.naffith.app.core.DeviceAction.LOCK_SCREEN
        ) {
            if (isPopup) finish()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val r = DeviceController(app).execute(command)
                if (r.needsSetup) android.widget.Toast.makeText(app, r.message, android.widget.Toast.LENGTH_LONG).show()
            }, 700)
            return
        }
        val opensScreen = command.action in setOf(
            com.naffith.app.core.DeviceAction.WIFI, com.naffith.app.core.DeviceAction.BLUETOOTH,
            com.naffith.app.core.DeviceAction.AIRPLANE, com.naffith.app.core.DeviceAction.LOCATION,
            com.naffith.app.core.DeviceAction.MOBILE_DATA,
        )
        val run: () -> Unit = {
            val result = DeviceController(app).execute(command)
            setStatus(result.message)
            orb?.state = VoiceOrbView.State.IDLE
            // في النافذة: نقول النتيجة ونسكّر، إلا إذا يحتاج إذن (فتحنا له الإعداد).
            if (isPopup && !result.needsSetup) {
                if (voiceMode && VoicePrefs.speakReplies(this)) say(result.message) { if (!isFinishing) finish() }
                else micButton.postDelayed({ if (!isFinishing) finish() }, 1800)
            } else {
                say(result.message)
            }
        }
        if (opensScreen) runUnlocked(run) else run()
    }

    /** «افتح التيك توك (وابحث عن …)» وأي تطبيق ثاني بالاسم. */
    private fun openApp(command: Command.OpenApp) {
        val resolver = AppResolver(this)
        val resolved = resolver.resolve(command.appName)
        if (resolved == null) {
            chooseApp(resolver, command)
            return
        }
        launchResolved(resolver, resolved, command)
    }

    /** ما لقينا التطبيق: يختاره من القائمة (الأقرب فوق)، ونحفظ الاسم للمرة الجاية. */
    private fun chooseApp(resolver: AppResolver, command: Command.OpenApp) {
        val apps = resolver.ranked(command.appName).map { it.first }
        if (apps.isEmpty()) {
            setStatus(getString(R.string.app_not_found, command.appName))
            return
        }
        val title = getString(R.string.app_choose, command.appName)
        setStatus(title)
        say(getString(R.string.app_not_found, command.appName))
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(apps.map { it.label }.toTypedArray()) { _, which ->
                val app = apps[which]
                resolver.remember(command.appName, app)
                Toast.makeText(this, getString(R.string.choice_saved, command.appName, app.label), Toast.LENGTH_SHORT).show()
                launchResolved(resolver, AppResolver.Resolved(app, com.naffith.app.core.Apps.findKnown(command.appName)), command)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> setStatus(getString(R.string.status_ready)) }
            .show()
    }

    private fun launchResolved(resolver: AppResolver, resolved: AppResolver.Resolved, command: Command.OpenApp) {
        val label = resolved.app.label
        runUnlocked {
            when {
                command.query.isBlank() -> {
                    setStatus(getString(R.string.opening_app, label))
                    if (!resolver.launch(resolved.app)) setStatus(getString(R.string.cannot_open))
                }
                resolver.search(resolved, command.query) ->
                    setStatus(getString(R.string.searching_in_app, command.query, label))
                else -> {
                    // التطبيق ما يدعم البحث برابط: نفتحه على الأقل.
                    setStatus(getString(R.string.app_no_search, label))
                    if (!resolver.launch(resolved.app)) setStatus(getString(R.string.cannot_open))
                }
            }
        }
    }

    private fun answerQuestion(text: String) {
        lastQuestion = text
        // سؤال عن الشاشة؟ (ضغط «اسأل عن الشاشة» أو قال «وش هذا؟») ومعنا لقطة حديثة.
        val screen = ScreenContext.fresh()?.takeIf { askAboutScreen || ScreenWords.mentionsScreen(text) }
        askAboutScreen = false
        if (screen != null) {
            if (GeminiClient.apiKey(this).isNotBlank()) {
                chat(text, ScreenContext.jpeg(screen))
            } else {
                setStatus(getString(R.string.opening_gemini))
                val uri = runCatching { ScreenContext.shareUri(this, screen) }.getOrNull()
                runUnlocked {
                    val ok = if (uri != null) launcher.openGeminiWithImage(text, uri) else launcher.openGemini(text)
                    if (!ok) setStatus(getString(R.string.cannot_open))
                }
            }
            return
        }
        when (GeminiClient.effectiveMode(this, launcher.isGeminiInstalled())) {
            GeminiClient.AnswerMode.IN_APP -> chat(text)
            GeminiClient.AnswerMode.GEMINI_APP -> {
                setStatus(getString(R.string.opening_gemini))
                runUnlocked { if (!launcher.openGemini(text)) setStatus(getString(R.string.cannot_open)) }
            }
            GeminiClient.AnswerMode.GOOGLE -> {
                setStatus(getString(R.string.opening_google))
                runUnlocked { if (!launcher.googleSearch(text)) setStatus(getString(R.string.cannot_open)) }
            }
            else -> askForClarification(text)
        }
    }

    /** آخر كلام مو أمر، نرسله لتطبيق Gemini لو ضغط زر «اسأل Gemini». */
    private var lastQuestion = ""

    /** ضغط «اسأل عن الشاشة»: السؤال الجاي يروح مع صورة الشاشة. */
    private var askAboutScreen = false

    private fun stopListeningNow() {
        if (isListening) recognizer?.cancel()
        setListening(false)
        followUp = null
    }

    private fun showScreenshotHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ask_screen)
            .setMessage(R.string.ask_screen_help)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** فيه جواب سوالف ظاهر؟ (ما نسكّر النافذة ولا نمسحه لين يسأل شي ثاني). */
    private var answerShown = false

    private fun showAnswer(text: String?) {
        val view = answerText
        answerShown = text != null
        if (view == null) {
            if (text != null) setStatus(text)
            return
        }
        view.text = text.orEmpty()
        view.scrollTo(0, 0)
        view.visibility = if (text == null) android.view.View.GONE else android.view.View.VISIBLE
    }

    // ---------- السوالف (Gemini) ----------

    /** محادثة الجلسة الحالية، عشان Gemini يتذكر اللي قبل. */
    private val chatHistory = mutableListOf<GeminiClient.Turn>()

    private fun chat(text: String, imageJpeg: ByteArray? = null) {
        unclearCount = 0
        chatHistory.add(GeminiClient.Turn(fromUser = true, text = text))
        setStatus(getString(if (imageJpeg != null) R.string.chat_looking else R.string.chat_thinking))
        orb?.state = VoiceOrbView.State.THINKING
        lifecycleScope.launch {
            GeminiClient.reply(this@MainActivity, chatHistory, imageJpeg)
                .onSuccess { reply ->
                    orb?.state = VoiceOrbView.State.IDLE
                    chatHistory.add(GeminiClient.Turn(fromUser = false, text = reply))
                    // الجواب يبقى ظاهر في مكانه، والحالة تحت تتغير بدون ما تمسحه.
                    showAnswer(reply)
                    setStatus("")
                    if (voiceMode && VoicePrefs.speakReplies(this@MainActivity)) {
                        // ننطق الجواب كامل، وبعد ما يخلص نسمعك عشان تكمّل السالفة.
                        say(reply) { if (!isListening && !isFinishing) startListeningWithPermission() }
                    }
                    // بدون صوت: نخلي الجواب قدامك تقرأه، وتضغط المايك إذا تبي تكمّل.
                }
                .onFailure { e ->
                    orb?.state = VoiceOrbView.State.IDLE
                    chatHistory.removeLastOrNull()
                    val err = e as? GeminiClient.ChatError
                    val msg = getString(err?.userMessage ?: R.string.chat_failed)
                    // السبب التقني يظهر على الشاشة بس (يساعدنا نعرف المشكلة)، وما ينقال بالصوت.
                    val detail = err?.detail?.takeIf { it.isNotBlank() }?.let { "\n($it)" }.orEmpty()
                    setStatus(msg + detail)
                    say(msg)
                    closePopupSoon()
                }
        }
    }

    /** نسأل سؤال (بالصوت في وضع «يا مرسال») ونفتح المايك مباشرة لسماع الجواب. */
    private fun askFollowUp(question: String, toCommand: (String) -> Command) {
        setStatus(question)
        followUp = toCommand
        say(question) { if (!isFinishing && followUp != null && !isListening) startListeningWithPermission() }
    }

    /** لما ما نفهم الأمر: نعرض اللي سمعناه ونخلي المستخدم يختار وش يقصد. */
    private fun askForClarification(heard: String) {
        if (voiceMode) {
            // الجوال ممكن يكون في جيبك، فنسألك بالصوت ونسمع مرة ثانية بدل القائمة (مرتين بالكثير).
            setStatus(getString(R.string.unclear_status))
            if (++unclearCount > 2) {
                say(getString(R.string.voice_give_up)) { if (isPopup) finish() }
                return
            }
            say(getString(R.string.voice_unclear)) { if (!isListening) startListeningWithPermission() }
            return
        }
        setStatus(getString(R.string.unclear_status))
        val options = arrayOf(
            getString(R.string.unclear_maps),
            getString(R.string.unclear_youtube),
            getString(R.string.unclear_call),
            getString(R.string.unclear_whatsapp),
            getString(R.string.unclear_retry),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.unclear_title, heard))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> execute(Command.OpenMaps(heard))
                    1 -> execute(Command.SearchYouTube(heard))
                    2 -> execute(Command.Call(CommandParser.parseName(heard)))
                    3 -> execute(Command.WhatsApp(CommandParser.parseName(heard)))
                    else -> startListeningWithPermission()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> setStatus(getString(R.string.status_ready)) }
            .show()
    }

    /** يقرأ جهات الاتصال (بعد الإذن) وينفذ [block]. */
    private fun withContacts(block: (List<Contact>) -> Unit) {
        withPermission(
            Manifest.permission.READ_CONTACTS,
            onDenied = {
                handleDenied(Manifest.permission.READ_CONTACTS, R.string.perm_contacts_denied, R.string.perm_permanent_contacts)
            }
        ) {
            lifecycleScope.launch {
                val contacts = runCatching { contactsRepository.loadContacts() }.getOrDefault(emptyList())
                if (contacts.isEmpty()) setStatus(getString(R.string.no_contacts)) else block(contacts)
            }
        }
    }

    private fun resolveContact(names: List<String>, retry: (String) -> Command, onContact: (Contact) -> Unit) {
        withPermission(
            Manifest.permission.READ_CONTACTS,
            onDenied = {
                handleDenied(
                    Manifest.permission.READ_CONTACTS,
                    R.string.perm_contacts_denied,
                    R.string.perm_permanent_contacts
                )
            }
        ) {
            setStatus(getString(R.string.searching_contacts, names.first()))
            lifecycleScope.launch {
                val contacts = runCatching { contactsRepository.loadContacts() }.getOrDefault(emptyList())
                if (contacts.isEmpty()) {
                    setStatus(getString(R.string.no_contacts))
                    return@launch
                }
                // اختيار سابق للمستخدم لنفس الاسم يغلب كل شي.
                choiceMemory.recall(names, contacts)?.let {
                    onContact(it)
                    return@launch
                }
                when (val result = ContactMatcher.match(names, contacts)) {
                    is MatchResult.Single -> onContact(result.contact)
                    is MatchResult.Multiple ->
                        chooseContact(getString(R.string.choose_contact), result.matches.map { it.contact }, names.first(), onContact)
                    MatchResult.None -> {
                        val suggestions = ContactMatcher.suggestions(names, contacts)
                        if (suggestions.isEmpty()) contactNotFound(names.first(), retry)
                        else chooseContact(
                            getString(R.string.contact_not_found_suggest, names.first()),
                            suggestions, names.first(), onContact, retry
                        )
                    }
                }
            }
        }
    }

    /** قائمة اختيار؛ اللي يختاره المستخدم ينحفظ للاسم اللي قاله، عشان المرة الجاية ما نسأله. */
    private fun chooseContact(
        title: String,
        contacts: List<Contact>,
        spokenName: String,
        onContact: (Contact) -> Unit,
        retry: ((String) -> Command)? = null,
    ) {
        setStatus(title)
        say(title)
        val labels = contacts.map { "${it.name}\n${it.number}" }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                val chosen = contacts[which]
                choiceMemory.remember(spokenName, chosen)
                onContact(chosen)
                Toast.makeText(this, getString(R.string.choice_saved, spokenName, chosen.name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> setStatus(getString(R.string.status_ready)) }
        if (retry != null) {
            dialog.setPositiveButton(R.string.retry) { _, _ ->
                followUp = retry
                startListeningWithPermission()
            }
        }
        dialog.show()
    }

    private fun contactNotFound(name: String, retry: (String) -> Command) {
        val message = getString(R.string.contact_not_found, name)
        setStatus(message)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.contact_not_found_title)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { _, _ ->
                followUp = retry
                startListeningWithPermission()
            }
            .setNegativeButton(R.string.cancel, null)
            .apply {
                // «اتصل على محل العبير» وما هو في جهات الاتصال: ندوّره كمحل.
                if (retry(name) is Command.Call) setNeutralButton(R.string.search_as_place) { _, _ -> callPlace(name) }
            }
            .show()
    }

    private fun openWhatsApp(contact: Contact, message: String, send: Boolean) {
        val installed = launcher.isWhatsAppInstalled()
        val autoSend = send && message.isNotBlank() && WhatsAppSendService.isEnabled(this)
        setStatus(
            when {
                message.isBlank() -> getString(R.string.opening_whatsapp, contact.name)
                autoSend -> getString(R.string.sending_whatsapp, message, contact.name)
                else -> getString(R.string.writing_whatsapp, message, contact.name)
            }
        )
        if (send && message.isNotBlank() && !autoSend) {
            // نكتب الرسالة الحين، ونشرح كيف يفعّل الإرسال التلقائي للمرات الجاية.
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.a11y_title)
                .setMessage(R.string.a11y_message)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
                .setNegativeButton(R.string.hf_overlay_later, null)
                .show()
        }
        lifecycleScope.launch {
            val entry = contactsRepository.findWhatsAppEntry(contact)
            runUnlocked {
                if (autoSend) WhatsAppSendService.requestSend() else WhatsAppSendService.cancel()
                when {
                    !launcher.openWhatsAppChat(contact.number, entry, message) -> setStatus(getString(R.string.cannot_open))
                    !installed -> setStatus(getString(R.string.whatsapp_not_installed))
                }
            }
        }
    }

    /** إذا الجوال مقفول (جينا من «يا مرسال» والشاشة مطفية) نطلب فتح القفل قبل نفتح تطبيق ثاني. */
    private fun runUnlocked(action: () -> Unit) {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && km.isKeyguardLocked) {
            setStatus(getString(R.string.unlock_needed))
            awaitingUnlock = true
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    awaitingUnlock = false
                    action()
                }
                override fun onDismissCancelled() {
                    awaitingUnlock = false
                    setStatus(getString(R.string.unlock_cancelled))
                }
                override fun onDismissError() {
                    awaitingUnlock = false
                    setStatus(getString(R.string.unlock_cancelled))
                }
            })
        } else {
            action()
        }
    }

    /** نطلع فوق قفل الشاشة ونشغّلها، بس لما نادانا «يا مرسال». */
    private fun showOverLockScreen(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(show)
            setTurnScreenOn(show)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (show) window.addFlags(flags) else window.clearFlags(flags)
        }
        if (show) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** اتصال مباشر إذا سمح المستخدم، وإلا نفتح لوحة الاتصال بالرقم. */
    private fun placeCall(contact: Contact) {
        val canCall = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        if (!canCall) {
            dialInstead(contact)
            return
        }
        withPermission(Manifest.permission.CALL_PHONE, onDenied = { dialInstead(contact) }) {
            setStatus(getString(R.string.calling, contact.name))
            if (!launcher.call(contact.number)) dialInstead(contact)
        }
    }

    private fun dialInstead(contact: Contact) {
        if (launcher.dial(contact.number)) {
            setStatus(getString(R.string.dialing_instead, contact.name))
        } else {
            setStatus(getString(R.string.cannot_open))
        }
    }

    // ---------- الواجهة ----------

    private fun setListening(listening: Boolean) {
        isListening = listening
        micButton.setBackgroundResource(if (listening) R.drawable.bg_mic_listening else R.drawable.bg_mic_idle)
        orb?.state = if (listening) VoiceOrbView.State.LISTENING else VoiceOrbView.State.IDLE
        if (!listening) {
            micButton.scaleX = 1f
            micButton.scaleY = 1f
        } else if (followUp == null) {
            setStatus(getString(if (answerShown) R.string.chat_more else R.string.status_listening))
        }
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    /** وقت الأسئلة نخلي السؤال ظاهر بدل «أسمعك». */
    private fun setStatusIfIdle(text: String) {
        if (followUp == null) setStatus(text)
    }

    companion object {
        const val EXTRA_COMMANDS = "com.naffith.app.extra.COMMANDS"
        const val EXTRA_START_LISTENING = "com.naffith.app.extra.START_LISTENING"
        /** افتح الشاشة الكاملة حتى لو «افتح كنافذة مساعد» مفعّل. */
        const val EXTRA_FULL_APP = "com.naffith.app.extra.FULL_APP"
        private const val LANGUAGE = "ar-SA"
        /** نقبل التصحيح خلال دقيقتين من الأمر (ثابت عشان يشتغل بين نوافذ المساعد). */
        private const val CORRECTION_WINDOW_MS = 120_000L
        private var lastHeard: String? = null
        /** آخر محل فتحناه في الخرائط (ثابت بين نوافذ المساعد): «اتصل عليه» تروح له. */
        private var lastPlaceQuery: String? = null
        private var lastPlaceAt = 0L
        private var lastAt = 0L
        // ثوابت من API 31، نكتبها كأرقام عشان تشتغل مع minSdk 24.
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
