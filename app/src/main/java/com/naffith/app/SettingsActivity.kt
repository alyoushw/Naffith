package com.naffith.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.naffith.app.core.ChoiceMemory
import com.naffith.app.core.Contact

/**
 * الإعدادات: الأسماء الثانية المحفوظة (عرض/حذف/إضافة)، تفعيل «نفّذ» كمساعد افتراضي،
 * اختيار صوت الرد، حساسية «يا نفّذ»، والإرسال التلقائي في واتساب.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var memory: ChoiceMemory
    private lateinit var aliasList: LinearLayout
    private lateinit var aliasEmpty: TextView
    private var tts: TextToSpeech? = null
    private var pendingAlias: String? = null

    /** منتقي جهات الاتصال من النظام (ما يحتاج إذن قراءة كل الجهات). */
    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val alias = pendingAlias
        pendingAlias = null
        val uri = result.data?.data ?: return@registerForActivityResult
        if (alias.isNullOrBlank()) return@registerForActivityResult
        val contact = readPickedContact(uri) ?: return@registerForActivityResult
        memory.remember(alias, contact)
        Toast.makeText(this, getString(R.string.choice_saved, alias, contact.name), Toast.LENGTH_SHORT).show()
        renderAliases()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        memory = ChoiceMemory(PrefsStore(getSharedPreferences("choices", MODE_PRIVATE)))

        aliasList = findViewById(R.id.aliasList)
        aliasEmpty = findViewById(R.id.aliasEmpty)
        findViewById<MaterialButton>(R.id.addAliasButton).setOnClickListener { askAliasName() }
        findViewById<MaterialButton>(R.id.assistantButton).setOnClickListener { openAssistantSettings() }
        findViewById<MaterialButton>(R.id.voiceButton).setOnClickListener { chooseVoice() }
        findViewById<MaterialButton>(R.id.a11yButton).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        setupChat()
        setupPlaces()

        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.speakSwitch).apply {
            isChecked = VoicePrefs.speakReplies(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> VoicePrefs.setSpeakReplies(this@SettingsActivity, checked) }
        }

        val group = findViewById<RadioGroup>(R.id.sensitivityGroup)
        group.check(
            when (VoicePrefs.sensitivity(this)) {
                0 -> R.id.sensitivityNormal
                2 -> R.id.sensitivityMax
                else -> R.id.sensitivityHigh
            }
        )
        group.setOnCheckedChangeListener { _, id ->
            VoicePrefs.setSensitivity(
                this,
                when (id) {
                    R.id.sensitivityNormal -> 0
                    R.id.sensitivityMax -> 2
                    else -> 1
                }
            )
            // نعيد تشغيل الخدمة عشان تاخذ الحساسية الجديدة.
            if (HandsFreeService.isEnabled(this)) {
                HandsFreeService.stop(this)
                HandsFreeService.start(this)
            }
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { VoicePrefs.applyBestVoice(this, it) }
                runOnUiThread { renderVoice() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderAliases()
        renderAssistant()
        renderA11y()
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ---------- البيت والدوام ----------

    private fun setupPlaces() {
        val homeInput = findViewById<EditText>(R.id.homeInput)
        val workInput = findViewById<EditText>(R.id.workInput)
        findViewById<MaterialButton>(R.id.placesSave).setOnClickListener {
            val home = homeInput.text.toString()
            val work = workInput.text.toString()
            lifecycleScope.launch {
                var saved = false
                if (home.isNotBlank()) saved = SavedPlaces.save(this@SettingsActivity, SavedPlaces.Kind.HOME, home) != null || saved
                if (work.isNotBlank()) saved = SavedPlaces.save(this@SettingsActivity, SavedPlaces.Kind.WORK, work) != null || saved
                homeInput.setText("")
                workInput.setText("")
                renderPlaces()
                Toast.makeText(
                    this@SettingsActivity,
                    if (saved) R.string.chat_saved_toast else R.string.places_nothing,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        renderPlaces()
    }

    private fun renderPlaces() {
        fun label(kind: SavedPlaces.Kind, title: Int) = SavedPlaces.destination(this, kind).let {
            if (it.isBlank()) getString(R.string.places_not_set, getString(title))
            else getString(R.string.places_set, getString(title), it)
        }
        findViewById<TextView>(R.id.homeStatus).text = label(SavedPlaces.Kind.HOME, R.string.places_home)
        findViewById<TextView>(R.id.workStatus).text = label(SavedPlaces.Kind.WORK, R.string.places_work)
    }

    // ---------- السوالف (Gemini) ----------

    private fun setupChat() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.chatSwitch)
        val keyInput = findViewById<EditText>(R.id.chatKey)
        val modelInput = findViewById<EditText>(R.id.chatModel)
        switch.isChecked = GeminiClient.enabledSetting(this)
        switch.setOnCheckedChangeListener { _, checked ->
            GeminiClient.setEnabled(this, checked)
            renderChat()
        }
        // ما نعرض المفتاح نفسه، بس نبيّن إنه محفوظ.
        if (GeminiClient.apiKey(this).isNotBlank()) keyInput.hint = getString(R.string.chat_key_saved)
        modelInput.setText(GeminiClient.model(this))
        findViewById<MaterialButton>(R.id.chatSave).setOnClickListener {
            val key = keyInput.text.toString().trim()
            if (key.isNotEmpty()) GeminiClient.setApiKey(this, key)
            GeminiClient.setModel(this, modelInput.text.toString().ifBlank { GeminiClient.DEFAULT_MODEL })
            keyInput.setText("")
            if (GeminiClient.apiKey(this).isNotBlank()) keyInput.hint = getString(R.string.chat_key_saved)
            Toast.makeText(this, R.string.chat_saved_toast, Toast.LENGTH_SHORT).show()
            renderChat()
        }
        findViewById<MaterialButton>(R.id.chatGetKey).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
        }
        renderChat()
    }

    private fun renderChat() {
        findViewById<TextView>(R.id.chatStatus).text = getString(
            when {
                GeminiClient.apiKey(this).isBlank() -> R.string.chat_status_no_key
                GeminiClient.isEnabled(this) -> R.string.chat_status_on
                else -> R.string.chat_status_off
            }
        )
    }

    // ---------- الأسماء الثانية ----------

    private fun renderAliases() {
        aliasList.removeAllViews()
        val aliases = memory.all()
        aliasEmpty.visibility = if (aliases.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        for (alias in aliases) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val text = TextView(this).apply {
                text = getString(R.string.alias_row, alias.spoken, alias.contactName, alias.number)
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val delete = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                background = null
                contentDescription = getString(R.string.alias_delete)
                setOnClickListener { confirmDelete(alias) }
            }
            row.addView(text)
            row.addView(delete)
            aliasList.addView(row)
        }
    }

    private fun confirmDelete(alias: ChoiceMemory.Alias) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.alias_delete)
            .setMessage(getString(R.string.alias_delete_confirm, alias.spoken, alias.contactName))
            .setPositiveButton(R.string.alias_delete) { _, _ ->
                memory.forget(alias)
                renderAliases()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askAliasName() {
        val input = EditText(this).apply {
            hint = getString(R.string.aliases_add_hint)
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.aliases_add)
            .setView(container)
            .setPositiveButton(R.string.aliases_pick_contact) { _, _ ->
                val alias = input.text.toString().trim()
                if (alias.isEmpty()) return@setPositiveButton
                pendingAlias = alias
                runCatching { pickContact.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun readPickedContact(uri: Uri): Contact? = runCatching {
        contentResolver.query(
            uri, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER), null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            Contact(c.getString(0) ?: "", c.getString(1) ?: return@use null, c.getString(2) ?: return@use null)
        }
    }.getOrNull()

    // ---------- المساعد الافتراضي ----------

    private fun isDefaultAssistant(): Boolean {
        val assistant = Settings.Secure.getString(contentResolver, "assistant").orEmpty()
        val voice = Settings.Secure.getString(contentResolver, "voice_interaction_service").orEmpty()
        return assistant.startsWith("$packageName/") || voice.startsWith("$packageName/")
    }

    private fun renderAssistant() {
        val status = findViewById<TextView>(R.id.assistantStatus)
        val button = findViewById<MaterialButton>(R.id.assistantButton)
        if (isDefaultAssistant()) {
            status.text = getString(R.string.assistant_on)
            button.text = getString(R.string.assistant_change)
        } else {
            status.text = getString(R.string.assistant_off)
            button.text = getString(R.string.assistant_button)
        }
    }

    private fun openAssistantSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in intents) {
            if (runCatching { startActivity(i) }.isSuccess) {
                Toast.makeText(this, R.string.assistant_toast, Toast.LENGTH_LONG).show()
                return
            }
        }
    }

    // ---------- الصوت ----------

    private fun voiceLabel(v: Voice): String {
        val country = v.locale.displayCountry.ifBlank { v.locale.country }
        val quality = when {
            v.quality >= Voice.QUALITY_VERY_HIGH -> getString(R.string.voice_quality_best)
            v.quality >= Voice.QUALITY_HIGH -> getString(R.string.voice_quality_high)
            else -> getString(R.string.voice_quality_normal)
        }
        val net = if (v.isNetworkConnectionRequired) " · " + getString(R.string.voice_needs_internet) else ""
        return "$country · $quality$net\n${v.name}"
    }

    private fun renderVoice() {
        val engine = tts ?: return
        val current = engine.voice
        findViewById<TextView>(R.id.voiceCurrent).text =
            if (current != null && current.locale.language == "ar") voiceLabel(current)
            else getString(R.string.voice_missing)
    }

    private fun chooseVoice() {
        val engine = tts ?: return
        val voices = VoicePrefs.arabicVoices(engine)
        if (voices.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.voice_title)
                .setMessage(R.string.voice_missing)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val currentIndex = voices.indexOfFirst { it.name == engine.voice?.name }
        var selected = currentIndex
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.voice_choose)
            .setSingleChoiceItems(voices.map { voiceLabel(it) }.toTypedArray(), currentIndex) { _, which ->
                selected = which
                // نسمّعك الصوت على طول.
                engine.voice = voices[which]
                engine.speak(getString(R.string.hf_greeting), TextToSpeech.QUEUE_FLUSH, null, "preview")
            }
            .setPositiveButton(R.string.voice_save) { _, _ ->
                if (selected >= 0) VoicePrefs.setVoiceName(this, voices[selected].name)
                renderVoice()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                VoicePrefs.applyBestVoice(this, engine)
            }
            .show()
    }

    // ---------- الإرسال التلقائي ----------

    private fun renderA11y() {
        findViewById<TextView>(R.id.a11yStatus).text = getString(
            if (WhatsAppSendService.isEnabled(this)) R.string.a11y_on else R.string.a11y_off
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
