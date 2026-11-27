# 本机离线语音识别 设计

> 日期：2026-11-26 · 状态：已实现（2026-11-27；批准于 2026-11-26） · 范围：语音输入（按住说话、胶囊）的离线识别。不含朗读、实时通话、文件转写

## 1. 为什么要做

- **很多安卓手机用不了语音输入。** Mika 现在的语音输入只有两种来源：
  - 手机自带的识别服务（`SystemSpeech`）。不少机型没有这个服务，或者有但不响应。
  - 云端模型（`ProviderSpeech` 等）。要用户自己去配服务商。

  手机没有可用的识别服务、用户也没配语音模型时，`SpeechResolver` 只能给出 `Why.NoSystemNoModel` 或 `Why.SystemNoResponse`，语音输入整个不可用。
- **备援链缺一个不会失败的结尾。** #880 加了语音输入备援链，但链上全是云端模型，会一起遇到断网、限流和欠费。本机模型断网能用、不限流，适合放在链的最后。
- **隐私。** 音频不离开手机。

**做成的样子**：任何一台 arm64 安卓手机，下载一个约 100–240 MB 的语音包，就能离线按住说话。这个语音包可以单独选用，也可以只当备援。

## 2. 目标、非目标、反目标

**目标**
1. 新增一种语音输入来源「本机离线模型」：按住说话，松手后整段识别。
2. 语音包按需下载，不打进 APK。下载来源可靠，国内能下，并且能离线导入。
3. 每个文件都用写在 APK 里的 SHA-256 校验，不管从哪里来，都只认校验通过的文件。
4. 接进现有语音目录：
   - 能被选为语音输入模型。
   - 能放进 `sttFallbacks` 备援链。
   - 「自动」模式在手机自带识别不可用时会用它。
5. 覆盖 Mika 的 13 种界面语言，每种语言都有推荐的语音包。

**非目标（第一版不做）**
- 边说边出字的流式离线识别。
- 离线朗读（TTS）。系统 TTS 基本都有，优先级低。
- 实时通话和 AI 接打电话的听写（`callStt`）。
- 转写已有的音频文件（`batchModel`）。长音频要先切段，单独做。
- 32 位设备（armeabi-v7a）和 x86 设备。
- GPU、NPU 加速。

**反目标（出现了就算失败，哪怕功能能跑）**
- APK 里出现任何模型权重，或者 APK 体积因此暴涨（见 §7 预算）。
- 从网络下载并加载可执行代码（`.so`、dex）。识别库必须随 APK 发布。
- 没通过校验的文件被加载。
- 在用户不知情时用移动数据下载上百 MB。
- 手机自带识别能用时，被「自动」悄悄换成离线模型，体验变差。

## 3. 约束

- **通用安卓**：只用公开 API，不针对任何品牌（CLAUDE.md）。
- **许可**：
  - 每个语音包要随包带上自己的许可文件和出处。
  - 在 `licenses/THIRD_PARTY_NOTICES.md` 和 App 内的开源许可页登记。
  - 识别库 sherpa-onnx 是 Apache-2.0。
- **发布渠道**：
  - 语音包放在 `wangzan101/Mika` 仓库的一个专门 Release 里。
  - 这个 Release **不能成为「最新发布」**：应用内更新读的是 `/releases/latest`（`MikaLinks.LATEST_RELEASE_API`、`UpdateProbe`）。要标成 pre-release，或者发布时设 `make_latest=false`。
- **存储**：
  - 放在应用内部目录，不进备份。现在 `BackupEngine.excludedDirs` 只有 `sandbox`、`library-attachments`，要加上语音包目录。
  - 下载前检查剩余空间。

## 4. 方案（已选定）

| 方案 | 做法 | 取舍 |
|---|---|---|
| A. 直连上游 | 从 HuggingFace 按文件下载，或下载 sherpa-onnx 在 GitHub 上的整包 | 国内直连 HuggingFace 不通；整包里同时有 fp32、int8 两套和测试音频（SenseVoice 整包 1 GB，实际只需要约 230 MB）；上游改名就失效；许可分散 |
| B. Mika 自己托管 | 精简后的文件放进自己仓库的 Release，APK 内带一份清单 | 复用现有的 GitHub 镜像链和校验；要维护清单，要确认每个模型允许再分发 |
| **C. B 为主，加备用来源和手动导入（选定）** | 同一份清单里每个文件列多个来源：Mika Release（走镜像链）→ HuggingFace 官方（固定到某个提交）→ 手动导入 | 信任只锚在 APK 里的 SHA-256，所以来源可以多；主来源挂了或者完全离线时都有退路 |

维护者 2026-11-26 选定 C，并确认可以把模型放在自己仓库的 Release 上。

## 5. 设计

### 5.1 语音包与清单

