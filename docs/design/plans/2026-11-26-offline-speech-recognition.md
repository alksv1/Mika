# Offline Speech Recognition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hold-to-talk voice input that works fully offline on any arm64 Android phone after the user downloads (or imports) a hash-verified voice pack, usable as a chosen model, as a backup in `sttFallbacks`, and by Auto when device recognition is unusable.

**Architecture:** A bundled manifest (`assets/voice-models/catalog.json`) is the only trust anchor; packs are fetched file by file from mirror → Mika Release → Hugging Face (pinned commit) or imported, verified, staged and atomically renamed into `filesDir/voice-models/`. Recognition goes through two small interfaces (`OnDeviceSttBackend` / `OnDeviceSttEngine`) with sherpa-onnx as the first backend; a new `SttProtocol.OnDevice` adapter exposes it to the existing voice catalog, failover chain and Auto resolution.

**Tech Stack:** Kotlin, sherpa-onnx v2.0.3 Android AAR (static onnxruntime, arm64-v8a only), OkHttp via `NetProxy`, kotlinx.serialization, JUnit + MockWebServer, instrumentation tests, Python 3 for the publish script.

**Spec:** `docs/design/specs/2026-11-26-offline-speech-recognition-design.md` (approved 2026-11-26). Read §5 before any task; §-references below point there.

## Global Constraints

- Generic Android only: public APIs, no brand-specific paths (CLAUDE.md). Unsupported ABI → source hidden with "此设备不支持离线识别", never a crash.
- No model weights in the APK; arm64 APK grows ≤ 20 MB (§7). No downloaded executable code (`.so`, dex) — native libs ship in the APK.
- A file is loaded only after its size and SHA-256 matched `catalog.json`. The manifest is never fetched from the network.
- The voice-models Release must never become "latest": pre-release or `make_latest=false` (UpdateChecker reads `/releases/latest`).
- Downloads start only from a user tap; not Wi-Fi-bound; through `NetProxy` (SOCKS works); if `ConnectivityManager.isActiveNetworkMetered()` the size is confirmed first; free space checked first.
- `voice-models` is added to `BackupEngine.excludedDirs`.
- Auto uses a pack only when device recognition is not `usable` (anti-goal §2). Auto has no fallback chain.
- Calls (`CascadeEngine.listenRef`, call STT pickers) never use `OnDevice` models in v1.
- On-device failures always fail over (`VoiceFailover.canTryNext == true`) and are never `ModelError.Network`.
- Removing the selected pack from its card sets STT selection to **Auto** (special case vs existing `Off`); restore-without-files keeps the selection and shows "需要下载".
- Every new string in default + all 13 locales; `python3 tools/i18n/translations.py check` passes.
- Third-party: sherpa-onnx and each model listed in `licenses/THIRD_PARTY_NOTICES.md` and the in-app licenses page (`LicensesTest` keeps them in step); each pack installs its own license file.
- Language codes: manifest `recommendedFor` uses `AppLanguage.tag` values (`zh-Hans`, `zh-Hant`, `id`, …); `languages` uses ISO 639 language parts (`zh`, `en`, `yue`, …).
- Unit tests: `./gradlew :app:testDebugUnitTest`; device tests: `./gradlew :app:connectedDebugAndroidTest`.

## Review Focus

- Process killed mid-install or mid-download: next launch has no half-installed pack, the old version still works, staging is cleaned — Task 4 test `cleanupRemovesStagingAndStaleVersions`, Task 7 test `killedDuringCommitKeepsOldVersion`.
- Backup restored onto a new phone: snapshot references `on-device/<pack>` but files are absent — selection and chain kept, voice input reports pack missing (not "provider gone"), download makes it work without reselecting — Task 6 test `missingPackKeepsSelectionAndReportsPackMissing`.
- Delete pressed while that pack is downloading or transcribing — download cancelled and staging removed; an in-flight transcription finishes or fails as `OnDeviceFailed`, never crashes on a closed engine — Task 7 test `deleteDuringDownloadCancelsAndCleans`, Task 3 test `closeWhileTranscribingFailsCleanly`.
- A source drops mid-file or a mirror serves a wrong file — that file moves to the next source, already-verified files are not re-downloaded, `.part` never survives — Task 5 test `wrongHashFallsToNextSourcePerFile`.
- App language set to "System" or a speech tag with script/region (`zh-Hans-CN`, `pt-BR`) — language part extracted, recommended pack still found — Task 2 tests `systemLanguageResolvesRecommendation`, `scriptAndRegionTagsMapToLanguagePart`.

---

### Task 0: Verify inputs (spec §8) — no product code

**Files:**
- Modify: `docs/design/specs/2026-11-26-offline-speech-recognition-design.md` (§5.1 table, §8 results)

