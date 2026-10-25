# Hapuya — Android app repository

This folder is the git repository root: app code, backend (from M3), CI and
engineering docs. Product docs live in `../03_Product`, test sets in
`../05_Testing`.

## Layout
| Path | What |
|---|---|
| `app/` | Android app (M0: an empty Compose shell) |
| `gradle/libs.versions.toml` | All dependency versions |
| `.github/workflows/ci.yml` | CI: secret scan, unit tests, lint, debug build |
| `.githooks/pre-commit` | Blocks secret and signing files; runs gitleaks |
| `docs/architecture.md`, `docs/threat-model.md`, `docs/adr/` | Engineering design |
| `spikes/` | Throwaway M2 experiments (never merged into `app/`) |
| `backend/` | Cloudflare Worker (M3) |

## Build
```bash
./gradlew testDevDebugUnitTest lintDevDebug assembleDevDebug
```
Needs JDK 17+ (Android Studio's bundled JBR works) and an Android SDK with
platform 37 (`compileSdk`) in `local.properties`, which is gitignored.

**After cloning:** run `git config core.hooksPath .githooks` once per clone.

## Machine notes (MAGNUS)
- **AVG HTTPS scanning** re-signs HTTPS traffic. Gradle trusts it through
  `~/.gradle/hapuya-truststore.p12`, which `~/.gradle/gradle.properties` points to;
  both live outside the repo.
- **Command line:** set
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/Users/Administrator/.gradle/tmp`
  or Java fails with "Unable to establish loopback connection".
- **SDK:** command-line tools in `%LOCALAPPDATA%\Android\Sdk`; only
  `android-sdk-license` is accepted (approved by Splash, 25 Oct 2026).

## Rules
- Never commit keys, keystores, `google-services.json`, `.env` or `local.properties`.
  The hook and CI enforce this.
- The app holds backend URLs only. AI provider keys live in the backend's secrets.
- Small, tested commits; never claim tests passed without running them.
