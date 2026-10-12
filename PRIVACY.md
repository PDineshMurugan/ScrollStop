# Privacy Policy for ScrollStop

**Last updated: October 12, 2026**

ScrollStop ("we", "our", or "the app") is an open-source digital wellbeing utility developed by Dinesh Murugan. We are committed to complete transparency and user privacy. This Privacy Policy explains our practices regarding user data and our use of the Android Accessibility Service.

---

## 1. Core Principle: 100% Local Processing

ScrollStop operates entirely on your device. We do **not** operate external tracking servers, analytics endpoints, or advertising networks. 

* **No Personal Data Collected:** We do not collect names, email addresses, phone numbers, or account credentials.
* **No Telemetry or Analytics:** No third-party analytics SDKs (e.g., Firebase Analytics, Mixpanel) are integrated into ScrollStop.
* **No Keystroke or Content Logging:** We never record text you type, content you view, or private communications.

---

## 2. Android Accessibility Service Usage

ScrollStop requires the Android Accessibility Service (`ScrollAccessibilityService`) to deliver its core digital wellbeing functionality.

### Purpose of Accessibility Usage
The Accessibility Service is used strictly to:
1. Detect when **Instagram Reels** (`com.instagram.android`) or **YouTube Shorts** (`com.google.android.youtube`) are active in the foreground.
2. Detect vertical scroll events within those short-form video players to calculate your daily scroll count.
3. Display the unobtrusive floating scroll counter HUD over active Reels/Shorts and provide mindful alerts when your self-imposed daily limit is reached.

### Privacy Safeguards
* **Immediate Event Discarding:** Any accessibility event originating from applications other than Instagram and YouTube is discarded instantly on the first line of code.
* **No Access to Sensitive Data:** The Accessibility Service never reads passwords, private messages, camera or microphone data, contacts, or financial information (including banking and UPI apps).
* **Zero Transmission:** All scroll counts, durations, and weekly analytics are stored locally on your device via Android DataStore / Encrypted SharedPreferences and are never transmitted over the internet.

---

## 3. Network and Internet Permission

ScrollStop requests `android.permission.INTERNET` exclusively for:
1. Checking public GitHub Releases (`api.github.com/repos/PDineshMurugan/ScrollStop/releases/latest`) to inform you when an updated version of the app is available.
2. Opening GitHub Issues forms in your browser when you explicitly tap "Report Bug" or "Request Feature".

No telemetry, device identifiers, or usage logs are ever uploaded.

---

## 4. Overlay Permission (Display Over Other Apps)

The `SYSTEM_ALERT_WINDOW` permission is used solely to render the minimalist floating scroll counter HUD on top of active Instagram Reels and YouTube Shorts, and to show the optional break prompt when your daily limit is reached.

---

## 5. Third-Party Services

ScrollStop does not use third-party tracking, advertising, or data brokerage services. The application source code is publicly auditable on GitHub:
[https://github.com/PDineshMurugan/ScrollStop](https://github.com/PDineshMurugan/ScrollStop)

---

## 6. Children's Privacy

ScrollStop does not knowingly collect any personal information from children or adults alike.

---

## 7. Changes to This Policy

Any revisions to this Privacy Policy will be reflected in the public repository with an updated modification date.

---

## 8. Contact

For inquiries regarding this privacy policy or the app's security practices, please open an issue on GitHub:
* Repository: [https://github.com/PDineshMurugan/ScrollStop](https://github.com/PDineshMurugan/ScrollStop)
* Developer: Dinesh Murugan ([PDineshMurugan](https://github.com/PDineshMurugan))