**语音包**是一个模型的一组文件。第一版准备四个，其中 `whisper-small-int8` 是可选包：

| 包 id | 模型 | 文件（只要 int8） | 大小（含许可文件） | 推荐给哪些界面语言 | 许可 |
|---|---|---|---|---|---|
| `sensevoice-small-int8` | SenseVoice Small（FunAudioLLM / 阿里） | `model.int8.onnx`、`tokens.txt` | 240 MB | zh-Hans、zh-Hant、en、ja、ko（也能识别粤语） | FunASR Model License 1.1：可使用、复制、修改、分享，须注明出处和作者、保留模型名 |
| `dolphin-base-ctc-int8` | Dolphin base CTC（DataoceanAI） | `model.int8.onnx`、`tokens.txt` | 104 MB | vi（效果有限）、fa（未实测，效果有限） | Apache-2.0 |
| `whisper-base-int8` | Whisper base（OpenAI） | `base-encoder.int8.onnx`、`base-decoder.int8.onnx`、`base-tokens.txt` | 161 MB | id；es、pt、ru、tr、ar（效果有限）；以及所有语言的兜底 | MIT（OpenAI whisper） |
| `whisper-small-int8` | Whisper small（OpenAI） | `small-encoder.int8.onnx`、`small-decoder.int8.onnx`、`small-tokens.txt` | 375 MB | 暂无。可选包：更准，但更慢、占内存更多（见 §8.1） | MIT（OpenAI whisper） |

上表已按 §8 的实测改过（2026-11-26）。「效果有限」指实测没达到 §7 的准确率要求，但前三个包里它最好。确切的仓库、提交、文件大小和哈希见 §8。

**清单**是 `app/src/main/assets/voice-models/catalog.json`，随 APK 发布，也就是信任锚。实际格式（由 `tools/voice-models/publish.py` 从 `packs.json` 生成，解析见 `voice/ondevice/VoicePackCatalog.kt`）：

```json
{
  "schema": 1,                       // 清单结构版本，对不上算打包错误
  "release": "https://github.com/wangzan101/Mika/releases/download/voice-models-v1/",  // Release 下载前缀，所有包共用
  "packs": [
    {
      "id": "sensevoice-small-int8",
      "version": 1,
      "backend": "sherpa-onnx",         // 用哪个推理后端跑（§5.4.1）
      "family": "sense_voice",          // 后端里的模型类型；sherpa-onnx 里对应离线识别的模型配置
      "displayName": "SenseVoice Small",
      "languages": ["zh", "en", "ja", "ko", "yue"],      // 模型能识别的语言（ISO 639 语言部分）
      "recommendedFor": ["zh-Hans", "zh-Hant", "en", "ja", "ko"],  // App 界面语言代码，与 AppLanguage 一致（印尼语是 id，不是 in）
      "files": [
        { "name": "model.int8.onnx", "role": "model", "size": 239233841, "sha256": "c71f0ce0…cd51" },
        { "name": "tokens.txt", "role": "tokens", "size": 315894, "sha256": "f449eb28…a1dc" },
        { "name": "LICENSE", "role": "license", "size": 5306, "sha256": "7dba975a…84f8",
          "url": "https://raw.githubusercontent.com/modelscope/FunASR/58830eca4012644aac0c3218c3ccc7d98f003fda/MODEL_LICENSE" }
      ],
      "license": { "name": "FunASR Model License 1.1", "file": "LICENSE", "attribution": "SenseVoice Small by FunAudioLLM (Tongyi Lab, Alibaba Group), …" },
      "upstream": "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/"
    }
  ]
}
```

- Release 地址是顶层 `release` 加 `<包id>--<文件名>`。上游地址是包的 `upstream` 加文件名；文件不在那里时（许可文件）在这个文件上单独写 `url`，同样固定到提交。
- 许可文件本身也作为一个文件列在 `files` 里，一起校验、一起放进安装目录。
- 清单只在发新版 App 时更新。App 不从网络读清单，这样别人改不了它要下载什么。
- 要换模型或修正哈希，就发一版 App。

**发布脚本** `tools/voice-models/publish.py`（开发脚本，放 `tools/`）：
1. 按固定的提交，从 HuggingFace 拉取所需文件，用上游给的 SHA-256 核对。
2. 附上许可文件。
3. 上传到 `voice-models-v1` Release，标为 pre-release，或者设 `make_latest=false`。
4. 生成 `catalog.json` 里的大小和哈希。

Release 的文件名带包 id 前缀（如 `sensevoice-small-int8--model.int8.onnx`），因为 GitHub Release 附件不支持目录。

### 5.2 下载

复用 `about/` 里的下载机制：`ProxiedApkDownload` 的做法、`UpdateMirrors` 的用户镜像列表、`NetProxy` 应用代理。抽出一个**通用的带校验下载器**，App 更新和语音包共用，不为语音另写一套。

