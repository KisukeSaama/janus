package io.janus.accounts;

import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a login into the principal the rest of Janus reasons about.
 *
 * <p>A disabled account is returned rather than hidden: Spring Security refuses it itself, so the
 * refusal is the same shape whatever the reason, and no timing difference says which accounts exist.
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
        return accounts.findByUsername(username)
                .map(ConsoleUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("No such account"));
    }
}
