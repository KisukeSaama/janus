package io.janus.accounts;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class AccountUserDetailsServiceTest {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountUserDetailsService users = new AccountUserDetailsService(accounts);

    private static final Account ADA =
            new Account("adalovelace", "Ada Lovelace", "ada@example.com", "hash", AccountRole.USER, true);

    /** Case is how a login is typed, not part of who it names. */
    @Test
    void findsAnAccountWhateverCaseWasTyped() {
        when(accounts.findByUsername("adalovelace")).thenReturn(Optional.of(ADA));

        assertThat(users.loadUserByUsername("AdaLovelace").getUsername()).isEqualTo("adalovelace");
        assertThat(users.loadUserByUsername("ADALOVELACE").getUsername()).isEqualTo("adalovelace");
    }

    /** A login pasted from somewhere else arrives with the space around it. */
    @Test
    void surroundingSpaceIsNotPartOfTheLogin() {
        when(accounts.findByUsername("adalovelace")).thenReturn(Optional.of(ADA));

        assertThat(users.loadUserByUsername("  Adalovelace ").getUsername()).isEqualTo("adalovelace");
    }

    /** The stored form is asked for, so the lookup stays on the unique index rather than folding rows. */
    @Test
    void asksTheRepositoryForTheStoredFormRatherThanWhatWasTyped() {
        when(accounts.findByUsername(any())).thenReturn(Optional.of(ADA));

        users.loadUserByUsername("AdaLovelace");

        verify(accounts).findByUsername("adalovelace");
    }

    @Test
    void anUnknownLoginIsRefused() {
        when(accounts.findByUsername(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> users.loadUserByUsername("NoSuchPerson"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
