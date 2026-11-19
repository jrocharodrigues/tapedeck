package com.impecabel.tapedeck.core

object Tapedeck {
    /**
     * The port Tapedeck's server listens on by default, and the one `ReplayInterceptor` sends requests to. Both sides can
     * be given another one, which must then be the same on both.
     */
    const val DEFAULT_PORT = 28765

    const val HEADER_ORIGINAL_URL = "X-Tapedeck-Original-Url"
    const val HEADER_ORIGINAL_SCHEME = "X-Tapedeck-Original-Scheme"
    const val HEADER_ORIGINAL_HOST = "X-Tapedeck-Original-Host"

    /**
     * Set by `ReplayInterceptor` when it bypasses the app's HTTP cache: the request's own `Cache-Control`, empty when it
     * had none. The server restores it in place of the `Cache-Control` the interceptor set, before recording or
     * forwarding the request.
     */
    const val HEADER_ORIGINAL_CACHE_CONTROL = "X-Tapedeck-Original-Cache-Control"

    /**
     * Set by the server on its 502 when forwarding a request got no response from the backend (e.g. it couldn't connect,
     * or the test APK's network security policy refused it), with the reason. `ReplayInterceptor` then sends the request
     * to the network itself. Real backend responses, 5xx included, never carry it.
     */
    const val HEADER_FORWARD_FAILED = "X-Tapedeck-Forward-Failed"

    /**
     * Path on Tapedeck's server that redirect targets are served through when the rule doesn't follow redirects: the
     * real target is in [REDIRECT_URL_PARAMETER], and the redirecting request's host in [REDIRECT_FROM_PARAMETER].
     */
    const val REDIRECT_PATH = "/__tapedeck/redirect"
    const val REDIRECT_URL_PARAMETER = "url"
    const val REDIRECT_FROM_PARAMETER = "from"

    const val VERSION = "0.2.2"
}