现在的 `ProxiedApkDownload` 是「一个文件、进程里同时只下一个，`start` 会取消上一个」，所以这一步是**改造**，不只是搬家。改造后要满足：
- 一个任务能下多个文件。
- App 更新和语音包各自是独立任务，互不取消。
- 同一个语音包同时只能有一个任务。

- **来源顺序**：
  1. 用户的 GitHub 镜像（`UpdateMirrors`，用户排的顺序）加上 Release 地址。
  2. 官方 Release 地址。
  3. HuggingFace 固定提交的地址。

  某个来源失败或校验没通过，就换下一个。

- **地址校验**：`ApkIntegrity.requireTrustedDownloadUrl` 现在只接受 `.apk` 结尾的地址。需要泛化，或者另写一个同样严格的版本，只接受清单里的地址和「镜像前缀 + 清单地址」。
- **逐文件下载**：先写 `<文件>.part`，大小和 SHA-256 都对上才改名。每个文件分别换来源，不要求整包从同一个来源下完。
- **整包原子生效**：
  1. 下到 `voice-models/.staging/<包id>-<版本>/`。
  2. 所有文件都通过后，改名为 `voice-models/<包id>/<版本>/`。
  3. 再删掉旧版本。

  没有单独的「已安装」记录文件：版本目录在、清单里每个文件都在且大小对得上，就算装好了（见 §5.2「校验时机」）。

  中途失败时清掉 staging，已安装的旧版本不受影响。
- **网络**：语音包只由用户点「下载」时开始，不在后台自动下载，所以**不绑定 Wi-Fi**。做法和手动下载 App 更新一样：走 `NetProxy`，不绑定网络。
  - 开始前用 `ConnectivityManager.isActiveNetworkMetered()` 判断：是按流量计费的网络（移动数据、热点等）就先弹窗写明大小，用户确认后再下。
  - 不绑定 Wi-Fi，用 SOCKS 应用代理的用户也能下载（绑定 Wi-Fi 的那条路不接受 SOCKS，见 `ProxiedApkDownload.kt` 的 `wifiBoundClient`）。
- **空间**：下载前检查剩余空间，至少要有包大小加一定余量（具体数值由实现者定），不够就直接提示，不开始下载。
- **进度和中断**：设置页显示进度，可以取消。进程被杀后算失败，用户可以重试。是否支持 `Range` 断点续传由实现者决定，第一版可以不做。
- **校验时机**：下载完先校验一次。之后每次加载只核对大小，不每次重算哈希，因为 230 MB 太慢。加载失败时提示「语音包已损坏，请重新下载」。

### 5.3 手动导入

给完全离线、或者所有来源都下不动的用户用。

- 设置页的语音包卡片上有「从文件导入」。
- 用系统文件选择器选一个 zip，内容是该包的文件，可以带或不带包 id 子目录。也可以分别选多个文件，交互由实现者定。
- 导入时按清单逐个核对文件名、大小和 SHA-256。全部对上才安装，安装走 §5.2 同样的 staging 和改名流程。
- 不在清单里的文件忽略；缺文件或哈希不对就拒绝，并说明是哪个文件。
- 卡片上提供「复制下载地址」，用户可以在电脑上下好再拷到手机。

### 5.4 识别引擎

- **依赖**：sherpa-onnx 的 Android AAR，用固定版本（写设计时最新是 v2.0.3）。
  - 选「静态链接 onnxruntime」的版本，只保留 `arm64-v8a`。
  - AAR 不在 Maven 上：放进仓库（比如 `app/libs/`）并记下 SHA-256，或者从源码构建。由实现者定，但必须固定版本、可复现。
  - 加进 `THIRD_PARTY_NOTICES.md`。
- **非 arm64 设备**：不提供这个来源，界面写明「此设备不支持离线识别」。沿用沙箱对 arm64 的处理方式（`app/build.gradle.kts` 的注释）。
- **用法**：sherpa-onnx 的离线识别器（`OfflineRecognizer`），按清单里的 `family` 字段配 `sense_voice`、`whisper` 或 `dolphin` 的模型配置，文件按 `role` 对上配置里的位置，不按文件名。输入是 16 kHz 单声道 PCM，`PcmRecorder` 和 `Wav` 现在就是这个格式，实现时需要确认。
- **语言参数**：
  - SenseVoice 和 Whisper 支持指定语言，也支持自动。
  - 运行时传进来的是识别语言标签（例如 `zh-CN`，来自 `VoiceInput` 的 `SystemSpeech.speechTag(...)`），不是界面语言代码。
  - 取标签的语言部分（`zh`、`en`……），与清单的 `languages` 比对：在里面就指定这个语言；不在就用自动检测。
