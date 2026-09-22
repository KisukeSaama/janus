package io.janus.mcp;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface McpAuthorizationRepository extends JpaRepository<McpAuthorization, String> {

    Optional<McpAuthorization> findByCodeHash(String codeHash);

    /**
     * Removes one row and reports whether this call is the one that removed it, so two requests
     * redeeming the same code cannot both succeed. The same reasoning as for a connected account's
     * state, in {@code AuthorizationStateRepository#consume}.
     */
    @Modifying
    @Query("delete from McpAuthorization a where a.id = :id")
    int consume(@Param("id") String id);

    @Modifying
    @Query("delete from McpAuthorization a where a.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
