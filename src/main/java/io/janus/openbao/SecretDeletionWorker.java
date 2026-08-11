package io.janus.openbao;

import java.util.Collection;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Destroys queued values now when possible, and keeps retrying when the vault is unavailable. */
@Service
class SecretDeletionWorker {
    private static final Logger log = LoggerFactory.getLogger(SecretDeletionWorker.class);

    private final PendingSecretDeletionRepository repository;
    private final OpenBaoClient openBao;

    SecretDeletionWorker(PendingSecretDeletionRepository repository, OpenBaoClient openBao) {
        this.repository = repository;
        this.openBao = openBao;
    }

    @Scheduled(fixedDelayString = "${janus.openbao.secret-deletion-retry-millis:60000}")
    void retryPending() {
        destroy(repository.findTop100ByOrderByCreatedAtAsc());
    }

    void destroy(Collection<PendingSecretDeletion> pending) {
        for (var deletion : pending) {
            try {
                openBao.delete(deletion.getSecretPath());
                repository.deleteById(deletion.getId());
            } catch (RuntimeException ex) {
                // The database no longer points at this value, so retaining the queue row is the
                // recoverable state. The scheduled pass will try it again without lying to the
                // caller whose registry deletion has already committed.
                log.error("Failed to destroy queued OpenBao secret {}; it will be retried", deletion.getId(), ex);
            }
        }
    }
}