- **超时**：现有整段识别每次最多等 30 秒（`ProviderSpeech.TRANSCRIBE_TIMEOUT_MS`），超时记为 `ModelError.Network`。本机识别不能套用这一条，否则慢机器上会显示假的「连不上」，或者断网时被切到云端模型。
  - 本机识别用自己的上限：音频时长的 3 倍，最少 30 秒。
  - 第一次加载模型的时间不算在上限里。加载在按下说话时就在后台开始，和录音同时进行。
  - 超时记为本机错误（见 §5.5「失败分类」），不记成网络错误。
- **加载与内存**：
  - 第一次使用时在后台线程加载模型，之后常驻。
  - 闲置一段时间，或者系统 `onTrimMemory` 时释放。闲置多久由实现者定。
  - 加载失败（文件损坏、内存不足）转成 `VoiceModelError`。
- **线程**：识别在 `Dispatchers.Default` 上跑，线程数取「CPU 核数和 4 中较小的那个」。实现者可以按 §8 的实测再调。

### 5.4.1 可扩展：能接入任意语音转文字模型

第一版只放三个包，但结构上不绑死这三个。接一个新模型分三种情况，越往下改动越大：

| 新模型 | 要改什么 |
|---|---|
| sherpa-onnx 已支持的类型（三个包之外还有 Paraformer、Zipformer、Moonshine、FireRedASR、NeMo 等） | 只在 `catalog.json` 加一条，发一版 App；不写代码 |
| sherpa-onnx 支持、但这里还没接的类型 | `SherpaOnnxBackend` 里加一个 `family` 的配置映射（一个分支） |
| 别的推理库（比如 whisper.cpp、Vosk） | 新写一个 `OnDeviceSttBackend` 实现，在注册表里登记；库随 APK 发布（反目标：不下载可执行代码） |

**接口**（都在 `app` 的 `voice/ondevice/` 包，纯 Kotlin 部分能在 JVM 上测）：

```kotlin
/** 一个推理后端，比如 sherpa-onnx。按清单的 backend 字段找到它。 */
interface OnDeviceSttBackend {
    val id: String                                   // "sherpa-onnx"
    fun supports(family: String): Boolean            // 这个后端认不认这种模型
    fun abiSupported(): Boolean                      // 这台设备能不能跑（arm64 检测）
    /** 加载一个已安装、已校验的包；失败抛 OnDeviceSttException。可能很慢，在后台线程调。 */
    fun load(pack: InstalledVoicePack, options: EngineOptions): OnDeviceSttEngine
}

/** 加载好的一个模型，常驻内存，直到 close。 */
interface OnDeviceSttEngine : AutoCloseable {
    /** 16 kHz 单声道 PCM 进、文字出。language 是 ISO 639 语言部分，null 是自动检测。 */
    suspend fun transcribe(pcm: ShortArray, language: String?): String
}

data class EngineOptions(val threads: Int)

/** 清单里一个包，加上安装位置；files 按 role 取路径。 */
data class InstalledVoicePack(val entry: VoicePackEntry, val dir: File) {
    fun file(role: String): File
}
```

- `OnDeviceBackends` 是后端注册表，和 `VoiceAdapterRegistry` 同样的做法：构造时传入列表，测试换成假的。
- `OnDeviceSttAdapter`（§5.5）只认这两个接口，不知道 sherpa-onnx 的存在。
- 清单解析时，`backend` 不在注册表里、或者 `supports(family)` 为 false 的包，不显示（旧版 App 读到新清单也不会崩）。
- **第一版不做**：用户自己导入清单里没有的模型（自定义模型）。那需要用户自己担保文件可信，信任锚就不再只是 APK 里的哈希，要单独设计。接口不挡这条路：自定义包也是一个 `VoicePackEntry`，只是来源不同。

### 5.5 接入语音目录

- **新增协议**：`SttProtocol` 加 `OnDevice`（序列化名固定）。
- **服务与模型存进目录快照**：
  - 安装语音包时，往目录快照（`VoiceCatalogSnapshot`）里写入：
    - 固定服务 `VoiceService("on-device", …, VoiceAccount.None)`，第一次安装时写入。
    - 这个包对应的 `VoiceModel`：`stt = SttBinding(SttProtocol.OnDevice, endpoint = 包id)`，`origin = Builtin`。
  - 和其他模型一样存在快照里，所以 `requireModel`、`backupsFor`、`VoiceCatalogStore` 的整理逻辑（`fallbackUsable`、`pruneSelections`）都不用特殊处理。
  - 不能像 `android-system` 那样每次临时生成：那样每次整理都会把它从备援链里删掉。
- **文件不在时**（从备份恢复：快照被备份了，但 `voice-models/` 不备份；或者文件被删、损坏）：
  - 快照里的模型和选择**保留，不悄悄删掉**。
  - 用到时报「语音包未安装」：`VoiceModelError.IncompleteConfig`，带上能让界面区分出来的原因。
  - 界面在选择列表和语音包卡片上标「需要下载」，用户下载后不用重新选就能直接用。
