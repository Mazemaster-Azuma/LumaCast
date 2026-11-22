# LumaCast — Native Android Chromecast Local Video Player

LumaCast is a native Kotlin + Jetpack Compose Android application that streams locally stored videos directly from an Android phone to Chromecast-enabled Smart TVs over local Wi-Fi using the **Official Google Cast Android Sender SDK**, a deployable **Custom Web Receiver (CAF v3)**, an **embedded HTTP/1.1 Range/HLS/Subtitle server**, an **automatic 6-strategy Video & Audio Transcoding Engine**, **multi-language dynamic audio track switching**, and an **intelligent TV-side Subtitle Engine** — with zero screen mirroring and zero cloud uploads.

## Architecture Overview

LumaCast follows MVVM + Clean Architecture modularized under `com.example` plus a deployable Custom Web Receiver under `/receiver/`:

- `core.common`: Immutable domain models (`VideoItem`, `VideoTrackInfo`, `AudioTrackInfo` with `AudioTrackRole`/`TrackOrigin`/compatibility annotations, `AudioConversionPlan`, `SubtitleTrackInfo`, `SubtitleCue`, `TimedBitmapCue`, `SubtitleAppearanceSettings`, `LiveTrackConversionState`, `CompatibilityReport`, `ConversionTelemetry`, `OutputValidationResult`, `ResourceHealthStatus`, `RemotePlaybackState`, `NetworkHealthState`), exhaustive `CastError` recovery hierarchy, ISO-639 language normalization (`en`, `hi`, `bn`, `ja`, `ko`, `es`, `fr`, `de`, etc.), and DataStore `AppPreferencesRepository`.
- `core.media`:
  - `LocalVideoRepository`: Scoped-storage compliant discovery via Android Photo Picker (`PickMultipleVisualMedia`), Storage Access Framework (`OpenDocumentTree` with persisted read permissions), and scoped `MediaStore.Video.Media` queries (zero broad storage permissions).
  - `MediaInspector`: Independent multi-track `MediaExtractor` + `MediaMetadataRetriever` + `MediaCodecList` analyzer inspecting container, video tracks, all embedded audio tracks (codec, channels, layout, sample rate, bit depth, bitrate, language, role, default flag, A/V PTS offset, HW decoder availability), all embedded subtitle tracks (`SRT`, `ASS`, `SSA`, `WebVTT`, `TTML`, `PGS`, `VobSub`, forced/SDH/default flags), and `validatePreparedOutput(...)`.
  - `MediaCompatibilityManager`: 6-strategy automatic decision engine evaluating container, video track, and each audio track independently against the target receiver profile (`DIRECT_CAST`, `REMUX`, `AUDIO_ONLY_TRANSCODE`, `VIDEO_ONLY_TRANSCODE`, `FULL_TRANSCODE`, `CONSERVATIVE_FALLBACK`) plus `annotateAudioTrackCompatibility(...)` so compatible alternate languages are preserved without unnecessary bulk conversion.
  - `SubtitleCompatibilityManager` & `SubtitleManager`:
    - Discovers embedded subtitles plus external subtitles inside user-authorized SAF folders (`Movie.srt`, `Movie.en.srt`, `Movie.hi.forced.srt`, `Movie.bn.sdh.vtt`, `.ass`, `.ssa`, `.ttml`, `.idx`/`.sub`, `.sup`) or manually picked via Android SAF `OpenDocument`. Never scans unauthorized directories.
    - Pure-Kotlin UTF-8/UTF-16/Windows-1252 BOM decoder and converters (`SRT → WebVTT`, `ASS/SSA → WebVTT` preserving inline `<i>`, `<b>`, `<u>`, alignment & stripping vector drawings, `TTML/DFXP → WebVTT`) with full multilingual Unicode fidelity (**English, Hindi, Bengali, Japanese, Korean**, etc.).
    - Real binary **Blu-ray PGS (`.sup`)** and **DVD VobSub (`.idx`)** packet/timestamp parsers outputting structured timed bitmap manifests — never treating bitmap subtitles as garbled plain text.
    - Millisecond subtitle delay sync (`+/- ms`), negative timestamp clamping to `00:00:00.000`, overlapping cue preservation, conversion caching, and active-file cache deletion protection (`inUseSubtitlePaths`).
  - `AdaptiveHlsManifestGenerator`: RFC 8216 HLS Master Playlist (`.m3u8`) builder with `#EXT-X-MEDIA:TYPE=AUDIO` and `#EXT-X-MEDIA:TYPE=SUBTITLES` renditions for seamless Custom Web Receiver switching.
  - `MediaPreparationEngine`, `FfmpegTranscodeBackend`, & `MediaResourceGuard`: Dual-backend media pipeline (Media3 Transformer + optional bundled `libffmpeg.so`), atomic `.part` → `.mp4` publication, active-stream cache protection (`inUsePaths`), disk headroom (`StatFs`), and thermal monitoring.
