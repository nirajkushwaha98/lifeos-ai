"use strict";
/**
 * Pure input validation & prompt building for the LifeOS AI backend.
 * No Firebase imports here, so it can be unit tested with `node --test`.
 */

const LIMITS = Object.freeze({
  MAX_MESSAGE_CHARS: 4000,
  MAX_TOOL_CHARS: 8000,
  MAX_INTENT_CHARS: 1000,
  MAX_HISTORY: 10,
  MAX_HISTORY_CHARS_EACH: 1500,
});

const MODES = new Set(["chat", "intent", "tool"]);
const TOOLS = new Set(["summarize", "rewrite", "translate_hi", "translate_en", "checklist"]);

class ValidationError extends Error {}

function cleanString(value, max) {
  if (typeof value !== "string") return null;
  // Strip control characters except newlines/tabs.
  const s = value.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, "").trim();
  if (!s || s.length > max) return null;
  return s;
}

/** Validates the callable payload and returns a normalized request object. Throws ValidationError. */
function validateRequest(data) {
  if (!data || typeof data !== "object") throw new ValidationError("Missing payload");
  const mode = data.mode;
  if (!MODES.has(mode)) throw new ValidationError("Invalid mode");

  if (mode === "chat") {
    const message = cleanString(data.message, LIMITS.MAX_MESSAGE_CHARS);
    if (!message) throw new ValidationError("Invalid message");
    const rawHistory = Array.isArray(data.history) ? data.history.slice(-LIMITS.MAX_HISTORY) : [];
    const history = [];
    for (const h of rawHistory) {
      if (!h || (h.role !== "user" && h.role !== "model")) continue;
      const content = typeof h.content === "string" ? h.content.slice(0, LIMITS.MAX_HISTORY_CHARS_EACH).trim() : "";
      if (content) history.push({ role: h.role, content });
    }
    // "voice" = the answer is read aloud in the app's voice mode; anything else is the normal text chat.
    const style = data.style === "voice" ? "voice" : "text";
    return { mode, message, history, style };
  }

  if (mode === "intent") {
    const message = cleanString(data.message, LIMITS.MAX_INTENT_CHARS);
    if (!message) throw new ValidationError("Invalid message");
    const now = typeof data.now === "string" && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(data.now) ? data.now.slice(0, 19) : null;
    const timeZone = typeof data.timeZone === "string" && /^[A-Za-z_\/+\-0-9]{1,64}$/.test(data.timeZone) ? data.timeZone : "Asia/Kolkata";
    if (!now) throw new ValidationError("Invalid time");
    return { mode, message, now, timeZone };
  }

  // tool
  const tool = data.tool;
  if (!TOOLS.has(tool)) throw new ValidationError("Invalid tool");
  const message = cleanString(data.message, LIMITS.MAX_TOOL_CHARS);
  if (!message) throw new ValidationError("Invalid text");
  return { mode, tool, message };
}

const SYSTEM_CHAT = [
  "You are LifeOS AI, a friendly, concise personal assistant inside a daily-life Android app used in India.",
  "Reply in the same language style the user writes in: English, Hindi (Devanagari) or Hinglish (Roman Hindi).",
  "Use short Markdown (headings, bullet lists, bold) when it helps readability.",
  "You cannot directly create reminders, expenses, notes or tasks; the app handles those commands itself after user confirmation.",
  "Never claim that you saved, scheduled or changed anything. If asked, tell the user to say e.g. 'Kal 7 baje reminder laga do'.",
  "Do not give personalised financial, legal or medical advice; give general information and suggest a professional when appropriate.",
].join(" ");

const SYSTEM_VOICE = [
  "You are LifeOS AI, a warm personal assistant in a live voice conversation inside a daily-life Android app used in India.",
  "Your reply is read aloud by text-to-speech, so write exactly the way a person speaks.",
  "Keep it short: one to three sentences (about 60 words at most) unless the user explicitly asks for more detail.",
  "Use plain sentences only: no Markdown, lists, headings, emojis, tables, links or code.",
  "Reply in the language the user spoke: Hindi in Devanagari script, English, or Hinglish in Roman script.",
  "You cannot directly create reminders, expenses, notes or tasks; the app handles those commands itself after the user confirms.",
  "Never claim that you saved, scheduled or changed anything.",
  "Do not give personalised financial, legal or medical advice; give general information and suggest a professional when appropriate.",
].join(" ");

const TOOL_PROMPTS = {
  summarize: "Summarize the following note in 3-6 concise bullet points, in the same language as the note. Output only the summary.",
  rewrite: "Rewrite the following note to be clear, well-structured and grammatically correct, keeping the same language and meaning. Output only the rewritten text.",
  translate_hi: "Translate the following text into natural Hindi (Devanagari script). Output only the translation.",
  translate_en: "Translate the following text into natural English. Output only the translation.",
  checklist: "Turn the following note into a practical checklist. Output ONLY the items, one per line, each starting with '- ', no headings, max 25 items, same language as the note.",
};

