package io.janus.credentials;

import java.time.Instant;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AuthorizationStateRepository extends JpaRepository<AuthorizationState, String> {

    /**
     * Removes everything that has run out, whether or not anyone came back.
     *
     * <p>Most of these rows are abandoned rather than used: somebody opened a consent screen and
     * closed the tab. Sweeping is what keeps a table of short-lived secrets from becoming a long-lived
     * one.
     */
    @Modifying
    @Query("delete from AuthorizationState s where s.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
