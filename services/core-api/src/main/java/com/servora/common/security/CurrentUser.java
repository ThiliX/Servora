package com.servora.common.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

public final class CurrentUser {

    private CurrentUser() {}

    /** The authenticated user's ID, taken from the verified access token subject. */
    public static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
