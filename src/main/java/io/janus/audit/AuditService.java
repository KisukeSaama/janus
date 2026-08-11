package io.janus.audit;

import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import io.janus.accounts.AccessScope;
import io.janus.shared.CorrelationIdFilter;

/**
 * Writes the operational event stream. Every record is committed in its own transaction so a denied
 * or failed operation is still audited when the surrounding transaction rolls back.
 *
 * <p>Callers name an action and an outcome from the enums rather than passing strings, and the one
 * event with enough fields to be mistakable — a gateway decision — is passed as a record, so its
 * arguments are named at the call site instead of merely ordered.
 *
 * <p>Gateway decisions are written off the request path. They are the only high-volume event, and a
 * proxied call should not pay for an insert before it can answer. Everything else is written inline:
 * those are rare, and they read the administrator's identity from the security context, which
 * belongs to the calling thread.
 */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private static final int ACTOR_ID_LIMIT = 120;
    private static final int PATH_LIMIT = 500;
    private static final int DETAIL_LIMIT = 500;

    private final AuditEventWriter writer;
    private final Executor gatewayWriter;
    private final AccessScope scope;

    AuditService(
            AuditEventWriter writer,
            @Qualifier("auditWriterExecutor") Executor auditWriterExecutor,
            AccessScope scope) {
        this.writer = writer;
        this.gatewayWriter = auditWriterExecutor;
        this.scope = scope;
    }

    /**
     * One gateway decision.
     *
     * @param detail what happened, for whoever reads the log; never any credential material
     */
    public record GatewayEvent(
            UUID applicationId,
            UUID ownerId,
            AuditOutcome outcome,
            UUID providerId,
            String method,
            String path,
            Integer status,
            String detail,
            String correlationId) {}

    /**
     * Records an administrative mutation attributed to the authenticated administrator. There is no
     * outcome parameter on purpose: a mutation that fails throws and its transaction rolls back, so
     * the only administrative change there is to record is one that happened.
     */
    public void recordAdmin(AuditAction action, UUID providerId, String detail) {
        var actor = scope.signedIn().orElse(null);
        // Nobody signed in means Janus acted on its own — a startup reconciliation, a scheduled
        // sweep. Naming an administrator that did not act would be worse than naming nobody.
        var entry = actor == null
                ? event(AuditActor.SYSTEM, null, action, AuditOutcome.SUCCESS, providerId, detail)
                : event(AuditActor.ADMIN, actor.id().toString(), action, AuditOutcome.SUCCESS, providerId, detail);
        if (actor != null) {
            entry.setActorLabel(actor.getUsername());
            entry.setOwnerId(actor.id());
        }
        entry.setCorrelationId(CorrelationIdFilter.current());
        writer.write(entry);
    }

    /**
     * Records a gateway decision attributed to the calling application. The event is built here, on
     * the request thread, and only its insert is handed off: the correlation identifier travels on
     * the record rather than in a thread local, so nothing about it depends on where it is written.
     */
    public void recordGateway(GatewayEvent decision) {
        String actorId = decision.applicationId() == null
                ? null
                : decision.applicationId().toString();
        var entry = event(
                AuditActor.APPLICATION,
                actorId,
                AuditAction.GATEWAY_REQUEST,
                decision.outcome(),
                decision.providerId(),
                decision.detail());
        entry.setRequestMethod(decision.method());
        entry.setRequestPath(truncate(decision.path(), PATH_LIMIT));
        entry.setStatusCode(decision.status());
        entry.setCorrelationId(decision.correlationId());
        // Whose activity this was, so the owner of the service can read back what it did — and only
        // that. There is no signed-in person on this thread to ask; the caller carries it.
        entry.setOwnerId(decision.ownerId());

        gatewayWriter.execute(() -> {
            try {
                writer.write(entry);
            } catch (RuntimeException ex) {
                // Nothing can be done from here, and an audit failure must not take a served request
                // down with it. It is logged loudly, with the identifier that ties it to the call.
                log.error("Failed to record a gateway audit event [correlationId={}]", decision.correlationId(), ex);
            }
        });
    }

    /** Records something Janus did on its own schedule, with no administrator or caller behind it. */
    public void recordSystem(AuditAction action, UUID providerId, String detail) {
        var entry = event(AuditActor.SYSTEM, null, action, AuditOutcome.SUCCESS, providerId, detail);
        entry.setCorrelationId(CorrelationIdFilter.current());
        writer.write(entry);
    }

    /** Records a refused sign-in, on either authenticated surface. */
    public void recordAuthenticationDenied(
            AuditActor actor, AuditAction action, String method, String path, int status, String detail) {
        var entry = event(actor, null, action, AuditOutcome.DENIED, null, detail);
        entry.setRequestMethod(method);
        entry.setRequestPath(truncate(path, PATH_LIMIT));
        entry.setStatusCode(status);
        entry.setCorrelationId(CorrelationIdFilter.current());
        writer.write(entry);
    }

    private AuditEvent event(
            AuditActor actor,
            String actorId,
            AuditAction action,
            AuditOutcome outcome,
            UUID providerId,
            String detail) {
        var entry = new AuditEvent();
        entry.setActorType(actor.name());
        entry.setActorId(truncate(actorId, ACTOR_ID_LIMIT));
        entry.setAction(action.name());
        entry.setOutcome(outcome.name());
        entry.setProviderId(providerId);
        entry.setDetail(truncate(detail, DETAIL_LIMIT));
        return entry;
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }
}
