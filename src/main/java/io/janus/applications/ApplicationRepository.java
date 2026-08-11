package io.janus.applications;

import java.util.*;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/**
 * Two families of finder, and the names say which is which.
 *
 * <p>{@code ...OwnedBy} is what the console uses: an owner is not optional, so a query cannot
 * accidentally answer with somebody else's records. The unscoped ones are for the paths that have no
 * signed-in person to scope to — the gateway authenticating a key, a transfer moving a whole
 * registry — and must never be reached from a console service.
 */
public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    @Query("select a from Application a join fetch a.owner where a.owner.id = :owner order by a.name")
    List<Application> findAllOwnedBy(@Param("owner") UUID owner);

    @Query("select a from Application a join fetch a.owner where a.id = :id and a.owner.id = :owner")
    Optional<Application> findOwnedBy(@Param("id") UUID id, @Param("owner") UUID owner);

    boolean existsByOwnerIdAndName(UUID ownerId, String name);

    /**
     * The gateway's own lookup, which has no signed-in person to scope to. The owner is fetched with
     * it rather than left lazy: the filter runs outside any open session, and the principal it builds
     * carries the owner so provider slugs resolve in the right namespace.
     */
    @Query("select a from Application a join fetch a.owner left join fetch a.allowedOrigins where a.id = :id")
    Optional<Application> findByIdWithOwner(@Param("id") UUID id);

    /** Unscoped, for handing one person's registry to another. */
    List<Application> findAllByOwnerId(UUID ownerId);

    long countByOwnerId(UUID ownerId);
}