- `core.network`:
  - `NetworkModeManager`: Dual-mode reactive network engine supporting **MODE 1 (Conventional Wi-Fi)** and **MODE 2 (Android Mobile Hotspot)** via official Android APIs (`ConnectivityManager`, `NetworkCapabilities`, `NetworkCallback`, `LinkProperties`, `WifiManager`, `MulticastLock`, and `NetworkInterface`). Dynamically resolves active RFC1918 interface IPs (never hardcodes `192.168.43.1`), acquires `MulticastLock` strictly during active discovery bursts, and opens System Hotspot Settings safely without hidden tethering APIs.
  - `HotspotNetworkDiagnostics`: Real-time diagnostics probing local RFC1918 interfaces, mDNS multicast group joinability (`224.0.0.251:5353`), embedded HTTP `/health` reachability on the actual bound IP, Google Cast SDK discovery status, and honest OEM hotspot compatibility guidance.
  - `LocalMediaHttpServer`: Pure Kotlin embedded HTTP/1.1 streaming server with strict RFC1918 private-LAN bind guard (never binds to public cellular/WAN interfaces), seamless `rebindIfNeeded(...)` preserving active stream/subtitle/HLS tokens across Wi-Fi ↔ Hotspot IP transitions, 192-bit random access tokens, strict directory-traversal rejection (`403 Forbidden`), full Cast CORS headers + `OPTIONS` preflight (`204 No Content`), `GET`/`HEAD` + RFC 7233 `206 Partial Content` seeking, 64 KB buffered I/O, and dedicated endpoints:
    - `/stream/<token>` — progressive original/converted video & audio streams
    - `/subtitle/<token>.<ext>` — converted UTF-8 WebVTT, TTML, and Timed Bitmap JSON manifests
    - `/hls/<token>/<relativeAsset>` — adaptive HLS master/media playlists and fMP4/TS segments
- `core.cast`:
  - `LumaCastOptionsProvider`: Official Google Cast `OptionsProvider` supporting both the deployed **Custom Web Receiver Application ID** (configured in Settings) and automatic fallback to `DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`.
  - `CastTrackProtocol`: Strongly typed bidirectional JSON codec for the custom namespace `urn:x-cast:com.lumacast.tracks`.
  - `CastSessionController`: Wraps `CastContext`, `SessionManager`, `MediaRouter`, `RemoteMediaClient`, Cast `MediaTrack` + `TextTrackStyle`, discovery timeout + `restartDiscovery()` on Wi-Fi ↔ Hotspot transitions, on-demand `MulticastLock` callbacks, and strict receiver-confirmation state updates (UI never marks an audio or subtitle track active until confirmed by the receiver).
  - `CastStreamingService`: Android 14+ `mediaPlayback` foreground service keeping conversions and LAN/Hotspot streaming alive in the background.
- `/receiver/` (Official Google Cast Custom Web Receiver — CAF v3):
  - `index.html`, `styles.css`, `receiver.js`, and `README_RECEIVER_DEPLOYMENT.md` implementing `AudioTracksManager`, `TextTracksManager`, `urn:x-cast:com.lumacast.tracks`, dynamic external WebVTT loading without interrupting playback, live millisecond cue shifting, TV-side styling, and timed bitmap overlay rendering.
- `ui`: Material 3 dark cinema UI across all 10 screens including a live **Network & Connection** control card in `SettingsScreen`, dual-mode **Hotspot & Discovery Timeout** banners in `CastDevicesScreen`, full **Hotspot & Multicast Diagnostics** in `TroubleshootingScreen`, dedicated **Audio & Language** (`AudioTrackSheet`) and **TV Subtitles** (`SubtitlePanelSheet` with Tracks, Timing & Delay, and TV Styling tabs) bottom sheets in `RemotePlayerScreen`, and live on-demand track conversion progress with cancel control.

