package io.janus.accounts;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AccountTest {

    private static Account account() {
        return new Account("ada", "Ada Lovelace", "ada@example.com", "first-hash", AccountRole.USER, true);
    }

    @Test
    void changingThePasswordMovesTheChangeTimestamp() throws Exception {
        var account = account();
        var before = account.getPasswordChangedAt();
        Thread.sleep(2);

        account.changePassword("second-hash");

        assertThat(account.getPasswordHash()).isEqualTo("second-hash");
        assertThat(account.getPasswordChangedAt()).isAfter(before);
    }

    /** The login names the actor on every entry already written; editing a person must not rewrite it. */
    @Test
    void theUsernameIsNotSomethingAnEditCanChange() {
        var account = account();
        account.describe("Ada, renamed", "ada2@example.com", false);

        assertThat(account.getUsername()).isEqualTo("ada");
        assertThat(account.getDisplayName()).isEqualTo("Ada, renamed");
        assertThat(account.getEmail()).isEqualTo("ada2@example.com");
        assertThat(account.isEnabled()).isFalse();
    }

    /** An account posted by the migration cannot be signed in to until the reconciler has run. */
    @Test
    void onlyThePlaceholderHashCountsAsAwaitingBootstrap() {
        var placeholder = new Account("admin", "Administrator", "admin@localhost", "!", AccountRole.ADMIN, true);
        assertThat(placeholder.awaitingBootstrap()).isTrue();

        placeholder.changePassword("$2a$10$realbcrypthashvalue");
        assertThat(placeholder.awaitingBootstrap()).isFalse();
        assertThat(account().awaitingBootstrap()).isFalse();
    }
}
