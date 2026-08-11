package io.janus.audit;

import java.util.List;
import java.util.Objects;

/**
 * The journal as a file, for whoever has to read it somewhere other than the console — a compliance
 * request, a spreadsheet, a ticket attached to an incident.
 *
 * <p>Two details are deliberate. Rows are separated by CRLF and quotes are doubled inside a quoted
 * field, which is what RFC 4180 asks for and what every spreadsheet expects. And a cell that would
 * otherwise open with an operator is prefixed with an apostrophe: paths and details come from the
 * outside, and a spreadsheet reads a leading {@code =} as a formula rather than as text.
 */
final class AuditCsv {
    private static final String SEPARATOR = ",";
    private static final String NEWLINE = "\r\n";
    private static final String HEADER =
            "occurred_at,actor_type,actor_label,actor_id,action,outcome,provider_id,request_method,request_path,status_code,detail,correlation_id";

    private AuditCsv() {}

    static String render(List<AuditEvent> events) {
        var csv = new StringBuilder(HEADER).append(NEWLINE);
        for (AuditEvent e : events) {
            csv.append(String.join(
                            SEPARATOR,
                            cell(
                                    e.getOccurredAt() == null
                                            ? null
                                            : e.getOccurredAt().toString()),
                            cell(e.getActorType()),
                            cell(e.getActorLabel()),
                            cell(e.getActorId()),
                            cell(e.getAction()),
                            cell(e.getOutcome()),
                            cell(Objects.toString(e.getProviderId(), null)),
                            cell(e.getRequestMethod()),
                            cell(e.getRequestPath()),
                            cell(Objects.toString(e.getStatusCode(), null)),
                            cell(e.getDetail()),
                            cell(e.getCorrelationId())))
                    .append(NEWLINE);
        }
        return csv.toString();
    }

    /** One field: neutralised against a spreadsheet reading it as a formula, then quoted. */
    private static String cell(String value) {
        if (value == null || value.isEmpty()) return "";
        String text = startsFormula(value) ? "'" + value : value;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static boolean startsFormula(String value) {
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }
}