## Dual-Mode Networking: Conventional Wi-Fi & Android Mobile Hotspot

LumaCast operates automatically in both network modes using **actual Google Cast SDK discovery and direct HTTP/1.1 streaming** (zero screen mirroring, zero simulated devices):

1. **MODE 1 — Conventional Wi-Fi**: Both the Android phone and the Chromecast-enabled Smart TV connect to the same Wi-Fi router. LumaCast binds its local HTTP media server to the active Wi-Fi STA RFC1918 IPv4 interface (`wlan0` / `eth0`).
2. **MODE 2 — Android Mobile Hotspot**: The Android phone enables its built-in Mobile Hotspot and the Chromecast-enabled TV connects directly to that hotspot. LumaCast dynamically detects the active SoftAP interface (`swlan0`, `ap0`, `wlan1`, or single-radio `wlan0` when STA is off) and its randomized session IP (e.g., `192.168.x.1`, `10.x.x.1`, `172.16–31.x.1`), binds the HTTP server exclusively to that hotspot-facing interface, acquires `WifiManager.MulticastLock` only during active Cast scanning, and automatically rebinds + preserves playback position across Wi-Fi ↔ Hotspot IP changes or brief disconnects.

### Android Permissions & Play Policy Compliance
- `CHANGE_WIFI_MULTICAST_STATE` (install-time): Required for `WifiManager.MulticastLock` during active Chromecast mDNS discovery.
- `NEARBY_WIFI_DEVICES` (`android:usesPermissionFlags="neverForLocation"`, Android 13+ optional runtime): Improves nearby Wi-Fi/Hotspot device visibility without ever requesting fine or coarse location permissions.
- **No Hidden Tethering APIs**: Android restricts programmatic hotspot toggling to privileged `@SystemApi` callers. LumaCast observes hotspot state through official APIs and guides users directly to Android System Hotspot Settings when they want to enable or configure their hotspot.

### Android OEM Hotspot & Multicast Compatibility Report

Android OEMs implement SoftAP (Mobile Hotspot) kernel bridging and mDNS (`224.0.0.251:5353`) forwarding differently across chipsets and OS skins. LumaCast never fakes discovery; it probes local multicast reachability and reports restrictions transparently:

| OEM / Android Flavor | Typical Hotspot Behavior | Known Limitations & Guidance |
| --- | --- | --- |
| **Google Pixel / AOSP (Android 12–16)** | Standard `swlan0` / `ap0` randomized RFC1918 subnet; generally permits hotspot client-to-host HTTP and mDNS when VPN is off. | Active VPN tunnels can hijack LAN/hotspot routes; allow LAN bypass or pause VPN. Some Chromecast models need Mobile Data enabled briefly to load the Cast Web Receiver app. |
| **Samsung One UI** | Supports local HTTP streaming on `swlan0`; Wi-Fi Sharing can enable simultaneous Wi-Fi + Hotspot mode. | If Chromecast is not discovered on hotspot, check Hotspot Band (`2.4 GHz` vs `5 GHz` compatibility with older TVs) and disable Protected Management Frames or Wi-Fi Sharing conflicts. |
| **Xiaomi MIUI / HyperOS** | Randomized SoftAP subnet works for HTTP when foreground service is running. | Some firmware builds restrict mDNS multicast between hotspot clients and host or aggressively throttle background sockets. Set LumaCast battery mode to **No restrictions**; if multicast probe reports blocked, use a conventional Wi-Fi router. |
| **OnePlus / OPPO / Realme (OxygenOS / ColorOS)** | Local HTTP streaming generally works on `ap0` / `swlan0`. | Check Hotspot **Connection Management** settings to ensure connected TVs are not isolated (AP Isolation disabled). |
| **Motorola / Generic AOSP** | Standard AOSP tethering stack usually allows hotspot HTTP. | Confirm TV supports the selected hotspot Wi-Fi band and disable any system-wide firewall/VPN apps. |

> **Important Chromecast Requirement on Hotspot**: Many Chromecast / Google TV firmware versions require internet reachability (e.g., active **Mobile Data** on the host phone) during session launch to verify Cast certificates and fetch Google's Default Media Receiver or your hosted Custom Web Receiver HTML. Once loaded, all video/audio/subtitle bytes stream **100% locally** from the phone over the hotspot Wi-Fi radio without consuming mobile data.

