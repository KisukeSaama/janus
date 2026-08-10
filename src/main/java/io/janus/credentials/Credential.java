package io.janus.credentials;

import io.janus.providers.Provider;
import io.janus.shared.Environment;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="credentials")
public class Credential {
    @Id public UUID id;
    @Column(nullable=false, length=120) public String name;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="provider_id") public Provider provider;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=8) public Environment environment;
    @Enumerated(EnumType.STRING) @Column(name="auth_type", nullable=false, length=24) public AuthType authType;
    @Column(name="header_name", length=100) public String headerName;
    @Column(name="secret_path", nullable=false, unique=true, length=500) public String secretPath;
    @Column(nullable=false) public boolean enabled = true;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="updated_at", nullable=false) public Instant updatedAt;
    @PrePersist void create(){ if(id==null) id=UUID.randomUUID(); createdAt=updatedAt=Instant.now(); }
    @PreUpdate void update(){ updatedAt=Instant.now(); }
}
