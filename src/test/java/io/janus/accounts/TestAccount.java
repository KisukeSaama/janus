package io.janus.accounts;

/**
 * One owner to hang test fixtures on.
 *
 * <p>Every registry record now belongs to somebody, so nearly every test that builds an application
 * or a provider needs an account first. Building it here keeps that detail from being restated in a
 * dozen files, where it would look like part of what is being tested.
 */
public final class TestAccount {
    private TestAccount() {}

    public static Account owner() {
        return owner("owner");
    }

    public static Account owner(String username) {
        return new Account(username, "Owner", username + "@example.com", "hash", AccountRole.USER, true);
    }
}