## Multi-Language Audio Track Switching

- **Live In-Place Switching (Custom Web Receiver)**: When casting multi-rendition HLS streams to the deployed LumaCast Custom Web Receiver, switching languages sends `SELECT_AUDIO_TRACK` over `urn:x-cast:com.lumacast.tracks` to `AudioTracksManager` and updates UI only upon `AUDIO_TRACK_CONFIRMED`.
- **Position-Preserving Selective Conversion Fallback**: When switching to an incompatible embedded audio track (e.g., DTS or FLAC) during progressive MP4 playback, LumaCast converts **only** the newly requested audio track on demand (preserving original video untouched, caching converted variants per track), captures the live TV playback position and play/pause state, and resumes seamlessly at the exact timestamp without restarting the movie.
- **Audio Conversion Profiles**: AAC-LC at 48 kHz (192–256 kbps Stereo, 384–512 kbps 5.1 preserved on 4K/Google TV, ITU-R BS.775 downmixing for 7.1 → 5.1 or 5.1/7.1 → Stereo).

## Intelligent Subtitle Management (TV-Side Rendering)

Subtitles are **never** rendered as a phone overlay during casting. All rendering happens independently on the Smart TV via Cast `TextTracksManager` / `MediaTrack` + `TextTrackStyle`:

| Source Format | Strategy | Notes |
| --- | --- | --- |
| `.vtt` (WebVTT) | Direct WebVTT | Served via `/subtitle/<token>.vtt` with CORS |
| `.srt` (SubRip) | Convert SRT → WebVTT | UTF-8/UTF-16/CP1252 BOM detection; preserves Unicode scripts |
| `.ass` / `.ssa` | Convert ASS/SSA → WebVTT | Preserves `<i>`, `<b>`, `<u>`, alignment; strips vector drawings |
| `.ttml` / `.dfxp` | Direct TTML (Custom) or Convert → WebVTT | Handles `begin`/`end`/`dur`, `<br/>`, XML entities |
| `.sup` (Blu-ray PGS) | Timed Bitmap Overlay / OCR Review | Real binary `0x50 0x47` PCS/PTS parser; never parsed as plain text |
| `.idx` / `.sub` (VobSub) | Timed Bitmap Overlay / OCR Review | Parses `.idx` timestamp/filepos tables into JSON bitmap manifest |

## Custom Web Receiver Deployment & Registration

Because Google's Default Media Receiver (`CC1AD845`) does not expose general runtime `AudioTracksManager` switching, dynamic external subtitle injection without reload, client-side millisecond cue shifting, or bitmap subtitle overlays, LumaCast ships a complete CAF v3 Custom Web Receiver in `/receiver/`.

