package com.lifeos.ai.data.remote.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One AI request, independent of which provider answers it. [turns] is the conversation in order
 * and ends with the user's message; each [AiRoute] renders this into its own wire format.
 */
data class AiPrompt(
    val system: String?,
    val turns: List<Turn>,
    val temperature: Double,
    val maxTokens: Int,
    /** A short, fast answer is wanted (voice replies, intents, note tools): routes keep reasoning minimal. */
    val quick: Boolean,
    /** The answer must be a single JSON object. */
    val json: Boolean = false,
) {
    data class Turn(val fromUser: Boolean, val text: String)
}

/**
 * Prompts shared by every provider. Mirrors backend/functions/src/validation.js so a debug build
 * behaves like the secure backend — keep prompts, limits and the intent whitelist in sync.
 */
object AiPrompts {

    private val SYSTEM_CHAT = listOf(
        "You are LifeOS AI, a friendly, concise personal assistant inside a daily-life Android app used in India.",
        "Reply in the same language style the user writes in: English, Hindi (Devanagari) or Hinglish (Roman Hindi).",
        "Use short Markdown (headings, bullet lists, bold) when it helps readability.",
        "You cannot directly create reminders, expenses, notes or tasks; the app handles those commands itself after user confirmation.",
        "Never claim that you saved, scheduled or changed anything. If asked, tell the user to say e.g. 'Kal 7 baje reminder laga do'.",
        "Do not give personalised financial, legal or medical advice; give general information and suggest a professional when appropriate.",
    ).joinToString(" ")

    private val SYSTEM_VOICE = listOf(
        "You are LifeOS AI, a warm personal assistant in a live voice conversation inside a daily-life Android app used in India.",
        "Your reply is read aloud by text-to-speech, so write exactly the way a person speaks.",
        "Keep it short: one to three sentences (about 60 words at most) unless the user explicitly asks for more detail.",
        "Use plain sentences only: no Markdown, lists, headings, emojis, tables, links or code.",
        "Reply in the language the user spoke: Hindi in Devanagari script, English, or Hinglish in Roman script.",
        "You cannot directly create reminders, expenses, notes or tasks; the app handles those commands itself after the user confirms.",
        "Never claim that you saved, scheduled or changed anything.",
        "Do not give personalised financial, legal or medical advice; give general information and suggest a professional when appropriate.",
    ).joinToString(" ")

    private val TOOL_PROMPTS = mapOf(
        NoteTool.SUMMARIZE to "Summarize the following note in 3-6 concise bullet points, in the same language as the note. Output only the summary.",
        NoteTool.REWRITE to "Rewrite the following note to be clear, well-structured and grammatically correct, keeping the same language and meaning. Output only the rewritten text.",
        NoteTool.TRANSLATE_HI to "Translate the following text into natural Hindi (Devanagari script). Output only the translation.",
        NoteTool.TRANSLATE_EN to "Translate the following text into natural English. Output only the translation.",
        NoteTool.CHECKLIST to "Turn the following note into a practical checklist. Output ONLY the items, one per line, each starting with '- ', no headings, max 25 items, same language as the note.",
    )

    private val ALLOWED_INTENTS = setOf(
        "create_reminder", "add_transaction", "add_shopping_items", "create_note", "create_task", "query_expenses", "query_schedule",
    )
    private val STRING_FIELDS = listOf("title", "datetime", "repeat", "kind", "category", "description", "content", "due", "period")
    private val TIME_ZONE = Regex("^[A-Za-z_/+\\-0-9]{1,64}$")
    private val ISO_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}.*")

    /** [history] comes from [buildHistoryPayload]: roles "user" / "model". */
    fun chat(message: String, history: List<Map<String, String>>, style: ChatStyle): AiPrompt {
        val voice = style == ChatStyle.VOICE
        val turns = history.map { AiPrompt.Turn(fromUser = it["role"] == "user", text = it["content"].orEmpty()) } +
            AiPrompt.Turn(fromUser = true, text = message)
        return AiPrompt(
            system = if (voice) SYSTEM_VOICE else SYSTEM_CHAT,
            turns = turns,
            temperature = if (voice) 0.7 else 0.6,
            maxTokens = if (voice) 1024 else 2048,
            quick = voice,
        )
    }

    fun intent(message: String, nowIso: String, timeZone: String): AiPrompt {
        val now = nowIso.takeIf { ISO_TIME.matches(it) }?.take(19) ?: nowIso.take(19)
        val zone = timeZone.takeIf { TIME_ZONE.matches(it) } ?: "Asia/Kolkata"
        return AiPrompt(
            system = null,
            turns = listOf(AiPrompt.Turn(fromUser = true, text = intentPrompt(message, now, zone))),
            temperature = 0.0,
            maxTokens = 1024,
            quick = true,
            json = true,
        )
    }

    fun tool(tool: NoteTool, text: String): AiPrompt = AiPrompt(
        system = null,
        turns = listOf(AiPrompt.Turn(fromUser = true, text = "${TOOL_PROMPTS.getValue(tool)}\n\n---\n$text")),
        temperature = 0.3,
        maxTokens = 2048,
        quick = true,
    )

    private fun intentPrompt(message: String, now: String, timeZone: String): String = listOf(
        "You convert a user's command for a personal organiser app into JSON. The user may write English, Hindi or Hinglish.",
        "Current local date-time: $now (time zone $timeZone). 'kal' in a future-action context means tomorrow, 'parso' the day after.",
        "Return ONLY one JSON object, no prose. Allowed shapes:",
        """{"type":"create_reminder","title":string,"datetime":"YYYY-MM-DDTHH:mm" or null,"repeat":"none"|"daily"|"weekly"|"monthly"}""",
        """{"type":"add_transaction","amount":number,"kind":"expense"|"income","category":"food"|"shopping"|"travel"|"education"|"bills"|"health"|"entertainment"|"salary"|"business"|"gift"|"other","description":string}""",
        """{"type":"add_shopping_items","items":[string]}""",
        """{"type":"create_note","title":string,"content":string}""",
        """{"type":"create_task","title":string,"due":"YYYY-MM-DDTHH:mm" or null}""",
        """{"type":"query_expenses","period":"today"|"month"}""",
        """{"type":"query_schedule","day_offset":integer}""",
        """{"type":"none"}  (use when it is not one of these commands, or when unsure)""",
        "Never invent amounts or times that the user did not state.",
        "User command: ${JsonPrimitive(message)}",
    ).joinToString("\n")

    /** Parses and whitelists the model's intent JSON; null when it isn't a supported action. */
    fun parseIntent(text: String): Map<String, Any?>? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (type !in ALLOWED_INTENTS) return null
        // Copy only known primitive fields; RemoteIntentMapper validates everything again.
        val out = linkedMapOf<String, Any?>("type" to type)
        for (key in STRING_FIELDS) {
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.let { out[key] = it.content.take(5000) }
        }
        (obj["amount"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }?.let { out["amount"] = it }
        (obj["day_offset"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()?.let { out["day_offset"] = it }
        (obj["items"] as? JsonArray)?.let { items ->
            out["items"] = items.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.take(20).map { it.take(60) }
        }
        return out
    }
}
