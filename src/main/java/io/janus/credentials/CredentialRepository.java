package io.janus.credentials;

import java.time.Instant;
import java.util.*;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/**
 * A secret has no owner column of its own: it is reached through its provider, which it can never
 * change and from which {@code secret_path} was derived once. Scoping therefore joins the provider's
 * owner, which cannot drift from the destination the secret actually belongs to.
 */
public interface CredentialRepository extends JpaRepository<Credential, UUID> {

    @Query("select c from Credential c join fetch c.provider p where p.owner.id = :owner order by c.name")
    List<Credential> findAllOwnedBy(@Param("owner") UUID owner);

    @Query("select c from Credential c join fetch c.provider p where c.id = :id and p.owner.id = :owner")
    Optional<Credential> findOwnedBy(@Param("id") UUID id, @Param("owner") UUID owner);

    /** Unscoped: the gateway and the expiry sweep, neither of which has a signed-in person. */
    @Query("select c from Credential c join fetch c.provider where c.id=:id")
    Optional<Credential> findByIdWithProvider(@Param("id") UUID id);

    boolean existsByProviderId(UUID providerId);

    /**
     * Secrets whose recorded deadline has arrived or is near enough to speak about. A disabled
     * secret is left out: it authorizes nothing, so its expiry is not work anyone has to do.
     */
    @Query(
            """
           select c from Credential c join fetch c.provider p join fetch p.owner
           where c.enabled = true and c.expiresAt is not null and c.expiresAt <= :horizon
           order by c.expiresAt""")
    List<Credential> findExpiringBy(@Param("horizon") Instant horizon);

    /**
     * The sweep's other read, for withdrawing announcements the current dates no longer support. It
     * fetches the owner for the same reason: the announcement it rebuilds carries one, and there is
     * no open session left by the time anything asks for it.
     */
    @Query("select c from Credential c join fetch c.provider p join fetch p.owner where c.id in :ids")
    List<Credential> findAllWithOwner(@Param("ids") Collection<UUID> ids);

    /**
     * Claims a stage for one credential, and answers 1 only for the caller that actually moved it.
     * Two instances sweeping on the same schedule therefore announce once between them rather than
     * twice, without either of them holding a lock.
     */
    @Modifying
    @Query(
            """
           update Credential c set c.expiryStageNotified = :stage
           where c.id = :id and (c.expiryStageNotified is null or c.expiryStageNotified <> :stage)""")
    int claimExpiryStage(@Param("id") UUID id, @Param("stage") ExpiryStage stage);

    /** Gives a stage back, so a withdrawn announcement can be made again if it becomes true again. */
    @Modifying
    @Query("update Credential c set c.expiryStageNotified = null where c.id in :ids")
    int releaseExpiryStages(@Param("ids") Collection<UUID> ids);
}
