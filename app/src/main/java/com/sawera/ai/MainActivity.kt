package com.sawera.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var mem: Memory
    private lateinit var brain: Brain
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var speaking = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var mic: TextView
    private lateinit var status: TextView
    private lateinit var input: EditText

    private val BG = 0xFF0B0F1A.toInt()
    private val CARD = 0xFF1B2233.toInt()
    private val PURPLE = 0xFF7C4DFF.toInt()
    private val RED = 0xFFE53935.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        mem = Memory(this); brain = Brain(mem)
        tts = TextToSpeech(this, this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildUi()
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        if (mem.apiKey.isBlank()) askApiKey()
        intent.getStringExtra("run_task")?.let { handleUser("Abhi yeh kaam shuru karo: $it") }
    }

    // ---------------- UI ----------------
    private fun pill(color: Int, radius: Int = 22) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(color)
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(Color.WHITE)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = pill(CARD, 18)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        // header
        val title = TextView(this).apply {
            text = "Sawera AI"; textSize = 26f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
        }
        status = TextView(this).apply { text = "Salaam ${mem.userName} 💜"; textSize = 13f; setTextColor(0xFF9AA4BF.toInt()) }
        val chips = LinearLayout(this).apply {
            setPadding(0, dp(10), 0, dp(6))
            addView(chip("🖐 Screen access") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            addView(chip("🔑 API key") { askApiKey() })
            addView(chip("🗑 Clear") { chat.removeAllViews() })
        }
        root.addView(title); root.addView(status); root.addView(chips)

        // chat
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(chat); isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // input row
        input = EditText(this).apply {
            hint = "Yahan likho…"; setHintTextColor(0xFF6B7490.toInt()); setTextColor(Color.WHITE)
            background = pill(CARD, 24); setPadding(dp(18), dp(12), dp(18), dp(12))
            maxLines = 3; imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine(false)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        val send = TextView(this).apply {
            text = "➤"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2E5BFF.toInt()) }
            setOnClickListener { sendTyped() }
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(send, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        }
        root.addView(row)

        // mic
        mic = TextView(this).apply {
            text = "🎤"; textSize = 32f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PURPLE) }
            elevation = dp(8).toFloat()
            setOnClickListener { toggleListen() }
        }
        val hint = TextView(this).apply {
            text = "Dabao aur bolo — live sunti rehti hoon"; textSize = 12f
            setTextColor(0xFF9AA4BF.toInt()); gravity = Gravity.CENTER
        }
        root.addView(mic, LinearLayout.LayoutParams(dp(76), dp(76)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        setContentView(root)
        addBubble("Salaam ${mem.userName}! Main Sawera hoon. Mic dabao aur bolo, ya likh do.", false)
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isNotEmpty()) { input.setText(""); handleUser(t) }
    }

    private fun addBubble(text: String, user: Boolean) {
        val tv = TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
            background = pill(if (user) 0xFF2E5BFF.toInt() else CARD, 18)
            setTextIsSelectable(true)
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (user) Gravity.END else Gravity.START
            setMargins(dp(2), dp(4), dp(2), dp(4))
        }
        chat.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun askApiKey() {
        val e = EditText(this).apply { hint = "Gemini API key (AIza...)"; setText(mem.apiKey) }
        android.app.AlertDialog.Builder(this).setTitle("Gemini API key").setView(e)
            .setPositiveButton("Save") { _, _ -> mem.apiKey = e.text.toString().trim() }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------------- Voice in (live, continuous) ----------------
    private fun toggleListen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            addBubble("Mic ki permission allow karein, phir dobara mic dabayein.", false)
            return
        }
        listening = !listening
        (mic.background as GradientDrawable).setColor(if (listening) RED else PURPLE)
        status.text = if (listening) "🔴 Sun rahi hoon…" else "Salaam ${mem.userName} 💜"
        if (listening) startRec() else { recognizer?.destroy(); recognizer = null }
    }

    private fun startRec() {
        if (!listening || speaking) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) {
                    val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank()) handleUser(t) else startRec()
                }
                override fun onError(e: Int) { lifecycleScope.launch { delay(500); startRec() } }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            })
        }
    }

    // ---------------- Voice out (Hindi) ----------------
    override fun onInit(code: Int) {
        if (code != TextToSpeech.SUCCESS) { ttsReady = false; return }
        val r = tts.setLanguage(Locale("hi", "IN"))
        ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true }
            override fun onDone(id: String?) { speaking = false; runOnUiThread { if (listening) startRec() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { speaking = false; runOnUiThread { if (listening) startRec() } }
        })
        if (!ttsReady) runOnUiThread {
            addBubble("Hindi voice phone mein install nahi hai. Settings > Text-to-speech > Google voice > Hindi download karein.", false)
        }
    }

    private fun speak(t: String) {
        if (t.isBlank() || !ttsReady) return
        speaking = true
        recognizer?.stopListening()
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "s")
    }

    // ---------------- Main loop ----------------
    private fun handleUser(text: String, screen: String? = null, depth: Int = 0) {
        addBubble(text, true)
        status.text = "Soch rahi hoon…"
        lifecycleScope.launch {
            val r = brain.ask(text, screen)
            r.remember.forEach { mem.addFact(it) }
            addBubble(r.text, false)
            status.text = if (listening) "🔴 Sun rahi hoon…" else "Salaam ${mem.userName} 💜"
            speak(r.speech)
            var needsScreen = false
            for (a in r.actions) {
                if (a.optString("type") == "read_screen") needsScreen = true else runAction(a)
                delay(1200)
            }
            if (needsScreen && depth < 8) {
                val s = SaweraAccessibilityService.instance?.readScreen()
                if (s == null) addBubble("Screen access on nahi hai. Upar 'Screen access' dabayein aur Sawera AI on karein.", false)
                else handleUser("Yeh screen hai, agla step batao.", s, depth + 1)
                return@launch
            }
            if (listening && !speaking) startRec()
        }
    }

    private fun runAction(a: JSONObject) {
        val svc = SaweraAccessibilityService.instance
        when (a.optString("type")) {
            "open_app" -> packageManager.getLaunchIntentForPackage(a.getString("package"))
                ?.let { startActivity(it) } ?: addBubble("App install nahi mili: ${a.getString("package")}", false)
            "tap_text" -> if (svc?.tapText(a.getString("text")) != true) addBubble("'${a.getString("text")}' screen par nahi mila.", false)
            "type_text" -> svc?.typeText(a.getString("text"))
            "scroll" -> svc?.scroll(a.optString("dir", "down"))
            "back" -> svc?.back()
            "home" -> svc?.home()
            "schedule" -> addBubble("⏰ Scheduled: ${a.getString("task")} @ ${Scheduler.schedule(this, a.getString("time"), a.getString("task"))}", false)
        }
    }

    override fun onDestroy() { recognizer?.destroy(); tts.shutdown(); super.onDestroy() }
}
