package com.servora.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.servora.ticket.EscalationPolicy.Decision;
import com.servora.ticket.EscalationPolicy.Priority;
import com.servora.ticket.EscalationPolicy.Reason;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The ticket status machine and the escalation policy, without a database. */
class TicketRulesTest {

    @Test
    void ticketsMoveOnlyAlongTheWorkflow() {
        assertThat(TicketStatus.OPEN.canMoveTo(TicketStatus.IN_PROGRESS)).isTrue();
        assertThat(TicketStatus.OPEN.canMoveTo(TicketStatus.RESOLVED)).isTrue();
        assertThat(TicketStatus.IN_PROGRESS.canMoveTo(TicketStatus.WAITING_FOR_CUSTOMER)).isTrue();
        assertThat(TicketStatus.WAITING_FOR_CUSTOMER.canMoveTo(TicketStatus.IN_PROGRESS)).isTrue();
        assertThat(TicketStatus.WAITING_FOR_CUSTOMER.canMoveTo(TicketStatus.RESOLVED)).isTrue();
        assertThat(TicketStatus.RESOLVED.canMoveTo(TicketStatus.CLOSED)).isTrue();

        assertThat(TicketStatus.OPEN.canMoveTo(TicketStatus.WAITING_FOR_CUSTOMER)).isFalse();
        assertThat(TicketStatus.OPEN.canMoveTo(TicketStatus.CLOSED)).isFalse();
        assertThat(TicketStatus.RESOLVED.canMoveTo(TicketStatus.IN_PROGRESS)).isFalse();
        assertThat(TicketStatus.CLOSED.canMoveTo(TicketStatus.OPEN)).isFalse();
        for (TicketStatus status : TicketStatus.values()) {
            assertThat(status.canMoveTo(status)).as(status.name()).isFalse();
            assertThat(TicketStatus.CLOSED.canMoveTo(status)).isFalse();
        }
        assertThat(TicketStatus.RESOLVED.isActive()).isFalse();
        assertThat(TicketStatus.WAITING_FOR_CUSTOMER.isActive()).isTrue();
    }

    @Test
    void riskyMessagesGoStraightToAPerson() {
        assertThat(EscalationPolicy.forCustomerMessage("I will contact my lawyer about this"))
                .contains(new Decision(Reason.RISK_RULE, Priority.HIGH));
        assertThat(EscalationPolicy.forCustomerMessage("Someone made a FRAUDULENT payment")).isPresent();
        assertThat(EscalationPolicy.forCustomerMessage("I want a chargeback")).isPresent();
        // Whole words only.
        assertThat(EscalationPolicy.forCustomerMessage("Is the suede jacket in stock?")).isEmpty();
        assertThat(EscalationPolicy.forCustomerMessage("What is your refund policy?")).isEmpty();
        assertThat(EscalationPolicy.forCustomerMessage(null)).isEmpty();
    }

    @Test
    void aiRepliesEscalateOnlyForTheListedReasons() {
        assertThat(after("ANSWERED", "question", null, false)).isEmpty();
        assertThat(after("ANSWERED", "human_request", null, false)).contains(EscalationPolicy.customerRequest());
        assertThat(after("ESCALATED", "question", "conflicting_sources", false))
                .contains(new Decision(Reason.INSUFFICIENT_EVIDENCE, Priority.NORMAL));
        assertThat(after("ESCALATED", "question", "refund_request", false))
                .contains(new Decision(Reason.SENSITIVE_REQUEST, Priority.NORMAL));
        assertThat(after("ESCALATED", "complaint", "upset_customer", false))
                .contains(new Decision(Reason.SENSITIVE_REQUEST, Priority.HIGH));
        for (String failure : new String[] {"PROVIDER_UNAVAILABLE", "SERVICE_UNAVAILABLE", "REFUSED"}) {
            assertThat(after(failure, null, null, false)).contains(new Decision(Reason.SERVICE_ERROR, Priority.NORMAL));
        }
    }

    @Test
    void oneUnusableReplyOnlyOffersAPersonButTwoInARowEscalate() {
        for (String unusable : new String[] {"NO_EVIDENCE", "UNGROUNDED", "INVALID_OUTPUT", "INVALID_CITATION"}) {
            assertThat(after(unusable, null, null, false)).as(unusable).isEmpty();
            assertThat(after(unusable, null, null, true))
                    .contains(new Decision(Reason.REPEATED_MISUNDERSTANDING, Priority.NORMAL));
        }
        assertThat(after("ANSWERED", "question", null, true)).isEmpty();
        assertThat(EscalationPolicy.isUnusable("NO_EVIDENCE")).isTrue();
        assertThat(EscalationPolicy.isUnusable("ANSWERED")).isFalse();
        assertThat(EscalationPolicy.isUnusable(null)).isFalse();
    }

    private static Optional<Decision> after(String outcome, String intent, String reason, boolean previousUnusable) {
        return EscalationPolicy.afterAiReply(outcome, intent, reason, previousUnusable);
    }
}
