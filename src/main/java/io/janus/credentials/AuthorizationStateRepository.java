package io.janus.credentials;

import java.time.Instant;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AuthorizationStateRepository extends JpaRepository<AuthorizationState, String> {

    /**
     * Consumes one state, and reports whether this call is the one that consumed it.
     *
     * <p>A delete rather than a flag, because there is nothing left to record: the row exists to be
     * read once. The count is what makes it single-use against two callbacks arriving together —
     * exactly one of them removes the row, and the other is refused rather than allowed to redeem a
     * code against a state somebody else is already redeeming.
     *
     * @return 1 when this call removed the row, 0 when it was already gone
     */
    @Modifying
    @Query("delete from AuthorizationState s where s.state = :state")
    int consume(@Param("state") String state);

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
