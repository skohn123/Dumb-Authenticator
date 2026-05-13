# Authenticator

A lightweight, offline TOTP (RFC 6238) authenticator for Android. Built D-pad first, with no Google Play dependencies and no cloud sync — everything stays on the phone.

## Features

- Generates 6/8-digit TOTP codes (HMAC-SHA1, SHA256, SHA512; configurable per-account period).
- Three ways to add accounts:
  - Live camera scan (CameraX + ML Kit).
  - Pick a QR image from the gallery (with inverted-bitmap retry for white-on-dark codes).
  - Manual Base32 entry.
- `otpauth://` deep-link support — any external QR scanner can hand off scans straight to the app.
- PIN gate with PBKDF2-HMAC-SHA256 (100k iterations) and constant-time comparison. After 5 failed attempts the PIN is locked for 30s, doubling on each subsequent failure up to a 30-minute cap.
- Re-locks on background return so a stolen handset can't see codes by tapping back into the app.
- Encrypted at rest via `EncryptedSharedPreferences` (AES-GCM-256, key in Android Keystore).
- `FLAG_SECURE` on the main and PIN screens — blocks screenshots and screen recording.
- Clipboard `IS_SENSITIVE` flag on copy — Android 13+ hides the value in clipboard previews.
- Storage Access Framework backups:
  - Encrypted (`DUMB1\n` magic header + AES-GCM-256 with PBKDF2-derived key).
  - Plaintext JSON (gated by an explicit warning dialog).
  - Cross-imports from totp_app's `TOTP1\n` encrypted blobs and `{v, entries:[{name, seed}]}` plaintext shape.
- Multi-select account deletion.
- In-app update check against GitHub releases — downloads the APK and launches the system installer.
- Full D-pad / keyboard navigation: explicit focus chains on every screen, list-row focus highlight, MENU key opens the add-account dialog, IME `actionDone`/Enter submits the PIN.

## Build

Open the project in Android Studio (AGP 9.2.1, JDK 21, min-SDK 23, target 35) and run. No additional setup.

## Dependencies

Bare minimum — no AppCompat, no Material, no Compose, no Play Services:

- `androidx.activity:activity-ktx` — ComponentActivity, LifecycleOwner, ActivityResult APIs.
- `androidx.security:security-crypto` — at-rest encryption.
- `androidx.camera:*` + `com.google.mlkit:barcode-scanning` — live scanner.
- `dev.turingcomplete:kotlin-onetimepassword` — TOTP generator.

## Releases

Cut a release on GitHub with the built APK attached as an asset and the tag set to the new `versionName` (e.g. `v1.0.3`). The in-app update checker (Settings → CHECK FOR UPDATES) polls the latest release and offers to install.

## License

GPL-3.0. See [LICENSE](LICENSE).
