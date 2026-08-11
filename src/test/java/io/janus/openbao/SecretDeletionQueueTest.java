package io.janus.openbao;

import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SecretDeletionQueueTest {
    private final PendingSecretDeletionRepository repository = Mockito.mock(PendingSecretDeletionRepository.class);
    private final SecretDeletionWorker worker = Mockito.mock(SecretDeletionWorker.class);
    private final SecretDeletionQueue queue = new SecretDeletionQueue(repository, worker);

    @BeforeEach
    void beginTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void endTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void destructionStartsOnlyAfterTheDatabaseCommit() {
        queue.enqueueAll(List.of("janus/tmdb/one", "janus/tmdb/two"));

        verify(repository).saveAll(anyList());
        verifyNoInteractions(worker);

        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(worker).destroy(argThat(pending -> pending.size() == 2));
    }
}
