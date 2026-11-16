package com.servora.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MailConfig {

    @Bean
    @ConditionalOnMissingBean(MailGateway.class)
    MailGateway mailGateway(@Value("${servora.mail.log-links:false}") boolean logLinks) {
        return new LoggingMailGateway(logLinks);
    }

    /**
     * Development stand-in for an email provider. It never sends anything. Links contain secret
     * tokens, so they are written to the log only when {@code servora.mail.log-links} is enabled.
     */
    static final class LoggingMailGateway implements MailGateway {

        private static final Logger log = LoggerFactory.getLogger(LoggingMailGateway.class);

        private final boolean logLinks;

        LoggingMailGateway(boolean logLinks) {
            this.logLinks = logLinks;
        }

        @Override
        public void sendEmailVerification(String toEmail, String verificationLink) {
            if (logLinks) {
                log.info("DEV MAIL verification link: {}", verificationLink);
            } else {
                log.info("Verification email not sent: no mail provider is configured");
            }
        }

        @Override
        public void sendWorkspaceInvitation(String toEmail, String workspaceName, String invitationLink) {
            if (logLinks) {
                log.info("DEV MAIL invitation link: {}", invitationLink);
            } else {
                log.info("Invitation email not sent: no mail provider is configured");
            }
        }
    }
}
