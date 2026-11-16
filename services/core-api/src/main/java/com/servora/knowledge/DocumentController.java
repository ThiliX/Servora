package com.servora.knowledge;

import com.servora.common.error.ApiException;
import com.servora.common.security.CurrentUser;
import com.servora.common.web.PageResponse;
import com.servora.knowledge.DocumentService.DocumentView;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/knowledge/documents")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    /** Accepts the file and returns at once; processing continues in the background. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    DocumentView upload(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @RequestPart("file") MultipartFile file) {
        return documents.upload(CurrentUser.id(jwt), workspaceId, file.getOriginalFilename(), bytesOf(file));
    }

    @PostMapping(path = "/{documentId}/replace", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    DocumentView replace(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            @RequestPart("file") MultipartFile file) {
        return documents.replace(
                CurrentUser.id(jwt), workspaceId, documentId, file.getOriginalFilename(), bytesOf(file));
    }

    @GetMapping
    PageResponse<DocumentView> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return documents.list(CurrentUser.id(jwt), workspaceId, page, size);
    }

    @GetMapping("/{documentId}")
    DocumentView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID documentId) {
        return documents.get(CurrentUser.id(jwt), workspaceId, documentId);
    }

    @PostMapping("/{documentId}/archive")
    DocumentView archive(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID documentId) {
        return documents.archive(CurrentUser.id(jwt), workspaceId, documentId);
    }

    @PostMapping("/{documentId}/reprocess")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DocumentView reprocess(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID documentId) {
        return documents.reprocess(CurrentUser.id(jwt), workspaceId, documentId);
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID documentId) {
        documents.delete(CurrentUser.id(jwt), workspaceId, documentId);
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("UPLOAD_FAILED", "The file could not be read. Try uploading it again.");
        }
    }
}
