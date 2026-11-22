# LumaCast Custom Web Receiver — Deployment & Cast Registration Guide

Google Cast's Default Media Receiver does not support general runtime audio track switching via `AudioTracksManager`, custom subtitle delay shifting, or timed bitmap subtitle rendering. Deploying this Custom Web Receiver unlocks full live audio & subtitle switching on your Smart TV / Chromecast.

## 1. Host the Custom Web Receiver over HTTPS

Chromecast devices require Custom Web Receivers to be hosted over **HTTPS** with a valid SSL certificate. Upload the contents of `/receiver/` (`index.html`, `receiver.js`, `styles.css`) to any HTTPS host:

### Option A: Firebase Hosting (Recommended)
```bash
npm install -g firebase-tools
firebase login
firebase init hosting   # set public directory to "receiver"
firebase deploy --only hosting
```
Your receiver URL will look like: `https://<your-project>.web.app/index.html`

### Option B: GitHub Pages
1. Push `/receiver/` contents to your repository's `gh-pages` branch (or `/docs`).
2. Enable GitHub Pages in **Settings → Pages** with HTTPS enforced.
3. Receiver URL: `https://<username>.github.io/<repo>/index.html`

## 2. Register in Google Cast SDK Developer Console

1. Open the [Google Cast SDK Developer Console](https://cast.google.com/publish) (one-time $5 developer registration).
2. Click **Add New Application** → choose **Custom Receiver**.
3. Enter your application name (`LumaCast Receiver`) and the HTTPS **Receiver Application URL** from Step 1.
4. Click **Save** and copy the generated **Application ID** (8-character hex ID, e.g., `A1B2C3D4`).
5. While testing before publishing, register your physical Chromecast / Smart TV's **Serial Number** under **Cast Receiver Devices**, then reboot the Chromecast.

## 3. Configure the Custom Receiver App ID in LumaCast Android

You have two ways to connect the Android app to your registered receiver:

1. **In-App Settings (No Rebuild Required)**:
   - Open **LumaCast → Settings → Custom Web Receiver App ID**.
   - Paste your 8-character Cast Application ID (e.g., `A1B2C3D4`), tap **Save**, and restart the app so `CastContext` initializes with your Custom Receiver.
2. **Build-Time Constant**:
   - Update `DEFAULT_CUSTOM_RECEIVER_APP_ID` in `LumaCastOptionsProvider.kt` and rebuild.

> Leaving the Custom Receiver App ID blank automatically uses Google's Default Media Receiver (`CC1AD845`) with WebVTT subtitle rendering (`MediaTrack` + `TextTrackStyle`) and position-preserving audio track switching fallback.

## 4. Custom Message Namespace Reference (`urn:x-cast:com.lumacast.tracks`)

| Direction | Message `type` | Purpose |
|---|---|---|
| Sender → Receiver | `REQUEST_TRACK_STATE` | Queries receiver `AudioTracksManager` & `TextTracksManager` tracks |
| Sender → Receiver | `SELECT_AUDIO_TRACK` | Switches active HLS/DASH audio rendition via `AudioTracksManager.setActiveById` |
| Sender → Receiver | `ADD_EXTERNAL_SUBTITLE` | Dynamically injects a local HTTP WebVTT/TTML subtitle URL into `TextTracksManager` |
| Sender → Receiver | `SELECT_SUBTITLE_TRACK` | Enables/disables text track IDs cleanly without restarting playback |
| Sender → Receiver | `LOAD_BITMAP_SUBTITLES` | Loads PGS/VobSub timed bitmap manifest JSON onto TV overlay layer |
| Sender → Receiver | `SET_SUBTITLE_DELAY` | Shifts active subtitle cues earlier/later in milliseconds on TV |
| Sender → Receiver | `SET_SUBTITLE_STYLE` | Applies font scale, color, background opacity, edge outline/shadow, and alignment |
| Receiver → Sender | `TRACK_STATE_SYNC` | Reports available & active audio/subtitle tracks on receiver |
| Receiver → Sender | `AUDIO_TRACK_CONFIRMED` | Confirms receiver audio track switch before Android UI marks track active |
| Receiver → Sender | `SUBTITLE_TRACK_CONFIRMED` | Confirms receiver subtitle activation/deactivation |
| Receiver → Sender | `TRACK_ERROR` | Reports receiver-side audio/subtitle or playback errors |
