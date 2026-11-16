import pytest
from pdfs import blank_pdf, text_pdf

from servora_worker.chunking import chunk_sections
from servora_worker.embeddings import HashingEmbedder
from servora_worker.errors import PermanentError
from servora_worker.extract import Section, extract, normalize

LIMITS = {"max_pdf_pages": 5, "max_text_chars": 10_000}


def _code(mime: str, data: bytes, **limits: int) -> str:
    with pytest.raises(PermanentError) as raised:
        extract(mime, data, **(LIMITS | limits))
    return raised.value.code


class TestNormalize:
    def test_collapses_whitespace_and_blank_lines(self):
        assert normalize("  Returns \t are   accepted.\r\n\r\n\r\n\r\nWithin 30 days.  ") == (
            "Returns are accepted.\n\nWithin 30 days."
        )

    def test_removes_control_characters_and_composes_unicode(self):
        assert normalize("café\x00\x07 menu") == "café menu"


class TestExtract:
    def test_plain_text_becomes_one_section(self):
        sections = extract("text/plain", b"Delivery takes 3 days.\n", **LIMITS)
        assert sections == [Section("Delivery takes 3 days.")]

    def test_utf8_byte_order_mark_is_ignored(self):
        assert extract("text/plain", b"\xef\xbb\xbfHello", **LIMITS)[0].text == "Hello"

    def test_markdown_is_split_at_headings_and_keeps_them_as_metadata(self):
        markdown = (
            "Intro line.\n\n# Returns\nWithin 30 days.\n\n## Refunds ##\nTo the original card.\n\n"
            "```\n# not a heading\n```\n"
        )
        sections = extract("text/markdown", markdown.encode(), **LIMITS)
        assert [(s.heading, s.text) for s in sections] == [
            (None, "Intro line."),
            ("Returns", "Within 30 days."),
            ("Refunds", "To the original card.\n\n```\n# not a heading\n```"),
        ]

    def test_pdf_text_is_extracted_per_page_with_page_numbers(self):
        sections = extract(
            "application/pdf", text_pdf(["Check-in is at 2pm.", "Breakfast until 10am."]), **LIMITS
        )
        assert [(s.page, s.text) for s in sections] == [
            (1, "Check-in is at 2pm."),
            (2, "Breakfast until 10am."),
        ]

    def test_pages_without_text_are_skipped(self):
        assert _code("application/pdf", blank_pdf(2)) == "EMPTY_DOCUMENT"

    def test_encrypted_pdf_is_refused(self):
        assert _code("application/pdf", blank_pdf(1, password="secret")) == "ENCRYPTED_PDF"

    def test_pdf_with_too_many_pages_is_refused(self):
        assert _code("application/pdf", blank_pdf(6)) == "TOO_MANY_PAGES"

    @pytest.mark.parametrize(
        "data",
        [
            b"%PDF-1.7\nthis is not really a pdf",
            b"%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>",
            b"%PDF-",
        ],
    )
    def test_malformed_pdf_fails_with_a_safe_code(self, data):
        assert _code("application/pdf", data) in {"PARSE_ERROR", "EMPTY_DOCUMENT"}

    def test_text_that_is_not_utf8_is_refused(self):
        assert _code("text/plain", b"\xff\xfe\x00bad") == "PARSE_ERROR"

    def test_whitespace_only_text_is_empty(self):
        assert _code("text/plain", b"  \n\n \t ") == "EMPTY_DOCUMENT"

    def test_oversized_text_is_refused(self):
        assert _code("text/plain", b"word " * 100, max_text_chars=50) == "TEXT_TOO_LARGE"

    def test_unknown_mime_is_refused(self):
        assert _code("application/zip", b"PK") == "UNSUPPORTED_CONTENT"


class TestChunking:
    def test_short_text_is_a_single_chunk(self):
        chunks = chunk_sections([Section("One short paragraph.")], max_chars=200, overlap_chars=20)
        assert [(c.index, c.text) for c in chunks] == [(0, "One short paragraph.")]

    def test_chunks_respect_the_size_limit_and_prefer_paragraph_boundaries(self):
        paragraphs = [f"Paragraph {i} " + "word " * 12 for i in range(8)]
        chunks = chunk_sections(
            [Section("\n\n".join(p.strip() for p in paragraphs))], max_chars=200, overlap_chars=0
        )
        assert len(chunks) > 1
        assert all(len(c.text) <= 200 for c in chunks)
        # With no overlap, every paragraph appears whole in exactly one chunk.
        for paragraph in paragraphs:
            assert sum(paragraph.strip() in c.text for c in chunks) == 1

    def test_a_long_paragraph_is_split_at_sentence_ends(self):
        text = " ".join(f"Sentence number {i} ends here." for i in range(20))
        chunks = chunk_sections([Section(text)], max_chars=120, overlap_chars=0)
        assert all(len(c.text) <= 120 for c in chunks)
        assert all(c.text.endswith(".") for c in chunks)
        assert " ".join(c.text for c in chunks) == text

    def test_text_without_any_break_is_hard_split(self):
        chunks = chunk_sections([Section("x" * 450)], max_chars=200, overlap_chars=0)
        assert [len(c.text) for c in chunks] == [200, 200, 50]

    def test_overlap_repeats_the_end_of_the_previous_chunk(self):
        paragraphs = ["alpha " * 20, "bravo " * 20, "charlie " * 20]
        chunks = chunk_sections(
            [Section("\n\n".join(p.strip() for p in paragraphs))], max_chars=200, overlap_chars=30
        )
        assert len(chunks) == 3
        assert chunks[1].text.startswith("alpha") and "bravo" in chunks[1].text
        assert all(len(c.text) <= 200 for c in chunks)

    def test_chunks_never_span_sections_and_keep_page_and_heading(self):
        sections = [
            Section("Returns text. " * 20, page=1, heading="Returns"),
            Section("Delivery text.", page=2, heading="Delivery"),
        ]
        chunks = chunk_sections(sections, max_chars=120, overlap_chars=20)
        assert [c.index for c in chunks] == list(range(len(chunks)))
        assert chunks[-1].text == "Delivery text."
        assert (chunks[-1].page, chunks[-1].heading) == (2, "Delivery")
        assert all((c.page, c.heading) == (1, "Returns") for c in chunks[:-1])
        assert all("Delivery" not in c.text for c in chunks[:-1])

    def test_chunking_is_deterministic(self):
        section = Section(" ".join(f"Fact {i} is stated plainly here." for i in range(60)))
        first = chunk_sections([section], max_chars=180, overlap_chars=25)
        second = chunk_sections([section], max_chars=180, overlap_chars=25)
        assert first == second

    def test_overlap_must_be_smaller_than_the_chunk(self):
        with pytest.raises(ValueError, match="overlap"):
            chunk_sections([Section("text")], max_chars=100, overlap_chars=100)


class TestHashingEmbedder:
    def test_is_deterministic_normalised_and_of_the_declared_dimension(self):
        embedder = HashingEmbedder(dimension=16)
        first, second = embedder.embed(["refund policy", "refund policy"])
        assert first == second
        assert len(first) == 16
        assert abs(sum(v * v for v in first) - 1.0) < 1e-9

    def test_empty_text_still_gets_a_unit_vector(self):
        (vector,) = HashingEmbedder(dimension=8).embed([""])
        assert abs(sum(v * v for v in vector) - 1.0) < 1e-9