- [ ] **Step 1:** Find the exact Hugging Face repos and pin one commit each for SenseVoice Small int8, Dolphin base CTC int8, Whisper base int8 (sherpa-onnx exports). Record repo, commit, file list, size and the LFS SHA-256 of each needed file.
- [ ] **Step 2:** Download sherpa-onnx v2.0.3 static-onnxruntime Android AAR; record its SHA-256, the arm64-v8a `.so` total size, and confirm the Kotlin API (`OfflineRecognizer`, `OfflineRecognizerConfig`, `OfflineModelConfig` with `senseVoice`/`whisper`/`dolphin` fields, `createStream`, `acceptWaveform`, `decode`, `getResult`).
- [ ] **Step 3:** On a mid-range arm64 test phone, run each model (sherpa-onnx demo or a scratch harness, not committed) on 10 everyday commands per §7 for each of the 13 languages; record accuracy, 5 s latency, resident memory; check SenseVoice output for zh-Hant (simplified vs traditional).
- [ ] **Step 4:** Update the §5.1 recommendation table and §8 with results (move any language that misses to Whisper or mark "效果有限"). Expected: every one of the 13 `AppLanguage` tags is in some pack's `recommendedFor`.
- [ ] **Step 5: Commit** `docs(voice): record offline STT verification results`.

### Task 1: Publish script and bundled manifest

**Files:**
- Create: `tools/voice-models/publish.py`, `tools/voice-models/packs.json` (source list: id, version, backend, family, displayName, languages, recommendedFor, hf repo + commit, files with role, license file + name + attribution), `tools/voice-models/test_publish.py`, `tools/voice-models/README.md`
- Create: `app/src/main/assets/voice-models/catalog.json` (generated, committed)

**Interfaces:**
- Produces: `catalog.json` shape `{ "schema": 1, "release": "https://github.com/wangzan101/Mika/releases/download/voice-models-v1/", "packs": [ { id, version, backend, family, displayName, languages, recommendedFor, files: [{name, role, size, sha256}], license: {name, file, attribution}, sources: { upstream } } ] }`. Release asset name is `<packId>--<fileName>`; the license file is one of `files` with role `license`.
- CLI: `publish.py fetch` (download pinned files to a cache dir, verify against HF LFS SHA-256), `publish.py catalog` (write `catalog.json`), `publish.py upload --tag voice-models-v1` (via `gh release create --prerelease` / `gh release upload`; refuses if the tag would be latest).

- [ ] **Step 1: Write failing tests** in `test_publish.py`: `test_asset_name_joins_pack_and_file` (`asset_name("sensevoice-small-int8", "model.int8.onnx") == "sensevoice-small-int8--model.int8.onnx"`), `test_fetch_rejects_hash_mismatch` (raises `HashMismatch` naming the file), `test_catalog_lists_license_as_file` (every pack has a file with `role == "license"`), `test_upload_refuses_latest` (`upload_args(tag)` contains `--prerelease`).
- [ ] **Step 2:** Run `python3 -m pytest tools/voice-models -q` → FAIL (module missing).
- [ ] **Step 3:** Implement `publish.py` with stdlib only (`urllib`, `hashlib`, `json`, `subprocess` for `gh`); `packs.json` filled from Task 0.
- [ ] **Step 4:** Run tests → PASS; run `publish.py fetch && publish.py catalog`; confirm `catalog.json` sizes/hashes equal Task 0 records.
- [ ] **Step 5: Commit** `feat(voice): voice-pack publish script and bundled catalog`. (Uploading the Release is a maintainer action after merge; README documents it.)

