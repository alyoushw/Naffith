package com.naffith.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import com.google.android.material.materialswitch.MaterialSwitch
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
 * تنادي «يا نفّذ» أو تضغط زر المساعد.
 */
open class MainActivity : AppCompatActivity() {

    /** نافذة المساعد الصغيرة؟ (ترد بالصوت وتسكّر نفسها بعد التنفيذ). */
    protected open val isPopup = false
    protected open val layoutRes = R.layout.activity_main

    private lateinit var transcriptText: TextView
    private lateinit var statusText: TextView
    private lateinit var micButton: ImageButton
    private var answerText: TextView? = null
    private var handsFreeSwitch: MaterialSwitch? = null
    private var updatingSwitch = false

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

    /** جينا من «يا نفّذ»: نرد ونسأل بالصوت. */
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
        setContentView(layoutRes)

        transcriptText = findViewById(R.id.transcriptText)
        statusText = findViewById(R.id.statusText)
        micButton = findViewById(R.id.micButton)

        launcher = AppLauncher(this)
        contactsRepository = ContactsRepository(this)
        choiceMemory = ChoiceMemory(PrefsStore(getSharedPreferences("choices", MODE_PRIVATE)))

        handsFreeSwitch = findViewById<MaterialSwitch?>(R.id.handsFreeSwitch)?.also { sw ->
            sw.isChecked = HandsFreeService.isEnabled(this)
            sw.setOnCheckedChangeListener { _, checked ->
                if (!updatingSwitch) onHandsFreeToggled(checked)
            }
        }
        findViewById<android.view.View?>(R.id.settingsButton)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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
        if (isPopup || HandsFreeService.isEnabled(this)) initTts()

