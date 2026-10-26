package io.janus.openbao;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the application's vault token alive between deploys.
 *
 * <p>The token's period is bounded by the token mount's maximum TTL, which defaults to 32 days. Left
 * to the deploy alone, a deployment nobody redeploys for a month loses every secret at once. Twice a
 * day leaves a wide margin under any period worth configuring, and runs once at startup so a restart
 * after a long outage renews before anything else is asked of the vault.
 */
@Service
class TokenRenewalWorker {
    private static final Logger log = LoggerFactory.getLogger(TokenRenewalWorker.class);

    private final OpenBaoClient openBao;

    TokenRenewalWorker(OpenBaoClient openBao) {
        this.openBao = openBao;
    }

    @Scheduled(
            initialDelayString = "${janus.openbao.token-renewal-initial-delay-millis:30000}",
            fixedDelayString = "${janus.openbao.token-renewal-millis:43200000}")
    void renew() {
        try {
            openBao.renewToken();
        } catch (RuntimeException ex) {
            // A token that can no longer be renewed is one the deploy's bootstrap has to recreate.
            // Said at ERROR every pass, so it is visible long before the period runs out.
            log.error("Could not renew the OpenBao token: {}", ex.getMessage());
        }
    }
}