### Task 2: Manifest parsing, language mapping, timeout budget (pure Kotlin)

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voice/ondevice/VoicePackCatalog.kt`, `voice/ondevice/OnDeviceLanguage.kt`, `voice/ondevice/OnDeviceTimeout.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voice/ondevice/VoicePackCatalogTest.kt`, `OnDeviceLanguageTest.kt`, `OnDeviceTimeoutTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class VoicePackFile(val name: String, val role: String, val size: Long, val sha256: String)`
  - `@Serializable data class VoicePackLicense(val name: String, val file: String, val attribution: String)`
  - `@Serializable data class VoicePackEntry(val id: String, val version: Int, val backend: String, val family: String, val displayName: String, val languages: List<String>, val recommendedFor: List<String>, val files: List<VoicePackFile>, val license: VoicePackLicense, val upstream: String)` with `val totalBytes: Long`
  - `class VoicePackCatalog(val release: String, val packs: List<VoicePackEntry>)`; `companion fun parse(json: String, supported: (backend: String, family: String) -> Boolean): VoicePackCatalog`; `companion fun load(context: Context, supported: (backend: String, family: String) -> Boolean): VoicePackCatalog` (reads the asset; callers pass `OnDeviceBackends::supports` from Task 3); `fun entry(id: String): VoicePackEntry?`; `fun releaseUrl(entry, file): String`; `fun upstreamUrl(entry, file): String`
  - `object OnDeviceLanguage { fun languagePart(speechTag: String?): String?; fun pick(speechTag: String?, languages: List<String>): String? /* null = auto-detect */; fun recommended(catalog: VoicePackCatalog, app: AppLanguage, system: Locale = Locale.getDefault()): VoicePackEntry? }`
  - `object OnDeviceTimeout { const val MIN_MS = 30_000L; const val FACTOR = 3; const val LOAD_TIMEOUT_MS = 60_000L; fun budgetMs(samples: Int, rate: Int = Wav.RATE): Long }`

- [ ] **Step 1: Write failing tests:**
  - `bundledCatalogIsConsistent`: parse the real asset; every pack has a `license` role file whose name equals `license.file`; every `sha256` matches `^[0-9a-f]{64}$`; every `size > 0`; ids unique; the union of `recommendedFor` equals `AppLanguage.entries.mapNotNull { it.tag }` (13 tags).
  - `unknownBackendOrFamilyIsHidden`: `parse(json, supported = { b, f -> b == "sherpa-onnx" && f != "future" })` drops the `future` pack, keeps the rest, does not throw.
  - `unknownFieldsAreIgnored` (newer manifest fields don't break parsing).
  - `releaseUrlUsesPackPrefix`: `releaseUrl(e, file) == release + "sensevoice-small-int8--model.int8.onnx"`.
  - `scriptAndRegionTagsMapToLanguagePart`: `languagePart("zh-Hans-CN") == "zh"`, `languagePart("pt-BR") == "pt"`, `languagePart("in-ID") == "id"` (legacy Indonesian), `languagePart(null) == null`.
  - `pickReturnsNullWhenUnsupported`: `pick("fa-IR", listOf("zh","en")) == null`; `pick("en-US", listOf("zh","en")) == "en"`.
  - `systemLanguageResolvesRecommendation`: `recommended(catalog, AppLanguage.System, Locale("vi","VN"))` is the pack whose `recommendedFor` has `vi`.
  - `budgetIsThreeTimesAudioWithFloor`: 5 s of samples → 30_000; 20 s → 60_000.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*ondevice*'` → FAIL.
