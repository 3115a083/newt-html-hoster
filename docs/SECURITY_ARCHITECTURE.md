# Security architecture

## Data flow

Internet -> HTTPS Pangolin endpoint -> Newt control channel -> userspace WireGuard/netstack tunnel -> Newt local proxy -> 127.0.0.1:<bucket-port> -> bucket server.

No Android listening socket is bound to Wi-Fi, cellular, or 0.0.0.0.

## Credentials

The user enters Pangolin endpoint, Newt ID and Newt secret once. The secret is converted to a temporary character array, encrypted with AES-256-GCM under an Android Keystore key, and the plaintext UI value is cleared. The UI has no read-back action.

At runtime the service decrypts only for Newt startup. The secret is placed in NEWT_SECRET in the child environment and the local character array is zeroed. Android and the JVM can still create internal copies, so this is defense in depth rather than a formal zero-residue guarantee.

## MITM controls

Enrollment uses the Android platform trust store over HTTPS and records SHA-256 of the server certificate SubjectPublicKeyInfo. Every later Newt start performs a fresh HTTPS handshake and compares the SPKI pin before launching Newt. A mismatch fails closed.

Newt itself still performs its own TLS validation. For highest assurance, deploy Pangolin with a stable private/public CA policy and optional mTLS supported by Newt, then rotate the stored pin deliberately when the Pangolin certificate key changes.

## Buckets

Each bucket is a UUID directory under the private app files directory and has a persistent loopback-only port. Canonical-path validation blocks ../ traversal and symlink escapes. Disabled buckets return 404. Assets can be replaced while hosting because each HTTP request resolves the current file contents.

Only GET and HEAD are served. There is no CGI, PHP, Node runtime, shell execution, upload endpoint, directory listing, or server-side script engine.

## HTML security

HTML is active content. The server sends a restrictive CSP, disables framing, disables MIME sniffing, strips powerful browser permissions, and prevents referrer leakage. Inline scripts/styles remain permitted so ordinary self-contained HTML exports continue to work.

## Runtime lifecycle

A user-visible foreground service runs only while Newt is requested. A partial wake lock is held only during that period. Stopping Newt tears down the child process, all per-bucket localhost servers and the wake lock. START_NOT_STICKY prevents Android from silently resurrecting the service after it is stopped.

## Update model

GitHub Actions builds the Android-targeted Newt runtime used by the app, including the repository Android compatibility patches, and then packages it into the APK. Android executable code is never downloaded into writable app storage for direct execution.

Production updates must be delivered as a newly signed APK with the same application signing key. The signing key must never exist in the public repository.


## Optional traffic diagnostics

Detailed per-bucket request diagnostics are disabled by default and stored only in process memory while explicitly enabled. The inspector records a bounded ring of timestamp, method, path without query string, local socket peer IP, selected proxy-forwarded client-IP headers and User-Agent. It intentionally does not capture cookies, Authorization headers, request bodies or query strings.

Forwarded client-IP headers are metadata supplied by the HTTP path and can be spoofed unless a trusted proxy overwrites them. They must not be treated as authenticated identity.

The inspector is a troubleshooting feature, not an analytics or fingerprinting system.
