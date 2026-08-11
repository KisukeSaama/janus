package io.janus.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.*;

import org.springframework.data.domain.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import io.janus.accounts.AccessScope;

@RestController
@RequestMapping("/api/admin/audit-events")
@Validated
public class AuditAdminController {
    private static final int MAX_PAGE_SIZE = 200;
    private final AuditEventRepository repository;
    private final AccessScope scope;

    public AuditAdminController(AuditEventRepository repository, AccessScope scope) {
        this.repository = repository;
        this.scope = scope;
    }

    public record Event(
            UUID id,
            Instant occurredAt,
            String actorType,
            String actorId,
            String actorLabel,
            String action,
            String outcome,
            UUID providerId,
            String requestMethod,
            String requestPath,
            Integer statusCode,
            String detail,
            String correlationId) {}
    /** Explicit envelope: serialising Spring's Page implementation directly is not a stable contract. */
    public record EventPage(List<Event> content, int page, int size, long totalElements, int totalPages) {}

    @GetMapping
    @Transactional(readOnly = true)
    public EventPage list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(required = false) String outcome) {
        var pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        UUID owner = scope.ownerFilter();
        Page<AuditEvent> found = outcome == null || outcome.isBlank()
                ? repository.findAllByOwnerIdOrderByOccurredAtDesc(owner, pageable)
                : repository.findAllByOwnerIdAndOutcomeOrderByOccurredAtDesc(owner, outcome, pageable);
        return new EventPage(
                found.getContent().stream().map(this::event).toList(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    private Event event(AuditEvent e) {
        return new Event(
                e.getId(),
                e.getOccurredAt(),
                e.getActorType(),
                e.getActorId(),
                e.getActorLabel(),
                e.getAction(),
                e.getOutcome(),
                e.getProviderId(),
                e.getRequestMethod(),
                e.getRequestPath(),
                e.getStatusCode(),
                e.getDetail(),
                e.getCorrelationId());
    }
}