function intentPrompt(message, now, timeZone) {
  return [
    "You convert a user's command for a personal organiser app into JSON. The user may write English, Hindi or Hinglish.",
    `Current local date-time: ${now} (time zone ${timeZone}). 'kal' in a future-action context means tomorrow, 'parso' the day after.`,
    "Return ONLY one JSON object, no prose. Allowed shapes:",
    '{"type":"create_reminder","title":string,"datetime":"YYYY-MM-DDTHH:mm" or null,"repeat":"none"|"daily"|"weekly"|"monthly"}',
    '{"type":"add_transaction","amount":number,"kind":"expense"|"income","category":"food"|"shopping"|"travel"|"education"|"bills"|"health"|"entertainment"|"salary"|"business"|"gift"|"other","description":string}',
    '{"type":"add_shopping_items","items":[string]}',
    '{"type":"create_note","title":string,"content":string}',
    '{"type":"create_task","title":string,"due":"YYYY-MM-DDTHH:mm" or null}',
    '{"type":"query_expenses","period":"today"|"month"}',
    '{"type":"query_schedule","day_offset":integer}',
    '{"type":"none"}  (use when it is not one of these commands, or when unsure)',
    "Never invent amounts or times that the user did not state.",
    `User command: ${JSON.stringify(message)}`,
  ].join("\n");
}

/**
 * "Thinking" settings per model family. Thinking tokens count against maxOutputTokens, so without a
 * budget a small limit can be used up before any answer is written (finishReason MAX_TOKENS).
 * Structured intents, note tools and spoken replies don't need deep reasoning and should be fast.
 * Unknown families (e.g. "-latest" aliases) keep their own defaults.
 */
function thinkingConfigFor(model, quick) {
  if (typeof model !== "string") return undefined;
  if (/^gemini-2\.5-flash/.test(model)) return { thinkingBudget: quick ? 0 : 512 };
  if (/^gemini-2\.5-pro/.test(model)) return { thinkingBudget: quick ? 128 : 1024 };
  if (/^gemini-3/.test(model)) return { thinkingLevel: model.includes("pro") ? "low" : quick ? "minimal" : "low" };
  return undefined;
}

/** True when a request wants a short, fast answer (spoken replies, intents, note tools). */
function isQuick(req) {
  return req.mode !== "chat" || req.style === "voice";
}

/**
 * Builds the Gemini generateContent request body.
 * `options.model` is the model id (used to pick thinking settings).
 */
function buildGeminiBody(req, options = {}) {
  const voice = req.mode === "chat" && req.style === "voice";
  const model = typeof options.model === "string" ? options.model : "";
  const thinking = thinkingConfigFor(model, isQuick(req));
  const config = (c) => {
    const out = { ...c };
    if (/^gemini-3/.test(model)) delete out.temperature; // Google recommends the default for Gemini 3
    if (thinking) out.thinkingConfig = thinking;
    return out;
  };
  if (req.mode === "chat") {
    const contents = req.history.map((h) => ({ role: h.role, parts: [{ text: h.content }] }));
    contents.push({ role: "user", parts: [{ text: req.message }] });
    return {
      systemInstruction: { parts: [{ text: voice ? SYSTEM_VOICE : SYSTEM_CHAT }] },
      contents,
      generationConfig: config({ temperature: voice ? 0.7 : 0.6, maxOutputTokens: voice ? 1024 : 2048 }),
    };
  }
  if (req.mode === "intent") {
    return {
      contents: [{ role: "user", parts: [{ text: intentPrompt(req.message, req.now, req.timeZone) }] }],
      generationConfig: config({ temperature: 0, maxOutputTokens: 1024, responseMimeType: "application/json" }),
    };
  }
  return {
    contents: [{ role: "user", parts: [{ text: `${TOOL_PROMPTS[req.tool]}\n\n---\n${req.message}` }] }],
    generationConfig: config({ temperature: 0.3, maxOutputTokens: 2048 }),
  };
}

/** Extracts text from a Gemini response; returns {text} or {blocked:true}. */
function extractText(json) {
  if (json && json.promptFeedback && json.promptFeedback.blockReason) return { blocked: true };
  const cand = json && Array.isArray(json.candidates) ? json.candidates[0] : null;
  if (!cand) return { text: "" };
  if (cand.finishReason === "SAFETY" || cand.finishReason === "PROHIBITED_CONTENT") return { blocked: true };
  const parts = (cand.content && cand.content.parts) || [];
  return { text: parts.map((p) => (typeof p.text === "string" ? p.text : "")).join("").trim() };
}

const ALLOWED_INTENTS = new Set([
  "create_reminder", "add_transaction", "add_shopping_items", "create_note", "create_task", "query_expenses", "query_schedule",
]);