- [ ] **Step 3:** Implement. Use `AppLanguage.modernCode` for legacy codes; `recommended` resolves System via `AppLanguage.resolved`-equivalent logic on the passed `Locale`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(voice): voice-pack manifest, language mapping and on-device timeout`.

### Task 3: Backend interfaces, sherpa-onnx backend, engine holder

**Files:**
- Create: `app/libs/sherpa-onnx-2.0.3-static.aar` (vendored; SHA-256 from Task 0 recorded in `app/libs/README.md`), `voice/ondevice/OnDeviceBackend.kt`, `voice/ondevice/SherpaOnnxBackend.kt`, `voice/ondevice/OnDeviceEngines.kt`
- Modify: `app/build.gradle.kts` (AAR dependency; keep only `arm64-v8a` from it via `packaging { jniLibs { excludes += ... } }` for other ABIs), `voice/Wav.kt` (add `decode`), `licenses/THIRD_PARTY_NOTICES.md`, `about/ui/LicensesScreen.kt`
- Test: `app/src/test/.../voice/WavTest.kt` (extend), `app/src/test/.../voice/ondevice/OnDeviceEnginesTest.kt`, `app/src/androidTest/java/com/wangzan101/mika/voice/OnDeviceRecognitionDeviceTest.kt`, `LicensesTest` (existing, must pass)

**Interfaces:**
- Produces (exactly as spec §5.4.1):
  - `interface OnDeviceSttBackend { val id: String; fun supports(family: String): Boolean; fun abiSupported(): Boolean; fun load(pack: InstalledVoicePack, options: EngineOptions): OnDeviceSttEngine }`
  - `interface OnDeviceSttEngine : AutoCloseable { suspend fun transcribe(pcm: ShortArray, language: String?): String }`
  - `data class EngineOptions(val threads: Int)`; `companion fun default() = EngineOptions(minOf(Runtime.getRuntime().availableProcessors(), 4))`
  - `data class InstalledVoicePack(val entry: VoicePackEntry, val dir: File) { fun file(role: String): File }` (throws `OnDeviceSttException(Kind.Missing)` if the role is absent)
  - `class OnDeviceSttException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) { enum class Kind { Missing, Corrupt, OutOfMemory, Timeout, Unsupported } }`
  - `class OnDeviceBackends(backends: List<OnDeviceSttBackend> = listOf(SherpaOnnxBackend())) { fun find(id: String): OnDeviceSttBackend?; fun supports(backend: String, family: String): Boolean }`
  - `class OnDeviceEngines(backends: OnDeviceBackends, scope: CoroutineScope, idleMs: Long = IDLE_MS)`: `fun prewarm(pack: InstalledVoicePack)`; `suspend fun engine(pack: InstalledVoicePack): OnDeviceSttEngine` (one load per pack id+version, mutex, bounded by `OnDeviceTimeout.LOAD_TIMEOUT_MS` → `Kind.Timeout`; `OutOfMemoryError` → `Kind.OutOfMemory`; other load errors → `Kind.Corrupt`); `fun release()` (closes all; called from `MikaApp.onTrimMemory` at `TRIM_MEMORY_RUNNING_LOW`+ and after `IDLE_MS = 5 * 60_000` without use); `fun release(packId: String)`; `companion val shared`.
  - `object Wav { fun decode(wav: ByteArray): ShortArray }` (16-bit mono PCM after the 44-byte header).
- `SherpaOnnxBackend`: `id = "sherpa-onnx"`; `supports` = `family in setOf("sense_voice", "whisper", "dolphin")` (adding a sherpa family later is one branch here); `abiSupported() = "arm64-v8a" in Build.SUPPORTED_ABIS`; maps roles `model`, `tokens`, `encoder`, `decoder` into `OfflineModelConfig`; `transcribe` runs on `Dispatchers.Default`, converts PCM to float, sets language (`senseVoice.language` / `whisper.language`, empty = auto), returns trimmed text.

- [ ] **Step 1: Write failing tests:**
  - `WavTest.decodeRoundTripsEncode`: `Wav.decode(Wav.encode(pcm)) contentEquals pcm`.
  - `OnDeviceEnginesTest` (fake backend): `loadsOncePerPackVersion`, `loadTimeoutMapsToTimeout` (fake load sleeps > timeout with test clock → `Kind.Timeout`), `oomMapsToOutOfMemory`, `releaseClosesEngines`, `idleReleaseAfterIdleMs`, `closeWhileTranscribingFailsCleanly` (release during an in-flight fake `transcribe` → that call ends with `OnDeviceSttException`, not `IllegalStateException`; the next `engine()` reloads).
  - Device test `OnDeviceRecognitionDeviceTest.transcribesFixture` (skipped via `assumeTrue` when the SenseVoice pack is not installed on the test phone; the test README says to install it with the Task 9 UI or `adb push`): a bundled 3 s English WAV in `androidTest/assets` → text contains an expected word; measure and log latency.
- [ ] **Step 2:** Run unit tests → FAIL.
- [ ] **Step 3:** Implement; add AAR to Gradle; add notices entries (sherpa-onnx Apache-2.0, onnxruntime MIT, SenseVoice FunASR Model License 1.1, Dolphin Apache-2.0, Whisper MIT) and matching `LicensesScreen` items.
- [ ] **Step 4:** Run unit tests, `LicensesTest` → PASS. Build `assembleRelease` split APKs; record the arm64 APK size delta (≤ 20 MB) for the PR.
- [ ] **Step 5: Commit** `feat(voice): on-device STT backend interface with sherpa-onnx`.

### Task 4: Pack storage and backup exclusion

**Files:**
- Create: `voice/ondevice/VoicePackStore.kt`
- Modify: `backup/BackupEngine.kt:103` (`excludedDirs` += `"voice-models"`), backup description string (all locales: "语音包体积大，不备份，恢复后重新下载")
- Test: `app/src/test/.../voice/ondevice/VoicePackStoreTest.kt`, existing `BackupEngine` test (add `voiceModelsNotBackedUp`)

**Interfaces:**
- Produces: `class VoicePackStore(root: File /* filesDir/voice-models */)`:
  - `fun installed(entry: VoicePackEntry): InstalledVoicePack?` — current-version dir exists and every file's length equals the manifest size (no hashing on load, §5.2).
  - `fun status(entry: VoicePackEntry): VoicePackStatus` — `enum class VoicePackStatus { NotInstalled, Installed, Corrupt, UpdateAvailable }` (`Corrupt` = dir present, size mismatch/missing file; `UpdateAvailable` = only an older version dir present, still usable via `installedAny`).
  - `fun installedAny(entry): InstalledVoicePack?` — current or older version (older keeps working, §5.7).
  - `fun stagingDir(entry): File` — `.staging/<id>-<version>/`, created empty.
  - `fun commit(entry, staging: File): InstalledVoicePack` — rename staging → `<id>/<version>/`, then delete other versions of that id.
  - `fun delete(id: String)`; `fun cleanup(catalog: VoicePackCatalog)` — removes `.staging`, versions not equal to the newest installed one, ids not in the catalog.
  - `fun freeBytes(): Long` (`StatFs`); `const val SPACE_MARGIN = 50L * 1024 * 1024`.

- [ ] **Step 1: Write failing tests:** `installedRequiresAllFilesWithManifestSizes`, `sizeMismatchIsCorrupt`, `commitIsAtomicAndRemovesOldVersion`, `olderVersionReportsUpdateAvailableAndStillLoads`, `cleanupRemovesStagingAndStaleVersions`, `deleteRemovesOnlyThatPack`, `BackupEngineTest.voiceModelsNotBackedUp`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement with `java.io.File` (`renameTo` within the same dir tree; on failure throw `IOException` and leave the old version).
- [ ] **Step 4:** Run → PASS. Call `cleanup` once at app start from `MikaApp` on a background thread.
- [ ] **Step 5: Commit** `feat(voice): voice-pack storage, atomic install, excluded from backup`.

### Task 5: Generic verified downloader (refactor of `ProxiedApkDownload`)

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/download/VerifiedDownload.kt`, `download/DownloadUrls.kt` (new top-level package: shared by `about` and `voice`, spec §10 left placement to the builder)
- Modify: `about/ProxiedApkDownload.kt` (becomes a thin wrapper over `VerifiedDownload` with its current public API and behaviour, including `wifiBoundClient`), `about/ApkIntegrity.kt` (`requireTrustedDownloadUrl` delegates to `DownloadUrls`)
- Test: `app/src/test/.../download/VerifiedDownloadTest.kt`, `download/DownloadUrlsTest.kt`; existing `about/ProxiedApkDownloadTest.kt` and ApkIntegrity tests unchanged and passing

