package io.janus.grants;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface GrantRepository extends JpaRepository<Grant, UUID> {
    /** Fetches every association the gateway reads, so no lazy proxy is resolved outside the query. */
    @Query(
            """
           select g from Grant g
             join fetch g.credential
           where g.application.id=:appId and g.provider.id=:providerId and g.enabled=true
           """)
    Optional<Grant> findActive(@Param("appId") UUID appId, @Param("providerId") UUID providerId);

    /**
     * An access rule has no owner column of its own: it is the statement "this service may call that
     * API", and both sides belong to the same person — {@code Grant.bind} refuses to tie together
     * two owners. Scoping through the application is therefore enough, and cannot drift.
     */
    @Query(
            """
           select g from Grant g
             join fetch g.application a
             join fetch g.provider
             join fetch g.credential
           where a.owner.id = :owner
           """)
    List<Grant> findAllOwnedBy(@Param("owner") UUID owner);

    @Query(
            """
           select g from Grant g
             join fetch g.application a
             join fetch g.provider
             join fetch g.credential
           where g.id=:id and a.owner.id = :owner
           """)
    Optional<Grant> findOwnedBy(@Param("id") UUID id, @Param("owner") UUID owner);

    boolean existsByCredentialId(UUID credentialId);

    List<Grant> findAllByCredentialId(UUID credentialId);

    boolean existsByApplicationId(UUID applicationId);

    boolean existsByProviderId(UUID providerId);

    /** Every connection that must stop when its API is removed. */
    List<Grant> findAllByProviderId(UUID providerId);
}
