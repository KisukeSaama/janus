package io.janus.audit;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> { Page<AuditEvent> findAllByOrderByOccurredAtDesc(Pageable pageable); }