**Interfaces:**
- Produces:
  - `data class DownloadFile(val target: File, val sources: List<String>, val verify: (File) -> Unit)`
  - `data class DownloadProgress(val phase: DownloadPhase, val doneBytes: Long, val totalBytes: Long, val error: Throwable? = null)` (reuse `DownloadPhase`)
  - `class VerifiedDownload(http: OkHttpClient, scope: CoroutineScope)`: `fun start(key: String, files: List<DownloadFile>, client: OkHttpClient? = null, onComplete: suspend () -> Unit = {}): Boolean` — false if `key` is already running; files in order; per file try each source, `fetch` → `verify` → next file; a file whose target already exists and passes `verify` is skipped; failure of all sources of one file fails the job; `fun progress(key): StateFlow<DownloadProgress>`; `fun cancel(key)`. Jobs with different keys never cancel each other.
  - `object DownloadUrls { fun requireTrusted(url: String, official: String, mirrors: List<UpdateMirror>, suffix: String?) }` — exactly the current APK rules, `suffix = ".apk"` for updates, `null` for packs (packs pass the exact Release or upstream URL as `official`; upstream must be `https://huggingface.co/` + manifest path).
  - `ProxiedApkDownload.fetch` moves to `VerifiedDownload.fetch` (same signature); `ProxiedApkDownload.fetch` delegates.

- [ ] **Step 1: Write failing tests:** `multiFileJobCompletesInOrder` (MockWebServer), `wrongHashFallsToNextSourcePerFile` (file 2 source 1 serves wrong bytes → source 2 used; file 1 fetched once; no `.part` left), `allSourcesFailFailsJobAndLeavesNoTargets`, `independentKeysDoNotCancel` (update + pack concurrently), `sameKeyStartReturnsFalse`, `cancelStopsConnectionAndDeletesPart`, `alreadyVerifiedFileIsSkipped`; `DownloadUrlsTest`: `apkRulesUnchanged`, `packAcceptsReleaseAndMirrorPrefix`, `packRejectsOtherHostsAndUserinfo`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement; then reduce `ProxiedApkDownload` to a wrapper (single key `"app-update"`, `start` keeps "cancel previous" semantics by calling `cancel(key)` first).
- [ ] **Step 4:** Run new tests and `ProxiedApkDownloadTest` → PASS.
- [ ] **Step 5: Commit** `refactor(download): shared verified multi-file downloader`.

### Task 6: Voice catalog integration and adapter

