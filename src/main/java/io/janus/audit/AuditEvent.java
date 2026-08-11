package io.janus.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

/** Append-only operational event. Never carries credential material. */
@Entity
@Table(name = "audit_events")
public class AuditEvent {
    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_type", nullable = false, length = 24)
    private String actorType;

    @Column(name = "actor_id", length = 120)
    private String actorId;

    /**
     * The actor's readable name at the moment it acted. Copied rather than joined, under the rule the
     * notifications table already follows: an entry says what was true when it was written, and a
     * person renamed afterwards does not rewrite what the journal shows about last month.
     */
    @Column(name = "actor_label", length = 120)
    private String actorLabel;

    /** Whose records this event is about, which is what a developer is allowed to read back. */
    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(nullable = false, length = 24)
    private String outcome;

    @Column(name = "provider_id")
    private UUID providerId;

    @Column(name = "request_method", length = 12)
    private String requestMethod;

    @Column(name = "request_path", length = 500)
    private String requestPath;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(length = 500)
    private String detail;

    @Column(name = "correlation_id", nullable = false, length = 80)
    private String correlationId;

    /**
     * Stamped when the event happens, not when it is written. Gateway events are inserted off the
     * request path, and a {@code @PrePersist} timestamp would record the moment the queue drained
     * instead of the moment the decision was made.
     */
    public AuditEvent() {
        this.id = UUID.randomUUID();
        this.occurredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public void setActorLabel(String actorLabel) {
        this.actorLabel = actorLabel;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public void setProviderId(UUID providerId) {
        this.providerId = providerId;
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public void setRequestMethod(String requestMethod) {
        this.requestMethod = requestMethod;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public void setRequestPath(String requestPath) {
        this.requestPath = requestPath;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
