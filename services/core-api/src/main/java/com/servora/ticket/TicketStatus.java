package com.servora.ticket;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Ticket workflow. Separate from the conversation's control mode: the ticket tracks the work, the
 * control mode decides who may reply.
 *
 * <pre>
 * OPEN -> IN_PROGRESS <-> WAITING_FOR_CUSTOMER
 *   \________\_______________\-> RESOLVED -> CLOSED
 * </pre>
 */
public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    WAITING_FOR_CUSTOMER,
    RESOLVED,
    CLOSED;

    private static final Map<TicketStatus, Set<TicketStatus>> NEXT = Map.of(
            OPEN, EnumSet.of(IN_PROGRESS, RESOLVED),
            IN_PROGRESS, EnumSet.of(WAITING_FOR_CUSTOMER, RESOLVED),
            WAITING_FOR_CUSTOMER, EnumSet.of(IN_PROGRESS, RESOLVED),
            RESOLVED, EnumSet.of(CLOSED),
            CLOSED, EnumSet.noneOf(TicketStatus.class));

    public boolean canMoveTo(TicketStatus target) {
        return NEXT.get(this).contains(target);
    }

    /** Still needs work from the team. */
    public boolean isActive() {
        return this == OPEN || this == IN_PROGRESS || this == WAITING_FOR_CUSTOMER;
    }
}
