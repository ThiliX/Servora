package com.servora.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Standard error envelope: {"error": {"code", "message", "trace_id", "details"}}. */
public record ApiError(Body error) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Body(String code, String message, String traceId, List<FieldDetail> details) {}

    public record FieldDetail(String field, String message) {}

    public static ApiError of(String code, String message, String traceId) {
        return new ApiError(new Body(code, message, traceId, List.of()));
    }

    public static ApiError of(String code, String message, String traceId, List<FieldDetail> details) {
        return new ApiError(new Body(code, message, traceId, details));
    }
}
