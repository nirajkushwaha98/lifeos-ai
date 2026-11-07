# LifeOS AI — Your Everyday Life, Smarter.

![LifeOS AI icon — circle, squircle and themed variants](docs/icon-preview.png)

An offline-first Android super-app for daily life: **tasks, reminders, expenses, notes, documents, an AI assistant and a Hindi/English voice assistant** — built with Kotlin, Jetpack Compose (Material 3), MVVM + Clean Architecture, Hilt, Room, DataStore, WorkManager, Firebase and a secure Gemini backend.

| | |
|---|---|
| Package | `com.lifeos.ai` |
| Min / target SDK | 26 (Android 8.0) / 36 |
| Languages | English, हिन्दी (per-app language picker) |
| Toolchain | AGP 8.10.0 · Kotlin 2.1.21 · Gradle 8.14.3 · JDK 17+ (Android Studio's JBR 21 works) |

---

## 1. What works out of the box (no credentials needed)

Everything below runs **fully offline in guest mode** with no Firebase or API keys:

- Splash → onboarding (3 pages, persisted) → login screen with validation → **Continue as guest**
- **Home dashboard** — greeting, date, avatar, AI card, quick actions, today's tasks, upcoming reminders, monthly expense summary + budget, recent notes, productivity stats. All live from Room (updates instantly).
- **Tasks** — CRUD, priority, category, due date/time, complete, filters (today / upcoming / overdue / completed), search, progress.
- **Reminders** — CRUD, once/daily/weekly/monthly/every-N-days, enable/disable, completed; exact `AlarmManager` alarms (falls back to inexact if the user denies exact alarms), notification channel + `POST_NOTIFICATIONS` handling, "Mark done" notification action, rescheduling after reboot / app update / time or time-zone change, DST-safe repeat math.
- **Expenses** — income & expenses in exact paise, 8 default + custom categories, payment method, date, monthly budget, month switcher, savings; **Reports** with donut chart by category, 6-month bar chart, income-vs-expense chart.
- **Notes** — text & checklist notes, categories, pin, archive, search, timestamps.
- **Documents** — import PDF/images via Storage Access Framework into private app storage, rename/move to folder, search, image preview, in-app PDF preview (`PdfRenderer`), delete, expiry date with automatic reminder, "Open with…" through a temporary `FileProvider` grant (never a public URL).
- **Assistant commands offline** — a deterministic Hindi/Hinglish/English parser handles e.g. *"Kal subah 7 baje mujhe uthne ka reminder laga do"*, *"Add 200 rupees expense for food"*, *"Shopping list mein milk add karo"*, *"Show my monthly expenses"*. Every write shows a **confirmation dialog**, and success is reported only after the row is read back from Room.
- **Voice mode** — full-screen, hands-free conversation that keeps going like a call until you end it (listen → think → speak → listen again; quiet moments just restart listening; pauses after 5 minutes of silence; continues with the screen off via a microphone foreground service with an End notification) around an interactive 3D dot-sphere that ripples with your voice, swirls while thinking, pulses with every spoken word and scatters when you touch it. `SpeechRecognizer` (hi-IN / en-IN) with live captions; replies are read by on-device text-to-speech with word-by-word caption highlighting; general questions use the AI backend's short spoken `style: "voice"`; commands use the same parser + confirmation flow; the conversation can be continued in the text chat.
- **Activity** — merged timeline of tasks, reminders, expenses, notes, AI chats and AI actions with type and date-range filters.
- **Profile & Settings** — photo (photo picker, stored privately), name, theme (system/light/dark), language (English/Hindi), notification & exact-alarm settings, privacy toggles, JSON data export, clear cache, logout, delete account & data, in-app privacy policy.
- **AdMob** with Google **test** IDs + UMP consent.

Features that need your credentials: **sign-in, cloud sync, AI chat / note AI tools, Google sign-in, FCM, Crashlytics/Analytics** (see §3–§5). Without them the app shows clear "not configured" messages instead of fake responses.

---

## 2. Build & run (macOS)

```bash
# Use Android Studio's bundled JDK (Gradle 8.14 does not run on JDK 25)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd "/Users/nirajkushwaha98/Documents/Game/LifeOS AI "
```

```bash
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the JVM unit tests (validators, parser, scheduling, expense math, AI error mapping, Robolectric Room/repository tests):

```bash
./gradlew :app:testDebugUnitTest
```

Run the instrumented end-to-end UI test on a connected device/emulator:

```bash
adb shell pm clear com.lifeos.ai
```

```bash
./gradlew :app:connectedDebugAndroidTest
```

Backend unit tests (no install needed):

```bash
cd backend/functions && node --test test/
```

Or simply open the folder in Android Studio and press **Run**.

---

## 3. Firebase setup (Auth, Firestore sync, FCM, Crashlytics, Analytics)

The Gradle build applies the Google Services and Crashlytics plugins **only when `app/google-services.json` exists**, so the project always builds.

1. Create a project at <https://console.firebase.google.com>.
2. **Add Android app** with package `com.lifeos.ai`. Add your debug and release **SHA-1/SHA-256**:
   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```
3. Download `google-services.json` → place at `app/google-services.json` (git-ignored).
4. **Authentication → Sign-in method**: enable *Email/Password*; optionally *Google* (this generates the `default_web_client_id` the app uses automatically; re-download `google-services.json` afterwards).
5. **Firestore Database**: create in production mode (region e.g. `asia-south1`).
6. **Crashlytics**: open the Crashlytics page once to enable it.
7. Rebuild. Sign-in, email verification, password reset, Google sign-in, sync and account deletion are now live.

Firestore layout (all owner-only):

```
users/{uid}                      name, email, createdAt, fcmToken
users/{uid}/tasks/{taskId}
users/{uid}/reminders/{reminderId}
users/{uid}/expenses/{expenseId}
users/{uid}/notes/{noteId}
aiUsage/{uid_yyyy-mm-dd}         server-only quota counters
```

Sync is offline-first: Room is the source of truth; `SyncWorker` (WorkManager, network-constrained, debounced, exponential backoff) pushes dirty rows using the client UUID as the document id (idempotent → **no duplicates**), propagates deletes as tombstones, and pulls changes newer than the last cursor (last-write-wins). Guest data is merged into the account on first sign-in.

---

## 4. AI backend (Cloud Functions) — keys never ship in the APK

```
Android app ──(Firebase ID token, callable)──▶ aiAssist (Cloud Function) ──(secret keys)──▶ Gemini models ─▶ Claude (last resort)
```

`backend/functions/index.js` validates input, requires auth, enforces a per-user daily quota (default 100), caps history to 10 messages, maps errors to proper codes, and whitelists the structured intents the model may return. The app re-validates every intent (`RemoteIntentMapper`) and always asks the user to confirm before saving.

**Retries and fallback** (`src/fallback.js`, mirrored in the app's `FallbackChain.kt`): each request tries the models in `GEMINI_MODELS` in order — every Gemini model has its own free-tier quota — then Claude. Timeouts, 5xx and 529 are retried once with exponential backoff + jitter (honouring the provider's retry hint); a 429 waits only when the provider asks for a pause of 2.5 s or less, otherwise the next model is tried. A model that hits its **daily** free quota (`...PerDay...` quota id) or an empty Claude balance is skipped until it resets, so later requests don't waste time on it. Bad requests (400) and safety blocks stop immediately. When nothing can answer, the app shows a specific message (busy → "try again in a minute"; quota used up → "limit resets in a few hours"). The whole request is capped at 45 s.

The callable functions require the **Blaze (pay-as-you-go)** plan.

```bash
npm install -g firebase-tools
```

```bash
firebase login
```

```bash
cd backend && cp .firebaserc.example .firebaserc
```

Edit `.firebaserc` with your project id, then:

```bash
cd functions && npm install
```

Create a Gemini API key at <https://aistudio.google.com/apikey> and store it in Secret Manager (you will be prompted to paste it — it is never written to disk or source control):

```bash
firebase functions:secrets:set GEMINI_API_KEY
```

Then the Anthropic key for the Claude fallback (create one at <https://console.anthropic.com> — the Claude API is prepaid, so add credits under *Plans & Billing*). Enter `none` to deploy without Claude:

```bash
firebase functions:secrets:set ANTHROPIC_API_KEY
```

```bash
firebase deploy --only functions,firestore:rules,firestore:indexes
```

Optional parameters (prompted on first deploy or set in `functions/.env`): `GEMINI_MODELS` (default `gemini-3.5-flash-lite,gemini-3-flash-preview,gemini-3.1-flash-lite,gemini-2.5-flash`), `ANTHROPIC_MODEL` (default `claude-opus-5-5`; `claude-haiku-5-5` is faster and much cheaper), `AI_DAILY_LIMIT` (default `100`). The function is deployed to `asia-south1`; if you change the region, also update `FirebaseProvider.FUNCTIONS_REGION`.

Recommended for production: enable **App Check** with Play Integrity, then set `enforceAppCheck: true` in `index.js`.

### Testing AI before the backend is deployed (debug builds only)

Put developer keys in the gitignored `local.properties`:

```properties
GEMINI_API_KEY=your-gemini-key
GEMINI_MODELS=gemini-3.5-flash-lite,gemini-3-flash-preview,gemini-3.1-flash-lite,gemini-2.5-flash
ANTHROPIC_API_KEY=your-anthropic-key
ANTHROPIC_MODEL=claude-opus-5-5
```

**Debug** builds then call the providers directly (`DirectAiService` + `FallbackChain`, same prompts, limits and fallback order as the backend, no sign-in needed), so AI chat, note tools and Voice mode work on the emulator or your own phone. Claude goes through the official Anthropic Java SDK, which is a `debugImplementation` dependency living only in `src/debug` — **release** builds contain neither the SDK nor any key (the `DEV_*` BuildConfig fields exist only in the debug build type) and always use the secure backend above. A key inside an APK can be extracted, so never share or publish a debug build that has one; restrict the Gemini key to the Generative Language API and set a spend limit on the Anthropic account. Remove both keys to test the backend path in debug. Logcat tag `AiFallback` shows which model answered and why a fallback happened (never keys or prompts).

---

## 5. AdMob setup

Development uses Google's official **test** IDs (configured in `gradle.properties`):

| Format | Placement | Rules |
|---|---|---|
| Adaptive banner | Bottom of Home, above the nav bar | never overlaps navigation |
| Native | Activity list, after the 6th item | clearly labelled "Ad" |
| Interstitial | When leaving Expense Reports (natural break) | max once per 5 min **and** every 3rd eligible transition |
| Rewarded | Not implemented — granting bonus AI quota safely needs server-side verification (SSV); add it with an SSV callback to the backend if desired |

No ads on login/auth, editors, dialogs or voice capture. Ads load only after the **UMP** consent flow allows it; users can revisit choices in *Settings → Privacy → Ad privacy choices*.

For production, create an AdMob app + ad units, then put the real IDs in your **user-level** `~/.gradle/gradle.properties` (overrides the project file, stays out of git):

```properties
lifeos.admob.appId=ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY
lifeos.admob.bannerId=ca-app-pub-XXXXXXXXXXXXXXXX/1111111111
lifeos.admob.interstitialId=ca-app-pub-XXXXXXXXXXXXXXXX/2222222222
lifeos.admob.nativeId=ca-app-pub-XXXXXXXXXXXXXXXX/3333333333
```

Also: create the GDPR message in AdMob → Privacy & messaging, add `app-ads.txt` to your developer website, and register test devices while testing real IDs. Set `lifeos.ads.enabled=false` to build an ad-free variant.

---

## 6. Release build & signing

1. Create an upload keystore (store it outside the repo and back it up):
   ```bash
   keytool -genkeypair -v -keystore ~/keys/lifeos-upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias lifeos-upload
   ```
2. Copy `keystore.properties.example` → `keystore.properties` (git-ignored) and fill in the path, alias and passwords.
3. Build the Play bundle (R8 minification + resource shrinking enabled):
   ```bash
   ./gradlew :app:bundleRelease
   ```
   Output: `app/build/outputs/bundle/release/app-release.aab`
4. Optional signed APK for side-loading:
   ```bash
   ./gradlew :app:assembleRelease
   ```
5. Upload the AAB to Play Console with **Play App Signing** enabled. Add the Play signing SHA-1 to Firebase for Google sign-in.

Bump `versionCode` / `versionName` in `app/build.gradle.kts` for each release. See [PLAY_STORE.md](PLAY_STORE.md) for the listing, permissions and Data safety answers.

---

## 7. Architecture

```
app/src/main/java/com/lifeos/ai/
├── core/        ads, common (UiText, time, money), navigation (type-safe routes, NavHost, bottom bar),
│                notifications, theme (palette, dark/light), ui (design system, charts, markdown, pickers)
├── data/        local (Room entities/DAOs/db, mappers, private file store), preferences (DataStore),
│                remote (FirebaseProvider, AiService → Cloud Function, FCM service), repository impls,
│                sync (SyncScheduler + SyncWorker + Firestore mappers)
├── domain/      models, repository interfaces, use cases (CommandParser, RemoteIntentMapper,
│                ReminderScheduleCalculator, ExpenseCalculator, TaskFilters, AuthValidator)
├── feature/     splash, onboarding, auth, home, assistant, tasks, reminders (+alarm receivers/workers),
│                expenses, notes, documents, voice, activity, tools, profile, settings
└── di/          Hilt modules
backend/         Cloud Functions (Gemini proxy), Firestore & Storage rules, firebase.json
```

- **Database**: Room v1 with exported schema (`app/schemas/`), indexes on owner/date columns, FK `ai_messages → ai_conversations` with `CASCADE`. Destructive migration is **disabled**; add `Migration` objects to `LifeOSDatabase.MIGRATIONS` when bumping the version.
- **Money** is stored as `Long` paise; all reports are computed from real rows.
- **Charts** are lightweight Compose `Canvas` implementations (no extra chart dependency).
- **App icon**: a neon hexagon "AI core" (broken frame with circuit nodes, a 3D orbit and a glowing spark) on a deep-space HUD-grid background. It is defined once as vectors in `res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml` (Android 13+ themed icons). The foreground also drives the system splash screen and the in-app logo (`LifeOSLogo`), and the status-bar icon (`ic_notification.xml`) is a matching silhouette. `app/src/main/ic_launcher-playstore.png` is the 512 px Play Store icon rendered from these vectors (regenerate it with the command in `PLAY_STORE.md`).

---

## 8. Known limitations

- OCR and AI document summarisation are not implemented (documents are designed for it as a future extension).
- Documents, AI chats and profile photos stay on-device and are not part of cloud sync (privacy by design).
- Speech recognition availability/quality depends on the device's installed recognition service (Google app on most phones); Hindi offline recognition requires the Hindi language pack.
- Rewarded ads are not implemented (see §5).
- Store screenshots must be captured from your final build (see `PLAY_STORE.md`).
