package io.janus.mcp;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface McpClientRepository extends JpaRepository<McpClient, UUID> {

    /**
     * Forgets clients that registered and were never let in by anybody.
     *
     * <p>Registration is open to anyone, which is what the protocol asks for, so most rows written
     * here by something other than an assistant somebody is setting up are exactly these.
     */
    @Modifying
    @Query("""
            delete from McpClient c where c.createdAt < :before
              and not exists (select 1 from McpConnection n where n.client = c)
              and not exists (select 1 from McpAuthorization a where a.client = c)""")
    int deleteUnusedBefore(@Param("before") Instant before);
}
