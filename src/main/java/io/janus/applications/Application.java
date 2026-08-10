package io.janus.applications;

import io.janus.shared.Environment;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="applications")
public class Application {
    @Id public UUID id;
    @Column(nullable=false, length=120) public String name;
    @Column(length=500) public String description;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=8) public Environment environment;
    @Column(name="api_key_hash", nullable=false, length=100) public String apiKeyHash;
    @Column(nullable=false) public boolean enabled = true;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="updated_at", nullable=false) public Instant updatedAt;
    @PrePersist void create(){ if(id==null) id=UUID.randomUUID(); createdAt=updatedAt=Instant.now(); }
    @PreUpdate void update(){ updatedAt=Instant.now(); }
}
