package io.janus.openbao;

import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SecretDeletionWorkerTest {
    private final PendingSecretDeletionRepository repository = Mockito.mock(PendingSecretDeletionRepository.class);
    private final OpenBaoClient openBao = Mockito.mock(OpenBaoClient.class);
    private final SecretDeletionWorker worker = new SecretDeletionWorker(repository, openBao);

    @Test
    void removesAQueueEntryOnlyAfterOpenBaoAcceptedTheDestruction() {
        var pending = new PendingSecretDeletion("janus/tmdb/credential");

        worker.destroy(List.of(pending));

        verify(openBao).delete(pending.getSecretPath());
        verify(repository).deleteById(pending.getId());
    }

    @Test
    void keepsAQueueEntryWhenOpenBaoIsUnavailable() {
        var pending = new PendingSecretDeletion("janus/tmdb/credential");
        doThrow(new IllegalStateException("OpenBao is unreachable"))
                .when(openBao)
                .delete(pending.getSecretPath());

        worker.destroy(List.of(pending));

        verify(repository, never()).deleteById(any());
    }
}
