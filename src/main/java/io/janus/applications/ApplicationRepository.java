package io.janus.applications;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<Application, UUID> { }
