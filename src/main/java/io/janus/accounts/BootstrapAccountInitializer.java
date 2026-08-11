package io.janus.accounts;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.janus.security.PasswordPolicy;

/**
 * Gives the bootstrap account its password, once.
 *
 * <p>The row itself is posted by {@code V7__accounts.sql}, because ownership becomes NOT NULL in the
 * migration straight after and the existing records need somebody to be adopted by. A migration
 * cannot compute a BCrypt hash, so it writes a placeholder that nothing can match and this runner
 * replaces it on the first start.
 *
 * <p>It runs exactly once in the life of a deployment. After that the password belongs to whoever
 * changed it from the console, and {@code janus.admin.password} is never read again — which is the
 * point: a configured password that keeps being re-applied is a password nobody can change.
 */
@Component
public class BootstrapAccountInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAccountInitializer.class);

    /** What the migration inserts. An address nobody reads, and therefore one worth replacing. */
    static final String PLACEHOLDER_EMAIL = "admin@localhost";

    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    private final String password;
    private final String email;

    public BootstrapAccountInitializer(
            AccountRepository accounts,
            PasswordEncoder encoder,
            @Value("${janus.admin.password}") String password,
            @Value("${janus.admin.email}") String email) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.password = password;
        this.email = email == null ? "" : email.trim();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var account = accounts.findById(Account.BOOTSTRAP_ID).orElse(null);
        if (account == null) return;
        adoptConfiguredEmail(account);
        adoptFixedUsername(account);
        if (!account.awaitingBootstrap()) return;

        // The production profile refuses to start on a weak value long before this runs; elsewhere a
        // placeholder is a development convenience, and saying so once is more useful than refusing.
        try {
            PasswordPolicy.check(Account.BOOTSTRAP_USERNAME, password);
        } catch (IllegalArgumentException ex) {
            log.warn(
                    "The bootstrap administrator password is weak ({}). Change it from the console before this"
                            + " deployment holds anything real.",
                    ex.getMessage());
        }

        account.changePassword(encoder.encode(password));
        log.info(
                "Bootstrap administrator '{}' is ready; janus.admin.password will not be read again",
                account.getUsername());
    }

    private void adoptFixedUsername(Account account) {
        if (Account.BOOTSTRAP_USERNAME.equals(account.getUsername())) return;
        if (accounts.existsByUsername(Account.BOOTSTRAP_USERNAME))
            throw new IllegalStateException("The username '" + Account.BOOTSTRAP_USERNAME
                    + "' is reserved for the bootstrap super-administrator but another account already uses it");
        account.rename(Account.BOOTSTRAP_USERNAME);
    }

    /**
     * Gives the bootstrap account a real address, and keeps giving it one until somebody sets one.
     *
     * <p>Unlike the password, this is not read once and then left alone: the migration writes an
     * address that goes nowhere, and expiry notices about this account's own secrets are sent to it.
     * So for as long as the row still carries that placeholder, configuration decides. The moment
     * anybody sets a real address — here or from the console — configuration stops being consulted,
     * and the value in the database is the one that stands.
     */
    private void adoptConfiguredEmail(Account account) {
        if (email.isEmpty() || email.equals(account.getEmail())) return;
        if (!PLACEHOLDER_EMAIL.equals(account.getEmail())) {
            log.debug("The bootstrap account already has an address of its own; janus.admin.email is not applied");
            return;
        }
        if (accounts.existsByEmail(email)) {
            log.warn("janus.admin.email is '{}', which another account already uses; it was not applied", email);
            return;
        }
        account.describe(account.getDisplayName(), email, account.isEnabled());
        log.info("The bootstrap administrator will receive expiry notices at {}", email);
    }
}
