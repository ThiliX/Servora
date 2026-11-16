package com.servora.knowledge;

import com.servora.common.error.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Decides whether an uploaded file is acceptable, from its bytes rather than from anything the
 * browser claims. The extension must be on the allow-list and the content must actually be that
 * kind of file.
 */
public final class UploadValidator {

    public record DetectedFile(String filename, String mime) {}

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_FILENAME_LENGTH = 200;

    private UploadValidator() {}

    public static DetectedFile validate(String submittedFilename, byte[] content, long maxBytes) {
        String filename = sanitizeFilename(submittedFilename);
        if (content.length == 0) {
            throw ApiException.badRequest("EMPTY_FILE", "The file is empty.");
        }
        if (content.length > maxBytes) {
            throw new ApiException(
                    HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "The file is larger than the upload limit.");
        }
        String extension = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "";
        String mime =
                switch (extension) {
                    case "pdf" -> "application/pdf";
                    case "txt" -> "text/plain";
                    case "md", "markdown" -> "text/markdown";
                    default -> throw new ApiException(
                            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            "UNSUPPORTED_FILE_TYPE",
                            "Only PDF, TXT and Markdown files can be uploaded.");
                };
        boolean contentMatches = mime.equals("application/pdf") ? startsWithPdfMagic(content) : isPlainText(content);
        if (!contentMatches) {
            throw ApiException.badRequest(
                    "CONTENT_MISMATCH", "The file content does not match its type. Upload a real PDF or text file.");
        }
        return new DetectedFile(filename, mime);
    }

    /** Keeps only the final path segment and drops control characters. Used for display, never as a storage path. */
    static String sanitizeFilename(String submitted) {
        String name = submitted == null ? "" : submitted;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        if (name.length() > MAX_FILENAME_LENGTH) {
            name = name.substring(name.length() - MAX_FILENAME_LENGTH);
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw ApiException.badRequest("INVALID_FILENAME", "The file needs a name.");
        }
        return name;
    }

    private static boolean startsWithPdfMagic(byte[] content) {
        if (content.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (content[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /** Text must be valid UTF-8 and contain no NUL bytes, which rules out binaries renamed to .txt. */
    private static boolean isPlainText(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
