package io.janus.accounts;

import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.security.PasswordPolicy;
import io.janus.shared.NotFoundException;

/**
 * The people who may sign in, and what each of them is.
 *
 * <p>The rule the whole class turns on: an administrator appoints, a super administrator arbitrates.
 * An ADMIN creates accounts and may appoint another administrator, but cannot edit or remove one —
 * peers do not hold power over each other, or the first one to act wins. Only a SUPER_ADMIN manages
 * administrators, and only a SUPER_ADMIN can make another.
 *
 * <p>Two further invariants live here rather than in the database, because both are about the state
 * of the whole table rather than of one row: the deployment always keeps somebody able to administer
 * it, and nobody locks themselves out by removing or disabling their own account.
 */
@Service
public class AccountService {
    private final AccountRepository repository;
    private final PasswordEncoder encoder;
    private final AccessScope scope;
    private final RegistryTransfer registry;
    private final AuditService audit;

    public AccountService(
            AccountRepository repository,
            PasswordEncoder encoder,
            AccessScope scope,
            RegistryTransfer registry,
            AuditService audit) {
        this.repository = repository;
        this.encoder = encoder;
        this.scope = scope;
        this.registry = registry;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list() {
        return repository.findAllByOrderByUsernameAsc().stream()
                .map(AccountResponse::of)
                .toList();
    }

    @Transactional
    public AccountResponse create(AccountRequest request) {
        guardAppointment(request.role());
        if (repository.existsByUsername(request.username()))
            throw new IllegalArgumentException("That username is already taken");
        if (repository.existsByEmail(request.email()))
            throw new IllegalArgumentException("That email address is already registered");
        PasswordPolicy.check(request.username(), request.password());

        var account = new Account(
                request.username(),
                request.displayName(),
                request.email(),
                encoder.encode(request.password()),
                request.role(),
                request.enabled());
        repository.save(account);
        audit.recordAdmin(AuditAction.ACCOUNT_CREATED, null, account.getUsername());
        return AccountResponse.of(account);
    }

    /**
     * Applies everything the caller may change about a person. The username in the request is
     * ignored — see {@link Account} — and a blank password leaves the current one in place.
     */
    @Transactional
    public AccountResponse update(UUID id, AccountRequest request) {
        var account = require(id);
        guardManagement(account);
        if (account.getRole() != request.role()) guardAppointment(request.role());

        boolean losingLastSuperAdmin = account.getRole() == AccountRole.SUPER_ADMIN
                && (request.role() != AccountRole.SUPER_ADMIN || !request.enabled())
                && isLastEnabledSuperAdmin(account);
        if (losingLastSuperAdmin)
            throw new IllegalArgumentException(
                    "This is the last super administrator; appoint another one before changing this account");
        if (!request.enabled() && account.getId().equals(scope.accountId()))
            throw new IllegalArgumentException("An account cannot disable itself");
        if (!account.getEmail().equals(request.email()) && repository.existsByEmail(request.email()))
            throw new IllegalArgumentException("That email address is already registered");

        account.describe(request.displayName(), request.email(), request.enabled());
        account.assignRole(request.role());
        if (request.password() != null && !request.password().isBlank()) {
            PasswordPolicy.check(account.getUsername(), request.password());
            account.changePassword(encoder.encode(request.password()));
            audit.recordAdmin(AuditAction.ACCOUNT_PASSWORD_CHANGED, null, account.getUsername());
        }
        audit.recordAdmin(AuditAction.ACCOUNT_UPDATED, null, account.getUsername());
        return AccountResponse.of(account);
    }

    @Transactional
    public void delete(UUID id) {
        var account = require(id);
        guardManagement(account);
        if (account.getId().equals(scope.accountId()))
            throw new IllegalArgumentException("An account cannot delete itself");
        if (account.getRole() == AccountRole.SUPER_ADMIN && isLastEnabledSuperAdmin(account))
            throw new IllegalArgumentException(
                    "This is the last super administrator; appoint another one before deleting this account");

        // Said here rather than left to the foreign key, which would answer "conflicts with an
        // existing record" and leave the reader to guess which. What is holding the deletion up is
        // the whole point: somebody has to take these over, or they stop working with the account.
        var held = registry.holdings(account.getId());
        if (held.any())
            throw new IllegalArgumentException("This account still holds " + held.describe()
                    + "; hand them to another account first, or disable this one instead");

        repository.delete(account);
        audit.recordAdmin(AuditAction.ACCOUNT_DELETED, null, account.getUsername());
    }

    /**
     * Hands everything one account holds to another. Reserved to a super administrator: it moves
     * records between two registries, which is the one thing the separation otherwise forbids.
     */
    @Transactional
    public RegistryTransfer.Holdings transferRecords(UUID fromId, UUID toId) {
        if (scope.role() != AccountRole.SUPER_ADMIN)
            throw new IllegalArgumentException("Only a super administrator hands one account's records to another");
        return registry.transfer(require(fromId), require(toId));
    }

    /**
     * Whether the caller may act on this account at all. A person always manages their own; beyond
     * that, an administrator reaches ordinary accounts and a super administrator reaches everyone.
     */
    private void guardManagement(Account target) {
        var caller = scope.current();
        if (target.getId().equals(caller.id())) return;
        if (caller.role() == AccountRole.SUPER_ADMIN) return;
        if (caller.role() == AccountRole.ADMIN && target.getRole() == AccountRole.USER) return;
        throw new IllegalArgumentException("Only a super administrator manages administrator accounts");
    }

    /** Which roles the caller may hand out. Appointing a peer is allowed; appointing an arbiter is not. */
    private void guardAppointment(AccountRole role) {
        var caller = scope.current();
        if (caller.role() == AccountRole.SUPER_ADMIN) return;
        if (caller.role() == AccountRole.ADMIN && role != AccountRole.SUPER_ADMIN) return;
        throw new IllegalArgumentException("Only a super administrator appoints a super administrator");
    }

    /**
     * Whether removing this account's administration would leave nobody holding it. A disabled super
     * administrator does not count: it cannot sign in, so it cannot arbitrate anything.
     */
    private boolean isLastEnabledSuperAdmin(Account account) {
        return account.isEnabled() && repository.countByRoleAndEnabledTrue(AccountRole.SUPER_ADMIN) <= 1;
    }

    private Account require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Account not found"));
    }
}
