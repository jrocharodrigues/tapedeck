# Changelog

## Unreleased

### Fixed
- A response served by Tapedeck carried the server's `127.0.0.1` URL in `response.request`, which clients read as the
  response's final URL: a DASH or HLS player (Media3's `OkHttpDataSource.getUri()`) resolved the manifest's relative
  URLs against it, and sent those requests to the server directly. `ReplayInterceptor` now sets the request's real URL
  (with `followRedirects = false`, the last redirect's target).
- A request reaching the server directly, without its original URL, was forwarded to the server's own URL, which came
  back as another such request, endlessly: hundreds of requests a second, starving the device under test. It's now
  answered with a 502 carrying `X-Tapedeck-Forward-Failed`, and a warning is logged once.

## 0.2.2

### Added
- `ReplayInterceptor(bypassHttpCache = true)`: requests sent to Tapedeck are neither served from nor stored in the
  app's OkHttp cache, whatever cache headers the responses carry. Off by default, so replayed responses are cached like
  real ones.
- `ReplayInterceptor(hosts = setOf("*"))`: every host except loopback ones (`localhost`, `127.0.0.0/8`, `::1`), which
  are never sent to Tapedeck through `*` (e.g. a local video proxy).

### Fixed
- 0.2.1 failed the benchmark module's manifest merge (`Attribute application@networkSecurityConfig … is also present
  at [androidx.benchmark:benchmark-common]`): two libraries can't both set it. `tapedeck-macrobenchmark` no longer sets
  it; it ships `@xml/tapedeck_network_security_config` (cleartext permitted everywhere), which the benchmark module
  selects in its own manifest (README, Setup step 3, now required).
- Replayed responses took longer than `latencyMs` on low-end devices while the app was starting: the server's threads,
  in the test process at default priority, waited for a core before reading each request and writing each response.
  They now run at display priority, and MockWebServer no longer logs every request.

### Changed
- README rewritten: setup steps in order, configuration tables, and JitPack as the only repository.

## 0.2.1

### Fixed
- The app's `http://` requests couldn't be forwarded (`CLEARTEXT communication … not permitted by network security
  policy`): the server runs in the test APK, which targets API 28+ and whose network security config, merged in by
  `androidx.benchmark`, permits cleartext only to `localhost`. `tapedeck-macrobenchmark`'s manifest now replaces it
  with a config that permits cleartext everywhere (and sets `usesCleartextTraffic="true"`). A network security config
  set by the benchmark module itself still takes precedence (see README).
- A request the server couldn't forward (no response from the backend) reached the app as Tapedeck's 502. The 502 now
  carries `X-Tapedeck-Forward-Failed: <reason>`, and `ReplayInterceptor` (with `fallbackToNetworkOnFailure`, the
  default) then sends the request to the network itself. Backend responses, 5xx included, are still returned as they
  are.
- README: Quickstart step 1 is about the app's benchmark build type, and no longer suggests replacing an app's own
  network security config; new section on what the test APK needs to reach the backend.

## 0.2.0

### Fixed
- Responses from backends that compress them (gzip) reached the app still compressed but without `Content-Encoding`, so
  they couldn't be parsed: the server no longer forwards the app's `Accept-Encoding` (nor hop-by-hop headers), and
  records and replays bodies decoded.
- Replay picked the first recorded entry with the same path even when another one matched the URL exactly: exact URL
  matches now win across the whole recording, then host and path; the earliest recorded response wins among equals.
- `*.example.com` also matched `badexample.com`: wildcards now match subdomains at a dot boundary only.
- URLs with characters `java.net.URI` rejects (e.g. `|` or `{}` in the query, which OkHttp sends as-is) failed the
  request in the server.
- XML request bodies were stored as text in HAR files but read back as base64.
- The benchmark APK lacked the INTERNET permission the server needs (now declared by `tapedeck-macrobenchmark`).
- A backend that couldn't be reached while recording was recorded as a 502; HEAD, 204 and 304 replays carried a body.
- The no-op `ReplayInterceptor` didn't accept the same constructor arguments as the real one.

### Changed
- Both sides default to port 28765 (`Tapedeck.DEFAULT_PORT`), still overridable (the same on both sides).
  `ReplayRule(port = 0)` takes a free port (`actualPort`), for JVM tests. A port already in use fails the rule with a
  message naming the port and the `port` parameter.
- `tapedeck-okhttp` no longer exposes `tapedeck-core` (and kotlinx-serialization) on the app's compile classpath.
- Built with Kotlin 2.4.10; the published modules target Kotlin 2.0 consumers (language/API version and standard
  library 2.0).

### Added
- `ReplayRule(enabled = false)`: no server, no recording, no port; the app talks to the real backend.
- `ReplayRule(followRedirects = false)`: records and replays each redirect hop (see README, "Redirects").
- `ReplayInterceptor(forwardPaths = …, alwaysForward = …)`: requests that always go straight to the network.
- `RequestMatcher.score()`, for matchers with several levels of match.

## 0.1.0

- First release.
