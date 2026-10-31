package com.sawera.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent memory: user profile + facts + recent chat, stored locally. */
class Memory(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sawera_memory", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v).apply()

    var userName: String
        get() = sp.getString("user_name", "Faraz Boss") ?: "Faraz Boss"
        set(v) = sp.edit().putString("user_name", v).apply()

    fun facts(): List<String> {
        val arr = JSONArray(sp.getString("facts", "[]"))
        return List(arr.length()) { arr.getString(it) }
    }

    fun addFact(f: String) {
        val arr = JSONArray(sp.getString("facts", "[]"))
        if (facts().contains(f)) return
        arr.put(f)
        sp.edit().putString("facts", arr.toString()).apply()
    }

    fun history(): JSONArray = JSONArray(sp.getString("history", "[]"))

    fun addTurn(role: String, text: String) {
        val arr = history()
        arr.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text))))
        // keep last 30 turns
        val trimmed = JSONArray()
        for (i in maxOf(0, arr.length() - 30) until arr.length()) trimmed.put(arr.get(i))
        sp.edit().putString("history", trimmed.toString()).apply()
    }
}
