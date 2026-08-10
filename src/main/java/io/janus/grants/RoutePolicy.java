package io.janus.grants;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="route_policies")
public class RoutePolicy {
    @Id public UUID id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="grant_id") public Grant grant;
    @Column(name="http_method", nullable=false, length=12) public String httpMethod;
    @Column(name="path_pattern", nullable=false, length=300) public String pathPattern;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @PrePersist void create(){ if(id==null) id=UUID.randomUUID(); createdAt=Instant.now(); }
}
