# LifeOS AI — Privacy Policy

**Effective date:** [DATE OF PUBLICATION]
**Developer / data controller:** [YOUR NAME OR COMPANY], [CONTACT ADDRESS]
**Contact:** [SUPPORT EMAIL]

> This is a draft. Replace the bracketed placeholders and have it reviewed before publishing.

LifeOS AI ("the app") helps you manage tasks, reminders, expenses, notes and documents, with an optional AI assistant. This policy explains what data the app handles, why, and the choices you have.

## 1. Data stored on your device

Everything you create — tasks, reminders, income and expense entries, budgets, notes, checklists, AI conversations, your display name and profile photo — is stored in the app's private storage on your device. You can use the app in **guest mode** without an account; in that case nothing is sent to our servers.

**Documents** (PDFs and images) you import are copied into the app's private storage. They are **never uploaded**, are excluded from Android cloud backup, and are only shared with another app when you explicitly choose "Open with…".

## 2. Data processed when you create an account

If you sign in (email/password or Google), we use **Firebase Authentication** (Google LLC) to manage your account. We process your email address, display name and a unique user ID.

When cloud backup is on (Settings → Data), your tasks, reminders, income/expense entries and notes are synced to **Cloud Firestore** under your user ID. Security rules allow only you to read or write your data. Documents, profile photos and AI conversations are **not** synced.

## 3. AI assistant

When you use AI features (chat, note summarise/rewrite/translate, natural-language commands that the app cannot understand offline), the text you send — plus up to 10 recent messages of the current conversation for context — is sent through our secure server (Firebase Cloud Functions) to **Google Gemini API** to generate a response. We do not use your messages for advertising. The server keeps only a daily usage counter per user to enforce fair-use limits; it does not store message content. Please do not share sensitive personal information (such as passwords, bank or ID numbers) with the assistant.

The assistant never saves anything on its own: actions such as creating a reminder or adding an expense are shown to you for confirmation first.

## 4. Voice assistant and microphone

The microphone permission is used **only while you press the mic button** in the Voice Assistant. Speech is converted to text by your device's speech-recognition service (which may be provided by Google and may process audio according to its own policy). LifeOS AI does not record or store audio.

## 5. Notifications and alarms

Notification and "Alarms & reminders" permissions are used solely to alert you about reminders you created. If you sign in, a Firebase Cloud Messaging token may be stored with your account so we can send service announcements.

## 6. Advertising

The free version shows ads via **Google AdMob**. Ads are shown only after consent is obtained where required (Google User Messaging Platform; EEA/UK/Switzerland). AdMob may collect device identifiers (such as the advertising ID), IP address and ad interaction data to show and measure ads. You can change your ad consent at any time in Settings → Privacy → Ad privacy choices, and reset your advertising ID in Android settings. Ads are never shown on login or while you are entering data.

## 7. Analytics and crash reports

- **Crash reports** (Firebase Crashlytics) help us fix bugs. They include device model, OS version, app state and stack traces. You can turn this off in Settings → Privacy.
- **Usage analytics** (Firebase Analytics) are **off by default** and only enabled if you switch them on.

## 8. What we do not collect

We do not access your contacts, location, call logs, SMS, or files outside the documents you pick. LifeOS AI is not connected to any bank; all financial figures come only from entries you type.

## 9. Data retention and deletion

- Local data stays on your device until you delete it or uninstall the app.
- Cloud data stays in your account until you delete it.
- **Settings → Account → Delete account & data** permanently deletes your cloud data, your Firebase account and all local data on the device.
- **Settings → Data → Export my data** gives you a JSON copy of your data at any time.

You can also request deletion by emailing [SUPPORT EMAIL].

## 10. Security

Data in transit is encrypted (TLS). Cloud data is protected by per-user access rules. The AI API key is held only on our server and is never included in the app.

## 11. Children

LifeOS AI is not directed at children under 13 (or the minimum age in your country), and we do not knowingly collect their data.

## 12. Changes

We may update this policy. Material changes will be announced in the app or on the store listing.

## 13. Contact

Questions or requests: [SUPPORT EMAIL]
