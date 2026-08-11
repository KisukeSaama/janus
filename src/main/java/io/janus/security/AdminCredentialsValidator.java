package io.janus.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.janus.accounts.Account;

/**
 * Refuses to start a production instance with a placeholder administrator password. Janus fronts
 * every third-party credential in the deployment, so a guessable console password is not a warning
 * condition — it is a failed deployment.
 *
 * <p>What counts as acceptable is {@link PasswordPolicy}, shared with every account created from the
 * console. This class only decides what a failure means here: there is nobody to answer at startup,
 * so the deployment stops instead.
 *
 * <p>Since accounts live in the database, the value it guards is the <em>bootstrap</em> password —
 * read once, by {@code BootstrapAccountInitializer}, and never again after somebody has changed it.
 */
@Component
@Profile("prod")
public class AdminCredentialsValidator implements InitializingBean {
    private final String password;

    public AdminCredentialsValidator(@Value("${janus.admin.password}") String password) {
        this.password = password;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            PasswordPolicy.check(Account.BOOTSTRAP_USERNAME, password);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("janus.admin.password is not usable in production: " + ex.getMessage(), ex);
        }
    }
}
