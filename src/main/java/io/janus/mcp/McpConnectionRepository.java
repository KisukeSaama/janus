package io.janus.mcp;

import java.time.Instant;
import java.util.*;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface McpConnectionRepository extends JpaRepository<McpConnection, UUID> {

    Optional<McpConnection> findByAccessTokenHash(String accessTokenHash);

    Optional<McpConnection> findByRefreshTokenHash(String refreshTokenHash);

    Optional<McpConnection> findByPreviousRefreshHash(String previousRefreshHash);

    /** Scoped by name, like every console query: nobody lists another account's assistants. */
    @Query("select c from McpConnection c join fetch c.client where c.accountId = :owner order by c.authorizedAt desc")
    List<McpConnection> findAllOwnedBy(@Param("owner") UUID owner);

    @Query("select c from McpConnection c where c.id = :id and c.accountId = :owner")
    Optional<McpConnection> findOwnedBy(@Param("id") UUID id, @Param("owner") UUID owner);

    @Modifying
    @Query("delete from McpConnection c where c.refreshExpiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