- **用户在卡片上删除语音包时**：删文件，并删掉快照里对应的模型。
  - 选中的是它：回到「自动」，**不是**现有删除模型时的「关闭」（`VoiceCatalogStore.pruneSelections` 现在设成 `SttSelection.Off`）。
  - 理由：现有规则设成关闭，是为了不悄悄换成要花钱的服务商；而「自动」只会用手机自带识别或别的本机语音包，不花钱，也不出手机。
  - 所以这里要加一个特例，并加测试固定下来。
  - 备援链里有它的，照现有规则删掉。
- **适配器**：`SttAdapters` 加一个 `OnDeviceSttAdapter`。
  - `utterance()` 返回「WAV 进、文字出」的转写，这样它自动适用 `transcribeChain` 和 `VoiceFallbacks`。
  - `start()` 用 `ProviderSpeech`（`PcmRecorder` 录音，松手后转写），和现有整段识别一致。
  - 识别出空文本时，按现有的「没听清」处理。
- **备援链**：`OnDevice` 加进 `UTTERANCE_STT_PROTOCOLS`，这样它既能当链头，也能当备援。不经过密钥池，因为是 `VoiceAccount.None`。
- **失败分类**：本机的每种失败都会换下一个模型（`VoiceFailover.canTryNext` 为 true）。理由：本机模型当链头时，用户配了云端备援，就是希望本机不行时有人接手。

  | 本机失败 | 映射为 | 是否换下一个 |
  |---|---|---|
  | 语音包未安装或文件缺失 | `VoiceModelError.PackMissing`（类别 `INCOMPLETE_CONFIG`） | 是（现有规则：`INCOMPLETE_CONFIG` 为 true） |
  | 文件损坏、模型加载失败、内存不足 | `VoiceModelError.OnDeviceFailed`（类别 `SYSTEM_UNAVAILABLE`） | 是（现有规则为 true） |
  | 识别超时（§5.4） | 同上，**不是** `ModelError.Network` | 是 |
  | 识别出空文本 | 不算失败，照「没听清」处理 | 否 |

  它在链尾时，失败了就把这条错误提示给用户。不在链头时，`VoiceFailover.run` 会在尝试前跳过配置不全的那一位，这也是现有行为。
- **「自动」不使用备援链**：维持现状。`backupsFor` 只在选了具体模型时才返回备援链。备援链属于「选了具体模型」的用法，「自动」本来就会在手机自带识别和本机语音包之间自己挑。
- **通话不用本机语音包**（第一版）：
  - 和 `SYSTEM_STT_REF` 一样，在 `CascadeEngine.listenRef` 里把 `OnDevice` 模型排除掉。
  - 应用内通话和 AI 接打电话的听写模型选择列表里不显示语音包。
  - 理由：Whisper 每 5 秒语音要约 4 秒才出字，放到通话里延迟太高。只有离线语音包的用户，通话会得到 `StandardBlocker.NoListenModel`，这和现在只用手机自带识别的用户一样。以后实测速度够了再单独放开。
- **「自动」的顺序**：改 `SpeechResolver` 的 `SpeechChoice.Auto` 分支，以及 `VoiceInput` 开会话时 Auto 对应的模型。
  1. 手机自带识别能用 → 用它（不变，它能边说边出字）。
  2. 否则，有已安装、并且推荐给当前识别语言的语音包 → 用它。
  3. 否则，有任意已安装的语音包 → 用它（模型不支持这个语言时用自动检测）。
  4. 否则，维持现状（`Why.SystemNoResponse` 或 `Why.NoSystemNoModel`），但提示里加一条入口：「下载离线语音包」。

  要满足反目标：只有手机自带识别不能用时，才走离线模型。

### 5.6 界面

- **语音设置**（`voice/ui/`）新增「离线语音包」一节，每个包一张卡片。卡片显示：
  - 名称、支持的语言、大小、许可。
  - 状态：未下载 / 下载中（进度、取消）/ 已安装（版本、占用空间、删除）/ 损坏（重新下载）。
  - 「从文件导入」「复制下载地址」。
  - 推荐给当前界面语言的那个包排在最前，并标上「推荐」。
- **语音输入模型的选择列表里**，已安装的包和其他模型并列，标注「本机 · 离线」。
- **`Why.NoSystemNoModel` / `Why.SystemNoResponse` 的提示里**，加一个直达入口。
- **不在首次启动时推销下载。**
- **文案**：13 种语言都要翻译，并通过 `python3 tools/i18n/translations.py check`。
- **无障碍**：进度、状态都要能被读屏读出来。

### 5.7 存储位置与清理

