package com.servora.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Users are global, not tenant-owned. Expose them to a tenant only through memberships. */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String normalizedEmail);
}
