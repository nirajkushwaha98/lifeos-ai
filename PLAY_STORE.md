# Google Play Store preparation — LifeOS AI

## Listing

**App name (30):** LifeOS AI – Everyday Super App

**Short description (80):** Tasks, reminders, expenses, notes & an AI assistant in Hindi and English.

**Full description (draft):**

LifeOS AI brings your everyday life into one beautiful, private app.

✅ **Tasks** — priorities, due dates, today / upcoming / overdue views and progress tracking.
⏰ **Smart reminders** — one-time or repeating (daily, weekly, monthly, every N days) alarms that survive restarts.
💰 **Expense manager** — track income and spending, set a monthly budget, see category pie charts and monthly trends. (A personal tracker — not connected to any bank.)
📝 **Smart notes** — text notes and checklists with pinning, archive, categories and search. Optional AI to summarise, rewrite, translate or turn notes into checklists.
📂 **Documents** — keep PDFs and photos of important papers privately inside the app, with expiry reminders.
🤖 **AI assistant** — ask anything in English, हिन्दी or Hinglish. Plan your day, summarise text, translate, or say "Kal subah 7 baje reminder laga do" — LifeOS asks you to confirm before saving anything.
🎙️ **Voice assistant** — speak in Hindi or English: "Aaj ka expense kitna hai?", "Kal ka schedule dikhao".
🌓 Beautiful dark & light themes, English and Hindi interface, works offline, optional secure cloud backup.

Privacy first: use it without an account, export your data anytime, and delete your account and data from inside the app.

**Category:** Productivity · **Tags:** productivity, planner, expense tracker, reminders, AI assistant
**Contains ads:** Yes · **In-app purchases:** No

## Graphics checklist

| Asset | Spec | Source |
|---|---|---|
| App icon | 512×512 PNG | **Ready:** `app/src/main/ic_launcher-playstore.png` (rendered from the launcher vectors; preview in `docs/icon-preview.png`) |
| Feature graphic | 1024×500 | Brand gradient `#3B82F6 → #8B5CF6`, logo + tagline |
| Phone screenshots | 2–8, 16:9 or 9:16, min 1080 px | Capture Home, AI chat, Reminders, Expense reports, Notes, Voice — in both English and Hindi |

If you change the icon vectors, regenerate the 512 px Play icon (and the preview sheet) from the project root with:

```bash
./gradlew :app:testDebugUnitTest --tests "*StoreIconExportTest" -PexportIcons=true -PiconPreviewDir="$PWD/docs"
```

Capture screenshots from a device/emulator:

```bash
adb exec-out screencap -p > screenshot_home.png
```

## Permissions explanation (for reviewers and the listing)

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | AI assistant, optional cloud sync, ads |
| `POST_NOTIFICATIONS` | Show reminders the user created (requested in context) |
| `SCHEDULE_EXACT_ALARM` | Ring reminders at the exact time the user chose; user-grantable in system settings, app falls back to inexact alarms if denied |
| `RECEIVE_BOOT_COMPLETED` | Re-register reminders after the device restarts |
| `RECORD_AUDIO` | Voice assistant speech-to-text, only while the mic button is active; audio is never stored |

Exact-alarm declaration (Play Console → App content → Exact alarm permission): core functionality is **user-scheduled reminders / alarms**.

No location, contacts, SMS, call log, or broad storage permissions are requested (documents use the Storage Access Framework picker).

## Data safety form (answers for this codebase)

**Does your app collect or share any of the required user data types?** Yes.
**Is all user data encrypted in transit?** Yes. **Can users request deletion?** Yes (in-app *Delete account & data*, plus email).

| Data type | Collected | Shared | Purpose | Optional? | Notes |
|---|---|---|---|---|---|
| Email address, name, user IDs | Yes (if signed in) | No | Account management | Yes (guest mode) | Firebase Auth |
| Other user-generated content (tasks, reminders, notes) | Yes (if cloud sync on) | No | App functionality | Yes | Firestore, owner-only |
| Financial info → Other financial info (user-entered income/expense amounts) | Yes (if cloud sync on) | No | App functionality | Yes | Not bank data |
| Messages → Other in-app messages (AI prompts) | Yes, processed ephemerally | Yes — processed by Google Gemini (or Anthropic Claude as a fallback) as a service provider | App functionality | Yes | Not stored on server |
| Audio | No | — | — | — | Speech handled by device recognizer; not collected by the app |
| Device or other IDs (advertising ID) | Yes | Yes (AdMob) | Advertising, fraud prevention | Consent where required | |
| App interactions / diagnostics (crash logs) | Yes | No | Analytics / stability | Yes (toggle) | Crashlytics; Analytics off by default |
| Photos / files | No (stay on device) | — | — | — | Documents & profile photo are local only |

Note: data processed by a service provider on your behalf (Firebase, Gemini, Claude) is generally not "sharing" under Play's definitions; AdMob's collection is disclosed as shared for advertising. Verify against the current Play Console guidance before submitting.

## Content rating & policies

- IARC questionnaire: utility/productivity app with user-generated text and AI chat; no violence, gambling or mature content.
- **AI-generated content policy**: the assistant has safety filtering (Gemini safety settings; Claude's safety classifiers with server-side refusal fallback), a system prompt restricting harmful content; users can report problems via the support email. Consider adding an in-app "Report response" action before launch if required for your target markets.
- **Financial features**: the description and in-app disclaimer state it is a personal tracker, not a bank-connected or lending service.
- **Foreground service (microphone)**: `VoiceSessionService` (`FOREGROUND_SERVICE_MICROPHONE`) keeps a user-started voice conversation going with the screen off, always with an ongoing notification and an End button; it stops when the conversation ends, is muted, or after 5 minutes of silence. Declare it under *App content → Foreground service permissions* ("Microphone — continue a voice conversation the user started") with a short video of starting voice mode, locking the screen, talking, and ending from the notification.
- **Ads policy**: no ads on launch/exit, auth or during data entry; interstitials frequency-capped at natural breaks.
- Privacy policy URL: publish `PRIVACY_POLICY.md` (after filling placeholders) at a public URL and paste it into Play Console.

## Release checklist

- [ ] `app/google-services.json` from the production Firebase project
- [ ] AI backend deployed, `GEMINI_API_KEY` and `ANTHROPIC_API_KEY` secrets set (fresh keys — not the ones used for testing), Anthropic credits + spend limit, App Check enforced
- [ ] Production AdMob IDs in `~/.gradle/gradle.properties`, UMP message published, `app-ads.txt` live
- [ ] `keystore.properties` + upload key; `./gradlew :app:bundleRelease`
- [ ] Bump `versionCode`
- [ ] Internal testing track → closed testing (new personal accounts need 12 testers for 14 days) → production
- [ ] Add Play App Signing SHA-1 to Firebase (Google sign-in)
