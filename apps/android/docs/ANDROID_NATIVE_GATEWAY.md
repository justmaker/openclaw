# AndroidClaw — Android-Native Gateway 可行性分析

> **Status:** Research / PoC  
> **Author:** Rex + AI  
> **Date:** 2026-03-31  
> **Branch:** `feature/android-native-gateway`

## 目標

讓 Android 手機**獨立運行** OpenClaw Gateway，不需要外部桌面/伺服器。使用者在手機上安裝 app 即可擁有完整的 AI agent。

## 現狀

目前 OpenClaw Android app 是 **companion node（遙控器）**，必須連接到桌面端的 Gateway：

```
Gateway（Linux/macOS/WSL）← WebSocket → Android app（UI 前端）
```

目標架構：

```
Android app = Gateway + Agent + UI（全部在手機上）
```

## 核心挑戰

| 元件 | 桌面版 Gateway | Android 需要的方案 |
|------|---------------|-------------------|
| **Runtime** | Node.js | Embedded Node.js (via libnode) 或改寫成 Kotlin-native |
| **Shell exec** | 直接 `child_process` | Termux 環境 / 受限 `Runtime.exec()` / 無 root 基本沒用 |
| **WebSocket server** | `ws` on Node | Ktor / OkHttp WebSocket server（Kotlin native 可做） |
| **LLM API** | HTTP calls to providers | ✅ 沒問題，手機也能打 API |
| **檔案系統** | 完整存取 | App sandbox 內可用，外部需權限 |
| **MCP servers** | stdio/SSE spawning | 最大障礙，需要 Node/Python runtime |
| **背景執行** | 永遠在線 | Foreground service，但 Android 會殺 |
| **Plugin system** | Node.js ESM | 要嘛 embed Node，要嘛全部重寫 |

## 方向評估

### 方向 A：Embedded Node.js（較快，相容性高）

- 用 [nodejs-mobile](https://github.com/nicknisi/nodejs-mobile) 或類似方案把 Node.js embed 到 Android app
- Gateway 的 Node.js code 幾乎原封不動跑
- **優點：** 相容性高，既有的 plugin/MCP 生態可直接沿用
- **缺點：** APK 體積大（+40MB），效能一般，記憶體佔用高

### 方向 B：Kotlin-native 重寫（乾淨，但工程量巨大）

- 用 Kotlin 重寫 Gateway core（session、agent loop、tool dispatch）
- Ktor 做 HTTP/WebSocket server
- **優點：** 效能好，原生整合，APK 小
- **缺點：** OpenClaw codebase 超過 10 萬行，完全重寫不現實

### 方向 C（推薦）：混合架構 — Embedded Node.js + Kotlin UI

結合 A 和 B 的優點：

1. **Kotlin native** 負責 UI、系統整合（通知、foreground service、權限）
2. **Embedded Node.js** 跑精簡版 Gateway core
3. 透過 IPC（localhost HTTP 或 message channel）溝通

## MVP 路線圖

### Phase 1: PoC — Node.js on Android ✏️

- [ ] 驗證 nodejs-mobile 或替代方案在 Android 上的可行性
- [ ] 在 Android app 內成功執行一段 Node.js code
- [ ] 測量 APK 大小增量、啟動時間、記憶體使用

### Phase 2: 精簡版 Gateway

- [ ] 從 OpenClaw Gateway 抽出最小核心（agent loop + LLM API call）
- [ ] 在 Android 內嵌 Node.js 上跑起來
- [ ] 實現基本 tool：web_search、read/write 本地檔案

### Phase 3: UI 整合

- [ ] Chat tab 直接對接本地 Gateway（不走 WebSocket，改 in-process IPC）
- [ ] 設定頁面：API key 管理、model 選擇
- [ ] Foreground service 保持 Gateway 存活

### Phase 4: 進階功能

- [ ] Canvas / Screen tab 支援
- [ ] MCP server support（受限）
- [ ] 通知整合（agent 可以推送通知）
- [ ] 與外部 Gateway 的混合模式（本地跑不動的任務 offload）

## 技術調研待辦

- [ ] nodejs-mobile Android 支援狀態（最新版本、ABI 支援）
- [ ] 替代方案：aspect-build/aspect-mobile、aspect Aspect 的 V8 binding、QuickJS embedded
- [ ] Android foreground service + 電池最佳化的最佳實踐
- [ ] OpenClaw Gateway 的最小依賴分析（哪些 npm packages 必要）

## 參考資料

- [nodejs-mobile](https://github.com/nicknisi/nodejs-mobile) — Node.js for Mobile Apps
- [aspect-build aspect-mobile](https://aspect.build/) — mobile build tooling
- [OpenClaw Gateway source](https://github.com/openclaw/openclaw/tree/main/src)
- [OpenClaw Android app source](https://github.com/openclaw/openclaw/tree/main/apps/android)
- [Termux](https://termux.dev/) — Linux 環境 on Android（參考用）
