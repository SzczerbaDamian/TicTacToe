# Hand Guard (telefon)

Android app that measures how hard you use your hands on the phone: clicks,
scrolling, typing, physical keys, screen-on time, per app and per hour.
It runs as an accessibility service and **never reads screen content or typed
text**; it only looks at event types and the app's package name.

## Get the APK

Every push that changes `hand-guard/` builds the app on GitHub Actions.

- **Easiest (on the phone):** GitHub → this repository → **Releases** →
  **Hand Guard (latest)** → `HandGuard.apk`.
- Or: **Actions** → latest "Hand Guard APK" run → **Artifacts** → `HandGuard-apk` (zip).

All builds are signed with the same key (`app/handguard.keystore`), so a new
version installs over the old one and keeps the collected data.

## First setup

1. Install the APK. If you had the old Hand Guard, uninstall it first, so two
   services do not collect data at the same time.
2. Open the app → **1. Ułatwienia dostępu** → turn on **Hand Guard – zbieranie danych**.
   If it is greyed out ("Restricted setting"): tap it once, then in the app tap
   **Odblokuj ustawienia z ograniczeniami** → ⋮ → **Allow restricted settings**.
3. **2. Bateria: bez ograniczeń** and **3. Autostart (Xiaomi)**, and lock the app
   in the recent-apps view (padlock).

## "Luki ochrony" (protection gaps)

The service sends a heartbeat every 30 s. When the screen is off the phone
sleeps and heartbeats stop; that is **not** a gap any more (in the old version
it was, which is why every night and every pause looked like a gap).
A gap is recorded only when:

- there was no heartbeat for over 2 minutes **while the screen was on**,
- the service started again after the system killed it,
- the phone was switched off / restarted (marked separately),
- the service was turned off manually (marked separately).

## How activity time is counted

Events closer than 2 s to each other form one continuous burst, and the time
between them counts as activity. A single isolated event counts as 0.4 s. The
time goes to the category of the event (touch / scroll / typing), the app and
the hour.

## Build locally

Requires JDK 17 and the Android SDK (`ANDROID_HOME` or `local.properties`):

```
cd hand-guard
./gradlew assembleRelease
```
