package com.servora.common.mail;

/** Outbound transactional email. M1 ships a development implementation only. */
public interface MailGateway {

    void sendEmailVerification(String toEmail, String verificationLink);

    void sendWorkspaceInvitation(String toEmail, String workspaceName, String invitationLink);
}
