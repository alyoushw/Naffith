package com.naffith.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.naffith.app.core.Command
import com.naffith.app.core.ChoiceMemory
import com.naffith.app.core.CommandParser
import com.naffith.app.core.Contact
import com.naffith.app.core.ContactMatcher
import com.naffith.app.core.MatchResult
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var transcriptText: TextView
    private lateinit var statusText: TextView
    private lateinit var micButton: ImageButton

    private lateinit var launcher: AppLauncher
    private lateinit var contactsRepository: ContactsRepository
    private lateinit var choiceMemory: ChoiceMemory

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    /** إذا سألنا المستخدم سؤال (مثل «على مين تتصل؟») فالجواب الجاي يروح هنا بدل المحلل. */
    private var followUp: ((String) -> Command)? = null

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
        setContentView(R.layout.activity_main)

        transcriptText = findViewById(R.id.transcriptText)
        statusText = findViewById(R.id.statusText)
        micButton = findViewById(R.id.micButton)

        launcher = AppLauncher(this)
        contactsRepository = ContactsRepository(this)
        choiceMemory = ChoiceMemory(PrefsStore(getSharedPreferences("choices", MODE_PRIVATE)))

        micButton.setOnClickListener { onMicClicked() }
        micButton.setOnLongClickListener {
            confirmForgetChoices()
            true
        }
    }

    override fun onPause() {
        super.onPause()
        if (isListening) recognizer?.cancel()
        setListening(false)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus(getString(R.string.speech_unavailable))
            return
        }
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
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
                return
            }
            handleSpeech(alternatives)
        }

        override fun onError(error: Int) {
            setListening(false)
            setStatus(errorMessage(error))
        }
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
        execute(command)
    }

    private fun execute(command: Command) {
        when (command) {
            is Command.OpenMaps -> {
                setStatus(
                    if (command.query.isBlank()) getString(R.string.opening_maps)
                    else getString(R.string.searching_maps, command.query)
                )
                if (!launcher.openMaps(command.query)) setStatus(getString(R.string.cannot_open))
            }

            is Command.SearchYouTube -> {
                setStatus(
                    if (command.query.isBlank()) getString(R.string.opening_youtube)
                    else getString(R.string.searching_youtube, command.query)
                )
                if (!launcher.openYouTube(command.query)) setStatus(getString(R.string.cannot_open))
            }

            is Command.WhatsApp ->
                if (command.contactNames.isEmpty()) {
                    askFollowUp(getString(R.string.ask_whatsapp_name)) { Command.WhatsApp(CommandParser.parseName(it)) }
                } else {
                    resolveContact(command.contactNames, retry = { Command.WhatsApp(CommandParser.parseName(it)) }) { contact ->
                        openWhatsApp(contact)
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

            is Command.Unknown -> askForClarification(command.heard)
        }
    }

    /** نسأل سؤال ونفتح المايك مباشرة لسماع الجواب. */
    private fun askFollowUp(question: String, toCommand: (String) -> Command) {
        setStatus(question)
        followUp = toCommand
        micButton.postDelayed({ if (!isFinishing && followUp != null) startListeningWithPermission() }, 600)
    }

    /** لما ما نفهم الأمر: نعرض اللي سمعناه ونخلي المستخدم يختار وش يقصد. */
    private fun askForClarification(heard: String) {
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

    private fun openWhatsApp(contact: Contact) {
        val installed = launcher.isWhatsAppInstalled()
        setStatus(getString(R.string.opening_whatsapp, contact.name))
        lifecycleScope.launch {
            val entry = contactsRepository.findWhatsAppEntry(contact)
            when {
                !launcher.openWhatsAppChat(contact.number, entry) -> setStatus(getString(R.string.cannot_open))
                !installed -> setStatus(getString(R.string.whatsapp_not_installed))
            }
        }
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

    /** ضغطة طويلة على المايك: نسيان الاختيارات المحفوظة. */
    private fun confirmForgetChoices() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.forget_title)
            .setMessage(R.string.forget_message)
            .setPositiveButton(R.string.forget_confirm) { _, _ ->
                choiceMemory.forgetAll()
                setStatus(getString(R.string.forget_done))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- الواجهة ----------

    private fun setListening(listening: Boolean) {
        isListening = listening
        micButton.setBackgroundResource(if (listening) R.drawable.bg_mic_listening else R.drawable.bg_mic_idle)
        if (!listening) {
            micButton.scaleX = 1f
            micButton.scaleY = 1f
        } else if (followUp == null) {
            setStatus(getString(R.string.status_listening))
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
        private const val LANGUAGE = "ar-SA"
        // ثوابت من API 31، نكتبها كأرقام عشان تشتغل مع minSdk 24.
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
