package io.janus.audit;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="audit_events")
public class AuditEvent {
    @Id public UUID id;
    @Column(name="occurred_at", nullable=false) public Instant occurredAt;
    @Column(name="actor_type", nullable=false, length=24) public String actorType;
    @Column(name="actor_id", length=120) public String actorId;
    @Column(nullable=false, length=80) public String action;
    @Column(nullable=false, length=24) public String outcome;
    @Column(name="provider_id") public UUID providerId;
    @Column(name="request_method", length=12) public String requestMethod;
    @Column(name="request_path", length=500) public String requestPath;
    @Column(name="status_code") public Integer statusCode;
    @Column(length=500) public String detail;
    @Column(name="correlation_id", nullable=false, length=80) public String correlationId;
    @PrePersist void create(){ if(id==null) id=UUID.randomUUID(); if(occurredAt==null) occurredAt=Instant.now(); }
}
