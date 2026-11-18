package io.github.remtav.dictee.stt

import java.io.IOException

/** Why a request to the server failed; drives the connection test's message and the fallback. */
enum class RemoteFailure {
    /** HTTP 401 or 403: the server or Cloudflare Access refused the credentials. */
    ACCESS_DENIED,
    /** HTTP 3xx: Cloudflare Access sends unauthenticated clients to its login page. */
    ACCESS_REDIRECT,
    /** Connection failed (DNS, refused, network down, TLS). */
    UNREACHABLE,
    /** No result within the wait budget. */
    TIMEOUT,
    /** Any other HTTP error (5xx, 4xx). */
    HTTP_ERROR,
    /** Not the expected JSON (an HTML page from a proxy, for instance). */
    BAD_RESPONSE,
    /** The server job ended in error or was cancelled on the server. */
    JOB_FAILED,
    /** Cancelled by the user (Annuler) or because the dictation was dropped. */
    CANCELLED,
}

open class RemoteException(
    message: String,
    cause: Throwable? = null,
    val failure: RemoteFailure = RemoteFailure.HTTP_ERROR,
    val httpCode: Int = 0,
    /** Seconds from a 429's `Retry-After` header (rate limit, issue #35), 0 when absent. */
    val retryAfterSeconds: Int = 0,
) : IOException(message, cause)