**Files:**
- Create: `voice/model/adapters/OnDeviceSttAdapter.kt`, `voice/ondevice/OnDeviceCatalog.kt`
- Modify: `voice/model/VoiceCatalog.kt` (`SttProtocol.OnDevice`), `voice/model/VoiceModelError.kt`, `voice/model/VoiceFallbacks.kt` (`UTTERANCE_STT_PROTOCOLS` += `OnDevice`), `voice/model/VoiceCatalogStore.kt`, `voice/model/VoiceAdapterRegistry.kt`, `voice/model/SttSelectionController.kt` (`transcribeChain`, `start`), `voice/ProviderSpeech.kt`, `voice/cascade/CascadeEngine.kt` (`listenRef`), `voice/SpeechResolver.kt` (`SpeechEngineSpec.OnDevice`, new `Why` values)
- Test: extend `VoiceCatalogStoreTest`, `VoiceFallbacksTest`, `VoiceFailoverSimulationTest`, `SttSelectionControllerTest`, `ProviderSpeechTest`, `voice/cascade/*` listen tests; create `voice/model/adapters/OnDeviceSttAdapterTest.kt`

**Interfaces:**
- Produces:
  - `SttProtocol.OnDevice` (serial name `OnDevice`).
  - `object OnDeviceCatalog { const val SERVICE_ID = "on-device"; val SERVICE = VoiceService(SERVICE_ID, "On-device", VoiceAccount.None); fun model(entry: VoicePackEntry): VoiceModel /* stt = SttBinding(OnDevice, endpoint = entry.id), origin = Builtin */; fun ref(packId: String): VoiceModelRef; fun isOnDevice(ref: VoiceModelRef): Boolean }`
  - `VoiceModelError.PackMissing(val packId: String)` — category `INCOMPLETE_CONFIG`.
  - `VoiceModelError.OnDeviceFailed(detail: String, cause: Throwable?)` — category `SYSTEM_UNAVAILABLE`; `cause` is never a `ModelError`. `VoiceModelError.from(OnDeviceSttException)`: `Missing` → `PackMissing`, others → `OnDeviceFailed`.
  - `VoiceCatalogStore.installVoicePack(entry: VoicePackEntry)` — upsert `SERVICE` and the model in one commit. `VoiceCatalogStore.removeVoicePack(packId: String)` — like `deleteModel`, but if `selections.stt` was that ref it becomes `SttSelection.Auto` (comment the deliberate difference from `pruneSelections`).
  - `SpeechEngineSpec.OnDevice(val packId: String)`; `SpeechEngineSpec.Why.VoicePackMissing`, `Why.OnDeviceUnsupported`.
  - `class OnDeviceSttAdapter(catalog: () -> VoicePackCatalog, store: VoicePackStore, engines: OnDeviceEngines, backends: OnDeviceBackends) : SttAdapter` — `protocol = OnDevice`; `spec()` → `OnDevice(packId)`, throws `PackMissing` when `store.installedAny(entry)` is null or the entry is gone from the catalog, `Unsupported` when the backend's `abiSupported()` is false; `start()` calls `engines.prewarm` then returns `categorizedSession { ProviderSpeech(PcmRecorder(), utterance(context, model, language), timeoutMs = Long.MAX_VALUE) }`; an external `hooks.recorder` (a call) returns `FailedSession(Unavailable(Why.OnDeviceUnsupported))`; `utterance()` = decode WAV → `withTimeout(OnDeviceTimeout.budgetMs(pcm.size)) { engines.engine(pack).transcribe(pcm, OnDeviceLanguage.pick(language, entry.languages)) }`, timeout → `OnDeviceFailed`, every `OnDeviceSttException` via `VoiceModelError.from`.
- Behaviour changes in existing code:
  - `ProviderSpeech`: `catch (e: VoiceModelError) → Failed(Provider(e))` before the generic wrap, so pack-missing/on-device errors reach the UI unwrapped.
  - `transcribeChain`: no outer `withTimeoutOrNull(attemptTimeoutMs)` for `OnDevice` candidates (the adapter bounds itself); `start()` passes `timeoutMs = Long.MAX_VALUE` to the chained `ProviderSpeech` when any candidate is `OnDevice`.
  - `CascadeEngine.listenRef`: both `shared` and `callStt` exclude `OnDeviceCatalog.isOnDevice(ref)` (→ `NoListenModel`).
  - `VoiceInput.spec()`: `PackMissing` → `Unavailable(Why.VoicePackMissing)` instead of `ProviderGone`.

