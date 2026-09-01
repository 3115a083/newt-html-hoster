# Newt HTML Hoster

Android app for temporarily publishing static files from a phone through an existing Pangolin/Newt deployment.

The app runs a local HTTP server for each bucket and starts an embedded Android-targeted Newt client in userspace mode. No root access, Android VPN service, kernel WireGuard interface, or LAN-facing HTTP listener is required.

## Features

- Multiple independent static-site buckets.
- One persistent local port per bucket, allocated from `8800..9799`.
- Each bucket is served directly from `127.0.0.1:<port>`.
- HTML, CSS, JavaScript, images, PDF, JSON, CSV, text and other static assets.
- Built-in editor for common text formats up to 2 MiB.
- Bucket enable/disable control without deleting content.
- Per-bucket transferred-byte counters.
- Optional connection timer. Starting a connection always requires an explicit choice between a duration and no timer.
- Foreground service and partial wake lock only while Newt is active.
- German and English UI.
- Material You, light/dark mode and selectable color palettes.
- Redacted connection diagnostics available by long-pressing the connection status.
- Optional per-bucket request diagnostics for troubleshooting. This feature is disabled by default and stores data only in memory while enabled.

## Requirements

- Android device supported by the APK build.
- Existing Pangolin deployment.
- A Newt site ID and secret.
- HTTPS Pangolin endpoint.
- Pangolin resources that can target the local ports exposed by this app through Newt.

## Pangolin setup

Each bucket displays its local target in the app:

```text
127.0.0.1:<bucket-port>
```

Example:

```text
127.0.0.1:8800
```

Configure the corresponding Pangolin resource to use that exact target port.

The bucket root is served as `/` and resolves to `index.html`. Relative assets such as `/style.css` or `/images/logo.png` are served from the same bucket.

Existing buckets keep their assigned port. New buckets receive a free local port automatically.

## Security model

### Local exposure

Bucket servers bind explicitly to IPv4 loopback:

```text
127.0.0.1
```

They do not listen on Wi-Fi, mobile-data, LAN, or `0.0.0.0` interfaces.

The local server accepts only `GET` and `HEAD`, limits request-header size, concurrent work, request rate, socket timeouts and maximum served file size. Paths are canonicalized before access to prevent traversal outside the bucket directory. Directory listing is disabled.

### Credentials

The Newt secret is encrypted using AES-256-GCM with a non-exportable key stored in Android Keystore.

The UI does not expose the stored secret after enrollment. Android backup and device-transfer extraction are disabled for application data.

Secrets, bearer values, tokens and authorization data are redacted from the in-app connection debug log.

### Pangolin TLS pinning

The configured Pangolin endpoint must use HTTPS.

During credential enrollment, the app performs normal TLS certificate validation and stores an SHA-256 SPKI pin. Before every Newt start, the app performs another TLS handshake and fails closed if the server public-key pin changed.

A legitimate Pangolin certificate/key rotation therefore requires re-enrollment in the app.

### Newt runtime

Newt runs as an embedded Android-targeted executable with:

```text
USE_NATIVE_INTERFACE=false
```

The Android build includes compatibility patches required for userspace DNS resolution on Android. Newt remains a separate upstream project and its applicable license terms must be respected.

## Traffic diagnostics and privacy

A hidden per-bucket traffic inspector can be opened by long-pressing the traffic value in the bucket detail screen.

It is disabled by default.

When explicitly enabled, it keeps at most a small in-memory ring buffer containing:

- timestamp,
- HTTP method,
- path without query string,
- local socket peer IP,
- proxy-reported client IP when supplied through common forwarding headers,
- User-Agent.

It does **not** intentionally record:

- cookies,
- Authorization headers,
- Newt credentials,
- query strings,
- request bodies.

The data is not persisted and is discarded when the feature is disabled or the app process ends.

A proxy-reported IP address is not inherently trustworthy. It is meaningful only when the trusted reverse proxy overwrites the relevant forwarding header.

Use request diagnostics only where local law, policy, and user expectations permit it.

## Connection timer

There is no automatic default timer.

When starting the tunnel, the user must explicitly choose either:

- a duration from 1 to 10080 minutes, or
- no automatic stop timer.

Disconnecting manually always stops Newt and all local bucket servers.

## Public-content warning

Files placed in enabled buckets can become publicly reachable through the configured Pangolin resource.

Only publish content you intend to expose. Static HTML may contain JavaScript and other active browser content.

The built-in server adds browser-hardening headers, but this does not make untrusted HTML safe to execute.

## DDoS and abuse protection

The Android process includes local resource limits, but it cannot prevent volumetric attacks that saturate the Pangolin host, VPS, provider uplink, or mobile connection before traffic reaches the phone.

For internet-facing deployments, apply appropriate filtering and rate limiting upstream of the device.

## Build

GitHub Actions builds the Android-targeted Newt runtime and the Android APK.

Local Android assembly:

```bash
gradle :app:assembleDebug
```

The Android build expects Newt native executables under the ABI-specific `jniLibs` directories. CI prepares these automatically.

Debug builds are debug-signed and are intended for testing. Public production distribution should use a controlled release signing key and a reproducible release process.

## Security reporting

Please report suspected vulnerabilities privately when possible rather than publishing credentials, private endpoints, full debug logs, or exploit details in a public issue.

See:

- [SECURITY.md](SECURITY.md)
- [Security architecture](docs/SECURITY_ARCHITECTURE.md)

## Limitations

- A rooted or otherwise compromised Android OS can defeat application-level secret protection.
- TLS pinning intentionally fails after a server-key rotation until credentials are enrolled again.
- Client IP information in traffic diagnostics depends on trusted proxy forwarding behavior.
- Device identification is limited to information voluntarily exposed by the HTTP client, primarily the User-Agent. The app does not fingerprint clients beyond captured request metadata.
- The app does not provide upstream DDoS protection.
- Newt and Pangolin are external upstream projects.

## Upstream projects

- Newt: `fosrl/newt`
- Pangolin: `fosrl/pangolin`

This repository is not the upstream Newt or Pangolin project.