1. **Host `/receiver/` over HTTPS** (Firebase Hosting, Cloud Run, Cloudflare Pages, or GitHub Pages) with valid TLS and CORS enabled.
2. **Register in the [Google Cast SDK Developer Console](https://cast.google.com/publish)**:
   - Create a new **Custom Receiver** application pointing to your hosted `https://<your-domain>/index.html`.
   - Add your physical Chromecast / Google TV serial number under **Cast Receiver Devices** while testing before publishing.
   - Copy the generated 8-character **Application ID** (e.g., `12AB34CD`).
3. **Configure in LumaCast**:
   - Open **Settings → Custom Web Receiver** in LumaCast, enter your Application ID, tap **Save**, and restart the app.
   - Leaving the field blank cleanly uses Google's Default Media Receiver (`CC1AD845`) with full WebVTT subtitle attachment and position-preserving audio switching fallback.

See `/receiver/README_RECEIVER_DEPLOYMENT.md` for complete hosting and Chromecast debugging (`chrome://inspect`) instructions.

## Dual-Backend Processing & Bundling Native FFmpeg (Backend B)

LumaCast strictly complies with Google Play Developer Program policies: it **never** uses retired FFmpegKit binary artifacts and **never** downloads executable binaries at runtime.

- **Backend A (Always Built-In)**: Android `MediaExtractor`/`MediaMuxer` (lossless remux) + AndroidX `Media3 Transformer` (hardware-accelerated selective audio-only, video-only, and full transcoding with `ChannelMixingAudioProcessor` ITU-R BS.775 downmixing and OpenGL HDR→SDR tone-mapping).
- **Backend B (Optional Bundled Native FFmpeg)**: `FfmpegTranscodeBackend` automatically activates if you bundle a legally compiled `libffmpeg.so` executable inside `app/src/main/jniLibs/<abi>/libffmpeg.so` at build time (LGPL or GPL-compliant build).

## GitHub Actions APK Builds & Automatic In-App Updater

LumaCast includes `.github/workflows/android-release.yml` for automated CI builds and GitHub Release APK publishing, plus a controlled GitHub Releases updater (`core.update`):

1. **Building APKs via GitHub Actions**:
   - Pushing to `main`/`master` runs unit & Robolectric tests and uploads an APK build artifact (`LumaCast-v<version>.apk`, `.sha256`, and `update-manifest.json`).
   - Pushing a version tag (e.g., `git tag v1.0.1 && git push origin v1.0.1`) or running **Actions → Build & Release Android APK → Run workflow** builds, runs all unit tests, generates a SHA-256 checksum and `update-manifest.json` (`versionName`, `versionCode`, `releaseTag`, `releaseName`, `releaseNotes`, `minimumSupportedVersion`, `apkUrl`, `sha256`, `fileSizeBytes`, `mandatoryUpdate`), and publishes a **GitHub Release** with all assets attached.
   - Optional Release Signing Secrets (in GitHub Repository **Settings → Secrets and variables → Actions**):
     - `ANDROID_KEYSTORE_BASE64`: Base64-encoded `.jks` upload keystore
     - `STORE_PASSWORD`: Keystore password
     - `KEY_ALIAS`: Key alias (defaults to `upload` when omitted)
     - `KEY_PASSWORD`: Key password
   - If release secrets are omitted, the workflow automatically uses `debug.keystore.base64` so CI builds maintain an identical signing certificate lineage for seamless in-place upgrades.
2. **Controlled GitHub-Based In-App Updater**:
   - APKs built via GitHub Actions automatically embed `BuildConfig.GITHUB_REPO` (`owner/repo`). You can also set or override any `owner/repo` inside **Settings → App Updates**.
   - **Strictly Controlled Checking**: Checks `/releases/latest` at most **once per cold app launch** (only when internet is available, auto-check is enabled, and Chromecast playback is inactive) or manually via **Settings → App Updates → Check for Updates**. Never polls in the background, never runs periodic WorkManager jobs, and never re-checks on navigation or connectivity changes.
   - **Verification & Install**: Compares integer `versionCode` primarily (`SemanticVersion` secondary fallback), strictly ignores drafts/prereleases and downgrades, streams `.apk` downloads to `cacheDir/updates/` with HTTP `Range` resumption, validates SHA-256 checksum + package ID + monotonic `versionCode` + signing certificate fingerprint match against the installed app, checks Android `canRequestPackageInstalls()`, and launches Android's Package Installer via `FileProvider`.

## Build & Automated Verification

- **Build Debug APK**: `gradle :app:assembleDebug`
- **Build Release APK / AAB**: `gradle :app:assembleRelease` / `gradle :app:bundleRelease`
- **Run Full Automated Test Suite**: `gradle :app:testDebugUnitTest`
  - `AppUpdaterTest`: Controlled single cold-launch checking, offline/casting suppression, concurrent check deduplication, `versionCode` + SemVer comparison, `update-manifest.json` validation, draft/prerelease filtering, HTTP error handling, storage headroom & HTTPS URL enforcement, APK SHA-256 / package ID / downgrade / certificate lineage verification, `FileProvider` install intent & cancellation recovery, and `AppUpdateDialog` + `SettingsScreen` UI flows.
  - `HotspotAndNetworkModeTest`: Dual-mode Wi-Fi & Android Mobile Hotspot classification, arbitrary dynamic SoftAP IPs (zero hardcoded `192.168.43.1`), single-radio `wlan0` hotspot detection, cellular/VPN exclusion, RFC1918 bind guard rejecting public IPs, `LocalMediaHttpServer.rebindIfNeeded` token preservation + `206 Partial Content`, `HotspotNetworkDiagnostics` report & OEM guidance matrix, and `MulticastLock` lifecycle.
  - `MediaCompatibilityManagerTest`: All 6 conversion strategies, HDR/4K/1080p receiver profiles, ITU 5.1/7.1 downmixing, AC-3 passthrough, and conservative fallback.
  - `TranscodingBackendAndProtectionTest`: FFmpeg selective CLI commands, Media3 ITU matrices, active-stream `inUsePaths` cache protection, resource guards, and `CastError` recovery actions.
  - `LocalMediaHttpServerTest`: RFC 7233 `206 Partial Content` seeking, `HEAD`, concurrent streams, chunked I/O, and security rejection.
  - `SubtitleEngineTest`: SRT/ASS/SSA/TTML/WebVTT conversion, BOM detection, English/Hindi/Bengali/Japanese/Korean Unicode preservation, +/- ms delay clamping & overlapping cues, external filename parsing, and real binary PGS `.sup` & VobSub `.idx` timing parsers.
  - `MultiTrackAudioAndReceiverTest`: Integration Cases 1–4 (4K HEVC+DTS+AAC+SRT, H.264+FLAC+ASS, H.264+AAC+PGS, 3-language English/Hindi/Bengali MKV), HLS Master Playlist generation, `CastTrackProtocol` serialization, receiver-confirmed UI state machine, `/subtitle/` + `/hls/` + `OPTIONS` CORS + traversal blocking, and active subtitle cache protection.

## Real-Device Chromecast & Hotspot Verification Matrix (14 Cases)

Physical Android + Chromecast / Google TV hardware is required to validate live mDNS multicast forwarding, TV hardware decoding, and OEM SoftAP behavior:

1. **Conventional Wi-Fi Discovery & Direct Cast**: Connect phone + TV to shared Wi-Fi → confirm `Wi-Fi LAN` in **Settings → Network & Connection**, discover TV, cast H.264 + AAC MP4.
2. **Android Mobile Hotspot Activation**: Turn off Wi-Fi STA, enable Android Mobile Hotspot via **Open Hotspot Settings**, connect TV to hotspot → confirm `Mobile Hotspot` badge and dynamic RFC1918 SoftAP IP (not hardcoded).
3. **Hotspot Chromecast Discovery**: Tap **Re-Scan TVs** on **Cast Devices** → verify `MulticastLock` activates during scan and real Chromecast TV appears (or honest multicast-blocked diagnostics if OEM blocks mDNS).
4. **Hotspot Direct Video Streaming**: Cast local H.264/AAC MP4 over hotspot → verify video and audio play smoothly on TV with zero mobile data consumed for the video stream.
5. **Hotspot Large File Seeking (206 Partial Content)**: Cast >2 GB MKV/MP4 over hotspot → scrub timeline rapidly forward/backward → confirm smooth `206 Partial Content` recovery without OOM.
6. **Hotspot Automatic Transcoding**: Cast MKV with incompatible audio (DTS/FLAC) or video over hotspot → confirm automatic selective conversion + seamless Cast playback.
7. **Hotspot Multi-Language Audio Switching**: Cast multi-audio MKV (English DTS 5.1 + Hindi AC-3 5.1 + Bengali AAC Stereo) → switch languages live without restarting from `0:00`.
8. **Hotspot TV-Side Subtitles & Styling**: Load external `.srt`/`.ass`/`.vtt` (English, Hindi, Bengali, Japanese), adjust **Sub Delay** (`+/- 500 ms`) and **Sub Style** → verify crisp TV-side rendering.
9. **Wi-Fi → Hotspot Mode Transition**: Switch from router Wi-Fi to Mobile Hotspot → confirm automatic discovery restart and HTTP server rebinding to new hotspot IP.
10. **Hotspot → Wi-Fi Mode Transition**: Disable hotspot and rejoin router Wi-Fi → confirm automatic rebind back to `wlan0` IP and discovery restart.
11. **Hotspot Disabled Mid-Playback & Re-Enabled**: Turn off hotspot during active casting → verify `HotspotDisconnected` recovery banner preserves timestamp, and re-enabling + TV reconnect offers/performs position-preserving resume.
12. **TV Disconnect & Reconnect**: Power-cycle or disconnect TV from hotspot, then reconnect → verify playback resumes at saved position instead of `0:00`.
13. **Phone Screen Lock & Background Playback**: Lock Android phone during hotspot casting → verify `CastStreamingService` (`mediaPlayback` foreground notification) keeps HTTP streaming uninterrupted.
14. **Full Diagnostics Verification**: Open **Diagnostics & Help → Run Diagnostics** → verify Network Mode, Bound IP, mDNS Multicast probe, HTTP `/health` latency, and OEM compatibility note match physical device behavior.

