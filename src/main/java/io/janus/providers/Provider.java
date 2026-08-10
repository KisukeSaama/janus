package io.janus.providers;

import io.janus.shared.Environment;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="providers", uniqueConstraints=@UniqueConstraint(name="uq_provider_slug_env", columnNames={"slug","environment"}))
public class Provider {
    @Id public UUID id;
    @Column(nullable=false, length=120) public String name;
    @Column(nullable=false, length=80) public String slug;
    @Column(name="base_url", nullable=false, length=500) public String baseUrl;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=8) public Environment environment;
    @Column(nullable=false) public boolean enabled = true;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="updated_at", nullable=false) public Instant updatedAt;
    @PrePersist void create(){ if(id==null) id=UUID.randomUUID(); createdAt=updatedAt=Instant.now(); }
    @PreUpdate void update(){ updatedAt=Instant.now(); }
}
