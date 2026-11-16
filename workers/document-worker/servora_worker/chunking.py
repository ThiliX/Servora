"""Deterministic structural chunking.

Text is split at paragraph boundaries first, then at sentence boundaries, and only as a last resort
in the middle of a sentence. A chunk never spans two sections, so its page and heading metadata is
exact. The same input always produces the same chunks.
"""

import re
from dataclasses import dataclass

from .extract import Section

_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")


@dataclass(frozen=True)
class Chunk:
    index: int
    text: str
    page: int | None
    heading: str | None


def chunk_sections(sections: list[Section], *, max_chars: int, overlap_chars: int) -> list[Chunk]:
    if overlap_chars >= max_chars:
        raise ValueError("overlap_chars must be smaller than max_chars")
    chunks: list[Chunk] = []
    for section in sections:
        for text in _chunk_text(section.text, max_chars, overlap_chars):
            chunks.append(Chunk(len(chunks), text, section.page, section.heading))
    return chunks


def _chunk_text(text: str, max_chars: int, overlap_chars: int) -> list[str]:
    pieces: list[str] = []
    for paragraph in text.split("\n\n"):
        paragraph = paragraph.strip()
        if paragraph:
            pieces.extend(_split_long(paragraph, max_chars))

    result: list[str] = []
    current = ""
    for piece in pieces:
        if not current:
            current = piece
        elif len(current) + 2 + len(piece) <= max_chars:
            current = f"{current}\n\n{piece}"
        else:
            result.append(current)
            # Carry a little of the previous chunk forward so a fact split across the boundary
            # is still found, but only when it fits.
            tail = _tail(current, overlap_chars)
            current = f"{tail}\n\n{piece}" if tail and len(tail) + 2 + len(piece) <= max_chars else piece
    if current:
        result.append(current)
    return result


def _split_long(paragraph: str, max_chars: int) -> list[str]:
    """Breaks a paragraph that is longer than a chunk into sentence groups, then hard-splits."""
    if len(paragraph) <= max_chars:
        return [paragraph]
    parts: list[str] = []
    current = ""
    for sentence in _SENTENCE_END.split(paragraph):
        sentence = sentence.strip()
        if not sentence:
            continue
        if current and len(current) + 1 + len(sentence) <= max_chars:
            current = f"{current} {sentence}"
            continue
        if current:
            parts.append(current)
            current = ""
        while len(sentence) > max_chars:
            cut = sentence.rfind(" ", 0, max_chars + 1)
            cut = cut if cut > max_chars // 2 else max_chars
            parts.append(sentence[:cut].strip())
            sentence = sentence[cut:].strip()
        current = sentence
    if current:
        parts.append(current)
    return parts


def _tail(text: str, overlap_chars: int) -> str:
    """The last few words of a chunk, cut at a word boundary."""
    if overlap_chars <= 0 or len(text) <= overlap_chars:
        return ""
    tail = text[-overlap_chars:]
    space = tail.find(" ")
    return tail[space + 1 :].strip() if space != -1 else ""
