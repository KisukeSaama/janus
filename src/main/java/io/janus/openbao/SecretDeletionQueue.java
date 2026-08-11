package io.janus.openbao;

import java.util.Collection;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

/** Couples a metadata deletion to a durable request to destroy the corresponding secret value. */
@Service
public class SecretDeletionQueue {
    private final PendingSecretDeletionRepository repository;
    private final SecretDeletionWorker worker;

    SecretDeletionQueue(PendingSecretDeletionRepository repository, SecretDeletionWorker worker) {
        this.repository = repository;
        this.worker = worker;
    }

    public void enqueue(String secretPath) {
        enqueueAll(java.util.List.of(secretPath));
    }

    public void enqueueAll(Collection<String> secretPaths) {
        if (secretPaths.isEmpty()) return;
        var pending =
                secretPaths.stream().distinct().map(PendingSecretDeletion::new).toList();
        repository.saveAll(pending);

        // Every production caller is transactional. Keeping the fallback makes this component safe
        // in maintenance code too, without ever attempting destruction before a live transaction
        // has committed the row that makes the request durable.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            worker.destroy(pending);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.destroy(pending);
            }
        });
    }
}
