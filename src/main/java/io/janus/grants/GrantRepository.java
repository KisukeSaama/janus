package io.janus.grants;

import io.janus.shared.Environment;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface GrantRepository extends JpaRepository<Grant, UUID> {
    @Query("select distinct g from Grant g left join fetch g.policies where g.application.id=:appId and g.provider.id=:providerId and g.environment=:environment and g.enabled=true")
    Optional<Grant> findActive(@Param("appId") UUID appId, @Param("providerId") UUID providerId, @Param("environment") Environment environment);
    @Query("select distinct g from Grant g left join fetch g.policies")
    List<Grant> findAllWithPolicies();
    boolean existsByCredentialId(UUID credentialId);
}
