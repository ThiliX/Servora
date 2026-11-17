package com.servora.privacy;

import com.servora.common.security.CurrentUser;
import com.servora.privacy.DataService.Retention;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class DataController {

    private final DataService data;

    public DataController(DataService data) {
        this.data = data;
    }

    @PutMapping("/retention")
    Retention setRetention(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @RequestBody Retention request) {
        return data.setRetention(CurrentUser.id(jwt), workspaceId, request.conversationRetentionDays());
    }

    @DeleteMapping("/conversations/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteConversation(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID conversationId) {
        data.deleteConversation(CurrentUser.id(jwt), workspaceId, conversationId);
    }

    @GetMapping("/export")
    ResponseEntity<Map<String, Object>> export(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        Map<String, Object> body = data.export(CurrentUser.id(jwt), workspaceId);
        String filename = "servora-export-" + LocalDate.now(ZoneOffset.UTC) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
