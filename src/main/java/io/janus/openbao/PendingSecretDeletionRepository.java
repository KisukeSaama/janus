package io.janus.openbao;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PendingSecretDeletionRepository extends JpaRepository<PendingSecretDeletion, UUID> {
    List<PendingSecretDeletion> findTop100ByOrderByCreatedAtAsc();
}
