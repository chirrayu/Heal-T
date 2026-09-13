# In-memory sliding window rate limiter for FastAPI
import time
from collections import defaultdict
from fastapi import Request, HTTPException, status
from typing import Dict, List, Tuple


class SlidingWindowRateLimiter:
    """
    Sliding window in-memory rate limiter.
    Tracks timestamps of requests per client IP or key.
    """
    def __init__(self):
        # key -> list of request timestamps
        self._requests: Dict[str, List[float]] = defaultdict(list)

    def is_allowed(self, key: str, max_requests: int, window_seconds: int) -> Tuple[bool, int, int]:
        """
        Check if request is allowed under rate limits.
        Returns: (allowed: bool, remaining_requests: int, retry_after_seconds: int)
        """
        now = time.time()
        cutoff = now - window_seconds
        
        # Filter out timestamps older than the sliding window
        timestamps = [ts for ts in self._requests[key] if ts > cutoff]
        self._requests[key] = timestamps

        if len(timestamps) >= max_requests:
            oldest_ts = timestamps[0]
            retry_after = max(1, int(oldest_ts + window_seconds - now))
            return False, 0, retry_after

        # Record this request
        timestamps.append(now)
        self._requests[key] = timestamps
        remaining = max_requests - len(timestamps)
        return True, remaining, 0

    def cleanup(self, max_age_seconds: int = 3600):
        """Cleanup inactive keys from memory."""
        now = time.time()
        cutoff = now - max_age_seconds
        keys_to_delete = []
        for key, timestamps in self._requests.items():
            valid_ts = [ts for ts in timestamps if ts > cutoff]
            if valid_ts:
                self._requests[key] = valid_ts
            else:
                keys_to_delete.append(key)
        for k in keys_to_delete:
            del self._requests[k]


_global_limiter = SlidingWindowRateLimiter()


def get_client_ip(request: Request) -> str:
    """Extracts client IP considering reverse proxy headers and direct client host."""
    forwarded = request.headers.get("X-Forwarded-For")
    if forwarded:
        # In case of proxy chains (client, proxy1, proxy2), extract the originating IP
        return forwarded.split(",")[0].strip()
    real_ip = request.headers.get("X-Real-IP")
    if real_ip:
        return real_ip.strip()
    if request.client and request.client.host:
        return request.client.host
    return "127.0.0.1"


def rate_limit(max_requests: int, window_seconds: int = 60, key_func=get_client_ip):
    """
    FastAPI dependency generator for endpoint rate limiting.
    Usage:
        @router.post("/login", dependencies=[Depends(rate_limit(5, 60))])
    """
    async def dependency(request: Request):
        client_key = key_func(request)
        endpoint = request.url.path
        composite_key = f"{client_key}:{endpoint}"

        allowed, remaining, retry_after = _global_limiter.is_allowed(
            composite_key, max_requests, window_seconds
        )

        if not allowed:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail=f"Too many requests. Please try again in {retry_after} seconds.",
                headers={
                    "Retry-After": str(retry_after),
                    "X-RateLimit-Limit": str(max_requests),
                    "X-RateLimit-Remaining": "0"
                }
            )

    return dependency
