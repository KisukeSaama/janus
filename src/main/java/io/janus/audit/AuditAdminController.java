package io.janus.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.*;

import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import io.janus.accounts.AccessScope;

@RestController
@RequestMapping("/api/admin/audit-events")
@Validated
public class AuditAdminController {
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * How much of the journal one export may carry. The stream is high volume — every proxied call
     * writes to it — and a request that would serialise a year of gateway decisions into one response
     * is a request that takes the process down with it. The console narrows with the same filters the
     * table uses and says when a window is wider than this.
     */
    static final int MAX_EXPORT_ROWS = 10_000;

    /** The bounds used when the reader named none, so the query always has a window to read. */
    private static final Instant BEGINNING = Instant.EPOCH;

    private static final Instant FOREVER = Instant.parse("9999-12-31T23:59:59Z");

    /** Byte order mark, so a spreadsheet reads the file as UTF-8 rather than as its own default. */
    private static final String BOM = "\uFEFF";

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC);

    private final AuditEventRepository repository;
    private final AccessScope scope;

    public AuditAdminController(AuditEventRepository repository, AccessScope scope) {
        this.repository = repository;
        this.scope = scope;
    }

    public record Event(
            UUID id,
            Instant occurredAt,
            String actorType,
            String actorId,
            String actorLabel,
            String action,
            String outcome,
            UUID providerId,
            String requestMethod,
            String requestPath,
            Integer statusCode,
            String detail,
            String correlationId) {}
    /** Explicit envelope: serialising Spring's Page implementation directly is not a stable contract. */
    public record EventPage(List<Event> content, int page, int size, long totalElements, int totalPages) {}

    /**
     * A page of the journal.
     *
     * <p>{@code from} is inclusive and {@code to} is exclusive, which is what makes a range that ends
     * where the next one begins count every event exactly once — the same hour or day picked twice in
     * a row never shows an event on both sides of the boundary.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public EventPage list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        checkWindow(from, to);
        Page<AuditEvent> found = repository.search(
                scope.ownerFilter(),
                filter(outcome),
                since(from),
                until(to),
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
        return new EventPage(
                found.getContent().stream().map(this::event).toList(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    /** The same window as a file. Filters travel identically, so what is exported is what was read. */
    @GetMapping(value = "/export", produces = "text/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        checkWindow(from, to);
        var rows = repository.searchAll(
                scope.ownerFilter(), filter(outcome), since(from), until(to), PageRequest.of(0, MAX_EXPORT_ROWS));

        // The byte order mark is what stops a spreadsheet from opening a UTF-8 file as the platform
        // encoding, which is how an accented actor label turns into mojibake before anyone reads it.
        byte[] body = (BOM + AuditCsv.render(rows)).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("janus-activity-" + FILE_STAMP.format(Instant.now()) + ".csv")
                                .build()
                                .toString())
                .body(body);
    }

    /** A range that ends before it starts is a mistake worth naming rather than an empty answer. */
    private static void checkWindow(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to))
            throw new IllegalArgumentException("The range must start before it ends");
    }

    private static Instant since(Instant from) {
        return from == null ? BEGINNING : from;
    }

    private static Instant until(Instant to) {
        return to == null ? FOREVER : to;
    }

    private static String filter(String outcome) {
        return outcome == null || outcome.isBlank() ? null : outcome;
    }

    private Event event(AuditEvent e) {
        return new Event(
                e.getId(),
                e.getOccurredAt(),
                e.getActorType(),
                e.getActorId(),
                e.getActorLabel(),
                e.getAction(),
                e.getOutcome(),
                e.getProviderId(),
                e.getRequestMethod(),
                e.getRequestPath(),
                e.getStatusCode(),
                e.getDetail(),
                e.getCorrelationId());
    }
}
