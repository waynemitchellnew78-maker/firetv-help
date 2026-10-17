# Fire Device Remote Help Tool

A consent-based tool to **see a family member's Fire device screen and press remote buttons for them, over the internet** — no device on your network, no AnyDesk/TeamViewer. Nothing happens until the person at the TV taps **Allow**, and a red banner shows on their TV the whole time. Also supports **pushing an APK to the stick to update sideloaded apps**.

This is a working project shared for your own Claude (or you) to adapt. It was built to run on a home PC with Docker. **Read the "What you MUST change" section first** — it won't work on someone else's domain until those edits are made.

---

## How it works (architecture)

```
  device (family)                relay (your server)              you (browser)
  ┌──────────────┐   outbound    ┌──────────────────┐   login    ┌───────────┐
  │ "Help Tool"  │ ───wss──────► │  Node.js + ws     │ ◄──wss──── │  web      │
  │  app         │  /ws/device   │  (Docker)         │  /ws/viewer│ controller│
  │ screen+ADB   │               │  behind HTTPS/443 │            │           │
  └──────────────┘               └──────────────────┘            └───────────┘
```

- The **device app** (Kotlin, `android/`) captures its own screen via Android MediaProjection and sends JPEG frames out to the relay. It presses remote buttons by talking to the device's **own ADB** on `127.0.0.1:5555` (the `dadb` library) — so the stick needs "ADB debugging" turned on once. The app connects **outbound** to the relay, so there's nothing to port-forward on the family end.
- The **relay** (`server/`, Node.js + `ws`) pairs a stick (6-digit code) with you, relays frames and button presses, is password-protected, and serves the APK for the Downloader app at `/tv`.
- The **web controller** is the relay's `public/index.html` — screen view, D-pad, text box, and an "Update/install app" card.

Button control uses ADB-over-loopback, which **Fire OS opens automatically** when "ADB debugging" is on. A normal Android phone's USB-debugging does NOT open that port, so test on a real device.

---

## What you'll need

- A **domain/subdomain** you control (e.g. `remote.example.com`) and its DNS.
- A **server that's reachable from the internet on port 443** (HTTPS). The included setup uses [Traefik](https://traefik.io) + Let's Encrypt on an existing Docker network; if you don't run Traefik, use any reverse proxy (Caddy/nginx) in front of the relay, or adapt the compose file.
- **Docker** (for the relay and for building the APK).
- A **device / Firestick** with "ADB debugging" and "Apps from Unknown Sources" enabled.

---

## What you MUST change (search-and-replace)

Everywhere the code says **`xxxxx`**, put **your** domain. The spots:

1. **`android/app/build.gradle.kts`** — the relay URL baked into the app:
   ```
   buildConfigField("String", "RELAY_URL", "\"wss://YOUR-domain/ws/device\"")
   ```
