package io.janus.testing;

import io.janus.accounts.Account;
import io.janus.accounts.TestAccount;
import io.janus.applications.Application;
import io.janus.credentials.AuthType;
import io.janus.credentials.Credential;
import io.janus.grants.Grant;
import io.janus.providers.Provider;

/**
 * The registry records a gateway test needs before it can say anything about the gateway.
 *
 * <p>A grant only exists once an owner, a service, a destination and a stored secret all do, and
 * three of those four have rules of their own about how they may be combined. Restating that chain
 * in every test file would make each one read as if the chain were the subject.
 */
public final class Fixtures {
    private Fixtures() {}

    public static Account owner() {
        return TestAccount.owner();
    }

    public static Provider provider(Account owner) {
        return provider(owner, "spotify");
    }

    public static Provider provider(Account owner, String slug) {
        return new Provider(
                owner, slug, slug, "https://api.example.com", true, new Provider.TrafficPolicy(true, 0, 0, 0));
    }

    public static Credential credential(Provider provider) {
        return credential(provider, AuthType.BEARER);
    }

    public static Credential credential(Provider provider, AuthType authType) {
        return new Credential(provider, "key", Credential.Strategy.of(authType), null, true);
    }

    public static Application application(Account owner) {
        return new Application(owner, "checkout", "Checkout service", true, "hash");
    }

    public static Grant grant(Application application, Provider provider, Credential credential) {
        return new Grant(application, provider, credential);
    }
}
