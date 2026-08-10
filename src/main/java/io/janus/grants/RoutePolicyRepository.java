package io.janus.grants;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface RoutePolicyRepository extends JpaRepository<RoutePolicy, UUID> { }
