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
     * Path on Tapedeck's server that redirect targets are served through when the rule doesn't follow redirects: the
     * real target is in [REDIRECT_URL_PARAMETER], and the redirecting request's host in [REDIRECT_FROM_PARAMETER].
     */
    const val REDIRECT_PATH = "/__tapedeck/redirect"
    const val REDIRECT_URL_PARAMETER = "url"
    const val REDIRECT_FROM_PARAMETER = "from"

    const val VERSION = "0.2.0"
}