- 安装目录：`filesDir/voice-models/<包id>/<版本>/`，staging 目录是 `filesDir/voice-models/.staging/`。
- `BackupEngine.excludedDirs` 加上 `voice-models`，备份说明里注明「语音包体积大，不备份，恢复后重新下载」。
- App 启动时清理残留的 staging 目录和非当前版本的目录。
- 清单里某个包的版本升了，已安装的旧版本照常能用；卡片上显示「有新版本」，由用户决定要不要更新。不自动下载。

## 6. 测试

- **单元测试（JVM）**
  - 清单解析，以及清单自身的一致性：
    - 每个包都带许可文件，哈希是 64 位十六进制。
    - 所有包的 `recommendedFor` 合起来覆盖 `AppLanguage` 的全部 13 种界面语言代码（`zh-Hans`、`id` 等）。
  - 识别语言标签（`zh-CN`、`id-ID` 等）到模型语言的映射。
  - 本机识别的超时，以及失败分类（§5.5 表）。
  - 从备份恢复后，引用的语音包不在：选择保留，显示「需要下载」。
  - 删除选中的语音包后回到「自动」；通话排除语音包。
  - 下载器：来源依次切换；校验不通过算这个来源失败；staging 和改名的原子性；取消；空间不足。
  - 导入：缺文件、多文件、哈希不对、带或不带子目录。
  - 地址校验只放行清单地址和「镜像前缀 + 清单地址」。
  - 目录接入：卸载后清理选择和备援链；`eligible` 包含 `OnDevice`；Auto 四步顺序。
  - 备份排除。
- **仪器测试（真机）**
  - 语音包卡片的状态切换和读屏文案。
  - 用测试夹具的小模型或真模型跑一次「WAV 进、文字出」。
  - 备援链里链头云端模型故意失败、离线模型接手。
- **发布脚本**：`tools/voice-models` 的单测，覆盖哈希核对和文件名拼接。

## 7. 预算与验收

- **APK 体积**：arm64 包增加 ≤ 20 MB（静态链接 onnxruntime 的 arm64 部分），实现时实测并写进 PR。通用包只带 arm64 的 `.so` 时，增量相同。
- **识别速度**（中端 arm64 机，大约骁龙 7 系或同级）：5 秒语音在 SenseVoice 上 ≤ 1.5 秒出字，在 Whisper base 上 ≤ 4 秒（第一次加载模型的时间另算）。达不到就在 PR 里写明实测数据，再决定换模型还是接受。
- **内存**：加载后常驻增加 ≤ 400 MB（SenseVoice int8）。低内存设备可能被系统回收，由加载失败的提示兜底。
- **准确率**：每种界面语言录 10 句日常指令（例如「打开设置」「给妈妈发消息说晚点到」），推荐的包大体能识别对。不达标的语言改推别的包，或者标「效果有限」。

## 8. 实现前要先做的核实

1. Dolphin 对 vi、in、ar、fa、tr、ru 的实际效果，以及 Whisper base 对 es、pt 的效果。按结果修正 §5.1 的推荐表。
2. 拿到三个包在 HuggingFace 上的确切仓库和固定提交，以及确切的文件列表和大小。
3. 确认 sherpa-onnx v2.0.3 静态链接版 AAR 里 arm64 部分的实际体积，以及它的 Kotlin API 是否就是 `OfflineRecognizer` 加 `OfflineModelConfig`。
4. 确认 SenseVoice 对繁体中文的输出形式（简体还是繁体）。如果需要转换，第一版先不转，在 PR 里说明。

### 8.1 核实结果（2026-11-26）

**怎么测的**
- 没有真人录音。在 Mac 上用 sherpa-onnx 2.0.3 的 Python 包跑，4 线程。
- 音频用 macOS `say` 合成，转成 16 kHz 单声道 WAV。每种语言一个系统声音。
- 每种语言 10 句日常指令，意思相同（打开设置、给妈妈发消息说晚点到、打开蓝牙……）。
- 中日韩按字算错误率（CER），其他语言按词算（WER）。比较前去掉标点、统一大小写；中文两边都先转成简体。
- 「接近对」指一句话的错误不超过 20%。10 句里至少 7 句接近对，算达标。
- **合成语音比真人说话干净，实际效果会更差。** 手机上的速度和内存不在这一步测，放到实现时的真机测试。

**1. 各语言效果**（每格：错误率 / 接近对的句数；粗体是推荐的包）

