package io.janus.oauth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Retires a token, and only if nobody has retired it already.
     *
     * <p>The condition is the whole point, and the reason rotation is not simply a field assignment
     * on the entity. Reading a row, finding it unspent and writing it back leaves a window between
     * the read and the write in which a second request holding the same value reads the same answer:
     * both pass, both are issued a successor, and reuse detection is defeated by arriving twice at
     * once rather than twice in a row. Here the database decides, and exactly one of the two updates
     * a row.
     *
     * @return 1 when this call is the one that retired it, 0 when it had already been spent
     */
    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    int markUsed(@Param("id") UUID id, @Param("now") Instant now);

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
