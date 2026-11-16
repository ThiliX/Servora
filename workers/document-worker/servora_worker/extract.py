"""Text extraction and normalisation for PDF, plain text and Markdown."""

import io
import re
import unicodedata
from dataclasses import dataclass

from pypdf import PdfReader
from pypdf.errors import PyPdfError

from .errors import PermanentError

_HEADING = re.compile(r"^#{1,6}[ \t]+(.+?)[ \t#]*$")
_CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_SPACES = re.compile(r"[ \t ]+")
_BLANK_LINES = re.compile(r"\n{3,}")


@dataclass(frozen=True)
class Section:
    """A run of text with the metadata that is preserved on every chunk cut from it."""

    text: str
    page: int | None = None
    heading: str | None = None


def normalize(text: str) -> str:
    """Canonical whitespace and Unicode form, so the same document always yields the same chunks."""
    text = unicodedata.normalize("NFC", text).replace("\r\n", "\n").replace("\r", "\n")
    text = _CONTROL.sub("", text)
    text = "\n".join(_SPACES.sub(" ", line).strip() for line in text.split("\n"))
    return _BLANK_LINES.sub("\n\n", text).strip()


def extract(mime: str, data: bytes, *, max_pdf_pages: int, max_text_chars: int) -> list[Section]:
    if mime == "application/pdf":
        sections = _extract_pdf(data, max_pdf_pages)
    elif mime == "text/markdown":
        sections = _extract_markdown(_decode(data))
    elif mime == "text/plain":
        sections = [Section(normalize(_decode(data)))]
    else:
        raise PermanentError("UNSUPPORTED_CONTENT")

    sections = [s for s in sections if s.text]
    if sum(len(s.text) for s in sections) > max_text_chars:
        raise PermanentError("TEXT_TOO_LARGE")
    if not sections:
        raise PermanentError("EMPTY_DOCUMENT")
    return sections


def _decode(data: bytes) -> str:
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise PermanentError("PARSE_ERROR") from error


def _extract_pdf(data: bytes, max_pages: int) -> list[Section]:
    # pypdf is a pure-Python parser. It never executes document scripts and does not fetch
    # anything a document links to.
    try:
        reader = PdfReader(io.BytesIO(data), strict=False)
        if reader.is_encrypted:
            raise PermanentError("ENCRYPTED_PDF")
        page_count = len(reader.pages)
        if page_count > max_pages:
            raise PermanentError("TOO_MANY_PAGES")
        return [
            Section(normalize(reader.pages[index].extract_text() or ""), page=index + 1)
            for index in range(page_count)
        ]
    except PermanentError:
        raise
    except (PyPdfError, ValueError, KeyError, IndexError, TypeError, AttributeError, RecursionError) as error:
        # A malformed file can fail in many ways inside the parser; all of them mean the same thing.
        raise PermanentError("PARSE_ERROR") from error


def _extract_markdown(text: str) -> list[Section]:
    """Splits at headings so each chunk knows which heading it sits under."""
    sections: list[Section] = []
    heading: str | None = None
    lines: list[str] = []

    def flush() -> None:
        body = normalize("\n".join(lines))
        if body:
            sections.append(Section(body, heading=heading))
        lines.clear()

    in_code_block = False
    for line in text.replace("\r\n", "\n").split("\n"):
        if line.lstrip().startswith("```"):
            in_code_block = not in_code_block
        match = None if in_code_block else _HEADING.match(line)
        if match:
            flush()
            heading = normalize(match.group(1))[:200] or None
        else:
            lines.append(line)
    flush()
    return sections
