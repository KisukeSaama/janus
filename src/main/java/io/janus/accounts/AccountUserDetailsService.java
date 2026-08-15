package io.janus.accounts;

import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a login into the principal the rest of Janus reasons about.
 *
 * <p>A disabled account is returned rather than hidden: Spring Security refuses it itself, so the
 * refusal is the same shape whatever the reason, and no timing difference says which accounts exist.
 *
 * <p>The login is normalised here rather than at each entry point, because this is where all of them
 * meet: the console sign-in and HTTP Basic both reach an account through this method. Normalising
 * the value looked up rather than making the query itself case-insensitive keeps the comparison on
 * the unique index, and keeps the stored form the only form — see {@link Account#normalise}.
 */
@Service
public class AccountUserDetailsService implements UserDetailsService {
    private final AccountRepository accounts;

    public AccountUserDetailsService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return accounts.findByUsername(Account.normalise(username))
                .map(ConsoleUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("No such account"));
    }
}
