package com.servora.common.error;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes an unexpected exception for the log without its messages. Exception messages can carry
 * data: a database error, for example, may quote the row values that violated a constraint. The
 * description keeps what is needed to find the fault: the exception types along the cause chain
 * and the first frame in Servora's own code. The trace id on the log line links it to the request.
 */
public final class SafeErrors {

    private static final int MAX_CAUSES = 5;

    private SafeErrors() {}

    public static String describe(Throwable error) {
        List<String> types = new ArrayList<>();
        Throwable current = error;
        while (current != null && types.size() < MAX_CAUSES) {
            types.add(current.getClass().getName());
            current = current.getCause() == current ? null : current.getCause();
        }
        return String.join(" <- ", types) + firstOwnFrame(error);
    }

    private static String firstOwnFrame(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (frame.getClassName().startsWith("com.servora.")) {
                    return " at " + frame.getClassName() + "." + frame.getMethodName() + "(" + frame.getFileName() + ":"
                            + frame.getLineNumber() + ")";
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return "";
    }
}
