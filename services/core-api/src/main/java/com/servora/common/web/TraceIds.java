package com.servora.common.web;

import org.slf4j.MDC;

public final class TraceIds {

    public static final String MDC_KEY = "trace_id";
    public static final String HEADER = "X-Request-Id";

    private TraceIds() {}

    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null ? "" : value;
    }
}
