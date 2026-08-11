package io.janus.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The exported file, which leaves Janus and is opened by something Janus does not control. What is
 * asserted here is that neither a comma nor a spreadsheet can change what a row says.
 */
class AuditCsvTest {

    @Test
    void namesEveryColumnOnTheFirstLine() {
        assertThat(AuditCsv.render(List.of()).lines().toList())
                .containsExactly("occurred_at,actor_type,actor_label,actor_id,action,outcome,provider_id,"
                        + "request_method,request_path,status_code,detail,correlation_id");
    }

    @Test
    void writesOneRowPerEvent() {
        String csv = AuditCsv.render(List.of(event("GET", "/spotify/v1/me"), event("POST", "/spotify/v1/play")));

        assertThat(csv.lines()).hasSize(3);
        assertThat(csv).contains("\"/spotify/v1/me\"").contains("\"GATEWAY_REQUEST\",\"SUCCESS\"");
    }

    /** A separator inside a value is what a quoted field is for; a quote inside one is doubled. */
    @Test
    void quotesWhatWouldOtherwiseSplitTheRow() {
        var entry = event("GET", "/x");
        entry.setDetail("cached, then \"revalidated\"");

        assertThat(AuditCsv.render(List.of(entry))).contains("\"cached, then \"\"revalidated\"\"\"");
    }

    /**
     * Paths and details come from callers. A spreadsheet reads a cell opening with an operator as a
     * formula, so the value is prefixed rather than handed over as something to evaluate.
     */
    @Test
    void refusesToHandASpreadsheetAFormula() {
        var entry = event("GET", "/x");
        entry.setDetail("=1+1");

        assertThat(AuditCsv.render(List.of(entry))).contains("\"'=1+1\"");
    }

    @Test
    void leavesAnAbsentValueEmptyRatherThanQuotingNothing() {
        var entry = event(null, null);

        assertThat(AuditCsv.render(List.of(entry))).contains(",,");
    }

    private static AuditEvent event(String method, String path) {
        var entry = new AuditEvent();
        entry.setActorType(AuditActor.APPLICATION.name());
        entry.setAction(AuditAction.GATEWAY_REQUEST.name());
        entry.setOutcome(AuditOutcome.SUCCESS.name());
        entry.setOwnerId(UUID.randomUUID());
        entry.setRequestMethod(method);
        entry.setRequestPath(path);
        entry.setCorrelationId("correlation");
        return entry;
    }
}
