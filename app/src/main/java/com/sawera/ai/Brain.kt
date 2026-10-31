package com.sawera.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** text = screen (Roman Urdu), speech = voice (Hindi, Devanagari). */
data class Reply(val text: String, val speech: String, val actions: List<JSONObject>, val remember: List<String>)

class Brain(private val mem: Memory) {

    // Tried in order. If Google retires one (404), the next is used automatically.
    private val models = listOf("gemini-3.8-flash", "gemini-flash-latest", "gemini-2.5-flash")

    private fun systemPrompt() = """
Tum "Sawera AI" ho — ek smart, pyaari aur caring voice assistant jo Android phone par chalti hai.
User ka naam: ${mem.userName}. Hamesha unhe "${mem.userName}" keh kar bulao.
Tumhara andaaz: dosti bhara, narm, chhota aur natural. Robot jaisa nahi. Jawab 1-3 chhote jumlon mein do.
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
 {"type":"schedule","time":"17:00","task":"video banao"}          // 24h format HH:mm
Agar kaam clear nahi, to chhota sa sawal poochho aur actions khali rakho.
""".trimIndent()

    private fun call(model: String, body: JSONObject): Pair<Int, String> {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${mem.apiKey}")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true
        c.connectTimeout = 20000; c.readTimeout = 60000
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to raw
    }

    private fun errorReply(code: Int, raw: String): Reply {
        val apiMsg = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
        val (urdu, hindi) = when (code) {
            400, 403 -> "API key galat hai ya kaam nahi kar rahi. Dobara check karein." to "एपीआई की गलत लग रही है, एक बार फिर जाँच लीजिए।"
            429 -> "Free limit khatam ho gayi, thori der baad try karein." to "लिमिट खत्म हो गई है, थोड़ी देर बाद कोशिश कीजिए।"
            -1 -> "Internet ya network ka masla hai." to "इंटरनेट में दिक्कत है, कनेक्शन देख लीजिए।"
            else -> "Gemini se jawab nahi aaya." to "जेमिनी से जवाब नहीं आया।"
        }
        return Reply("⚠️ $urdu\n(Code $code${if (!apiMsg.isNullOrBlank()) ": " + apiMsg.take(160) else ""})", hindi, emptyList(), emptyList())
    }

    suspend fun ask(userText: String, screenContext: String? = null): Reply = withContext(Dispatchers.IO) {
        if (mem.apiKey.isBlank()) {
            return@withContext Reply("⚠️ Pehle Gemini API key daalein (upar 'API key' button).", "पहले जेमिनी एपीआई की डालिए।", emptyList(), emptyList())
        }
        val content = if (screenContext != null) "$userText\n\n[SCREEN]\n$screenContext" else userText
        val userTurn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", content)))
        val contents = mem.history().put(userTurn)

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        var lastCode = -1
        var lastRaw = ""
        for (m in models + models) {
            try {
                val (code, raw) = call(m, body)
                lastCode = code; lastRaw = raw
                if (code in 200..299) {
                    val textOut = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    mem.addTurn("user", content)
                    mem.addTurn("model", textOut)
                    return@withContext try {
                        val j = JSONObject(textOut)
                        val acts = j.optJSONArray("actions") ?: JSONArray()
                        val rem = j.optJSONArray("remember") ?: JSONArray()
                        Reply(
                            j.optString("text").ifBlank { textOut }, j.optString("speech"),
                            List(acts.length()) { acts.getJSONObject(it) },
                            List(rem.length()) { rem.getString(it) }
                        )
                    } catch (e: Exception) {
                        Reply(textOut, textOut, emptyList(), emptyList())
                    }
                }
                if (code !in listOf(404, 429, 500, 503)) break else Thread.sleep(1500)
            } catch (e: Exception) {
                lastCode = -1; lastRaw = e.message ?: ""
                break
            }
        }
        errorReply(lastCode, lastRaw)
    }
}
