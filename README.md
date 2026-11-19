# Tapedeck

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![CI](https://github.com/jrocharodrigues/tapedeck/actions/workflows/ci.yml/badge.svg)](https://github.com/jrocharodrigues/tapedeck/actions)
[![JitPack](https://jitpack.io/v/jrocharodrigues/tapedeck.svg)](https://jitpack.io/#jrocharodrigues/tapedeck)

HTTP record and replay for Android Macrobenchmark.

Startup benchmarks of apps that load their first screen from a backend measure the backend as much as the app:
response times drift through the day, intermittent 5xx errors fail runs, and two builds measured an hour apart are
measured against two different backends. Tapedeck records the backend's responses once, in a warm-up launch, and
replays them with a fixed latency in every measured iteration, so results depend on the app alone.

- Runs inside the Macrobenchmark test process, so recordings survive `am force-stop` and `pm clear` of the app.
- No proxy, no custom CA, no checked-in fixtures: recordings are taken from the live backend on each run.
- Requests that must stay live (streaming sessions, writes) go straight to the network.
- Recordings can be exported as HAR 1.2.
- Release builds use a no-op artifact with the same API.

## How it works

```
 Benchmark (test) process                           App process (benchmark build)
┌─────────────────────────────────────────┐        ┌───────────────────────────────────┐
│ ReplayRule                              │        │ OkHttpClient                      │
│  MockWebServer on 127.0.0.1:28765       │◄─http──│  ReplayInterceptor                │
│   RECORD: forward to backend, record    │        │   https://api.example.com/v1/feed │
│   REPLAY: serve recording, fixed delay  │        │   → http://127.0.0.1:28765/…      │
└─────────────────────────────────────────┘        └───────────────────────────────────┘
```

1. `ReplayInterceptor`, in the app, sends requests for the configured hosts to a local server instead of the network.
2. `ReplayRule`, in the benchmark, runs that server. During the warm-up launch (`recordOnce`) it forwards requests to
   the backend and records the responses; afterwards it serves them from the recording with a fixed latency.
3. When no server is running (the app opened outside a benchmark, or the rule disabled), the connection to
   `127.0.0.1` is refused immediately and the interceptor sends the request to the network.

## Installation

Tapedeck is distributed through [JitPack](https://jitpack.io/#jrocharodrigues/tapedeck). Add the repository in
`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then add the dependencies:

```kotlin
// App module
benchmarkImplementation("com.github.jrocharodrigues.tapedeck:tapedeck-okhttp:v0.2.2")
releaseImplementation("com.github.jrocharodrigues.tapedeck:tapedeck-okhttp-noop:v0.2.2")

// Macrobenchmark module
implementation("com.github.jrocharodrigues.tapedeck:tapedeck-macrobenchmark:v0.2.2")
```

Every build type of the app needs one of the two app-side artifacts, because the code that installs the interceptor
is shared. Alternatively, depend on `tapedeck-okhttp` in all build types and install the interceptor only behind a
benchmark-only `BuildConfig` flag; R8 removes it from the other builds.

| Artifact | Side | Contents |
|---|---|---|
| `tapedeck-okhttp` | App | `ReplayInterceptor` |
| `tapedeck-okhttp-noop` | App | No-op `ReplayInterceptor` with the same constructor |
| `tapedeck-macrobenchmark` | Benchmark | `ReplayRule` and the replay server |
| `tapedeck-core` | Both (transitive) | Recording model, HAR 1.2 codec, request matchers |

**Requirements:** minSdk 23, Java 17, Kotlin 2.0 or later, OkHttp 4.12.

## Setup

### 1. Install the interceptor in the app

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(
        ReplayInterceptor(
            hosts = setOf("api.example.com", "*.cdn.example.com"),
        ),
    )
    .build()
```

Add it as an application interceptor, ahead of any interceptor that depends on the request's host or scheme. Clients
derived with `newBuilder()` inherit it.

### 2. Allow the app to reach the server

The interceptor sends requests to `http://127.0.0.1`, which apps targeting API 28 or later may only do when their
network security config permits cleartext traffic to that address. This applies to the app under test, in the build
type the benchmark runs.

- If the app's config already permits cleartext globally, nothing is needed.
- If the app has no config, add one to the benchmark build type, for example
  `app/src/benchmark/res/xml/network_security_config.xml`:

  ```xml
  <network-security-config>
      <domain-config cleartextTrafficPermitted="true">
          <domain includeSubdomains="false">127.0.0.1</domain>
          <domain includeSubdomains="false">localhost</domain>
      </domain-config>
  </network-security-config>
  ```

  and reference it from `app/src/benchmark/AndroidManifest.xml` with
  `android:networkSecurityConfig="@xml/network_security_config"`.
- If the app has its own config, add the `domain-config` above to a copy of it in the benchmark build type rather than
  replacing it. Replacing it drops the app's own rules (cleartext, trust anchors, pinning), which the requests that
  bypass Tapedeck still rely on.

### 3. Allow the benchmark to reach the backend

The server runs in the benchmark APK and forwards requests to the real backend, so that APK must be able to reach
every host the app uses, including `http://` ones. `androidx.benchmark` sets a network security config that permits
cleartext only to `localhost`. Replace it with the one Tapedeck ships, in the benchmark module's
`src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        android:networkSecurityConfig="@xml/tapedeck_network_security_config"
        tools:replace="android:networkSecurityConfig" />
</manifest>
```

This has to be declared by the benchmark module: two libraries setting different configs fail the manifest merge, so
Tapedeck doesn't set one itself. A module with its own config can keep it, provided it permits cleartext to the app's
hosts and to `localhost`.

### 4. Record and replay in the benchmark

```kotlin
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @get:Rule
    val replayRule = ReplayRule(
        latencyMs = 150L,
        replayPost = listOf("/auth/token"),
    )

    @Test
    fun startup() = benchmarkRule.measureRepeated(
        packageName = "com.example.app",
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = {
            // Runs once, before the first measured iteration; the app is killed afterwards
            replayRule.recordOnce(this) {
                startActivityAndWait()
                // Wait here until the app has made the requests the measured launches will make
            }
            pressHome()
        },
    ) {
        startActivityAndWait()
    }
}
```

## Configuration

### `ReplayInterceptor`

| Parameter | Default | Description |
|---|---|---|
| `hosts` | — | Hosts routed to the server: exact names, `*.example.com` for its subdomains (not `example.com` itself), or `*` for every host except loopback (`localhost`, `127.0.0.0/8`, `::1`). |
| `port` | `28765` | Server port. Must match `ReplayRule.port`. |
| `forwardPaths` | empty | Paths that always go straight to the network, including everything under them. |
| `alwaysForward` | `{ false }` | Predicate for any other request that must go straight to the network. |
| `fallbackToNetworkOnFailure` | `true` | Send the request to the network when the server can't be reached, or when it couldn't reach the backend. |
| `bypassHttpCache` | `false` | Keep requests sent to Tapedeck out of the client's HTTP cache: they are neither served from it nor stored in it. See [HTTP cache](#http-cache). |
| `enabled` | `true` | When `false`, every request goes straight to the network. |

### `ReplayRule`

| Parameter | Default | Description |
|---|---|---|
| `port` | `28765` | Port to listen on. `0` picks a free port (see `actualPort`); the interceptor must then be given the same port. |
| `latencyMs` | `100` | Delay applied to every replayed response. |
| `replayPost` | empty | POST paths that may be recorded and replayed. Other POSTs, and PUT, PATCH and DELETE, are always forwarded. |
| `recordServerErrors` | `false` | Record 5xx responses. When `false`, they are forwarded on every run instead. |
| `onUnmatched` | `FORWARD` | Requests missing from the recording during replay: `FORWARD` them to the backend, or answer `500` (`FAIL`) or `404` (`NOT_FOUND`). |
| `followRedirects` | `true` | Follow redirects when forwarding. See [Redirects](#redirects). |
| `harOutputFile` | `null` | Write the recording to this file as HAR 1.2. |
| `matcher` | method + URL | Custom `RequestMatcher` for replay lookups. |
| `enabled` | `true` | When `false`, no server is started and `recordOnce` does nothing; the app uses the real backend. |

To run the same benchmark against the real backend, drive `enabled` from an instrumentation argument:

```kotlin
val replayRule = ReplayRule(
    enabled = InstrumentationRegistry.getArguments().getString("tapedeck") != "off",
)
```

```shell
./gradlew :benchmark:connectedCheck -Pandroid.testInstrumentationRunnerArguments.tapedeck=off
```

## Behaviour

### Matching

A request is answered with the recorded response for the same method and full URL. When none exists, a response for
the same method, host and path, ignoring the query, is used instead. When several responses match equally, the one
recorded first is served. Bodies are stored and served decoded, without `Content-Encoding`.

### Redirects

With `followRedirects = true`, the server follows redirects while recording, and the app receives the final response
for the original URL.

With `followRedirects = false`, each 3xx response is recorded and replayed as received, and each redirect target is
recorded as a request of its own. Because OkHttp follows redirects below application interceptors, the server rewrites
`Location` to point back at itself, so every hop is served by Tapedeck. Recordings keep the original `Location`. As
with OkHttp, `Authorization` is not forwarded to a different host. Apps that handle redirects themselves see the
rewritten `Location`.

### HTTP cache

Requests sent to Tapedeck pass through the app's whole OkHttp stack, including its HTTP cache: replayed responses are
written to the cache, and later requests may be answered from it, as with the real backend. This keeps the cache's
cost in the measurement, which on devices with slow storage can be significant.

With `bypassHttpCache = true`, the interceptor marks those requests `Cache-Control: no-cache, no-store`, so OkHttp
neither serves them from the cache nor stores their responses, even when a network interceptor rewrites the response's
cache headers. The server restores the request's own `Cache-Control` before recording or forwarding it. Use it to
measure the app without its HTTP cache, or to reduce run-to-run variance. Requests that go straight to the network
still use the cache.

### Failures

If the server cannot reach the backend (unresolved host, refused connection, blocked cleartext), it responds with
`502` and an `X-Tapedeck-Forward-Failed` header, and the interceptor retries the request through the app's own network
stack. Error responses from the backend itself are passed through unchanged.

## Report

At the end of each test, `ReplayRule` logs a summary:

```text
=== Tapedeck Benchmark Report ===
Total Requests Handled : 647
  - Recorded (Warmup)  : 56
  - Replayed           : 583
  - Forwarded to Net   : 8
  - Unmatched Requests : 8
=================================
```

Unmatched requests are worth checking: they usually mean the measured launches made requests the warm-up launch did
not, and those requests still depend on the backend.

## Inspecting recordings

Recordings written with `harOutputFile` are standard HAR 1.2 files and open in Chrome DevTools (Network › Import HAR),
Charles, Proxyman and Fiddler.

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
