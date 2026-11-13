"""Access control for a server exposed on the internet (issue #23).

``DICTEE_TOKENS`` (comma- or space-separated list) turns on authentication:
every route except the liveness probe, the web page and its static files then
needs ``Authorization: Bearer <token>``, else 401. Unset, the server behaves
as before (local use, no authentication).

``DICTEE_RATE_LIMIT`` (default 30, 0 = off) caps, per token, the requests
that start work on the server (transcriptions, text polishing, model switch / eject) per
minute; beyond it the server answers 429. Polling a job (``GET /jobs/{id}``)
is not counted: the Android app polls every 0.4 s while it waits.

Tokens are never logged: only a short hash identifies the caller (job owner,
rate-limit bucket).
"""
import hashlib
import hmac
import threading
import time
from collections import deque

from fastapi.responses import JSONResponse

RATE_WINDOW_SECONDS = 60.0

# Reachable without a token: liveness probe and the web page (which then cannot
# call the API without a token; see docs/DEPLOIEMENT_SERVEUR.md).
PUBLIC_PATHS = ("/", "/healthz")
PUBLIC_PREFIXES = ("/static/",)

# Requests that make the server work (and cost GPU time): rate-limited per token.
RATE_LIMITED = {
    ("POST", "/transcribe"),
    ("POST", "/transcribe/streaming"),
    ("POST", "/jobs"),
    ("POST", "/polish"),
    ("POST", "/switch-model"),
    ("POST", "/eject"),
}


def parse_tokens(raw):
    """Tokens from the DICTEE_TOKENS value: separated by commas, spaces or newlines."""
    if not raw:
        return ()
    return tuple(t for t in raw.replace(",", " ").split() if t)


def token_id(token):
    """Short, non-reversible id of a token (job owner, rate-limit key, logs)."""
    return hashlib.sha256(token.encode()).hexdigest()[:16]


def bearer_token(request):
    header = request.headers.get("authorization") or ""
    scheme, _, value = header.partition(" ")
    if scheme.lower() != "bearer":
        return None
    return value.strip() or None


def match_token(candidate, tokens):
    """Constant-time comparison against every configured token."""
    if not candidate:
        return False
    found = False
    for token in tokens:
        found |= hmac.compare_digest(candidate.encode(), token.encode())
    return found


class RateLimiter:
    """Sliding one-minute window per key, in memory (one server process)."""

    def __init__(self, limit, window=RATE_WINDOW_SECONDS, clock=time.monotonic):
        self.limit = limit
        self.window = window
        self.clock = clock
        self._hits = {}
        self._lock = threading.Lock()

    def allow(self, key):
        """Records a request for ``key``; returns (allowed, seconds until the next free slot)."""
        if self.limit <= 0:
            return True, 0
        now = self.clock()
        with self._lock:
            hits = self._hits.setdefault(key, deque())
            while hits and hits[0] <= now - self.window:
                hits.popleft()
            if len(hits) >= self.limit:
                return False, max(1, int(hits[0] + self.window - now + 0.999))
            hits.append(now)
            if len(self._hits) > 10000:  # forget idle keys, bounded memory
                for k in [k for k, v in self._hits.items() if not v]:
                    del self._hits[k]
            return True, 0


def is_public(path):
    return path in PUBLIC_PATHS or path.startswith(PUBLIC_PREFIXES)


def install(app, settings):
    """Adds the token check and the rate limit to ``app`` when DICTEE_TOKENS is set."""
    app.state.auth_required = settings.auth_required
    if not settings.auth_required:
        return
    tokens = settings.tokens
    limiter = RateLimiter(settings.rate_limit)
    app.state.rate_limiter = limiter

    @app.middleware("http")
    async def require_token(request, call_next):
        request.state.owner = None
        path = request.url.path
        if request.method == "OPTIONS" or is_public(path):  # CORS preflight carries no credentials
            return await call_next(request)
        token = bearer_token(request)
        if not match_token(token, tokens):
            return JSONResponse(status_code=401, content={"detail": "A valid access token is required"},
                                headers={"WWW-Authenticate": "Bearer"})
        owner = token_id(token)
        request.state.owner = owner
        if (request.method, path) in RATE_LIMITED:
            allowed, retry = limiter.allow(owner)
            if not allowed:
                return JSONResponse(status_code=429,
                                    content={"detail": f"Too many requests: limit {limiter.limit} per minute"},
                                    headers={"Retry-After": str(retry)})
        return await call_next(request)
