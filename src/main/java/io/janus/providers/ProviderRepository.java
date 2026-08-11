package io.janus.providers;

import java.util.*;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/**
 * The {@code ...OwnedBy} finders are the console's, and take an owner that is never optional. The
 * others belong to paths with no signed-in person: the gateway resolving a slug for the application
 * that is calling, and a transfer moving a whole registry.
 */
public interface ProviderRepository extends JpaRepository<Provider, UUID> {

    /**
     * The gateway's own lookup. A slug names a destination only within its owner's namespace: two
     * people may each register {@code spotify}, and the caller's application decides which one is
     * meant. A slug also only names a destination while that destination is enabled.
     */
    Optional<Provider> findBySlugAndOwnerIdAndEnabledTrue(String slug, UUID ownerId);

    @Query("select p from Provider p join fetch p.owner where p.owner.id = :owner order by p.name")
    List<Provider> findAllOwnedBy(@Param("owner") UUID owner);

    @Query("select p from Provider p join fetch p.owner where p.id = :id and p.owner.id = :owner")
    Optional<Provider> findOwnedBy(@Param("id") UUID id, @Param("owner") UUID owner);

    boolean existsBySlugAndOwnerId(String slug, UUID ownerId);

    /** Unscoped, for handing one person's registry to another. */
    List<Provider> findAllByOwnerId(UUID ownerId);

    long countByOwnerId(UUID ownerId);
}
