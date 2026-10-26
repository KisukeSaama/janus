package io.janus.openbao;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

/** The token outlives the deploy that created it only because something keeps renewing it. */
class TokenRenewalWorkerTest {

    @Test
    void renewsTheTokenOnEachPass() {
        var openBao = mock(OpenBaoClient.class);

        new TokenRenewalWorker(openBao).renew();

        verify(openBao).renewToken();
    }

    /** A failed renewal is logged and retried on the next pass, never thrown into the scheduler. */
    @Test
    void survivesAVaultThatRefusesTheRenewal() {
        var openBao = mock(OpenBaoClient.class);
        doThrow(new SecretStoreUnavailableException("OpenBao refused to renew its token (status 403)"))
                .when(openBao)
                .renewToken();
        var worker = new TokenRenewalWorker(openBao);

        worker.renew();
        worker.renew();

        verify(openBao, times(2)).renewToken();
    }
}
