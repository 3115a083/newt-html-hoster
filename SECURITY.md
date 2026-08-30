# Security Policy

## Reporting

Do not publish credentials, Newt secrets, Pangolin tokens, private endpoints, signing keys, or exploit details in a public issue.

Use GitHub private vulnerability reporting when enabled for this repository. Otherwise contact the repository owner privately before disclosing a vulnerability.

## Security boundaries

Newt HTML Hoster is designed for non-rooted Android devices. It does not claim to protect secrets from a compromised Android kernel, a rooted device, malicious accessibility services with elevated privileges, or an already-unlocked device under attacker control.

The application:
- stores the Newt secret encrypted with AES-256-GCM using a non-exportable Android Keystore key;
- never intentionally logs the secret;
- passes the secret to Newt through the child-process environment, not command-line arguments;
- binds the static server only to 127.0.0.1;
- prevents canonical-path escape from a bucket directory;
- disables Android backup and device-transfer extraction for private application data;
- requires HTTPS for the Pangolin endpoint;
- records the server SPKI SHA-256 pin during enrollment and refuses to start when it changes;
- forces Newt userspace mode and does not request root or VPN/kernel-interface privileges;
- limits request headers, connection concurrency, file size, methods, and socket timeouts.

## DDoS scope

The Android server provides local resource-exhaustion controls. It cannot stop a volumetric DDoS attack that saturates the VPS or network uplink. Production deployments must rate-limit and filter before Pangolin, using the VPS provider, firewall, reverse proxy, CDN, or equivalent upstream controls.

## Release signing

Never commit an Android release keystore. Production self-updates require a stable signing key stored only in protected GitHub Actions secrets or another dedicated signing system.
