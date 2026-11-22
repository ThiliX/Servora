package com.servora.support;

import com.servora.support.IntegrationTestBase.Member;
import com.servora.support.IntegrationTestBase.Session;
import java.util.UUID;

/**
 * The minimum two-tenant fixture: TechStore and HotelPro, each with an owner and
 * an agent, and no user in common.
 */
public record TwoTenants(
        UUID techStore, Session ownerA, Member agentA, UUID hotelPro, Session ownerB, Member agentB) {

    public static TwoTenants create(IntegrationTestBase test) {
        Session ownerA = test.newUser("owner-a");
        UUID techStore = test.createWorkspace(ownerA, "TechStore");
        Member agentA = test.addMember(ownerA, techStore, "agent-a", "AGENT");

        Session ownerB = test.newUser("owner-b");
        UUID hotelPro = test.createWorkspace(ownerB, "HotelPro");
        Member agentB = test.addMember(ownerB, hotelPro, "agent-b", "AGENT");

        return new TwoTenants(techStore, ownerA, agentA, hotelPro, ownerB, agentB);
    }
}
