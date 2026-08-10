package io.janus.grants;

import io.janus.applications.Application;
import io.janus.credentials.Credential;
import io.janus.providers.Provider;
import io.janus.shared.Environment;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity @Table(name="grants")
public class Grant {
    @Id public UUID id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="application_id") public Application application;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="provider_id") public Provider provider;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="credential_id") public Credential credential;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=8) public Environment environment;
    @Column(nullable=false) public boolean enabled = true;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="updated_at", nullable=false) public Instant updatedAt;
    @OneToMany(mappedBy="grant", cascade=CascadeType.ALL, orphanRemoval=true) public Set<RoutePolicy> policies = new HashSet<>();
    @PrePersist void create(){ if(id==null) id=UUID.randomUUID(); createdAt=updatedAt=Instant.now(); }
    @PreUpdate void update(){ updatedAt=Instant.now(); }
}
