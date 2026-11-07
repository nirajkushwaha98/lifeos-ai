"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { createFallbackChain } = require("../src/fallback");

/** A fake model that returns scripted results; each call can take `costMs` of virtual time. */
function model(id, results, clock, costMs = 100) {
  const script = [...results];
  return {
    id,
    calls: 0,
    async send() {
      this.calls += 1;
      clock.now += costMs;
      if (!script.length) throw new Error(`${id} called more often than expected`);
      return script.shift();
    },
  };
}

function setup() {
  const clock = { now: 0 };
  const waits = [];
  const make = (routes) => createFallbackChain(routes, {
    now: () => clock.now,
    sleep: async (ms) => { waits.push(ms); clock.now += ms; },
    jitter: () => 0,
  });
  return { clock, waits, make };
}

const fail = (kind, retryAfterMs = null) => ({ type: "failure", kind, retryAfterMs });
const answer = (text) => ({ type: "answer", text });

test("a model out of daily quota is skipped now and on later requests", async () => {
  const { clock, waits, make } = setup();
  const first = model("gemini-2.5-flash", [fail("quota_exhausted", 4 * 3600 * 1000)], clock);
  const second = model("gemini-3.5-flash-lite", [answer("A"), answer("B")], clock);
  const chain = make([first, second]);
  assert.deepEqual(await chain.send({}), { ok: true, text: "A", model: "gemini-3.5-flash-lite" });
  assert.deepEqual(await chain.send({}), { ok: true, text: "B", model: "gemini-3.5-flash-lite" });
  assert.equal(first.calls, 1);
  assert.deepEqual(waits, []);
});

test("a transient error is retried once with backoff, honouring the retry hint", async () => {
  const { clock, waits, make } = setup();
  const busy = model("a", [fail("transient"), answer("ok")], clock);
  assert.equal((await make([busy]).send({})).text, "ok");
  assert.deepEqual(waits, [500]);

  const hinted = model("b", [fail("transient", 900), fail("transient")], clock);
  const next = model("c", [answer("next")], clock);
  assert.equal((await make([hinted, next]).send({})).text, "next");
  assert.deepEqual(waits, [500, 900]);
});

test("a short rate-limit pause is honoured but a long one switches models", async () => {
  const { clock, waits, make } = setup();
  const shortPause = model("a", [fail("rate_limited", 1000), answer("waited")], clock);
  assert.equal((await make([shortPause]).send({})).text, "waited");
  assert.deepEqual(waits, [1000]);

  const longPause = model("b", [fail("rate_limited", 30000)], clock);
  const other = model("c", [answer("x"), answer("y")], clock);
  const chain = make([longPause, other]);
  assert.equal((await chain.send({})).text, "x");
  assert.equal((await chain.send({})).text, "y");
  assert.equal(longPause.calls, 1);
  assert.deepEqual(waits, [1000]);
});

test("invalid requests and safety blocks stop the chain", async () => {
  const { clock, make } = setup();
  const untouched = model("z", [], clock);
  assert.deepEqual(await make([model("a", [fail("invalid_request")], clock), untouched]).send({}), { ok: false, reason: "invalid_request" });
  assert.deepEqual(await make([model("b", [{ type: "blocked" }], clock), untouched]).send({}), { ok: false, reason: "blocked" });
  assert.equal(untouched.calls, 0);
});

test("when every model is out of quota the caller hears it without new calls", async () => {
  const { clock, make } = setup();
  const a = model("a", [fail("quota_exhausted")], clock);
  const b = model("b", [fail("unavailable")], clock);
  const chain = make([a, b]);
  assert.deepEqual(await chain.send({}), { ok: false, reason: "quota_exhausted" });
  assert.deepEqual(await chain.send({}), { ok: false, reason: "quota_exhausted" });
  assert.equal(a.calls + b.calls, 2);
});

test("a per-minute limit is reported ahead of daily exhaustion", async () => {
  const { clock, make } = setup();
  const chain = make([model("a", [fail("quota_exhausted")], clock), model("b", [fail("rate_limited")], clock)]);
  assert.deepEqual(await chain.send({}), { ok: false, reason: "rate_limited" });
});

test("the overall deadline stops further attempts", async () => {
  const { clock, make } = setup();
  const slow = model("slow", [fail("transient")], clock, 46000);
  const never = model("never", [], clock);
  assert.deepEqual(await make([slow, never]).send({}), { ok: false, reason: "timeout" });
  assert.equal(never.calls, 0);
});
