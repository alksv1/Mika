# app/libs

不在 Maven 上、随仓库固定版本发布的库。换版本时同时更新这里的校验值和 `licenses/THIRD_PARTY_NOTICES.md`。

| 文件 | 来源 | SHA-256 | 大小 |
|---|---|---|---|
| `sherpa-onnx-2.0.3-static.aar` | https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-static-link-onnxruntime-2.0.3.aar(原文件名 `sherpa-onnx-static-link-onnxruntime-2.0.3.aar`,未修改) | `7744a3a8f08f017d59c2d8ed8d9b700345561f5abe9f4e06dc6eaef3a0b977b4` | 39,999,115 B |

sherpa-onnx AAR 里带四种架构的原生库,`app/build.gradle.kts` 的 `packaging.jniLibs.excludes` 只留 `arm64-v8a`。

核对:`shasum -a 256 app/libs/sherpa-onnx-2.0.3-static.aar`