        micButton.setOnClickListener { onMicClicked() }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (savedInstanceState == null) handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        HandsFreeService.setAppVisible(true)
        // إذا الوضع مفعّل والخدمة انقفلت (إعادة تشغيل الجوال مثلاً) نشغّلها والتطبيق ظاهر.
        if (HandsFreeService.isEnabled(this) && HandsFreeService.instance == null &&
            hasPermission(Manifest.permission.RECORD_AUDIO)
        ) {
            HandsFreeService.start(this)
        }
        setSwitchChecked(HandsFreeService.isEnabled(this))
    }

    override fun onPause() {
        super.onPause()
        if (isListening) recognizer?.cancel()
        setListening(false)
        HandsFreeService.setAppVisible(false)
    }

    override fun onStop() {
        super.onStop()
        if (isPopup) {
            // النافذة الصغيرة تسكّر بعد ما فتحنا التطبيق المطلوب (أو لما يطلع منها المستخدم).
            if (!isChangingConfigurations) finish()
            return
        }
        showOverLockScreen(false)
        voiceMode = false
    }

    /** أوامر جاية من وضع «يا نفّذ» أو من زر الأوامر الصوتية في السماعة. */
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
                val fromWake = intent.getBooleanExtra(EXTRA_FROM_WAKE_WORD, false)
                intent.removeExtra(EXTRA_START_LISTENING)
                intent.removeExtra(EXTRA_FROM_WAKE_WORD)
                intent.action = null
                if (fromWake) {
                    voiceMode = true
                    showOverLockScreen(true)
                    transcriptText.text = ""
                    setStatus(getString(R.string.hf_wake_heard))
                    // نرد بصوت «هلا والله» وبعدها نسمع (عشان المايك ما يلقط صوتنا).
                    say(getString(R.string.hf_greeting)) { if (!isListening) startListeningWithPermission() }
                } else {
                    micButton.postDelayed({ if (!isListening) startListeningWithPermission() }, 400)
                }
            }
        }
    }

    // ---------- وضع «يا نفّذ» ----------

    private fun onHandsFreeToggled(enabled: Boolean) {
        if (!enabled) {
            HandsFreeService.setEnabled(this, false)
            HandsFreeService.stop(this)
            setStatus(getString(R.string.hf_disabled))
            return
        }
        withPermission(
            Manifest.permission.RECORD_AUDIO,
            onDenied = {
                setSwitchChecked(false)
                handleDenied(Manifest.permission.RECORD_AUDIO, R.string.perm_mic_denied, R.string.perm_permanent_mic)
            }
        ) {
            requestNotificationsThen {
                HandsFreeService.setEnabled(this, true)
                HandsFreeService.start(this)
                setSwitchChecked(true)
                setStatus(getString(R.string.hf_enabled))
                if (!Settings.canDrawOverlays(this)) askOverlayPermission() else askIgnoreBatteryOptimizations()
            }
        }
    }

    /** أندرويد 13+: إذن الإشعارات للإشعار الثابت. رفضه ما يوقف الوضع. */
    private fun requestNotificationsThen(next: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withPermission(Manifest.permission.POST_NOTIFICATIONS, onDenied = next, onGranted = next)
        } else {
            next()
        }
    }

    private fun askOverlayPermission() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hf_overlay_title)
            .setMessage(R.string.hf_overlay_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
            }
            .setNegativeButton(R.string.hf_overlay_later) { _, _ -> askIgnoreBatteryOptimizations() }
            .show()
    }

    /** بعض الجوالات تقفل الخدمات في الخلفية لتوفير البطارية؛ نطلب استثناء «نفّذ». */
    @SuppressLint("BatteryLife")
    private fun askIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun setSwitchChecked(checked: Boolean) {
        updatingSwitch = true
        handsFreeSwitch?.isChecked = checked
        updatingSwitch = false
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ---------- الرد بالصوت (وضع «يا نفّذ») ----------

    /** ننطق النص (لو جينا من «يا نفّذ»)، وبعد ما نخلص ننفذ [then]. */
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
        if (then != null) utteranceCallbacks[id] = then
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
        if (isListening) {
            recognizer?.stopListening()
            return
        }
        followUp = null
        startListeningWithPermission()
    }

    private fun startListeningWithPermission() {
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
        }
        setListening(true)
        if (followUp == null) transcriptText.text = ""
        sr.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = setStatusIfIdle(getString(R.string.status_listening))
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val scale = 1f + (rmsdB.coerceIn(0f, 10f) / 50f)
            micButton.scaleX = scale
            micButton.scaleY = scale
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
            val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (alternatives.none { it.isNotBlank() }) {
                setStatus(getString(R.string.err_no_match))
                closePopupSoon()
                return
            }
            handleSpeech(alternatives)
        }

        override fun onError(error: Int) {
            setListening(false)
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
            setStatus(errorMessage(error))
            closePopupSoon()
        }
    }

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

    // ---------- تنفيذ الأوامر ----------

    private fun handleSpeech(alternatives: List<String>) {
        val pending = followUp
        followUp = null
        val (heard, command) = if (pending != null) {
            val text = alternatives.first { it.isNotBlank() }.trim()
            text to pending(text)
        } else {
            CommandParser.parseBest(alternatives)
        }
        transcriptText.text = getString(R.string.heard_format, heard)
        // أمر جديد (مو سؤال): نشيل جواب السوالف القديم.
        if (command !is Command.Unknown) showAnswer(null)
        execute(command)
    }

    private fun execute(command: Command) {
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

            // مو أمر؟ سؤال أو سالفة: يجاوبه Gemini (داخل نفّذ أو تطبيقه) أو Google، حسب الإعدادات.
            is Command.Unknown -> answerQuestion(command.heard)
        }
    }

    /** «افتح التيك توك (وابحث عن …)» وأي تطبيق ثاني بالاسم. */
    private fun openApp(command: Command.OpenApp) {
        val resolver = AppResolver(this)
        val resolved = resolver.resolve(command.appName)
        if (resolved == null) {
            val msg = getString(R.string.app_not_found, command.appName)
            setStatus(msg)
            say(msg)
            closePopupSoon()
            return
        }
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
        lifecycleScope.launch {
            GeminiClient.reply(this@MainActivity, chatHistory, imageJpeg)
                .onSuccess { reply ->
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

    /** نسأل سؤال (بالصوت في وضع «يا نفّذ») ونفتح المايك مباشرة لسماع الجواب. */
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

    /** إذا الجوال مقفول (جينا من «يا نفّذ» والشاشة مطفية) نطلب فتح القفل قبل نفتح تطبيق ثاني. */
    private fun runUnlocked(action: () -> Unit) {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && km.isKeyguardLocked) {
            setStatus(getString(R.string.unlock_needed))
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = action()
                override fun onDismissCancelled() = setStatus(getString(R.string.unlock_cancelled))
                override fun onDismissError() = setStatus(getString(R.string.unlock_cancelled))
            })
        } else {
            action()
        }
    }

    /** نطلع فوق قفل الشاشة ونشغّلها، بس لما نادانا «يا نفّذ». */
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
        const val EXTRA_FROM_WAKE_WORD = "com.naffith.app.extra.FROM_WAKE_WORD"
        private const val LANGUAGE = "ar-SA"
        // ثوابت من API 31، نكتبها كأرقام عشان تشتغل مع minSdk 24.
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
