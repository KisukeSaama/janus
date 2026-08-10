package io.janus.providers;

import io.janus.shared.Environment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ProviderRepository extends JpaRepository<Provider, UUID> {
    Optional<Provider> findBySlugAndEnvironmentAndEnabledTrue(String slug, Environment environment);
    boolean existsBySlugAndEnvironment(String slug, Environment environment);
}
