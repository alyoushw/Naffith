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
 * الإعدادات: الأسماء الثانية المحفوظة (عرض/حذف/إضافة)، تفعيل «مرسال» كمساعد افتراضي،
 * اختيار صوت الرد، حساسية «يا مرسال»، والإرسال التلقائي في واتساب.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** رجعنا من تسجيل «اختصار بخطوات». */
        const val EXTRA_MACRO_DONE = "com.naffith.app.extra.MACRO_DONE"
        /** ضغطت إشعار «تحديث جديد». */
        const val EXTRA_START_UPDATE = "com.naffith.app.extra.START_UPDATE"

        /**
         * الاختصار اللي نعدّله الحين (نحفظه هنا عشان يبقى حتى لو رحنا نسجّل خطوات ورجعنا).
         * null = اختصار جديد.
         */
        private var editingId: String? = null
        private var editingTriggers: List<String> = emptyList()
    }

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

    // ---------- الاختصارات ----------

    private lateinit var shortcuts: ShortcutStore
    private var pendingShortcutType: com.naffith.app.core.ShortcutType? = null

    /** اختيار صورة أو ملف للاختصار (ونحفظ صلاحية فتحه دايماً). */
    private val pickDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val type = pendingShortcutType ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        askTriggers(type, uri.toString(), displayName(uri))
    }

    /** اختيار جهة اتصال للاختصار. */
    private val pickShortcutContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        val contact = readPickedContact(uri) ?: return@registerForActivityResult
        askTriggers(com.naffith.app.core.ShortcutType.CALL, contact.number, contact.name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        memory = ChoiceMemory(PrefsStore(getSharedPreferences("choices", MODE_PRIVATE)))
        shortcuts = ShortcutStore(this)
        findViewById<MaterialButton>(R.id.addShortcutButton).setOnClickListener {
            editingId = null
            editingTriggers = emptyList()
            chooseShortcutType()
        }
        findViewById<TextView>(R.id.versionText).text = getString(R.string.upd_version, Updater.currentVersionName(this))
        findViewById<MaterialButton>(R.id.checkUpdateButton).setOnClickListener { UpdateDialogs.manualCheck(this) }

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
        handleMacroResult(intent)
        if (intent?.getBooleanExtra(EXTRA_START_UPDATE, false) == true) {
            intent.removeExtra(EXTRA_START_UPDATE)
            UpdateDialogs.manualCheck(this)
        }

        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.openAsAssistantSwitch).apply {
            isChecked = VoicePrefs.openAsAssistant(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> VoicePrefs.setOpenAsAssistant(this@SettingsActivity, checked) }
        }

        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.speakSwitch).apply {
            isChecked = VoicePrefs.speakReplies(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> VoicePrefs.setSpeakReplies(this@SettingsActivity, checked) }
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
        renderShortcuts()
        renderAliases()
        renderAssistant()
        renderA11y()
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ---------- الاختصارات ----------

    private fun kindLabel(t: com.naffith.app.core.ShortcutType): Pair<String, Int> = when (t) {
        com.naffith.app.core.ShortcutType.IMAGE -> "🖼️" to R.string.sc_kind_image
        com.naffith.app.core.ShortcutType.FILE -> "📄" to R.string.sc_kind_file
        com.naffith.app.core.ShortcutType.APP -> "📱" to R.string.sc_kind_app
        com.naffith.app.core.ShortcutType.LINK -> "🔗" to R.string.sc_kind_link
        com.naffith.app.core.ShortcutType.CALL -> "📞" to R.string.sc_kind_call
        com.naffith.app.core.ShortcutType.PLACE -> "📍" to R.string.sc_kind_place
        com.naffith.app.core.ShortcutType.TEXT -> "📝" to R.string.sc_kind_text
        com.naffith.app.core.ShortcutType.COMMANDS -> "⚡" to R.string.sc_kind_commands
        com.naffith.app.core.ShortcutType.MACRO -> "🎬" to R.string.sc_kind_macro
    }

    private fun renderShortcuts() {
        val list = findViewById<LinearLayout>(R.id.shortcutList)
        list.removeAllViews()
        val all = shortcuts.all()
        findViewById<TextView>(R.id.shortcutEmpty).visibility =
            if (all.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        for (sc in all) {
            val (icon, kind) = kindLabel(sc.type)
            val detail = sc.title.ifBlank { getString(kind) }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                // اضغط على الاختصار عشان تعدّله.
                isClickable = true
                setOnClickListener { editShortcut(sc) }
            }
            row.addView(TextView(this).apply {
                text = getString(R.string.sc_row, icon, sc.triggers.joinToString("، "), detail)
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                background = null
                contentDescription = getString(R.string.alias_delete)
                setOnClickListener {
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setMessage(getString(R.string.sc_delete_confirm, sc.triggers.first()))
                        .setPositiveButton(R.string.alias_delete) { _, _ ->
                            shortcuts.delete(sc.id)
                            renderShortcuts()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            })
            list.addView(row)
        }
    }

    /** خيارات التعديل: الكلام، اللي ينفّذه (أو إعادة التسجيل)، أو حذف. */
    private fun editShortcut(sc: com.naffith.app.core.Shortcut) {
        val isMacro = sc.type == com.naffith.app.core.ShortcutType.MACRO
        val options = arrayOf(
            getString(R.string.sc_edit_triggers),
            getString(if (isMacro) R.string.sc_edit_rerecord else R.string.sc_edit_action),
            getString(R.string.alias_delete),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sc_edit_title, sc.triggers.first()))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        editingId = sc.id
                        editingTriggers = sc.triggers
                        askTriggers(sc.type, sc.value, sc.title)
                    }
                    1 -> {
                        editingId = sc.id
                        editingTriggers = sc.triggers
                        if (isMacro) startMacroRecording() else chooseShortcutType()
                    }
                    else -> MaterialAlertDialogBuilder(this)
                        .setMessage(getString(R.string.sc_delete_confirm, sc.triggers.first()))
                        .setPositiveButton(R.string.alias_delete) { _, _ ->
                            shortcuts.delete(sc.id)
                            renderShortcuts()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseShortcutType() {
        val types = listOf(
            com.naffith.app.core.ShortcutType.IMAGE to R.string.sc_type_image,
            com.naffith.app.core.ShortcutType.FILE to R.string.sc_type_file,
            com.naffith.app.core.ShortcutType.APP to R.string.sc_type_app,
            com.naffith.app.core.ShortcutType.LINK to R.string.sc_type_link,
            com.naffith.app.core.ShortcutType.CALL to R.string.sc_type_call,
            com.naffith.app.core.ShortcutType.PLACE to R.string.sc_type_place,
            com.naffith.app.core.ShortcutType.TEXT to R.string.sc_type_text,
            com.naffith.app.core.ShortcutType.COMMANDS to R.string.sc_type_commands,
            com.naffith.app.core.ShortcutType.MACRO to R.string.sc_type_macro,
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sc_choose_type)
            .setItems(types.map { getString(it.second) }.toTypedArray()) { _, which ->
                val type = types[which].first
                pendingShortcutType = type
                when (type) {
                    com.naffith.app.core.ShortcutType.IMAGE -> pickDocument.launch(arrayOf("image/*"))
                    com.naffith.app.core.ShortcutType.FILE -> pickDocument.launch(arrayOf("*/*"))
                    com.naffith.app.core.ShortcutType.APP -> pickApp()
                    com.naffith.app.core.ShortcutType.CALL ->
                        runCatching { pickShortcutContact.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)) }
                    com.naffith.app.core.ShortcutType.LINK -> askText(R.string.sc_link_hint, false) { askTriggers(type, it, it) }
                    com.naffith.app.core.ShortcutType.PLACE -> askText(R.string.sc_place_hint, false) { input ->
                        lifecycleScope.launch {
                            val dest = SavedPlaces.resolveDestination(input) ?: input
                            askTriggers(type, dest, "")
                        }
                    }
                    com.naffith.app.core.ShortcutType.TEXT -> askText(R.string.sc_text_hint, true) { askTriggers(type, it, "") }
                    com.naffith.app.core.ShortcutType.COMMANDS -> askText(R.string.sc_commands_hint, true) { askTriggers(type, it, "") }
                    com.naffith.app.core.ShortcutType.MACRO -> startMacroRecording()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- اختصار بخطوات ----------

    /** نختار التطبيق، ونشرح الطريقة، وبعدين نفتحه ونبدأ نسجّل الضغطات. */
    private fun startMacroRecording() {
        val service = WhatsAppSendService.instance
        if (service == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sc_type_macro)
                .setMessage(R.string.dev_need_a11y)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val apps = AppResolver(this).installedApps().sortedBy { it.label }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sc_pick_app)
            .setItems(apps.map { it.label }.toTypedArray()) { _, which ->
                val app = apps[which]
                macroAppLabel = app.label
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.sc_type_macro)
                    .setMessage(getString(R.string.macro_explain, app.label))
                    .setPositiveButton(R.string.macro_start) { _, _ ->
                        if (!Macro.startRecording(service, app.packageName)) {
                            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var macroAppLabel = ""

    /** رجعنا من التسجيل: نطلب الكلام اللي يشغّل الاختصار. */
    private fun handleMacroResult(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_MACRO_DONE, false) != true) return
        intent.removeExtra(EXTRA_MACRO_DONE)
        val rec = Macro.lastRecording ?: return
        Macro.lastRecording = null
        if (rec.steps.isEmpty()) {
            Toast.makeText(this, R.string.macro_empty, Toast.LENGTH_LONG).show()
            return
        }
        val label = macroAppLabel.ifBlank {
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(rec.packageName, 0)).toString() }
                .getOrDefault(rec.packageName)
        }
        askTriggers(
            com.naffith.app.core.ShortcutType.MACRO,
            Macro.encode(rec),
            getString(R.string.macro_title, label, rec.steps.size)
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleMacroResult(intent)
        if (intent.getBooleanExtra(EXTRA_START_UPDATE, false)) {
            intent.removeExtra(EXTRA_START_UPDATE)
            UpdateDialogs.manualCheck(this)
        }
    }

    private fun pickApp() {
        val apps = AppResolver(this).installedApps().sortedBy { it.label }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sc_pick_app)
            .setItems(apps.map { it.label }.toTypedArray()) { _, which ->
                val app = apps[which]
                askTriggers(com.naffith.app.core.ShortcutType.APP, app.packageName, app.label)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** خانة نص (سطر أو أكثر) وبعدها [next]. */
    private fun askText(hintRes: Int, multiLine: Boolean, next: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(hintRes)
            if (multiLine) {
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            } else {
                setSingleLine()
            }
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setView(container)
            .setPositiveButton(R.string.sc_next) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) next(text)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** آخر خطوة: الكلام اللي يشغّل الاختصار. */
    private fun askTriggers(type: com.naffith.app.core.ShortcutType, value: String, title: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.sc_triggers_hint)
            // تعديل: نعرض الكلام الحالي.
            if (editingTriggers.isNotEmpty()) setText(editingTriggers.joinToString("، "))
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sc_triggers_title)
            .setView(container)
            .setPositiveButton(R.string.chat_save) { _, _ ->
                val triggers = com.naffith.app.core.ShortcutMatcher.splitTriggers(input.text.toString())
                if (triggers.isEmpty()) return@setPositiveButton
                shortcuts.save(com.naffith.app.core.Shortcut(editingId ?: shortcuts.newId(), triggers, type, value, title))
                editingId = null
                editingTriggers = emptyList()
                Toast.makeText(this, getString(R.string.sc_saved, triggers.first()), Toast.LENGTH_SHORT).show()
                renderShortcuts()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** اسم الملف/الصورة للعرض. */
    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull().orEmpty()

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
        val keyInput = findViewById<EditText>(R.id.chatKey)
        val modelInput = findViewById<EditText>(R.id.chatModel)
        val modeIds = mapOf(
            GeminiClient.AnswerMode.AUTO to R.id.modeAuto,
            GeminiClient.AnswerMode.IN_APP to R.id.modeInApp,
            GeminiClient.AnswerMode.GEMINI_APP to R.id.modeGeminiApp,
            GeminiClient.AnswerMode.GOOGLE to R.id.modeGoogle,
            GeminiClient.AnswerMode.ASK to R.id.modeAsk,
        )
        val group = findViewById<RadioGroup>(R.id.answerModeGroup)
        group.check(modeIds.getValue(GeminiClient.answerMode(this)))
        group.setOnCheckedChangeListener { _, id ->
            modeIds.entries.firstOrNull { it.value == id }?.let { GeminiClient.setAnswerMode(this, it.key) }
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
        val geminiInstalled = AppLauncher(this).isGeminiInstalled()
        findViewById<TextView>(R.id.chatStatus).text = getString(
            when (GeminiClient.effectiveMode(this, geminiInstalled)) {
                GeminiClient.AnswerMode.IN_APP ->
                    if (GeminiClient.apiKey(this).isBlank()) R.string.chat_status_no_key else R.string.chat_status_on
                GeminiClient.AnswerMode.GEMINI_APP -> R.string.chat_status_gemini_app
                GeminiClient.AnswerMode.GOOGLE -> R.string.chat_status_google
                else -> R.string.chat_status_off
            }
        )
    }

    // ---------- الأسماء الثانية ----------

    private fun renderAliases() {
        aliasList.removeAllViews()
        val contactAliases = memory.all()
        val apps = AppResolver(this)
        val appAliases = apps.aliases()
        aliasEmpty.visibility =
            if (contactAliases.isEmpty() && appAliases.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        for (alias in contactAliases) {
            addAliasRow(getString(R.string.alias_row, alias.spoken, alias.contactName, alias.number)) {
                confirmDelete(alias.spoken, alias.contactName) { memory.forget(alias) }
            }
        }
        // أسماء التطبيقات اللي اخترتها من القائمة («تيك» ← TikTok).
        for (alias in appAliases) {
            addAliasRow(getString(R.string.app_alias_row, alias.spoken, alias.label)) {
                confirmDelete(alias.spoken, alias.label) { apps.forget(alias) }
            }
        }
    }

    private fun addAliasRow(label: String, onDelete: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val text = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val delete = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            background = null
            contentDescription = getString(R.string.alias_delete)
            setOnClickListener { onDelete() }
        }
        row.addView(text)
        row.addView(delete)
        aliasList.addView(row)
    }

    private fun confirmDelete(spoken: String, target: String, forget: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.alias_delete)
            .setMessage(getString(R.string.alias_delete_confirm, spoken, target))
            .setPositiveButton(R.string.alias_delete) { _, _ ->
                forget()
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