- [ ] **Step 1: Write failing tests:**
  - `VoiceFallbacksTest.onDeviceIsEligible`.
  - `VoiceFailoverSimulationTest`: `packMissingFailsOver`, `onDeviceCorruptFailsOver`, `onDeviceTimeoutFailsOverAndIsNotNetwork` (category `SYSTEM_UNAVAILABLE`), `cloudHeadFailsOverToOnDeviceTail`, `emptyTextIsNotFailure` (`""` returned → session `Final("")`, no next attempt).
  - `VoiceCatalogStoreTest`: `installVoicePackAddsServiceAndModel`, `removeSelectedPackFallsBackToAuto`, `removePackDropsItFromFallbacks`, `deleteModelStillSetsOff` (existing rule unchanged), `missingPackKeepsSelectionAndReportsPackMissing` (snapshot with pack model, empty store → selection and chain intact; `SttSelectionController.spec(ref)` throws `PackMissing`; after files appear it works without reselecting).
  - `OnDeviceSttAdapterTest` (fake engine): `languageMappedFromSpeechTag`, `budgetUsesAudioLength`, `loadTimeNotCountedInBudget` (slow fake load within `LOAD_TIMEOUT_MS`, fast transcribe → success), `externalRecorderRefused`, `unsupportedAbiThrowsUnsupported`.
  - `ProviderSpeechTest.voiceModelErrorPassesThroughUnwrapped`.
  - `SttSelectionControllerTest.chainSkipsOuterTimeoutForOnDevice`.
  - Cascade test `callsNeverListenWithOnDevice` (both `stt` and `callStt` set to a pack → `NoListenModel`).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement; register `OnDeviceSttAdapter` in `VoiceAdapterRegistry` defaults (singletons from `VoiceModelEnvironment.get`).
- [ ] **Step 4:** Run the whole voice test suite `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.*'` → PASS.
- [ ] **Step 5: Commit** `feat(voice): on-device STT protocol, adapter and failover integration`.

### Task 7: Pack installer — download, import, delete

**Files:**
- Create: `voice/ondevice/VoicePackInstaller.kt`
- Test: `app/src/test/.../voice/ondevice/VoicePackInstallerTest.kt`

**Interfaces:**
- Consumes: Task 2 catalog, Task 4 store, Task 5 `VerifiedDownload`/`DownloadUrls`, `UpdateMirrors.candidates`, `MirrorSettings`, `ApkIntegrity`-style `verify(file, size, "sha256:…")`, Task 6 `VoiceCatalogStore.installVoicePack/removeVoicePack`.
- Produces: `class VoicePackInstaller(catalog: VoicePackCatalog, store: VoicePackStore, download: VerifiedDownload, mirrors: () -> MirrorSettings, metered: () -> Boolean, voiceCatalog: VoiceCatalogStore, engines: OnDeviceEngines)`:
  - `fun sources(entry, file): List<String>` = `UpdateMirrors.candidates(releaseUrl)` (mirrors first when enabled, then official Release) + `upstreamUrl`.
  - `fun preflight(entry): Preflight` — `sealed interface Preflight { data object Ready; data class ConfirmMetered(val bytes: Long); data class NoSpace(val needed: Long, val free: Long); data object UnsupportedDevice }` (`needed = totalBytes + SPACE_MARGIN`; metered check only after space check).
  - `fun download(entry): Boolean` — key `"voice-pack:<id>"`; into `store.stagingDir`; on success `store.commit` then `voiceCatalog.installVoicePack(entry)`; on failure or cancel delete staging.
  - `fun progress(entry): StateFlow<DownloadProgress>`; `fun cancel(entry)`.
  - `suspend fun import(entry, inputs: List<ImportInput>): ImportResult` — `data class ImportInput(val name: String, val open: () -> InputStream)`; zip inputs are expanded (entries with or without a `<id>/` prefix); files not in the manifest ignored; `sealed interface ImportResult { data object Installed; data class Missing(val files: List<String>); data class Mismatch(val file: String) }`; same staging/commit path.
  - `fun delete(entry)` — cancel download, `engines.release(id)`, `store.delete(id)`, `voiceCatalog.removeVoicePack(id)`.
  - `fun downloadUrls(entry): String` — newline-joined official Release URLs (for "复制下载地址").

