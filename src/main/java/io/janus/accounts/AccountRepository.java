package io.janus.accounts;

import java.time.Instant;
import java.util.*;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByUsername(String username);

    List<Account> findAllByOrderByUsernameAsc();

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /** Guards the invariant that a deployment always keeps somebody able to administer it. */
    long countByRoleAndEnabledTrue(AccountRole role);

    /**
     * Signing in is something that happens to an account, not a change somebody made to it, so it is
     * written as a targeted update: {@code updated_at} keeps meaning "when was this last edited",
     * and a console left open does not rewrite a row on every reconnection.
     */
    @Modifying
    @Query("update Account a set a.lastSignedInAt = :at where a.id = :id")
    void markSignedIn(@Param("id") UUID id, @Param("at") Instant at);
}
