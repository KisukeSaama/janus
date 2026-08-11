package io.janus.providers;

import java.util.*;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/**
 * Shared API catalogue queries. Slugs are global, while activation state is joined separately from
 * the signed-in account's credentials.
 */
public interface ProviderRepository extends JpaRepository<Provider, UUID> {

    /**
     * The gateway's own lookup. A global slug names a destination only while it is enabled; the
     * application grant decides whether the caller may use it.
     */
    Optional<Provider> findBySlugAndEnabledTrue(String slug);

    @Query(
            value =
                    "select p from Provider p where lower(p.name) like lower(concat('%', :query, '%')) or lower(p.slug) like lower(concat('%', :query, '%'))",
            countQuery =
                    "select count(p) from Provider p where lower(p.name) like lower(concat('%', :query, '%')) or lower(p.slug) like lower(concat('%', :query, '%'))")
    Page<Provider> search(@Param("query") String query, Pageable pageable);

    boolean existsBySlug(String slug);

    /** @deprecated APIs are global; retained while older integrations migrate. */
    @Deprecated
    default Optional<Provider> findOwnedBy(UUID id, UUID ignoredOwner) {
        return findById(id);
    }

    /** @deprecated APIs are global; retained while older integrations migrate. */
    @Deprecated
    default boolean existsBySlugAndOwnerId(String slug, UUID ignoredOwner) {
        return existsBySlug(slug);
    }

    /** @deprecated APIs are global; retained while older integrations migrate. */
    @Deprecated
    default List<Provider> findAllOwnedBy(UUID ignoredOwner) {
        return findAll(Sort.by("name"));
    }

    /** Global APIs are no longer transferred with accounts. */
    default List<Provider> findAllByOwnerId(UUID ignoredOwner) {
        return List.of();
    }

    default long countByOwnerId(UUID ignoredOwner) {
        return 0;
    }
}
