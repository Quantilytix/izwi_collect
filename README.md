# Izwi — Smart-Q officer voice capture

Android app implementing Plan B of the SAVE / Smart-Q Shona TTS project:
[PLAN_B_officer_voice_capture_app.md](../SAVE_tts/PLAN_B_officer_voice_capture_app.md).
Captures the Smart-Q business development officer's consented Shona voice
for the production TTS voice, separate from Plan A's internal Shona
benchmark.

`izwi` is Shona for "voice."

## Status

Phase 1–5 implemented (project shell, capture, script/review, quality
checks, export/upload). **Not yet through Phase 6** (device rehearsal,
go/no-go gate) — this has been compiled (`./gradlew assembleDebug`
succeeds) and the relay has been smoke-tested end to end, but **not run on
a physical device**. Do not use it for Audry's real session before Phase 6
passes.

## Architecture

```
Izwi (Android app)              Smart-Q Voice Relay (HF Space)         Dataset repo (HF)
  consent + recording      -->    validates batch (X-Relay-Key)   -->   pull request
  local review queue              holds the HF write token               human review + merge
  bounded batch export            never exposes it to the app
```

- **App**: `com.quantilytix.izwi`, Kotlin, classic Views (matches Injini's
  proven pattern), Room for local storage, WorkManager for explicit
  (non-automatic) upload retries, OkHttp for the relay call.
- **Relay**: `../smart-q-voice-relay` — FastAPI on Hugging Face Spaces
  (`rairo/smart-q-voice-relay`, public — see security note below). Holds
  the only HF write token in the whole pipeline.
- **Dataset**: `rairo/smart-q-shona-voice-corpus` (private). Every batch
  lands as a PR, never a direct commit to `main`.
- **Dashboard**: `../smart-q-voice-dashboard` — read-only Streamlit Space
  (`rairo/smart-q-voice-dashboard`, private) showing open PRs and merged
  corpus stats.

## Namespace note

The plan document targets `quantilytix/...`. The HF token available when
this was built only had write scope to the personal `rairo` namespace, not
the Quantilytix org, so all three resources were created under `rairo/`
instead. Transfer to `quantilytix/` once an org-scoped token is available —
HF supports repo/space transfer without breaking most references.

## Security

- The app bundles only a narrow relay upload key (`local.properties` →
  `BuildConfig.RELAY_API_KEY`), never an HF token. `local.properties` is
  gitignored; the key currently baked into the debug build is the one
  provisioned as the relay's `RELAY_API_KEY` secret.
- The relay Space is **public** so the app can reach it without an HF
  login — access control is the `X-Relay-Key` header check inside
  `app.py`, not HF's space-privacy gate. Confirmed with the user before
  making it public. The HF write token lives only in the relay's Space
  secrets.
- Rotate the relay key by updating both the Space secret and
  `local.properties` together, then rebuild.

## Building

Requires Android SDK (compileSdk 35) and JDK 17.

```bash
./gradlew assembleDebug
```

`local.properties` needs `sdk.dir`, `relay.baseUrl`, and `relay.apiKey`
(see the checked-in template values — replace before shipping a real
build).

## What's still open (see PLAN_B for full detail)

- **Script review**: `app/src/main/assets/script_v1.json` is a
  machine-drafted seed script (40 prompts), explicitly flagged in its own
  `note` field as unverified Shona. A native speaker — ideally Audry
  herself — must review, correct, and expand it toward the 1,000–1,800
  utterance target before real recording.
- **Phase 6 device rehearsal**: install on Audry's actual phone, run a
  ten-clip rehearsal, inspect the resulting PR manually. Hard gate before
  Phase 7.
- **Consent copy**: `strings.xml` → `consent_body` covers the required
  points from the plan (purpose, use, storage, retention, withdrawal,
  scope, disclosure) but has not been reviewed by counsel.
- **App icon**: currently a placeholder vector mark, not a designed
  brand asset.
