# Tapedeck 📼

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![CI](https://github.com/jrocharodrigues/tapedeck/actions/workflows/ci.yml/badge.svg)](https://github.com/jrocharodrigues/tapedeck/actions)
[![JitPack](https://jitpack.io/v/jrocharodrigues/tapedeck.svg)](https://jitpack.io/#jrocharodrigues/tapedeck)

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

Tapedeck runs OkHttp's **MockWebServer** directly inside the **Macrobenchmark test process** on `127.0.0.1:28765` (`Tapedeck.DEFAULT_PORT`), the port `ReplayInterceptor` sends requests to.

```
 Macrobenchmark Test Process                         App Process (Benchmark Build)
 ┌──────────────────────────────────────┐            ┌──────────────────────────────┐
 │ MockWebServer @ 127.0.0.1:28765      │◄── http ───│ ReplayInterceptor            │
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
4. **Offline / Fallback Safety**: If the app is opened manually or running in a benchmark without Tapedeck active, nothing listens on the port: the connection to `127.0.0.1` is refused at once and `ReplayInterceptor` sends the request to the real backend (`fallbackToNetworkOnFailure`, on by default).

---

## Modules

| Artifact | Purpose |
|---|---|
| `tapedeck-okhttp` | App side: `ReplayInterceptor` for benchmark builds (URL rewriting & localhost routing). |
| `tapedeck-okhttp-noop` | App side: Drop-in no-op `ReplayInterceptor` for release builds (0 overhead). |
| `tapedeck-macrobenchmark` | Test side: `ReplayRule` (MockWebServer, forwarding dispatcher, record/replay, reports). |
| `tapedeck-core` | Shared: HAR 1.2 parser/serializer, matchers, recorded models. |

---

## Installation

Tapedeck is built with Kotlin 2.4 but compiled for **Kotlin 2.0+ consumers** (language and API version 2.0, and a
dependency on the 2.0 standard library), with minSdk 23 and Java 17 bytecode.

Artifacts are served by **JitPack** (no authentication) and **GitHub Packages** (needs a GitHub token). They are not on Maven Central.

### Option 1: Via JitPack (no authentication required)

Add JitPack to your `settings.gradle.kts`, restricted to Tapedeck's artifacts so it isn't searched for anything else:
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.jrocharodrigues.*") }
        }
    }
}
```

JitPack serves the artifacts under its own group, `com.github.jrocharodrigues.tapedeck`, with the git tag as the version (`v0.2.0`).

Add dependencies to your app and benchmark modules:
```kotlin
// App module (build.gradle.kts)
benchmarkImplementation("com.github.jrocharodrigues.tapedeck:tapedeck-okhttp:v0.2.0")
releaseImplementation("com.github.jrocharodrigues.tapedeck:tapedeck-okhttp-noop:v0.2.0")

// Macrobenchmark module (build.gradle.kts)
implementation("com.github.jrocharodrigues.tapedeck:tapedeck-macrobenchmark:v0.2.0")
```

Every build type of the app needs one of the two app-side artifacts (e.g. also `debugImplementation`), since the code
that adds `ReplayInterceptor` is shared. Alternatively, depend on `tapedeck-okhttp` in all build types and add the
interceptor only when a benchmark-only `BuildConfig` flag is set: R8 then removes it from release builds.

### Option 2: Via GitHub Packages

Add GitHub Packages to your `settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/jrocharodrigues/tapedeck")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "<your-github-username>"
                password = System.getenv("GITHUB_TOKEN") ?: "<your-github-pat>"
            }
        }
    }
}
```

GitHub Packages needs a token with the `read:packages` scope, even for public packages. Add dependencies using the
coordinates the build publishes there:
```kotlin
// App module (build.gradle.kts)
benchmarkImplementation("com.impecabel.tapedeck:tapedeck-okhttp:0.2.0")
releaseImplementation("com.impecabel.tapedeck:tapedeck-okhttp-noop:0.2.0")

// Macrobenchmark module (build.gradle.kts)
implementation("com.impecabel.tapedeck:tapedeck-macrobenchmark:0.2.0")
```

---

## Quickstart

### 1. Configure Cleartext Traffic for Localhost

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

### 2. Add `ReplayInterceptor` to OkHttpClient

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

Add it as an application interceptor, before interceptors that depend on the request's host or scheme. Options:

```kotlin
ReplayInterceptor(
    hosts = setOf("api.example.com", "*.cdn.example.com"), // "*.x.com" matches subdomains of x.com, not x.com itself
    forwardPaths = setOf("/media/stream"),  // always straight to the network: the path itself and anything under it
    alwaysForward = { request -> request.url.queryParameter("live") == "true" }, // any other request to never record
    fallbackToNetworkOnFailure = true      // Tapedeck's server not reachable: use the network (default)
)
```

Both sides use port 28765 (`Tapedeck.DEFAULT_PORT`). To use another one, pass the same `port = …` to
`ReplayInterceptor` and `ReplayRule`. If the port is already taken when the test starts, `ReplayRule` fails with a
message naming it.

### 3. Use `ReplayRule` in Macrobenchmark

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
    port = Tapedeck.DEFAULT_PORT,  // 28765; must match ReplayInterceptor's. 0 = a free port (JVM tests, see actualPort)
    latencyMs = 50L,               // Fixed simulated latency in milliseconds (default: 100ms)
    replayPost = listOf("/auth"), // POST endpoints safe to replay
    recordServerErrors = false,    // Do not record 5xx backend responses (default: false)
    onUnmatched = OnUnmatched.FORWARD, // What to do if an unknown request appears in replay mode (FORWARD, FAIL, NOT_FOUND)
    harOutputFile = File(context.filesDir, "benchmark_run.har"), // Optional: export recording to HAR
    enabled = true,                // false: no server and no recording, the app talks to the real backend
    followRedirects = true         // false: record and replay each 3xx hop instead of only the final response
)
```

### Redirects

With `followRedirects = true` (the default), the server follows the backend's redirects: the final response is
recorded and replayed for the original URL, and **the app sees the final response, not the 3xx**.

With `followRedirects = false`, each 3xx is recorded and replayed as-is (with its `Location`), and the redirect target
is fetched, recorded and replayed as a request of its own. OkHttp follows redirects *below* application interceptors,
so the app's follow-up request never passes through `ReplayInterceptor`; and it can't be added with
`addNetworkInterceptor` instead, since network interceptors may not change the host or port. So the server serves each
3xx with its `Location` pointing back at itself (`http://127.0.0.1:<port>/__tapedeck/redirect?url=<target>`, with the port the server actually listens on), and the
app's own redirect handling sends every hop through Tapedeck. Recordings keep the backend's original `Location`. As
when OkHttp follows a redirect, `Authorization` isn't sent to a different host. An app that handles redirects itself
(`followRedirects(false)` on its client) sees the rewritten `Location`.

To measure against the real backend without code changes, drive `enabled` from an instrumentation argument:
```kotlin
val replayRule = ReplayRule(
    enabled = InstrumentationRegistry.getArguments().getString("tapedeck") != "off"
)
```
and run with `-Pandroid.testInstrumentationRunnerArguments.tapedeck=off`.

Replayed responses are matched by method and full URL first, across the whole recording, and only then by method, host
and path (ignoring the query); the earliest recorded response wins among equal matches. Bodies are recorded and replayed
decoded (no `Content-Encoding`), whatever compression the backend used.

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
