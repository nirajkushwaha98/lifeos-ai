"use strict";
/**
 * Retries and model fallback for AI calls (mirrors app/.../data/remote/api/FallbackChain.kt).
 *
 * Each route is `{ id, send(request) }` and resolves to one of:
 *   { type: "answer", text } | { type: "blocked" } | { type: "failure", kind, retryAfterMs }
 * where kind is "transient" (5xx/529/timeout/network), "rate_limited" (429 per-minute),
 * "quota_exhausted" (429 daily quota), "unavailable" (model gone / key rejected) or
 * "invalid_request" (400 — neither retrying nor switching models helps).
 */

const DEFAULT_POLICY = Object.freeze({
  attemptsPerRoute: 2,
  maxInlineWaitMs: 2500, // longer retry hints mean "use another model" instead of waiting
  backoffBaseMs: 500,
  jitterMs: 300,
  deadlineMs: 45000, // overall budget for one request across retries and fallbacks
  minAttemptMs: 3000, // don't start a retry that couldn't finish in the remaining budget
  transientCooldownMs: 20000,
  rateLimitCooldownMs: 60000,
  quotaCooldownMs: 3 * 3600 * 1000,
  unavailableCooldownMs: 6 * 3600 * 1000,
});

function createFallbackChain(routes, options = {}) {
  const policy = { ...DEFAULT_POLICY, ...(options.policy || {}) };
  const now = options.now || Date.now;
  const sleep = options.sleep || ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
  const jitter = options.jitter || ((bound) => Math.floor(Math.random() * (bound + 1)));
  const log = options.log || (() => {});
  const cooldowns = new Map(); // route id -> { until, kind }; per instance, best effort

  function retryWait(failure, attempt) {
    if (attempt >= policy.attemptsPerRoute) return null;
    let wait;
    if (failure.kind === "transient") wait = Math.max(policy.backoffBaseMs * 2 ** (attempt - 1), failure.retryAfterMs || 0);
    else if (failure.kind === "rate_limited" && failure.retryAfterMs != null) wait = failure.retryAfterMs;
    else return null;
    return wait > policy.maxInlineWaitMs ? null : wait + jitter(policy.jitterMs);
  }

  function coolDown(id, failure) {
    const fallback = {
      transient: policy.transientCooldownMs,
      rate_limited: policy.rateLimitCooldownMs,
      quota_exhausted: policy.quotaCooldownMs,
    }[failure.kind] ?? policy.unavailableCooldownMs;
    cooldowns.set(id, { until: now() + (failure.retryAfterMs ?? fallback), kind: failure.kind });
  }

  /** "Try again in a minute" beats "come back later today". */
  function summarize(reasons) {
    if (reasons.includes("rate_limited")) return "rate_limited";
    if (reasons.includes("transient")) return "transient";
    if (reasons.includes("quota_exhausted")) return "quota_exhausted";
    if (reasons.includes("unavailable")) return "unavailable";
    return "transient";
  }

  async function send(request) {
    if (!routes.length) return { ok: false, reason: "unavailable" };
    const start = now();
    const reasons = [];
    for (const route of routes) {
      const cooling = cooldowns.get(route.id);
      if (cooling && cooling.until > now()) {
        reasons.push(cooling.kind);
        continue;
      }
      for (let attempt = 1; ; attempt++) {
        const remaining = policy.deadlineMs - (now() - start);
        if (remaining <= 0) {
          log(`AI request ran out of time before ${route.id}`);
          return { ok: false, reason: "timeout" };
        }
        const result = await route.send(request);
        if (result.type === "answer") {
          cooldowns.delete(route.id);
          if (reasons.length || attempt > 1) log(`AI answered by ${route.id} (attempt ${attempt}; skipped: ${reasons.join(", ")})`);
          return { ok: true, text: result.text, model: route.id };
        }
        if (result.type === "blocked") return { ok: false, reason: "blocked" };
        if (result.kind === "invalid_request") return { ok: false, reason: "invalid_request" };
        const wait = retryWait(result, attempt);
        if (wait != null && wait < remaining - policy.minAttemptMs) {
          log(`${route.id}: ${result.kind}, retrying in ${wait}ms`);
          await sleep(wait);
          continue;
        }
        coolDown(route.id, result);
        reasons.push(result.kind);
        log(`${route.id}: ${result.kind}, trying the next model`);
        break;
      }
    }
    log(`AI unavailable on all ${routes.length} model(s): ${reasons.join(", ")}`);
    return { ok: false, reason: summarize(reasons) };
  }

  return { send };
}

module.exports = { createFallbackChain, DEFAULT_POLICY };
