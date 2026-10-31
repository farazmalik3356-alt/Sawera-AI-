package com.sawera.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
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
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var micBtn: Button

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        mem = Memory(this); brain = Brain(mem)
        tts = TextToSpeech(this, this)
        buildUi()
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        if (mem.apiKey.isBlank()) askApiKey()
        intent.getStringExtra("run_task")?.let { handleUser("Abhi yeh kaam shuru karo: $it") }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        val title = TextView(this).apply { text = "Sawera AI"; textSize = 26f }
        log = TextView(this).apply { textSize = 16f }
        scroll = ScrollView(this).apply { addView(log) }
        val row = LinearLayout(this)
        val input = EditText(this).apply { hint = "Yahan likho ya mic dabao…" }
        val send = Button(this).apply { text = "Bhejo" }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(send)
        micBtn = Button(this).apply { text = "🎤 Live suno (on/off)" }
        val a11y = Button(this).apply { text = "Screen access on karo" }
        val key = Button(this).apply { text = "Gemini API key" }

        send.setOnClickListener { val t = input.text.toString().trim(); if (t.isNotEmpty()) { input.setText(""); handleUser(t) } }
        micBtn.setOnClickListener { toggleListen() }
        a11y.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        key.setOnClickListener { askApiKey() }

        root.addView(title)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(row); root.addView(micBtn); root.addView(a11y); root.addView(key)
        setContentView(root)
        append("Sawera", "Salaam ${mem.userName}! Main hazir hoon. Pehle API key aur screen access on kar dein.")
    }

    private fun askApiKey() {
        val e = EditText(this).apply { hint = "Gemini API key"; setText(mem.apiKey) }
        android.app.AlertDialog.Builder(this).setTitle("Gemini API key").setView(e)
            .setPositiveButton("Save") { _, _ -> mem.apiKey = e.text.toString().trim() }.show()
    }

    private fun append(who: String, t: String) { log.append("\n$who: $t\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }

    // ---------- Voice in (live, continuous) ----------
    private fun toggleListen() {
        listening = !listening
        micBtn.text = if (listening) "🔴 Sun rahi hoon… (band karne ke liye dabao)" else "🎤 Live suno (on/off)"
        if (listening) startRec() else recognizer?.stopListening()
    }

    private fun startRec() {
        if (!listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) {
                    val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank()) handleUser(t) else startRec()
                }
                override fun onError(e: Int) { lifecycleScope.launch { delay(400); startRec() } }
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")   // understands Hindi/Urdu speech
            })
        }
    }

    // ---------- Voice out (Hindi) ----------
    override fun onInit(status: Int) { tts.language = Locale("hi", "IN") }

    private fun speak(t: String) {
        if (t.isBlank()) return
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "s")
    }

    // ---------- Main loop ----------
    private fun handleUser(text: String, screen: String? = null, depth: Int = 0) {
        append("Aap", text)
        lifecycleScope.launch {
            val r = brain.ask(text, screen)
            r.remember.forEach { mem.addFact(it) }
            append("Sawera", r.text)
            speak(r.speech)
            var needsScreen = false
            for (a in r.actions) {
                if (a.optString("type") == "read_screen") needsScreen = true else runAction(a)
                delay(1200)
            }
            if (needsScreen && depth < 8) {
                val s = SaweraAccessibilityService.instance?.readScreen()
                if (s == null) append("Sawera", "Screen access on nahi hai, Settings mein Sawera AI ko enable karein.")
                else handleUser("Yeh screen hai, agla step batao.", s, depth + 1)
                return@launch
            }
            if (listening) startRec()
        }
    }

    private fun runAction(a: JSONObject) {
        val svc = SaweraAccessibilityService.instance
        when (a.optString("type")) {
            "open_app" -> packageManager.getLaunchIntentForPackage(a.getString("package"))
                ?.let { startActivity(it) } ?: append("Sawera", "App install nahi mili: ${a.getString("package")}")
            "tap_text" -> if (svc?.tapText(a.getString("text")) != true) append("Sawera", "'${a.getString("text")}' nahi mila.")
            "type_text" -> svc?.typeText(a.getString("text"))
            "scroll" -> svc?.scroll(a.optString("dir", "down"))
            "back" -> svc?.back()
            "home" -> svc?.home()
            "schedule" -> append("Sawera", "Scheduled: ${a.getString("task")} @ ${Scheduler.schedule(this, a.getString("time"), a.getString("task"))}")
        }
    }

    override fun onDestroy() { recognizer?.destroy(); tts.shutdown(); super.onDestroy() }
}
