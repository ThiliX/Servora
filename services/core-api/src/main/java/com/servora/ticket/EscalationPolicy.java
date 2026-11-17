package com.servora.ticket;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides when a conversation goes to a person. Pure, so every rule is unit tested.
 *
 * <ul>
 *   <li><b>Customer request:</b> the customer asks for a person, or the model reports that intent.</li>
 *   <li><b>Insufficient evidence:</b> the model reports conflicting sources.</li>
 *   <li><b>Sensitive request:</b> the model asks for a person for any other reason; a complaint raises the priority.</li>
 *   <li><b>Repeated misunderstanding:</b> two unusable replies in a row. One alone only offers a person.</li>
 *   <li><b>Service error:</b> the model or the AI service failed or declined.</li>
 *   <li><b>Risk rule:</b> the customer's message mentions legal, fraud or data-protection matters.</li>
 * </ul>
 *
 * Sentiment is not used: it may one day adjust priority, but must never trigger escalation alone.
 */
public final class EscalationPolicy {

    public enum Reason {
        CUSTOMER_REQUEST,
        INSUFFICIENT_EVIDENCE,
        SENSITIVE_REQUEST,
        REPEATED_MISUNDERSTANDING,
        SERVICE_ERROR,
        RISK_RULE,
        AGENT_INITIATED
    }

    public enum Priority {
        LOW,
        NORMAL,
        HIGH,
        URGENT
    }

    public record Decision(Reason reason, Priority priority) {}

    /** Outcomes where the customer got the fallback message because the reply was unusable. */
    static final Set<String> UNUSABLE = Set.of("NO_EVIDENCE", "UNGROUNDED", "INVALID_OUTPUT", "INVALID_CITATION");

    private static final Set<String> FAILED = Set.of("PROVIDER_UNAVAILABLE", "SERVICE_UNAVAILABLE", "REFUSED", "OVER_LIMIT");

    // English only for the MVP. Matched on whole words, case-insensitively.
    private static final Pattern RISK = Pattern.compile(
            "\\b(lawyer|attorney|solicitor|legal action|lawsuit|sue|suing|court|chargeback|fraud|fraudulent|scam"
                    + "|stolen|police|data breach|gdpr|data protection)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private EscalationPolicy() {}

    public static Decision customerRequest() {
        return new Decision(Reason.CUSTOMER_REQUEST, Priority.NORMAL);
    }

    public static Decision agentInitiated() {
        return new Decision(Reason.AGENT_INITIATED, Priority.NORMAL);
    }

    /** Checked before the AI is asked: a risky message goes straight to a person. */
    public static Optional<Decision> forCustomerMessage(String text) {
        return text != null && RISK.matcher(text).find()
                ? Optional.of(new Decision(Reason.RISK_RULE, Priority.HIGH))
                : Optional.empty();
    }

    /**
     * Checked after an AI reply was delivered.
     *
     * @param previousReplyUnusable whether the assistant's previous reply in this conversation was unusable
     */
    public static Optional<Decision> afterAiReply(
            String outcome, String intent, String escalationReason, boolean previousReplyUnusable) {
        if ("human_request".equals(intent)) {
            return Optional.of(customerRequest());
        }
        if ("ESCALATED".equals(outcome)) {
            if (escalationReason != null && escalationReason.contains("conflicting")) {
                return Optional.of(new Decision(Reason.INSUFFICIENT_EVIDENCE, Priority.NORMAL));
            }
            Priority priority = "complaint".equals(intent) ? Priority.HIGH : Priority.NORMAL;
            return Optional.of(new Decision(Reason.SENSITIVE_REQUEST, priority));
        }
        if (FAILED.contains(outcome)) {
            return Optional.of(new Decision(Reason.SERVICE_ERROR, Priority.NORMAL));
        }
        if (UNUSABLE.contains(outcome) && previousReplyUnusable) {
            return Optional.of(new Decision(Reason.REPEATED_MISUNDERSTANDING, Priority.NORMAL));
        }
        return Optional.empty();
    }

    public static boolean isUnusable(String outcome) {
        return outcome != null && UNUSABLE.contains(outcome);
    }
}
