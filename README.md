# Newt HTML Hoster

Android app for temporarily publishing static HTML sites and assets through an existing Pangolin/Newt setup.

## Status

Early security-focused test build. The app is intended for non-rooted Android devices and uses Newt's userspace netstack mode.

## Features

- Multiple independent **buckets**.
- Each bucket contains one HTML site plus arbitrary assets such as CSS, JavaScript, images, PDF, CSV, JSON and text files.
- Buckets can be enabled and disabled without deleting content.
- Assets can be replaced while hosting. The next request reads the updated file.
- Built-in editor for HTML, CSS, JS, JSON, TXT, Markdown and CSV up to 2 MB.
- Per-bucket transferred-byte counters.
- User-defined hosting timer from 1 minute up to 7 days.
- Newt connection status, embedded Newt version and current Android link bandwidth shown in the UI.
- Foreground service and persistent notification while Newt is running.
- Partial wake lock only while hosting is active.
- No runtime service and no wake lock while stopped.
- English and German resources. Android chooses German when the system language is German, otherwise the base language is English.
- Material You dynamic colors plus Ocean, Forest and Sunset palettes.
- System, light and dark appearance modes.
- Adaptive launcher icon combining a newt/salamander mark with a home symbol.

## Security model

The static HTTP server listens **only on 127.0.0.1:8793**. It never binds to Wi-Fi, mobile data or 0.0.0.0.

The Newt secret is entered once. It is encrypted with AES-256-GCM under a non-exportable Android Keystore key. The UI has no action that reads the secret back after saving. Android cloud backup and device-transfer extraction are disabled for app data.

The Pangolin endpoint must use HTTPS. During enrollment the app performs normal Android TLS certificate validation and stores an SHA-256 SPKI pin for the server certificate key. Every later Newt start performs a new TLS handshake and fails closed if the pin changed.

Newt is started from the APK native-library directory. The app forces userspace mode with `USE_NATIVE_INTERFACE=false`. It does not request root, TUN, VPN or kernel WireGuard privileges.

The bucket server accepts only GET and HEAD. It canonicalizes paths to block `../` traversal and symlink escape, limits request headers, file size, connection concurrency and socket timeouts, disables directory listing, and sends browser hardening headers.

See [SECURITY.md](SECURITY.md) and [docs/SECURITY_ARCHITECTURE.md](docs/SECURITY_ARCHITECTURE.md) for the threat model and limitations.

## DDoS protection

The Android process has local resource-exhaustion controls. Those controls cannot stop a volumetric attack that saturates the VPS or uplink before traffic reaches Newt.

For internet exposure, configure rate limiting and filtering **upstream of Pangolin**, using the VPS firewall/provider, reverse proxy or CDN where appropriate.

## Newt updates

The Android CI workflow asks the official `fosrl/newt` GitHub Releases API for the newest release and embeds the official `newt_linux_arm64` and `newt_linux_amd64` binaries into each APK build. The workflow records SHA-256 hashes as build artifacts.

Executable Newt code is **not** downloaded into Android writable storage and executed from there.

Production self-update requires the entire APK to be rebuilt and signed with the same Android release signing key. The `Signed Newt Release` workflow checks Newt daily and publishes a new signed APK only when the embedded Newt version changes.

For security, this workflow remains safely inactive until these repository Actions secrets exist:

- `ANDROID_RELEASE_KEYSTORE_B64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Never commit the keystore or its passwords. Once configured, the app checks this repository's latest release, verifies SHA-256, then verifies that the candidate APK has exactly the same Android signing certificate as the installed app before opening the system installer.

## Pangolin setup

Create one Newt site in Pangolin and point the public resource to:

```
127.0.0.1:8793
```

Bucket URLs are:

```
https://your-public-host.example/b/<bucket-uuid>/
```

A bucket's root maps to its `index.html`.

## Build

GitHub Actions builds a debug APK on every push to `main`.

Locally:

```bash
gradle :app:assembleDebug
```

The build expects the Newt executables at:

```
app/src/main/jniLibs/arm64-v8a/libnewt.so
app/src/main/jniLibs/x86_64/libnewt.so
```

The CI workflow downloads those files automatically.

## Important limitations

- The test APK is debug-signed. It is not a production self-updating release.
- Android cannot protect app secrets against a fully compromised/rooted OS or an attacker controlling an already unlocked device.
- SPKI preflight pinning adds a fail-closed check before Newt starts. Newt still performs its own standard TLS validation for its control connection.
- Newt is AGPLv3/commercial dual licensed upstream. This project distributes the unmodified Newt executable as a separate process and must preserve the applicable Newt licensing obligations.
- Public HTML can contain active JavaScript. Only host content you trust.

## Upstream

- Newt: `fosrl/newt`
- Pangolin: `fosrl/pangolin`

Newt is a separate upstream project and is not maintained by this repository.
