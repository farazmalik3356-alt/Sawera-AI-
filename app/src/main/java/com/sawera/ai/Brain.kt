package com.sawera.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** What Sawera decided: text for screen (Roman Urdu), speech (Hindi, Devanagari), and optional actions. */
data class Reply(val text: String, val speech: String, val actions: List<JSONObject>, val remember: List<String>)

class Brain(private val mem: Memory) {

    // Change if Google renames the model.
    private val model = "gemini-2.5-flash"

    private fun systemPrompt() = """
Tum "Sawera AI" ho — ek smart, caring voice assistant jo ek Android phone par chalti hai.
User ka naam: ${mem.userName}. Hamesha unhe "${mem.userName}" keh kar bulao.
Tumhara andaaz: dosti bhara, pyaar aur ehtiram wala, chhota aur natural. Robot jaisa nahi.
Yaad rakhi hui baatein: ${mem.facts().joinToString("; ").ifEmpty { "abhi kuch nahi" }}

HAMESHA sirf ek valid JSON object do, aur kuch nahi:
{
 "text": "screen par dikhane ke liye jawab, ROMAN URDU mein (Latin letters)",
 "speech": "wohi jawab bolne ke liye, HINDI mein DEVANAGARI script mein",
 "actions": [ ... ],
 "remember": [ "naye zaroori facts jo yaad rakhne chahiye" ]
}

Allowed actions (jab user koi kaam kahe):
 {"type":"open_app","package":"com.lemon.lvoverseas"}            // CapCut
 {"type":"tap_text","text":"Export"}                               // screen par us text wale button par tap
 {"type":"type_text","text":"..."}
 {"type":"scroll","dir":"down"}
 {"type":"back"} {"type":"home"}
 {"type":"read_screen"}                                            // screen ka text mujhe do, phir main agla step bataungi
 {"type":"schedule","time":"17:00","task":"video banao"}          // 24h format HH:mm, aaj ya agle aane wale waqt par
Agar kaam clear nahi, to chhota sa sawal poochho aur actions khali rakho.
""".trimIndent()

    suspend fun ask(userText: String, screenContext: String? = null): Reply = withContext(Dispatchers.IO) {
        val content = if (screenContext != null) "$userText\n\n[SCREEN]\n$screenContext" else userText
        mem.addTurn("user", content)

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", mem.history())
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${mem.apiKey}")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true
        c.connectTimeout = 20000; c.readTimeout = 60000
        c.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) {
            return@withContext Reply("Error $code: $raw".take(300), "माफ़ कीजिए, कनेक्शन में दिक्कत है।", emptyList(), emptyList())
        }

        val textOut = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        mem.addTurn("model", textOut)

        val j = JSONObject(textOut)
        val acts = j.optJSONArray("actions") ?: JSONArray()
        val rem = j.optJSONArray("remember") ?: JSONArray()
        Reply(
            j.optString("text"), j.optString("speech"),
            List(acts.length()) { acts.getJSONObject(it) },
            List(rem.length()) { rem.getString(it) }
        )
    }
}
