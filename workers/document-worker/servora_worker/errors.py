"""Failure types. Codes are safe to store and show: they never contain document content."""


class PermanentError(Exception):
    """The document cannot be processed. Retrying will not help."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class TransientError(Exception):
    """A dependency failed. The task is retried with backoff."""
