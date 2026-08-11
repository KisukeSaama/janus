package io.janus.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.janus.accounts.AccessScope;
import io.janus.accounts.TestAccount;
import io.janus.shared.ApiExceptionHandler;

/**
 * How the journal is read: one owner's events, narrowed to a window, on screen or as a file. The
 * window matters more than it looks — an investigation names an hour, and an export that quietly
 * ignored the range would answer a different question than the table it was started from.
 */
class AuditAdminControllerTest {
    private static final Instant BEGINNING = Instant.EPOCH;
    private static final Instant FOREVER = Instant.parse("9999-12-31T23:59:59Z");

    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final AccessScope scope = mock(AccessScope.class);
    private final UUID owner = TestAccount.owner().getId();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(scope.ownerFilter()).thenReturn(owner);
        when(repository.search(any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(decision())));
        when(repository.searchAll(any(), any(), any(), any(), any())).thenReturn(List.of(decision()));
        mvc = MockMvcBuilders.standaloneSetup(new AuditAdminController(repository, scope))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    // --- reading a page ------------------------------------------------------

    @Test
    void readsTheWholeJournalWhenNoRangeIsNamed() throws Exception {
        mvc.perform(get("/api/admin/audit-events")).andExpect(status().isOk());

        verify(repository).search(eq(owner), isNull(), eq(BEGINNING), eq(FOREVER), any());
    }

    @Test
    void narrowsToTheRangeItIsGiven() throws Exception {
        mvc.perform(get("/api/admin/audit-events")
                        .param("from", "2026-08-01T08:00:00Z")
                        .param("to", "2026-08-01T09:00:00Z")
                        .param("outcome", "DENIED"))
                .andExpect(status().isOk());

        verify(repository)
                .search(
                        eq(owner),
                        eq("DENIED"),
                        eq(Instant.parse("2026-08-01T08:00:00Z")),
                        eq(Instant.parse("2026-08-01T09:00:00Z")),
                        any());
    }

    /** A blank outcome is the console's "all", not a filter on the empty string. */
    @Test
    void treatsABlankOutcomeAsNoFilter() throws Exception {
        mvc.perform(get("/api/admin/audit-events").param("outcome", " ")).andExpect(status().isOk());

        verify(repository).search(eq(owner), isNull(), any(), any(), any());
    }

    @Test
    void refusesARangeThatEndsBeforeItStarts() throws Exception {
        mvc.perform(get("/api/admin/audit-events")
                        .param("from", "2026-08-02T00:00:00Z")
                        .param("to", "2026-08-01T00:00:00Z"))
                .andExpect(status().isBadRequest());

        verify(repository, never()).search(any(), any(), any(), any(), any());
    }

    // --- exporting -----------------------------------------------------------

    @Test
    void exportsTheSameWindowAsAFile() throws Exception {
        var response = mvc.perform(get("/api/admin/audit-events/export")
                        .param("from", "2026-08-01T08:00:00Z")
                        .param("outcome", "ERROR"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"janus-activity-")))
                .andReturn()
                .getResponse();

        verify(repository)
                .searchAll(eq(owner), eq("ERROR"), eq(Instant.parse("2026-08-01T08:00:00Z")), eq(FOREVER), any());
        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .startsWith("\uFEFFoccurred_at,")
                .contains("/spotify/v1/me");
    }

    /** One export is bounded: the gateway writes to this stream on every proxied call. */
    @Test
    void boundsHowMuchOneExportCarries() throws Exception {
        var page = ArgumentCaptor.forClass(Pageable.class);

        mvc.perform(get("/api/admin/audit-events/export")).andExpect(status().isOk());

        verify(repository).searchAll(any(), any(), any(), any(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(AuditAdminController.MAX_EXPORT_ROWS);
        assertThat(page.getValue().getPageNumber()).isZero();
    }

    @Test
    void refusesToExportARangeThatEndsBeforeItStarts() throws Exception {
        mvc.perform(get("/api/admin/audit-events/export")
                        .param("from", "2026-08-02T00:00:00Z")
                        .param("to", "2026-08-01T00:00:00Z"))
                .andExpect(status().isBadRequest());

        verify(repository, never()).searchAll(any(), any(), any(), any(), any());
    }

    private AuditEvent decision() {
        var entry = new AuditEvent();
        entry.setActorType(AuditActor.APPLICATION.name());
        entry.setAction(AuditAction.GATEWAY_REQUEST.name());
        entry.setOutcome(AuditOutcome.SUCCESS.name());
        entry.setOwnerId(owner);
        entry.setRequestMethod("GET");
        entry.setRequestPath("/spotify/v1/me");
        entry.setStatusCode(200);
        entry.setCorrelationId("correlation");
        return entry;
    }
}
