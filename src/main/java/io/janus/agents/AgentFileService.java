package io.janus.agents;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.janus.accounts.AccessScope;
import io.janus.applications.ApplicationRepository;
import io.janus.grants.Grant;
import io.janus.grants.GrantRepository;
import io.janus.shared.NotFoundException;

/**
 * Writes {@code JANUS.md} for one of the caller's services, from what the gateway would do right now.
 *
 * <p>Only what would be forwarded is listed. An API behind a disabled grant, service, destination or
 * credential is one the file would promise and the gateway would refuse — checked in the order the
 * gateway checks them, like the console's own view of a connection.
 *
 * <p>"Right now" is why the file carries the day it was written: it lands in a repository and is read
 * on every task thereafter, long after a grant may have been withdrawn, and an agent that cannot see
 * how old its instructions are has no reason to suspect them.
 */
@Service
public class AgentFileService {
    private final ApplicationRepository applications;
    private final GrantRepository grants;
    private final AccessScope scope;
    private final String origin;

    public AgentFileService(
            ApplicationRepository applications,
            GrantRepository grants,
            AccessScope scope,
            @Value("${janus.public-url:http://localhost:8080}") String publicUrl) {
        this.applications = applications;
        this.grants = grants;
        this.scope = scope;
        this.origin = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    /** @param apiCount how many APIs the file lists, which is what the console says about it */
    public record Rendered(String fileName, String content, int apiCount) {}

    /** The file for one service, or the placeholder when none is named. */
    @Transactional(readOnly = true)
    public Rendered render(UUID applicationId) {
        var today = LocalDate.now(ZoneOffset.UTC);
        if (applicationId == null) return rendered(AgentFile.placeholder(origin, today));
        var owner = scope.ownerFilter();
        var application = applications
                .findOwnedBy(applicationId, owner)
                .orElseThrow(() -> new NotFoundException("Application not found"));
        var apis = grants.findAllOwnedBy(owner).stream()
                .filter(grant -> grant.getApplication().getId().equals(applicationId))
                .filter(AgentFileService::live)
                .map(AgentFileService::api)
                .sorted(Comparator.comparing(AgentFile.Api::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return rendered(
                new AgentFile.Target(origin, application.getName(), applicationId.toString(), apis, today));
    }

    private static Rendered rendered(AgentFile.Target target) {
        return new Rendered(
                AgentFile.FILE_NAME, AgentFile.render(target), target.apis().size());
    }

    private static boolean live(Grant grant) {
        return grant.isEnabled()
                && grant.getApplication().isEnabled()
                && grant.getProvider().isEnabled()
                && grant.getCredential().isEnabled();
    }

    private static AgentFile.Api api(Grant grant) {
        var provider = grant.getProvider();
        var grantScope = grant.getScope();
        return new AgentFile.Api(
                provider.getName(),
                provider.getSlug(),
                provider.isNormalizeJson(),
                grantScope.pathPrefix(),
                grantScope.orderedMethods(),
                grant.getCredential().getAuthorizedAt() != null,
                provider.getGraphqlPath(),
                grantScope.orderedOperations().stream().map(Enum::name).toList(),
                grantScope.orderedRootFields());
    }
}