| 界面语言 | SenseVoice | Dolphin | Whisper base | 结论 |
|---|---|---|---|---|
| zh-Hans | **0.00 / 10** | 0.00 / 10 | 0.06 / 8 | 达标 |
| zh-Hant | **0.00 / 10** | 0.00 / 10 | 0.04 / 8 | 达标，但输出是简体（见 4） |
| en | **0.02 / 10** | 不支持 | 0.00 / 10 | 达标 |
| ja | **0.03 / 10** | 0.10 / 9 | 0.17 / 7 | 达标 |
| ko | **0.04 / 9** | 0.18 / 6 | 0.11 / 7 | 达标 |
| id | 不支持 | 0.26 / 5 | **0.16 / 7** | 达标 |
| ru | 不支持 | 0.33 / 5 | **0.21 / 6** | 效果有限 |
| es | 不支持 | 不支持 | **0.33 / 5** | 效果有限 |
| pt | 不支持 | 不支持 | **0.33 / 4** | 效果有限 |
| tr | 不支持 | 不支持 | **0.32 / 4** | 效果有限 |
| vi | 不支持 | **0.21 / 5** | 0.34 / 4 | 效果有限 |
| ar | 不支持 | 0.73 / 1 | **0.61 / 0** | 效果有限，基本认不对 |
| fa | — | — | — | 未实测：macOS 没有波斯语声音，三个模型仓库也没有波斯语样例音频 |

- fa 推荐 Dolphin：Dolphin 官方语言表里有 fa，面向中东语言训练过。没实测，标「效果有限」。
- Dolphin 实际不支持 en、es、pt、tr。识别这几种语言时输出是乱的，不能当它们的兜底。
- Whisper base 的错误多是听错近音词，不是截断。
- 另测了 Whisper small（int8，375 MB，作为可选包 `whisper-small-int8` 放进第一版）：es 0.10 / 8、pt 0.06 / 9、ru 0.09 / 9、vi 0.11 / 7，都达标；tr 0.29 / 5、ar 0.55 / 0 仍不达标。它在 Mac 上比 base 慢约 2.7 倍，手机上 5 秒语音估计要 10 秒左右。它先不推荐给任何语言；es、pt、ru、vi 要不要改推它，由实现时的真机测速定。
- 实现时的真机实测（arm64 旗舰级处理器，4 线程）：5 秒语音 Whisper small 用 2.0–2.9 秒（es 2.7、pt 2.0、ru 2.9、vi 2.7）；同一批句子 es 0.04 / 9、pt 0.09 / 8、ru 0.09 / 9、vi 0.09 / 8，明显好于 Whisper base（es 0.33、pt 0.26、ru 0.21）和 Dolphin（vi 0.21）。但识别时进程内存多约 0.85 GB（预算 400 MB；Whisper base 约 0.42 GB，也略超），中端机上的速度没有测过。**决定**：默认推荐不变，es、pt、ru 仍推 Whisper base，vi 仍推 Dolphin；Whisper small 留作可选包，不推荐给任何语言。以后要不要改推它由维护者决定，可以考虑按内存大小做能力检测（内存够的手机才推荐）。

**2. 仓库和文件**（全部是 sherpa-onnx 作者 csukuangfj 在 HuggingFace 上的导出，固定到下面的提交）

| 包 id | 仓库 | 提交 | 文件（字节） |
|---|---|---|---|
| `sensevoice-small-int8` | `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` | `2365baeacb507f821a0c8120fcee3d484dba7a07` | `model.int8.onnx` 239,233,841；`tokens.txt` 315,894 |
| `dolphin-base-ctc-int8` | `csukuangfj/sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02` | `1f3a53d0ecf658f8b0974e2cfde368eee40732fa` | `model.int8.onnx` 103,729,802；`tokens.txt` 504,662 |
| `whisper-base-int8` | `csukuangfj/sherpa-onnx-whisper-base` | `bb53ee204431c90d314c1cc08d28d23e5b7927cc` | `base-encoder.int8.onnx` 29,120,534；`base-decoder.int8.onnx` 130,672,026；`base-tokens.txt` 816,730 |
| `whisper-small-int8`（可选，更准，但更慢、占内存更多，不推荐给任何语言） | `csukuangfj/sherpa-onnx-whisper-small` | `8f3c18b358db4d1f2fc1eae49d75cd20989e4309` | `small-encoder.int8.onnx` 112,442,483；`small-decoder.int8.onnx` 262,226,114；`small-tokens.txt` 816,730 |

- 下载后逐个核对过：大模型文件和 HuggingFace 给的 LFS SHA-256 一致，小文件和 git blob 哈希一致。每个文件的 SHA-256 由发布脚本写进清单。
- SenseVoice 还有一个 `…-int8-2025-09-09` 仓库。它是 ASLP-lab 用粤语数据微调过的版本（WSYue-ASR），不是官方通用版，不用。
- **许可文件不在 HuggingFace 仓库里**，要从模型的原始仓库取，固定到提交：
  - SenseVoice：`modelscope/FunASR` 的 `MODEL_LICENSE`，提交 `58830eca4012644aac0c3218c3ccc7d98f003fda`。HuggingFace 仓库里的 `LICENSE` 只有一行链接，不能当许可文件。
  - Dolphin：`DataoceanAI/Dolphin` 的 `LICENSE`（Apache-2.0），提交 `bb70b9c0cab1235b8458141819c0ba92773d3b41`。
  - Whisper：`openai/whisper` 的 `LICENSE`（MIT），两个 Whisper 包共用，提交 `6e3be77e1a105e59086e3e21ff5f609fd6fa89a5`。
  - 所以许可文件在 HuggingFace 上没有备用来源。发布脚本要按文件单独记来源地址，不能都拼在 `upstream` 后面。

