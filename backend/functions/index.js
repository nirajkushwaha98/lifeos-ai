"use strict";
/**
 * LifeOS AI secure backend.
 *
 *   Android app --(Firebase Auth ID token, callable)--> aiAssist --(server-side keys)--> Gemini / Claude
 *
 * - API keys live ONLY in Secret Manager (`firebase functions:secrets:set GEMINI_API_KEY` and
 *   `ANTHROPIC_API_KEY`).
 * - Every call requires a signed-in Firebase user; inputs are validated; history is capped;
 *   per-user daily quotas are enforced in Firestore.
 * - Each request tries the configured Gemini models in order (each has its own quota), retrying
 *   transient errors with backoff, then Claude as a paid last resort (see src/fallback.js).
 */
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret, defineString, defineInt } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const AnthropicModule = require("@anthropic-ai/sdk");
const {
  ValidationError, validateRequest, buildGeminiBody, parseIntent, parseModels, classifyGemini, buildClaudeParams,
} = require("./src/validation");
const { createFallbackChain } = require("./src/fallback");

const Anthropic = AnthropicModule.default || AnthropicModule;

initializeApp();

const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
// Set to "none" to run without Claude; otherwise Claude answers only when every Gemini model is out of quota.
const ANTHROPIC_API_KEY = defineSecret("ANTHROPIC_API_KEY");
const GEMINI_MODELS = defineString("GEMINI_MODELS", {
  default: "gemini-3.5-flash-lite,gemini-3-flash-preview,gemini-3.1-flash-lite,gemini-2.5-flash",
});
const ANTHROPIC_MODEL = defineString("ANTHROPIC_MODEL", { default: "claude-opus-5-5" });
const DAILY_LIMIT = defineInt("AI_DAILY_LIMIT", { default: 100 });

async function enforceQuota(uid) {
  const day = new Date().toISOString().slice(0, 10);
  const ref = getFirestore().collection("aiUsage").doc(`${uid}_${day}`);
  const limit = DAILY_LIMIT.value();
  await getFirestore().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const count = snap.exists ? snap.get("count") || 0 : 0;
    if (count >= limit) throw new HttpsError("resource-exhausted", "Daily AI limit reached");
    tx.set(ref, { uid, day, count: FieldValue.increment(1), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  });
}

/** A Gemini model over REST. Timeouts and network errors come back as retryable failures. */
function geminiRoute(model, key) {
  return {
    id: model,
    async send(req) {
      const controller = new AbortController();
      // A model that hasn't answered a quick request in 15 s is overloaded; the next one usually answers in 2-4 s.
      const timer = setTimeout(() => controller.abort(), req.mode === "chat" && req.style !== "voice" ? 30000 : 15000);
      try {
        const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
          method: "POST",
          headers: { "Content-Type": "application/json", "x-goog-api-key": key },
          body: JSON.stringify(buildGeminiBody(req, { model })),
          signal: controller.signal,
        });
        const json = await res.json().catch(() => ({}));
        return classifyGemini(res.status, json, res.headers.get("retry-after"));
      } catch (e) {
        return { type: "failure", kind: "transient", retryAfterMs: null };
      } finally {
        clearTimeout(timer);
      }
    },
  };
}

function retryAfterMs(error) {
  const headers = error && error.headers;
  const raw = headers && (typeof headers.get === "function" ? headers.get("retry-after") : headers["retry-after"]);
  const seconds = parseFloat(raw);
  return Number.isFinite(seconds) ? Math.round(seconds * 1000) : null;
}

/** Claude through the official SDK; typed errors map onto the fallback chain's failure kinds. */
function claudeRoute(client, model) {
  return {
    id: model,
    async send(req) {
      try {
        const message = await client.beta.messages.create(buildClaudeParams(req, model));
        // Check the stop reason before reading content: a declined request carries no answer.
        if (message.stop_reason === "refusal") return { type: "blocked" };
        const text = message.content.filter((b) => b.type === "text").map((b) => b.text).join("").trim();
        return text ? { type: "answer", text } : { type: "failure", kind: "transient", retryAfterMs: null };
      } catch (e) {
        if (e instanceof Anthropic.RateLimitError) return { type: "failure", kind: "rate_limited", retryAfterMs: retryAfterMs(e) };
        if (e instanceof Anthropic.InternalServerError) return { type: "failure", kind: "transient", retryAfterMs: retryAfterMs(e) }; // 500, 529 overloaded
        if (e instanceof Anthropic.AuthenticationError || e instanceof Anthropic.PermissionDeniedError || e instanceof Anthropic.NotFoundError) {
          return { type: "failure", kind: "unavailable" };
        }
        if (e instanceof Anthropic.BadRequestError) {
          // An empty prepaid balance is a 400 with no distinct error type — only its message says so.
          return { type: "failure", kind: /credit balance/i.test(e.message || "") ? "quota_exhausted" : "invalid_request" };
        }
        if (e instanceof Anthropic.APIConnectionError) return { type: "failure", kind: "transient", retryAfterMs: null }; // network, timeout
        if (e instanceof Anthropic.APIError) return { type: "failure", kind: e.status === 402 ? "quota_exhausted" : "invalid_request" };
        throw e;
      }
    },
  };
}

let chain = null;

/** One chain per instance, so models found exhausted stay skipped for later requests. */
function getChain() {
  if (chain) return chain;
  const routes = [];
  const geminiKey = GEMINI_API_KEY.value();
  if (geminiKey) parseModels(GEMINI_MODELS.value()).forEach((m) => routes.push(geminiRoute(m, geminiKey)));
  const claudeKey = ANTHROPIC_API_KEY.value();
  if (claudeKey && claudeKey.startsWith("sk-ant-")) {
    // The fallback chain owns retries, so the SDK's own are off and attempts don't multiply.
    const client = new Anthropic({ apiKey: claudeKey, maxRetries: 0, timeout: 30000 });
    routes.push(claudeRoute(client, ANTHROPIC_MODEL.value()));
  }
  chain = createFallbackChain(routes, { log: (msg) => logger.info(msg) });
  return chain;
}

/** How a failed chain is reported to the app (see mapFunctionsErrorCode in AiService.kt). */
const FAILURES = {
  rate_limited: ["resource-exhausted", "AI is busy right now, please retry shortly"],
  quota_exhausted: ["resource-exhausted", "Daily AI quota used up on every model"],
  transient: ["unavailable", "AI service unreachable"],
  timeout: ["deadline-exceeded", "AI request timed out"],
  unavailable: ["failed-precondition", "AI service not configured"],
  blocked: ["failed-precondition", "Response blocked by safety filters"],
  invalid_request: ["invalid-argument", "Request rejected by AI provider"],
};

exports.aiAssist = onCall(
  {
    secrets: [GEMINI_API_KEY, ANTHROPIC_API_KEY],
    region: "asia-south1",
    timeoutSeconds: 60,
    memory: "256MiB",
    maxInstances: 20,
    // Set enforceAppCheck: true after enabling App Check (Play Integrity) in the console.
    enforceAppCheck: false,
  },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign in required");
    let req;
    try {
      req = validateRequest(request.data);
    } catch (e) {
      if (e instanceof ValidationError) throw new HttpsError("invalid-argument", e.message);
      throw e;
    }
    await enforceQuota(request.auth.uid);
    const result = await getChain().send(req);
    if (!result.ok) {
      const [code, message] = FAILURES[result.reason] || FAILURES.transient;
      throw new HttpsError(code, message);
    }
    if (req.mode === "intent") return { intent: parseIntent(result.text) };
    return { text: result.text };
  },
);
