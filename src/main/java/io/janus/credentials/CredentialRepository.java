package io.janus.credentials;

import java.time.Instant;
import java.util.*;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/**
 * Personal API activations and their secret metadata. The provider is global, so ownership lives on
 * the credential itself and every console query scopes by that column.
 */
public interface CredentialRepository extends JpaRepository<Credential, UUID> {

    @Query("select c from Credential c join fetch c.provider where c.ownerId = :owner order by c.name")
    List<Credential> findAllOwnedBy(@Param("owner") UUID owner);

    @Query("select c from Credential c join fetch c.provider where c.id = :id and c.ownerId = :owner")
    Optional<Credential> findOwnedBy(@Param("id") UUID id, @Param("owner") UUID owner);

    Optional<Credential> findByProviderIdAndOwnerId(UUID providerId, UUID ownerId);

    List<Credential> findAllByOwnerId(UUID ownerId);

    long countByOwnerId(UUID ownerId);

    @Query("select c.provider.id from Credential c where c.ownerId = :owner and c.provider.id in :providers")
    Set<UUID> findActivatedProviderIds(@Param("owner") UUID owner, @Param("providers") Collection<UUID> providers);

    /** Unscoped: the gateway and the expiry sweep, neither of which has a signed-in person. */
    @Query("select c from Credential c join fetch c.provider where c.id=:id")
    Optional<Credential> findByIdWithProvider(@Param("id") UUID id);

    boolean existsByProviderId(UUID providerId);

    /** All metadata rows removed with an API. The provider has already been ownership-scoped. */
    List<Credential> findAllByProviderId(UUID providerId);

    /**
     * Secrets whose recorded deadline has arrived or is near enough to speak about. A disabled
     * secret is left out: it authorizes nothing, so its expiry is not work anyone has to do.
     */
    @Query(
            """
           select c from Credential c join fetch c.provider
           where c.enabled = true and c.expiresAt is not null and c.expiresAt <= :horizon
           order by c.expiresAt""")
    List<Credential> findExpiringBy(@Param("horizon") Instant horizon);

    /**
     * The sweep's other read, for withdrawing announcements the current dates no longer support. It
     * fetches the owner for the same reason: the announcement it rebuilds carries one, and there is
     * no open session left by the time anything asks for it.
     */
    @Query("select c from Credential c join fetch c.provider where c.id in :ids")
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