**3. sherpa-onnx AAR**
- v2.0.3 是目前最新的正式版（2026-11-22）。静态链接版的文件名是 `sherpa-onnx-static-link-onnxruntime-2.0.3.aar`，39,999,115 字节，SHA-256 `7744a3a8f08f017d59c2d8ed8d9b700345561f5abe9f4e06dc6eaef3a0b977b4`（和 GitHub 给的一致）。
- arm64-v8a 只有一个 `libsherpa-onnx-jni.so`，24,914,464 字节，已去符号。压缩后约 9.3 MB。Mika 用 `useLegacyPackaging = true`，`.so` 在 APK 里是压缩的，所以 arm64 包大约增加 9–10 MB，在 §7 的 20 MB 以内。
- AAR 里还带了 armeabi-v7a、x86、x86_64 的库（压缩后共约 30 MB），要在打包时排除，只留 arm64-v8a。
- Kotlin API 和设计一致，包名 `com.k2fsa.sherpa.onnx`：
  - `OfflineRecognizer(assetManager = null, config)`，用文件路径加载。配置不对时构造会抛异常。
  - `createStream()`、`decode(stream)`、`getResult(stream).text`、`release()`。
  - `OfflineStream.acceptWaveform(samples: FloatArray, sampleRate)`：要的是 -1 到 1 的浮点数，不是 `kotlin.ShortArray`，要自己转换。
  - `OfflineModelConfig` 里有 `senseVoice`、`whisper`、`dolphin`，以及共用的 `tokens`、`numThreads`。
  - `senseVoice.language`：空字符串是自动，也可以是 zh、en、ja、ko、yue。`whisper.language`：空字符串是自动。`dolphin` 没有语言参数，总是自动。
- **Whisper 要设一个流选项**：v2.0.3 默认每秒语音最多出 6 个词元，短指令会被截断。每个流在 `decode` 前调用 `setOption("max_tokens_per_second", "25")`。上面的表就是这样测的。
- Whisper 的语言代码有两个和 ISO 不同：希伯来语是 `iw`，爪哇语是 `jw`。

**4. 繁体中文**：SenseVoice 和 Dolphin 输出都是简体，10 句全是。按原计划第一版不转换，在 PR 里说明。Whisper base 指定 zh 时输出简繁混杂。

**速度参考**（Mac，只作参考）：每秒语音的处理时间，SenseVoice 约 0.015 秒，Dolphin 约 0.008 秒，Whisper base 约 0.08 秒，Whisper small 约 0.23 秒。

## 9. 审阅后定下的事（设计者的判断，维护者可推翻）

审阅者从实现者角度通读后提了 6 个问题，都由设计者按现有代码的惯例定了下来，写在上面对应的小节：

1. 语音包模型存进目录快照，不临时生成；文件不在时保留选择、标「需要下载」（§5.5）。
2. 删除选中的语音包后回到「自动」，不跟现有删除模型规则设成「关闭」（§5.5）。
3. 第一版通话不用语音包（§5.5）。
4. 本机识别有自己的超时（音频时长 × 3，最少 30 秒，不含加载），超时不记成网络错误（§5.4）。
5. 下载不绑定 Wi-Fi，按流量计费的网络先确认，SOCKS 代理照常能用（§5.2）。
6. 「自动」不用备援链（§5.5）。

另外按代码修正了三处：
- 语言代码改用 `zh-Hans`、`id`。
- 本机失败的分类改成写明映射，不再说「照现有规则」。
- 下载器要改造，不只是抽出来。

## 10. 留给实现者决定

- 通用下载器怎么从 `about/` 抽出来，以及放在哪个包（app 层，比如 `net/` 或 `download/`）。
- AAR 是放进仓库还是从源码构建。
- Release 附件的命名规则、断点续传、余量空间、闲置释放的时间、识别线程数。
- 导入时的具体交互：选单个 zip，还是选多个文件。
- 语音包卡片的具体布局，沿用现有 `ListRow(…)` 等组件。

## 11. 文档

- 实现时更新 `docs/dev/architecture.md` §16「语音输入」：加入离线来源、语音包清单、下载和导入、Auto 顺序。
- 在 `licenses/THIRD_PARTY_NOTICES.md` 加上 sherpa-onnx 和三个模型。
- 在用户文档里写一段「离线语音包」：在哪里下载、怎么导入、占多少空间。
