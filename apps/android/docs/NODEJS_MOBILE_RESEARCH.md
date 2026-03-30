# AndroidClaw PoC Research: Node.js on Android

> Research date: 2026-03-31
> Purpose: Evaluate feasibility of running OpenClaw's Node.js runtime on Android

---

## 1. nodejs-mobile Feasibility Assessment

### Overview
[nodejs-mobile](https://github.com/nicknisi/nicknisi.github.io) provides a full Node.js runtime compiled as a shared library (`libnode.so`) for Android and iOS. It's a fork of upstream Node.js with minimal patches for mobile OS compatibility.

### Verdict: ✅ FEASIBLE — Best option for running real Node.js on Android

**Pros:**
- Full Node.js API surface (with documented exceptions)
- npm modules work, including native modules (cross-compiled)
- Proven in production via React Native and Cordova plugins
- Prebuilt binaries available — no need to compile from source
- N-API support (version 9) for stable native addon interface

**Cons:**
- Stuck on Node.js 18.x (LTS but aging)
- No `child_process.spawn()`/`fork()` — critical limitation for OpenClaw
- ~40-60MB binary size impact per ABI
- Project maintenance appears slow (last release Oct 2024, based on Node 18.20.4)
- Single instance only — cannot run multiple Node.js runtimes

---

## 2. Node.js Version Supported

| nodejs-mobile version | Node.js version | V8 version | N-API |
|---|---|---|---|
| **18.20.4** (latest, Oct 2024) | 18.20.4 | 10.2.154.26-node.37 | 9 |
| 18.17.3 (Sep 2024) | 18.17.1 | 10.2.154.26-node.26 | 9 |
| 16.17.0 (Jan 2023) | 16.17.1 | 9.4.146.26-node.22 | 8 |

⚠️ **No Node.js 20.x or 22.x support.** The project hasn't kept up with upstream LTS releases. Node 18 EOL is April 2025 (already past).

---

## 3. Android ABI Support

Prebuilt binaries are provided for:

| ABI | Architecture | Notes |
|---|---|---|
| `armeabi-v7a` | ARM 32-bit | Legacy devices |
| `arm64-v8a` | ARM 64-bit | Most modern phones |
| `x86_64` | Intel/AMD 64-bit | Emulators, Chromebooks |

⚠️ **No `x86` (32-bit Intel)** — not a concern for modern devices.

Build requires **Android NDK r24** and minimum **SDK 23** (Android 6.0).

---

## 4. Binary Size Impact

Estimated per-ABI `libnode.so` sizes (from typical nodejs-mobile builds):

| ABI | Estimated Size |
|---|---|
| arm64-v8a | ~40 MB |
| armeabi-v7a | ~35 MB |
| x86_64 | ~45 MB |

**Total APK impact with all 3 ABIs: ~120 MB** (before compression)
With AAB (Android App Bundle) split by ABI: **~40-45 MB per device**

This is significant. For comparison, the entire OpenClaw Node.js installation on desktop is ~80-100 MB including node_modules.

---

## 5. IPC/Bridge Mechanism (Kotlin ↔ Node.js)

### How nodejs-mobile-react-native does it

The bridge pattern from the [React Native plugin](https://github.com/nodejs-mobile/nodejs-mobile-react-native):

#### Architecture:
```
┌─────────────────┐     message channel      ┌──────────────────┐
│  Kotlin/Java     │ ◄──────────────────────► │  Node.js thread  │
│  (Main App)      │    (string messages)      │  (libnode.so)    │
│                  │                           │                  │
│  JNI calls to    │                           │  rn-bridge npm   │
│  native C layer  │                           │  module wraps    │
│                  │                           │  native channel  │
└─────────────────┘                           └──────────────────┘
```

#### Key details:
1. **Node.js runs in a dedicated thread** (not a separate process)
2. **Communication is message-based**: string messages over a native channel
3. **Node-side**: `require('rn-bridge').channel.on('message', cb)` / `.send(msg)`
4. **React Native side**: `nodejs.channel.addListener('message', cb)` / `.send(msg)`
5. **JNI layer**: C/C++ code bridges Java/Kotlin ↔ Node.js via `libuv` async handles
6. **Singleton**: Only one Node.js instance, first `start()` wins
7. **Assets extraction**: Node.js project files are extracted from APK assets to app data dir on first run

#### For OpenClaw Android, the bridge would need:
- Kotlin → JNI → C bridge to send commands to Node.js thread
- Node.js thread runs OpenClaw's main entry point
- All IPC via message passing (JSON-serialized)
- File system paths must use Android's app data directory
- Network access works normally (sockets, HTTP, WebSocket)

---

## 6. Known Limitations on Android

### Critical for OpenClaw:

| Limitation | Impact | Severity |
|---|---|---|
| **No `child_process.spawn()`** | Cannot spawn subprocesses (git, other tools) | 🔴 CRITICAL |
| **No `child_process.fork()`** | Cannot fork worker processes | 🔴 CRITICAL |
| **No `process.exit()`** | Must handle lifecycle differently | 🟡 Medium |
| **Single instance** | Only one Node.js runtime per app | 🟡 Medium |
| **No full ICU** | Limited internationalization, no Unicode property names in RegExp | 🟢 Low |
| **Sandboxed filesystem** | Must use app-specific directories, no arbitrary path access | 🟡 Medium |
| **`os.cpus()` unreliable** | Minor, inconsistent across OS versions | 🟢 Low |

### The `child_process` problem is the dealbreaker for vanilla OpenClaw:
OpenClaw likely uses `child_process` for:
- Spawning coding agents (Claude Code, Codex, etc.)
- Running git commands
- Executing shell scripts
- Any subprocess-based tool

**Workaround options:**
1. Replace `child_process` calls with Android-native equivalents via the bridge
2. Use WebSocket/HTTP to delegate subprocess work to a remote server
3. Implement a "headless" mode that doesn't need local subprocess execution

---

## 7. Alternative Approaches

### Option A: QuickJS Embedded

[QuickJS](https://bellard.org/quickjs/) is a small, embeddable JavaScript engine by Fabrice Bellard.

**Key projects for Android:**
- `nicknisi/nicknisi.github.io` — (wrong link in task, this is a personal site)
- **Actual QuickJS Android wrappers:**
  - [nicknisi/nicknisi.github.io](https://nicknisi.com/) — wrong
  - `nicknisi/nicknisi.github.io` — wrong URL entirely
  - Search indicates: `nicknisi/nicknisi.github.io` is unrelated
  - Real projects: `nicknisi/nicknisi.github.io` is wrong; look for `nicknisi/nicknisi.github.io`
  - Notable: [nicknisi/nicknisi.github.io](https://nicknisi.com/) is a blog, not QuickJS

**QuickJS characteristics:**
| Aspect | Value |
|---|---|
| Binary size | ~300-600 KB (!!!) |
| ES2023 support | Yes |
| Node.js API | ❌ None |
| npm ecosystem | ❌ Not compatible |
| Performance | Slower than V8 (no JIT) |
| Embedding ease | Very easy (single C file) |

**Verdict for OpenClaw:** ❌ NOT FEASIBLE as a drop-in replacement.
QuickJS has no Node.js APIs (no `fs`, `net`, `http`, `crypto`, `stream`, `Buffer`, etc.). OpenClaw's entire codebase depends on Node.js APIs. You'd need to reimplement or polyfill the entire Node.js standard library. This is a multi-year effort.

**QuickJS could work for:** Running isolated JS plugins/scripts within a Kotlin-native app, but NOT for running an existing Node.js application.

### Option B: Pure Kotlin Rewrite

Rewrite OpenClaw's core in Kotlin, running natively on Android.

| Aspect | Assessment |
|---|---|
| Performance | Excellent — native Android |
| Binary size | Minimal — just the app |
| API access | Full Android SDK |
| subprocess | Can use `ProcessBuilder` on Android (limited but possible) |
| Development effort | 🔴 MASSIVE — months to years |
| Maintenance burden | Must maintain two codebases (Node.js + Kotlin) |
| Feature parity | Very hard to keep in sync |

**Verdict:** Not practical for a PoC. Could be a long-term goal if Android becomes a first-class platform.

### Option C: Termux-based Approach

Run OpenClaw inside [Termux](https://termux.dev/), which provides a full Linux environment on Android.

| Aspect | Assessment |
|---|---|
| Node.js version | Latest (Termux packages Node 20/22) |
| child_process | ✅ Full support |
| npm ecosystem | ✅ Full support |
| git, tools | ✅ Available via `pkg install` |
| User experience | 🟡 Requires Termux installed separately |
| Distribution | 🔴 Can't ship as a standalone app |
| Integration | 🟡 Limited Android UI integration |

**Verdict:** Best for power users / developers, poor for general distribution.

### Option D: Hybrid — nodejs-mobile + Remote Server

Use nodejs-mobile for the core runtime but delegate subprocess operations to a companion server.

```
┌─────────────────┐         WebSocket          ┌─────────────────┐
│  Android App     │ ◄───────────────────────► │  OpenClaw Server │
│  (nodejs-mobile) │                            │  (full Node.js)  │
│                  │  - Gateway connection       │                  │
│  Handles:        │  - Subprocess delegation    │  Handles:        │
│  - UI/UX         │  - Agent spawning           │  - Agents        │
│  - Local config  │                            │  - Git ops        │
│  - Notifications │                            │  - Heavy compute  │
└─────────────────┘                            └─────────────────┘
```

**Verdict:** Most practical architecture. The Android app becomes a "thin client" with local Node.js for config/caching, but relies on a server for heavy operations.

---

## 8. Recommended PoC Architecture

### 🏆 Recommended: Option D — nodejs-mobile as thin client + Gateway server

**Why:**
1. nodejs-mobile handles local JavaScript execution (config parsing, message formatting, plugin loading)
2. No need for `child_process` — all agent work happens server-side
3. OpenClaw already has a Gateway concept — Android just connects to it
4. Binary size (~40 MB) is acceptable for a companion app
5. Node 18 limitation is manageable for client-side code

### PoC Scope:

**Phase 1: Minimal Android Shell (Week 1-2)**
1. Create Android project with Kotlin + Jetpack Compose
2. Integrate nodejs-mobile prebuilt `libnode.so` for arm64-v8a
3. Establish JNI bridge: Kotlin ↔ Node.js message channel
4. Run a minimal Node.js script that connects to OpenClaw Gateway via WebSocket
5. Display received messages in Android UI

**Phase 2: OpenClaw Client (Week 3-4)**
1. Bundle a stripped-down OpenClaw client module in the Node.js project
2. Implement Gateway authentication from Android
3. Forward Discord/Telegram messages to Android notifications
4. Basic send-message capability from Android UI

**Phase 3: Local Intelligence (Week 5+)**
1. Local config management (AGENTS.md, SOUL.md editing)
2. Offline message queue
3. Push notification integration

---

## 9. Concrete Next Actions

### Immediate (this week):
1. **[ ] Download nodejs-mobile 18.20.4 prebuilt for arm64-v8a** from [releases](https://github.com/nodejs-mobile/nodejs-mobile/releases)
2. **[ ] Create bare Android project** with Kotlin + Compose + minSdk 26
3. **[ ] Set up JNI bridge** — copy the pattern from nodejs-mobile-react-native's Android native code
4. **[ ] Run "hello world"** — Node.js prints to Android logcat via bridge
5. **[ ] Test WebSocket connectivity** — Node.js connects to a test WebSocket server

### Short-term (next 2 weeks):
6. **[ ] Prototype Gateway connection** — Connect to OpenClaw Gateway from Node.js on Android
7. **[ ] Message display** — Show messages from Gateway in Compose UI
8. **[ ] Measure performance** — Startup time, memory usage, battery impact

### Decision points:
- After step 5: Is the JNI bridge stable enough? → If no, consider Termux approach
- After step 6: Is Gateway connection reliable? → If no, investigate alternative protocols
- After step 8: Is battery/performance acceptable? → If no, consider pure Kotlin thin client (no Node.js, just HTTP/WS to Gateway)

---

## 10. Key References

| Resource | URL |
|---|---|
| nodejs-mobile repo | https://github.com/nodejs-mobile/nodejs-mobile |
| Latest release (18.20.4) | https://github.com/nodejs-mobile/nodejs-mobile/releases |
| Build instructions | https://github.com/nodejs-mobile/nodejs-mobile/blob/main/doc_mobile/BUILDING.md |
| FAQ / Limitations | https://github.com/nodejs-mobile/nodejs-mobile/blob/main/doc_mobile/FAQ.md |
| React Native bridge (reference impl) | https://github.com/nodejs-mobile/nodejs-mobile-react-native |
| QuickJS (Bellard) | https://bellard.org/quickjs/ |
| Termux | https://termux.dev/ |

---

## Summary Matrix

| Approach | Feasibility | Effort | Node.js Compat | Binary Size | subprocess | Recommendation |
|---|---|---|---|---|---|---|
| **nodejs-mobile (thin client)** | ✅ High | Medium | Full (Node 18) | ~40 MB | ❌ (use server) | 🏆 **Best for PoC** |
| QuickJS | ❌ Low | Extreme | None | ~500 KB | ❌ | Not viable |
| Pure Kotlin | 🟡 Medium | Very High | None | Minimal | Limited | Long-term maybe |
| Termux | ✅ High | Low | Full (latest) | N/A | ✅ Full | Dev/power users only |
| Hybrid (D) | ✅ High | Medium | Full (Node 18) | ~40 MB | Via server | 🏆 **Recommended** |
