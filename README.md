# Tapedeck 📼

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![CI](https://github.com/jrocharodrigues/tapedeck/actions/workflows/ci.yml/badge.svg)](https://github.com/jrocharodrigues/tapedeck/actions)
[![Maven Central](https://img.shields.io/maven-central/v/com.impecabel.tapedeck/tapedeck-core.svg)](https://search.maven.org/)

**Deterministic HTTP record & replay for Android Macrobenchmark.**

Startup benchmarks (Time To Initial Display / Time To Full Display) of apps that load their first screen from a backend measure backend variance as much as the app itself:
* Backend latency drifts throughout the day by hundreds of milliseconds.
* Occasional backend errors (502/504) fail whole benchmark runs.
* Comparing two builds run an hour apart compares two different backends.

**Tapedeck** eliminates backend noise by recording backend responses **once** during warm-up and replaying them deterministically with fixed (or zero) simulated latency across all measured iterations.

---

## Why Existing Tools Don't Fit Macrobenchmark

| Tool | Why it doesn't fit |
|---|---|
| **OkReplay (Airbnb)** | Built for single-process Espresso tests. In Macrobenchmark, the app runs in a **separate process**, repeatedly killed (`am force-stop`) and cleared (`pm clear`). |
| **mitmproxy** | Requires proxying on the host machine and custom CA certificates, which release/benchmark builds reject. Adds latency hops and intercepts video streams. |
| **Manual MockWebServer assets** | JSON response fixtures drift over time and require manual maintenance. |

---

## How Tapedeck Works

Tapedeck runs OkHttp's **MockWebServer** directly inside the **Macrobenchmark test process** on `127.0.0.1:28765`.

```
 Macrobenchmark Test Process                         App Process (Benchmark Build)
 ┌──────────────────────────────────────┐            ┌──────────────────────────────┐
 │ MockWebServer @ 127.0.0.1:28765      │◄── http ───│ ReplayInterceptor    │
 │  Dispatcher:                         │            │  rewrites https://api.x/...  │
 │   RECORD → forwards to real backend  │            │  → http://127.0.0.1:28765/   │
 │            & saves to HAR cassette   │            │    + X-Tapedeck-Original-Url │
 │   REPLAY → serves stored response    │            │  (allow-listed hosts only)   │
 │            with simulated latency    │            └──────────────────────────────┘
 └──────────────────────────────────────┘
```

1. **Warm-up Launch (`RECORD`)**: In iteration 0's setup block, `replay.recordOnce()` boots the app, intercepts network calls to allowed hosts, forwards them to the real backend, and records the responses into an in-memory HAR 1.2 cassette.
2. **Measured Iterations (`REPLAY`)**: Subsequent cold starts hit the test process's local server, which immediately serves the recorded responses with deterministic latency (e.g., `50ms` or `0ms`).
3. **`pm clear` Resilience**: Because the server and cassette reside in the test process (`com.example.app.benchmark`), **`pm clear` on the app never touches the test recording**.
4. **Offline / Fallback Safety**: If the app is opened manually or running in a benchmark without Tapedeck active, `ReplayInterceptor` automatically falls back to the real backend in `< 1ms`.

---

## Modules

| Artifact | Purpose |
|---|---|
| `tapedeck-okhttp` | App side: `ReplayInterceptor` for benchmark builds (URL rewriting & localhost routing). |
| `tapedeck-okhttp-noop` | App side: Drop-in no-op `ReplayInterceptor` for release builds (0 overhead). |
| `tapedeck-macrobenchmark` | Test side: `ReplayRule` (MockWebServer, forwarding dispatcher, record/replay, reports). |
| `tapedeck-core` | Shared: HAR 1.2 parser/serializer, matchers, recorded models. |

---

## Quickstart

### 1. Add Dependencies

In your app's `build.gradle.kts`:
```kotlin
dependencies {
    // Benchmark builds use the active interceptor
    benchmarkImplementation("com.impecabel.tapedeck:tapedeck-okhttp:0.1.0")

    // Release builds use the no-op implementation
    releaseImplementation("com.impecabel.tapedeck:tapedeck-okhttp-noop:0.1.0")
}
```

In your macrobenchmark module's `build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.impecabel.tapedeck:tapedeck-macrobenchmark:0.1.0")
}
```

### 2. Configure Cleartext Traffic for Localhost

In modern Android (API 28+), cleartext HTTP to localhost must be permitted for benchmark builds.

Create `app/src/benchmark/res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

And reference it in `app/src/benchmark/AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        android:networkSecurityConfig="@xml/network_security_config"
        tools:targetApi="24" />
</manifest>
```

### 3. Add `ReplayInterceptor` to OkHttpClient

```kotlin
val okHttpClient = OkHttpClient.Builder()
    .addInterceptor(
        ReplayInterceptor(
            hosts = setOf("api.example.com", "auth.example.com")
        )
    )
    .build()
```

*In release builds, `ReplayInterceptor` is compiled from `tapedeck-okhttp-noop` and is a pure no-op.*

### 4. Use `ReplayRule` in Macrobenchmark

```kotlin
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @get:Rule
    val replayRule = ReplayRule(
        latencyMs = 50L, // Fixed simulated response latency
        replayPost = listOf("/auth/token") // POST paths safe to replay
    )

    @Test
    fun startup() = benchmarkRule.measureRepeated(
        packageName = "com.example.app",
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = {
            // Records backend responses once during warm-up iteration
            replayRule.recordOnce(this) {
                pressHome()
                startActivityAndWait()
            }
        }
    ) {
        pressHome()
        startActivityAndWait()
    }
}
```

---

## Configuration Options

### `ReplayRule` (Test Side)

```kotlin
val replayRule = ReplayRule(
    port = Tapedeck.DEFAULT_PORT, // Default: 28765
    latencyMs = 50L,               // Fixed simulated latency in milliseconds (default: 100ms)
    replayPost = listOf("/auth"), // POST endpoints safe to replay
    recordServerErrors = false,    // Do not record 5xx backend responses (default: false)
    onUnmatched = OnUnmatched.FORWARD, // What to do if an unknown request appears in replay mode (FORWARD, FAIL, NOT_FOUND)
    harOutputFile = File(context.filesDir, "benchmark_run.har") // Optional: export recording to HAR
)
```

### Summary Report

When the benchmark run finishes, Tapedeck logs a summary of all intercepted requests to Logcat:
```text
=== Tapedeck Benchmark Report ===
Total Requests Handled : 42
  - Recorded (Warmup)  : 4
  - Replayed           : 36
  - Forwarded to Net   : 2
  - Unmatched Requests : 0
=================================
```

---

## Inspecting Recordings

Tapedeck uses the standard **HAR 1.2 (HTTP Archive)** format. Any recording exported via `harOutputFile` can be imported directly into:
- Google Chrome DevTools (Network tab -> Import HAR)
- Charles Proxy
- Proxyman
- Fiddler

---

## License

```text
Copyright 2026 Tapedeck Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
