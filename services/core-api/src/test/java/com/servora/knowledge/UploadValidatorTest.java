package com.servora.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.servora.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UploadValidatorTest {

    private static final long LIMIT = 1024;
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TEXT = "Returns are accepted within 30 days.".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsTheThreeSupportedFormats() {
        assertThat(UploadValidator.validate("policy.pdf", PDF, LIMIT).mime()).isEqualTo("application/pdf");
        assertThat(UploadValidator.validate("policy.txt", TEXT, LIMIT).mime()).isEqualTo("text/plain");
        assertThat(UploadValidator.validate("FAQ.MD", TEXT, LIMIT).mime()).isEqualTo("text/markdown");
        assertThat(UploadValidator.validate("faq.markdown", TEXT, LIMIT).mime()).isEqualTo("text/markdown");
    }

    @Test
    void acceptsUnicodeText() {
        byte[] sinhala = "ආපසු ගෙවීම් දින 30ක් ඇතුළත".getBytes(StandardCharsets.UTF_8);
        assertThat(UploadValidator.validate("policy.txt", sinhala, LIMIT).mime()).isEqualTo("text/plain");
    }

    @Test
    void rejectsExtensionsThatAreNotOnTheAllowList() {
        for (String name : new String[] {"malware.exe", "page.html", "archive.zip", "noextension", "doc.pdf.exe"}) {
            assertRejected(name, TEXT, "UNSUPPORTED_FILE_TYPE");
        }
    }

    @Test
    void rejectsContentThatDoesNotMatchTheExtension() {
        byte[] executable = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00};
        assertRejected("invoice.pdf", executable, "CONTENT_MISMATCH");
        assertRejected("invoice.pdf", TEXT, "CONTENT_MISMATCH");
        // A binary renamed to .txt: contains NUL bytes or invalid UTF-8.
        assertRejected("notes.txt", executable, "CONTENT_MISMATCH");
        assertRejected("notes.md", new byte[] {(byte) 0xff, (byte) 0xfe, 'a'}, "CONTENT_MISMATCH");
    }

    @Test
    void rejectsEmptyAndOversizedFiles() {
        assertRejected("empty.txt", new byte[0], "EMPTY_FILE");
        assertRejected("big.txt", new byte[(int) LIMIT + 1], "FILE_TOO_LARGE");
    }

    @Test
    void keepsOnlyTheFinalPathSegmentOfTheFilename() {
        assertThat(UploadValidator.sanitizeFilename("../../etc/passwd.txt")).isEqualTo("passwd.txt");
        assertThat(UploadValidator.sanitizeFilename("C:\\Users\\me\\policy.pdf")).isEqualTo("policy.pdf");
        assertThat(UploadValidator.sanitizeFilename("re\u0000turns\n.txt")).isEqualTo("returns.txt");
        assertThatThrownBy(() -> UploadValidator.sanitizeFilename("..")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> UploadValidator.sanitizeFilename(null)).isInstanceOf(ApiException.class);
    }

    private static void assertRejected(String filename, byte[] content, String code) {
        assertThatThrownBy(() -> UploadValidator.validate(filename, content, LIMIT))
                .as(filename)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
