package io.janus.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.mock.web.*;

/**
 * The identifier that ties a log line, an audit record and a response together.
 *
 * <p>A caller may supply its own, which is what makes tracing across services work — and also what
 * makes it a place to inject content into logs and headers. It is honoured only when it could not
 * carry anything but an identifier.
 */
class CorrelationIdFilterTest {
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    private MockHttpServletResponse pass(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest requestCarrying(String supplied) {
        var request = new MockHttpServletRequest("GET", "/gateway/spotify/v1");
        if (supplied != null) request.addHeader(CorrelationIdFilter.REQUEST_HEADER, supplied);
        return request;
    }

    @Test
    void mintsAnIdentifierForARequestThatBroughtNone() throws Exception {
        var request = requestCarrying(null);

        var response = pass(request);

        String assigned = response.getHeader(CorrelationIdFilter.RESPONSE_HEADER);
        assertThat(assigned).isNotBlank();
        assertThat(request.getAttribute(CorrelationIdFilter.ATTRIBUTE)).isEqualTo(assigned);
    }

    @Test
    void honoursAnIdentifierTheCallerSupplied() throws Exception {
        var response = pass(requestCarrying("order-42_abc.DEF"));

        assertThat(response.getHeader(CorrelationIdFilter.RESPONSE_HEADER)).isEqualTo("order-42_abc.DEF");
    }

    /**
     * Everything a supplied value could be abused for: a newline forges a log line, a control
     * character forges a header, and a long one fills the journal. Each is replaced rather than
     * refused — the request itself is perfectly valid.
     */
    @Test
    void replacesASuppliedIdentifierThatCouldCarryMoreThanAnIdentifier() throws Exception {
        for (String hostile : new String[] {
            "abc\r\nSet-Cookie: session=stolen", "abc\ndef", "abc def", "abc;drop", "<script>", "a".repeat(81), ""
        }) {
            var response = pass(requestCarrying(hostile));

            assertThat(response.getHeader(CorrelationIdFilter.RESPONSE_HEADER))
                    .describedAs("supplied %s", hostile)
                    .isNotEqualTo(hostile)
                    .doesNotContain("\n", "\r", " ");
        }
    }

    @Test
    void acceptsAnIdentifierRightUpToTheLengthLimit() throws Exception {
        String longest = "a".repeat(80);

        assertThat(pass(requestCarrying(longest)).getHeader(CorrelationIdFilter.RESPONSE_HEADER))
                .isEqualTo(longest);
    }

    /** Every log line written while the request runs carries it, and nothing after it does. */
    @Test
    void putsTheIdentifierInTheLoggingContextForTheLifeOfTheRequest() throws Exception {
        var request = requestCarrying("during-request");
        var seen = new String[1];

        filter.doFilter(
                request, new MockHttpServletResponse(), (req, res) -> seen[0] = MDC.get(CorrelationIdFilter.MDC_KEY));

        assertThat(seen[0]).isEqualTo("during-request");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    /** A thread that never served a request still gets an identifier rather than a null. */
    @Test
    void mintsOneWhenAskedOutsideAnyRequest() {
        assertThat(CorrelationIdFilter.current()).isNotBlank();
        assertThat(CorrelationIdFilter.current()).isNotEqualTo(CorrelationIdFilter.current());
    }
}
