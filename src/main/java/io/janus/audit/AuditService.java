package io.janus.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Service
public class AuditService {
    private final AuditEventRepository repository;
    public AuditService(AuditEventRepository repository) { this.repository = repository; }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(String actorType, String actorId, String action, String outcome, UUID providerId,
                       String method, String path, Integer status, String detail, String correlationId) {
        var event = new AuditEvent();
        event.actorType=actorType; event.actorId=actorId; event.action=action; event.outcome=outcome;
        event.providerId=providerId; event.requestMethod=method; event.requestPath=truncate(path,500);
        event.statusCode=status; event.detail=truncate(detail,500); event.correlationId=correlationId;
        repository.save(event);
    }
    private String truncate(String value, int max) { return value == null ? null : value.substring(0, Math.min(value.length(), max)); }
}