/** Parses and whitelists the model's intent JSON. Returns null when not a supported action. */
function parseIntent(text) {
  let obj;
  try {
    const start = text.indexOf("{");
    const end = text.lastIndexOf("}");
    if (start < 0 || end <= start) return null;
    obj = JSON.parse(text.slice(start, end + 1));
  } catch (_) {
    return null;
  }
  if (!obj || typeof obj !== "object" || !ALLOWED_INTENTS.has(obj.type)) return null;
  // Copy only known primitive fields; the app re-validates everything again.
  const out = { type: obj.type };
  for (const k of ["title", "datetime", "repeat", "kind", "category", "description", "content", "due", "period"]) {
    if (typeof obj[k] === "string") out[k] = obj[k].slice(0, 5000);
  }
  if (typeof obj.amount === "number" && Number.isFinite(obj.amount)) out.amount = obj.amount;
  if (Number.isInteger(obj.day_offset)) out.day_offset = obj.day_offset;
  if (Array.isArray(obj.items)) out.items = obj.items.filter((i) => typeof i === "string").slice(0, 20).map((i) => i.slice(0, 60));
  return out;
}

/** Parses a comma-separated model list, dropping blanks, duplicates and malformed ids. */
function parseModels(csv) {
  const ids = String(csv || "").split(",").map((m) => m.trim()).filter((m) => /^[A-Za-z0-9._-]{1,64}$/.test(m));
  return [...new Set(ids)];
}

/** Parses "15769s" / "7.5s" (google.rpc.RetryInfo) or a retry-after header in seconds into ms. */
function delayMs(value, suffixed) {
  const m = suffixed ? /^(\d+(?:\.\d+)?)s$/.exec(String(value || "").trim()) : /^(\d+(?:\.\d+)?)$/.exec(String(value || "").trim());
  return m ? Math.round(parseFloat(m[1]) * 1000) : null;
}

/**
 * Classifies one Gemini HTTP exchange for the fallback chain. Free-tier 429s come in two kinds:
 * a per-minute limit (worth a short wait) and a per-day quota (`...PerDay...` quota id), which
 * resets only at midnight Pacific time — that model is skipped until then.
 */
function classifyGemini(status, json, retryAfterHeader) {
  const headerDelay = delayMs(retryAfterHeader, false);
  if (status >= 200 && status < 300) {
    const { text, blocked } = extractText(json);
    if (blocked) return { type: "blocked" };
    return text ? { type: "answer", text } : { type: "failure", kind: "transient", retryAfterMs: null };
  }
  if (status === 429) {
    let perDay = false;
    let retry = null;
    for (const d of (json && json.error && json.error.details) || []) {
      const t = String((d && d["@type"]) || "");
      if (t.endsWith("QuotaFailure")) perDay = perDay || (d.violations || []).some((v) => String(v.quotaId || "").includes("PerDay"));
      if (t.endsWith("RetryInfo")) retry = delayMs(d.retryDelay, true);
    }
    return { type: "failure", kind: perDay ? "quota_exhausted" : "rate_limited", retryAfterMs: retry ?? headerDelay };
  }
  const raw = JSON.stringify(json || {});
  if (status === 400 && (raw.includes("API_KEY_INVALID") || raw.includes("API key not valid"))) return { type: "failure", kind: "unavailable" };
  if (status === 401 || status === 403 || status === 404) return { type: "failure", kind: "unavailable" };
  if (status === 408 || status >= 500) return { type: "failure", kind: "transient", retryAfterMs: headerDelay };
  return { type: "failure", kind: "invalid_request" };
}

/**
 * Builds Claude Messages API params (for client.beta.messages.create) from a validated request.
 * No temperature: current Claude models reject sampling parameters. Thinking is adaptive and
 * counts toward max_tokens, so quick requests still get room. Refusals are re-run server-side on
 * Anthropic's recommended model (`fallbacks: "default"`), except on Haiku, which has no fallback.
 */
function buildClaudeParams(req, model) {
  const quick = isQuick(req);
  let system;
  let messages;
  if (req.mode === "chat") {
    system = req.style === "voice" ? SYSTEM_VOICE : SYSTEM_CHAT;
    messages = req.history.map((h) => ({ role: h.role === "model" ? "assistant" : "user", content: h.content }));
    while (messages.length && messages[0].role !== "user") messages.shift(); // first message must be the user's
    messages.push({ role: "user", content: req.message });
  } else if (req.mode === "intent") {
    messages = [{ role: "user", content: intentPrompt(req.message, req.now, req.timeZone) }];
  } else {
    messages = [{ role: "user", content: `${TOOL_PROMPTS[req.tool]}\n\n---\n${req.message}` }];
  }
  const params = {
    model,
    max_tokens: quick ? 4096 : 16000,
    messages,
    output_config: { effort: quick ? "low" : "medium" },
  };
  if (system) params.system = system;
  if (!model.includes("haiku")) {
    params.betas = ["server-side-fallback-2026-07-01"];
    params.fallbacks = "default";
  }
  return params;
}

module.exports = {
  LIMITS, ValidationError, validateRequest, buildGeminiBody, extractText, parseIntent,
  parseModels, classifyGemini, buildClaudeParams, thinkingConfigFor,
};
