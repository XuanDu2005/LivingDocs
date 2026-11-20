"""Retry behaviour of the OpenAI-compatible provider.

The provider serves OpenAI itself and every OpenAI-compatible endpoint
(Groq, Together, Fireworks). Their rate limits come in two flavours, and
telling them apart is the whole point of this suite:

* a *momentary* rate limit clears in seconds, so retrying helps;
* an *exhausted quota* — a daily token budget, for instance — does not
  clear until the provider's window resets, so retrying only burns the
  caller's time.

Retrying the second kind was the reason a documentation sync took four
minutes to produce nothing: the provider said "try again in 17 minutes"
and the client slept 2, 4, 8 and 16 seconds before giving up anyway.
"""

from __future__ import annotations

from app.llm.providers.openai_provider import (
    _is_quota_exhausted,
    _is_retryable,
    _retry_after_hint,
    _retry_delay,
)

#: The exact error Groq answers when the daily token budget is spent.
GROQ_DAILY_QUOTA = (
    "Error code: 429 - {'error': {'message': 'Rate limit reached for model "
    "`openai/gpt-oss-20b` in organization `org_01m59` service tier `on_demand` "
    "on tokens per day (TPD): Limit 200000, Used 199323, Requested 3071. "
    "Please try again in 17m14.208s. Need more tokens? Upgrade to Dev Tier "
    "today at https://console.groq.com/settings/billing'}}"
)


class FakeHttpError(Exception):
    """A provider error shaped like the ones the SDK raises."""

    def __init__(self, text: str, status: int = 429) -> None:
        super().__init__(text)
        self.status_code = status
        self.body = text


def test_daily_quota_is_recognised_as_quota_exhaustion() -> None:
    """The daily token budget must not be mistaken for a momentary limit."""

    exc = FakeHttpError(GROQ_DAILY_QUOTA)
    assert _is_quota_exhausted(exc) is True


def test_daily_quota_is_not_retried() -> None:
    """Retrying an exhausted quota cannot succeed — it must give up at once.

    The provider asked for a 17 minute wait. Honouring that would hold
    the request open far past any sensible timeout, and the call would
    still fail, so the error is raised immediately instead.
    """

    exc = FakeHttpError(GROQ_DAILY_QUOTA)
    assert _retry_delay(exc, 0) is None


def test_retry_after_hint_is_read_from_the_error_text() -> None:
    """The "Please try again in 4.2s." sentence is a usable hint."""

    exc = FakeHttpError("Error code: 429 - Rate limit reached. Please try again in 4.2s.")
    assert _retry_after_hint(exc) == 4.2


def test_short_retry_after_hint_is_honoured() -> None:
    """A momentary rate limit is waited out — that is what the hint is for."""

    exc = FakeHttpError("Error code: 429 - Rate limit reached. Please try again in 4.2s.")
    assert _is_quota_exhausted(exc) is False
    assert _retry_delay(exc, 0) == 4.2


def test_hint_beyond_the_wait_budget_is_refused() -> None:
    """A hint that does not fit the budget means the quota window has to reset."""

    exc = FakeHttpError("Error code: 429 - Please try again in 17m14.208s.")
    assert _retry_delay(exc, 0) is None


def test_plain_rate_limit_uses_the_exponential_backoff() -> None:
    """Without a hint the ordinary backoff applies."""

    exc = FakeHttpError("Error code: 429 - too many requests")
    assert _is_quota_exhausted(exc) is False
    assert _retry_delay(exc, 0) == 2.0
    assert _retry_delay(exc, 1) == 4.0
    assert _retry_delay(exc, 2) == 8.0


def test_server_error_is_retried_but_a_bad_key_is_not() -> None:
    """5xx is transient; 401 will fail again no matter how often we retry."""

    assert _is_retryable(FakeHttpError("boom", status=500)) is True
    assert _is_retryable(FakeHttpError("bad key", status=401)) is False
    assert _is_retryable(FakeHttpError("bad request", status=400)) is False
