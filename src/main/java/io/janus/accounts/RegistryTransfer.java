package io.janus.accounts;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.janus.applications.ApplicationRepository;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.credentials.CredentialRepository;
import io.janus.gateway.TrafficPolicyRegistry;
import io.janus.security.ApiKeyCache;

/**
 * Moving one person's registry to another, and knowing whether they have one at all.
 *
 * <p>This exists because ownership is enforced by the database with {@code ON DELETE RESTRICT}
 * rather than a cascade: deleting a provider row from under Janus would leave its secret behind in
 * OpenBao, which nothing can then enumerate. Removing somebody therefore has to be preceded by a
 * decision about their records, and this is where that decision is carried out.
 *
 * <p>Applications and personal API activations move. The API catalogue itself is global and is
 * never transferred with an account.
 */
@Service
public class RegistryTransfer {
    private final ApplicationRepository applications;
    private final CredentialRepository credentials;
    private final ApiKeyCache keyCache;
    private final TrafficPolicyRegistry traffic;
    private final AuditService audit;

    public RegistryTransfer(
            ApplicationRepository applications,
            CredentialRepository credentials,
            ApiKeyCache keyCache,
            TrafficPolicyRegistry traffic,
            AuditService audit) {
        this.applications = applications;
        this.credentials = credentials;
        this.keyCache = keyCache;
        this.traffic = traffic;
        this.audit = audit;
    }

    /** What somebody holds, in the words the console uses for them. */
    public record Holdings(long services, long apis) {
        public boolean any() {
            return services > 0 || apis > 0;
        }

        public String describe() {
            return "%d service(s) and %d API(s)".formatted(services, apis);
        }
    }

    public Holdings holdings(UUID ownerId) {
        return new Holdings(applications.countByOwnerId(ownerId), credentials.countByOwnerId(ownerId));
    }

    /**
     * Hands everything one account holds to another, in one transaction.
     *
     * <p>Both caches have to be dropped as it goes. A verified key carries the owner it was issued
     * under, and the gateway resolves a provider slug inside that owner's namespace — an application
     * that changed hands would otherwise keep reaching the previous owner's destinations for as long
     * as the cached principal lives. Stored responses are addressed by provider, and a slug that now
     * means something else must not answer from what the old one fetched.
     */
    @Transactional
    public Holdings transfer(Account from, Account to) {
        if (from.getId().equals(to.getId()))
            throw new IllegalArgumentException("An account cannot be handed its own records");

        var moved = holdings(from.getId());
        var personalCredentials = credentials.findAllByOwnerId(from.getId());
        personalCredentials.forEach(credential -> {
            if (credentials
                    .findByProviderIdAndOwnerId(credential.getProvider().getId(), to.getId())
                    .isPresent())
                throw new IllegalArgumentException("The destination account already activated "
                        + credential.getProvider().getName());
        });
        applications.findAllByOwnerId(from.getId()).forEach(application -> {
            application.transferTo(to);
            keyCache.invalidate(application.getId());
        });
        personalCredentials.forEach(credential -> {
            credential.transferTo(to.getId());
            traffic.forgetCredential(credential.getId());
        });

        audit.recordAdmin(
                AuditAction.ACCOUNT_RECORDS_TRANSFERRED,
                null,
                "%s -> %s: %s".formatted(from.getUsername(), to.getUsername(), moved.describe()));
        return moved;
    }
}
