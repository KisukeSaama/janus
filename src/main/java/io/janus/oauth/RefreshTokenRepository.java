package io.janus.oauth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Drops a whole chain at once, which is what a replayed token costs its family. */
    @Modifying
    @Query("delete from RefreshToken t where t.familyId = :familyId")
    int deleteByFamilyId(@Param("familyId") UUID familyId);

    @Modifying
    @Query("delete from RefreshToken t where t.applicationId = :applicationId")
    int deleteByApplicationId(@Param("applicationId") UUID applicationId);

    /** Housekeeping: an expired row proves nothing and is never read again. */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