- [ ] **Step 1: Write failing tests:** `sourcesOrderMirrorsReleaseUpstream`, `preflightNoSpaceBeforeMetered`, `preflightMeteredAsksWithSize`, `downloadCommitsAndRegistersModel`, `killedDuringCommitKeepsOldVersion` (simulate rename failure → old version still `installed`, staging removed, catalog unchanged), `deleteDuringDownloadCancelsAndCleans`, `importZipWithAndWithoutSubdir`, `importReportsMissingAndMismatchByName`, `importIgnoresExtraFiles`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement; hashing on `Dispatchers.IO`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(voice): voice-pack download, import and delete`.

### Task 8: Auto resolution order

**Files:**
- Create: `voice/ondevice/OnDeviceAuto.kt`
- Modify: `voice/VoiceInput.kt` (`migratedSpec`, `startMigrated`), `voice/SpeechResolver.kt` (Auto branch takes an optional pack), `voice/ui/VoiceHold.kt` (`unavailable()` strings for new `Why` values)
- Test: `SpeechResolverTest`, `VoiceInputUnifiedTest`, create `voice/ondevice/OnDeviceAutoTest.kt`

**Interfaces:**
- Produces: `object OnDeviceAuto { fun pick(speechTag: String?, app: AppLanguage, catalog: VoicePackCatalog, installed: (VoicePackEntry) -> Boolean): VoicePackEntry? }` — recommended-for-language installed pack, else any installed pack (catalog order), else null.
- `SpeechResolver.resolve(choice, system, providers, autoPack: String? = null)`: Auto → `system.usable` → System; else `autoPack != null` → `OnDevice(autoPack)`; else existing `SystemNoResponse` / `NoSystemNoModel`.
- `VoiceInput.startMigrated`, Auto: `SYSTEM_STT_REF` when system usable, else `OnDeviceCatalog.ref(pick.id)` with **no** backups, else existing failure.

- [ ] **Step 1: Write failing tests:** `autoPrefersUsableSystem` (system usable + pack installed → System), `autoUsesRecommendedPack`, `autoFallsBackToAnyPack`, `autoWithoutPackKeepsExistingWhy`, `autoNeverUsesFallbackChain` (Auto + pack → `start` called with empty backups), `silentSystemWithPackUsesPack` (available but not responsive → pack).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(voice): Auto falls back to installed voice packs`.

### Task 9: UI and strings

**Files:**
- Create: `voice/ui/VoicePacksSection.kt`, `voice/ui/VoicePacksViewModel.kt`
- Modify: `voice/ui/VoiceCatalogScreen.kt` (insert section), STT model picker (`voice/ui/SpeechModelScreen.kt` / `VoiceModelScreen.kt`: packs listed with "本机 · 离线", "需要下载" when missing), call STT pickers (exclude packs), `voice/ui/VoiceHold.kt` (`NoSystemNoModel` / `SystemNoResponse` / `VoicePackMissing` add a "下载离线语音包" action to the voice section), `res/values*/strings_voice*.xml` (13 locales)
- Test: `app/src/test/.../voice/ui/VoicePacksViewModelTest.kt`; `app/src/androidTest/.../voice/VoicePacksScreenTest.kt`

**Interfaces:**
- Consumes: Task 7 `VoicePackInstaller`, Task 4 `VoicePackStatus`, Task 2 `OnDeviceLanguage.recommended`.
- Produces: `data class VoicePackCard(val entry: VoicePackEntry, val status: VoicePackStatus, val progress: DownloadProgress?, val recommended: Boolean)`; `VoicePacksViewModel.cards: StateFlow<List<VoicePackCard>>` (recommended first); actions `download`, `confirmMetered`, `cancel`, `delete`, `import(uris)`, `copyUrls`.

- [ ] **Step 1: Write failing tests:** ViewModel `recommendedPackFirst`, `meteredConfirmationFlow`, `noSpaceShowsMessageWithoutStarting`, `unsupportedDeviceHidesSection`; device test: card states NotInstalled → Downloading (progress readable by TalkBack via `contentDescription`/`stateDescription`) → Installed; "需要下载" label in the picker after deleting files behind the store's back.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement with existing `ListRow` / card components; import via `ActivityResultContracts.OpenMultipleDocuments`; copy via `ClipboardManager`. No first-launch prompt.
- [ ] **Step 4:** Run unit + device tests and `python3 tools/i18n/translations.py check` → PASS.
- [ ] **Step 5: Commit** `feat(voice): offline voice pack settings and picker`.

### Task 10: Docs and acceptance

**Files:**
- Modify: `docs/dev/architecture.md` §16 (offline source, manifest, download/import, Auto order, extension points), user docs voice page ("离线语音包": where, import, space), spec status → implemented
- Test: device acceptance on the test phone

- [ ] **Step 1:** Install each pack via the UI on the test phone (Release not yet published: point a mirror entry at a local `python3 -m http.server` serving `publish.py fetch` output, or import the zip).
- [ ] **Step 2:** Measure and record for the PR: arm64 APK delta, 5 s latency (SenseVoice ≤ 1.5 s, Whisper ≤ 4 s), resident memory (≤ 400 MB), 10-sentence accuracy per language. Misses are written down, not hidden.
- [ ] **Step 3:** Airplane mode: hold-to-talk with the pack selected; with a cloud head + pack tail (head fails over); Auto on a profile where device recognition is disabled.
- [ ] **Step 4:** Full suites `./gradlew :app:testDebugUnitTest :core:test` and `python3 -m pytest tools/voice-models -q` → PASS.
- [ ] **Step 5: Commit** `docs(voice): offline speech recognition`. After merge the maintainer runs `publish.py upload --tag voice-models-v1`.