2. **`server/docker-compose.yml`** — the Traefik host rule and the public URL:
   ```
   traefik.http.routers.fireremote.rule=Host(`YOUR-domain`)
   PUBLIC_URL=https://YOUR-domain
   ```
   (If you're NOT using Traefik, delete the `labels:` and `networks: traefik` blocks and put your own proxy in front of port `8790`. The `ports: 8790:8790` mapping lets you reach it directly on the LAN.)
3. **`server/server.js`** — only a fallback default; `PUBLIC_URL` from the compose env overrides it, so you usually don't need to touch this.

Optional but recommended — make it your own app identity (so it's not under `com.firehelp`):
- Change `namespace` and `applicationId` in `android/app/build.gradle.kts`, rename the folder `android/app/src/main/java/com/firehelp/tvhelp` to match, and update the `package` line at the top of each `.kt` file. The display name is in `android/app/src/main/res/values/strings.xml` (`app_name`).

---

## Setup steps

1. **DNS** — add an A record for your subdomain → your server's public IP (DNS-only / not proxied if you use Cloudflare, so Let's Encrypt's TLS-ALPN challenge on 443 works).

2. **Secrets** — create `server/.env` (never commit it):
   ```
   ADMIN_PASSWORD=choose-a-strong-one-8-chars-plus
   SESSION_SECRET=any-long-random-string
   PUBLIC_URL=https://YOUR-domain
   ```

3. **Build the APK** (in Docker — the script auto-generates a fresh signing key the first time, so your build is signed with YOUR key):
   ```
   cd android
   docker run --rm -v "$PWD:/work" \
     -v firehelp_gradle:/opt/gradle -v firehelp_gcache:/root/.gradle -v firehelp_sdk:/root/.android \
     ghcr.io/cirruslabs/android-sdk:34 bash /work/build-in-docker.sh
   ```
   The signed APK lands at `android/app/build/outputs/apk/release/app-release.apk`.
   Copy it to where the relay serves it:
   ```
   mkdir -p server/data
   cp android/app/build/outputs/apk/release/app-release.apk server/data/FireTVHelp.apk
   ```
   (The served filename is set by `APK_PATH` in the compose file — keep them matching. Downloader shows it as `HelpTool.apk`.)

   > Keep your generated `android/release.jks` + `android/signing.properties` safe and reuse them for every future build — rebuilding with the same key is what lets the app update in place. A different key = can't update over the old install.

4. **Run the relay:**
   ```
   cd server
   docker compose up -d --build
   ```
   Your login page should be at `https://YOUR-domain` once TLS issues (first load can take ~30s). The APK hotlink is `https://YOUR-domain/tv`.

5. **On each device (one-time):**
   - Settings → My device → About → click the device name **7×** to unlock Developer options.
   - Developer options → turn on **ADB debugging** and **Apps from Unknown Sources**.
   - Open **Downloader**, type the full `https://YOUR-domain/tv` (must be https — there's no port-80 listener), install, open.
   - The app shows a 6-digit code; read it to the helper. First connect, tap **Allow** on the two pop-ups ("record screen?" → Start now, and "Allow USB debugging?" → tick *Always*).

6. **Use it** — open `https://YOUR-domain`, log in, enter the code, you're in. The "Update/install app" card pushes an APK to the stick and installs it over ADB (during an active session).

---

## Notes & gotchas

- **HTTPS only** — the family types `https://YOUR-domain/tv` in Downloader. There's no plain-HTTP (port 80) listener in this setup, so `http://` gives a 404.
- **Home Wi-Fi loopback** — if your router can't hairpin your public domain back inside your house, the domain won't resolve at home. At home, reach the controller directly at `http://YOUR-SERVER-LAN-IP:8790`.
- **The session cookie** is only marked `Secure` when the request arrives over HTTPS (via the proxy), so logging in over plain-HTTP LAN still works.
- **Pushing an update to the Help Tool app itself** restarts it mid-session (session drops, then reconnects). Updating *other* sideloaded apps is seamless, as long as they were signed with the same key as what's installed.
- This targets **Fire OS** (minSdk 25 / targetSdk 30). The release lint "expired target SDK" gate is disabled on purpose because it's sideloaded, not going to Google Play.

---

## Files

```
android/                     Kotlin device app
  app/build.gradle.kts         <-- RELAY_URL + app id here
  app/src/main/...             AppState, AdbControl (buttons/install), HelpService (relay+screen),
                               MainActivity (consent screen), manifest, layouts, strings
  build-in-docker.sh           builds + signs the APK in the android-sdk container
server/
  server.js                    the relay (pairing, auth, APK serving, push/install)
  public/index.html            web controller UI
  public/login.html            login page
  docker-compose.yml           <-- Host rule + PUBLIC_URL here
  Dockerfile, package.json
```

Built consent-first on purpose: visible code, an on-screen Allow button, Android's own "Start now", and a persistent banner the remote side can't hide. Keep those if you adapt it.
