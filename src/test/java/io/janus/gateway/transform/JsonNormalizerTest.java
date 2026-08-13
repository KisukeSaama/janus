package io.janus.gateway.transform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import io.janus.gateway.GatewayTrafficProperties;

/**
 * What a caller receives once a destination is asked to answer in JSON.
 *
 * <p>Two properties are asserted throughout, because both are what a client depends on. The shape
 * must follow from the document rather than from its data — a list of one and a list of two produce
 * the same kind of thing — and a conversion must never be a way for the request to fail: every
 * refusal below returns the upstream's own bytes with a reason attached.
 */
class JsonNormalizerTest {

    private static final GatewayTrafficProperties PROPERTIES = properties(true, 2_097_152);

    private final JsonNormalizer normalizer = new JsonNormalizer(new ObjectMapper(), PROPERTIES);

    private static GatewayTrafficProperties properties(boolean enabled, int maxBytes) {
        return new GatewayTrafficProperties(
                new GatewayTrafficProperties.Cache(true, 100, 1_000_000, 10_000_000, 300),
                new GatewayTrafficProperties.Throttle(2000, 300),
                new GatewayTrafficProperties.Retry(2, 200, 2000),
                new GatewayTrafficProperties.Authorization(true, 10, 100),
                new GatewayTrafficProperties.Transform(enabled, maxBytes));
    }

    // --- building one call --------------------------------------------------

    private static HttpHeaders responded(String contentType) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        return headers;
    }

    private static HttpHeaders asked(String accept) {
        var headers = new HttpHeaders();
        if (accept != null) headers.set(HttpHeaders.ACCEPT, accept);
        return headers;
    }

    private JsonNormalizer.Outcome convert(String body, String contentType) {
        return convert(body, contentType, ArrayPaths.NONE, asked(null));
    }

    private JsonNormalizer.Outcome convert(String body, String contentType, ArrayPaths arrays, HttpHeaders request) {
        return normalizer.normalize(body.getBytes(StandardCharsets.UTF_8), responded(contentType), request, arrays);
    }

    private static String text(JsonNormalizer.Outcome outcome) {
        return new String(outcome.body(), StandardCharsets.UTF_8);
    }

    // --- XML ----------------------------------------------------------------

    @Test
    void turnsAttributesIntoPrefixedKeys() {
        var outcome = convert("<MediaContainer size=\"6\" title1=\"Plex Library\"/>", MediaType.APPLICATION_XML_VALUE);

        assertThat(outcome.converted()).isTrue();
        assertThat(outcome.note()).isEqualTo("xml->json");
        assertThat(text(outcome)).isEqualTo("{\"MediaContainer\":{\"@size\":\"6\",\"@title1\":\"Plex Library\"}}");
    }

    @Test
    void nestsChildElements() {
        var outcome = convert(
                "<MediaContainer><Directory key=\"4\"><Location path=\"/movies\"/></Directory></MediaContainer>",
                MediaType.APPLICATION_XML_VALUE);

        assertThat(text(outcome))
                .isEqualTo(
                        "{\"MediaContainer\":{\"Directory\":{\"@key\":\"4\",\"Location\":{\"@path\":\"/movies\"}}}}");
    }

    @Test
    void collectsARepeatedElementIntoAnArray() {
        var outcome = convert(
                "<MediaContainer><Directory key=\"4\"/><Directory key=\"6\"/></MediaContainer>",
                MediaType.APPLICATION_XML_VALUE);

        assertThat(text(outcome)).isEqualTo("{\"MediaContainer\":{\"Directory\":[{\"@key\":\"4\"},{\"@key\":\"6\"}]}}");
    }

    /**
     * The reason {@link ArrayPaths} exists. Without a declaration these two documents differ in kind
     * rather than in length, and a client written against the second breaks on the first.
     */
    @Test
    void keepsADeclaredElementAnArrayEvenWhenItAppearsOnce() {
        var arrays = ArrayPaths.parse("MediaContainer.Directory");

        var one = convert(
                "<MediaContainer><Directory key=\"4\"/></MediaContainer>",
                MediaType.APPLICATION_XML_VALUE,
                arrays,
                asked(null));
        var two = convert(
                "<MediaContainer><Directory key=\"4\"/><Directory key=\"6\"/></MediaContainer>",
                MediaType.APPLICATION_XML_VALUE,
                arrays,
                asked(null));

        assertThat(text(one)).isEqualTo("{\"MediaContainer\":{\"Directory\":[{\"@key\":\"4\"}]}}");
        assertThat(text(two)).isEqualTo("{\"MediaContainer\":{\"Directory\":[{\"@key\":\"4\"},{\"@key\":\"6\"}]}}");
    }

    @Test
    void appliesABareNameAtEveryDepth() {
        var outcome = convert(
                "<MediaContainer><Directory><Location path=\"/movies\"/></Directory></MediaContainer>",
                MediaType.APPLICATION_XML_VALUE,
                ArrayPaths.parse("Directory, Location"),
                asked(null));

        assertThat(text(outcome))
                .isEqualTo("{\"MediaContainer\":{\"Directory\":[{\"Location\":[{\"@path\":\"/movies\"}]}]}}");
    }

    @Test
    void readsAnElementWithNothingButTextAsAString() {
        assertThat(text(convert("<root><path>/movies</path></root>", MediaType.APPLICATION_XML_VALUE)))
                .isEqualTo("{\"root\":{\"path\":\"/movies\"}}");
    }

    @Test
    void keepsTextBesideAttributesUnderTextKey() {
        assertThat(text(convert("<root><title lang=\"fr\">Films</title></root>", MediaType.APPLICATION_XML_VALUE)))
                .isEqualTo("{\"root\":{\"title\":{\"@lang\":\"fr\",\"#text\":\"Films\"}}}");
    }

    /** An identifier that survives conversion only because nothing here tries to type it. */
    @Test
    void leavesEveryValueAString() {
        assertThat(text(convert("<root id=\"0123\" size=\"6\" on=\"true\"/>", MediaType.APPLICATION_XML_VALUE)))
                .isEqualTo("{\"root\":{\"@id\":\"0123\",\"@size\":\"6\",\"@on\":\"true\"}}");
    }

    /** What makes a SOAP response readable without the caller knowing the envelope's prefixes. */
    @Test
    void dropsNamespacePrefixes() {
        String soap = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body><Result>ok</Result></soap:Body></soap:Envelope>";

        assertThat(text(convert(soap, "application/soap+xml")))
                .isEqualTo("{\"Envelope\":{\"Body\":{\"Result\":\"ok\"}}}");
    }

    @Test
    void readsFeedsThroughTheXmlSuffix() {
        var outcome =
                convert("<rss version=\"2.0\"><channel><title>Feed</title></channel></rss>", "application/rss+xml");

        assertThat(outcome.converted()).isTrue();
        assertThat(text(outcome)).isEqualTo("{\"rss\":{\"@version\":\"2.0\",\"channel\":{\"title\":\"Feed\"}}}");
    }

    /**
     * The one refusal that is about the process rather than the caller: a gateway allowed to reach
     * private addresses is exactly what an external entity is worth aiming at.
     */
    @Test
    void refusesToResolveAnExternalEntity() {
        String attack = "<?xml version=\"1.0\"?><!DOCTYPE root [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<root>&xxe;</root>";

        var outcome = convert(attack, MediaType.APPLICATION_XML_VALUE);

        assertThat(outcome.converted()).isFalse();
        assertThat(text(outcome)).isEqualTo(attack);
        assertThat(outcome.note()).startsWith("none (");
    }

    @Test
    void returnsTheOriginalWhenTheXmlIsMalformed() {
        String broken = "<MediaContainer><Directory></MediaContainer>";

        var outcome = convert(broken, MediaType.APPLICATION_XML_VALUE);

        assertThat(outcome.converted()).isFalse();
        assertThat(text(outcome)).isEqualTo(broken);
        assertThat(outcome.note()).contains("XML document could not be parsed");
    }

    // --- form-encoded and NDJSON --------------------------------------------

    @Test
    void readsAFormBody() {
        var outcome = convert("oauth_token=abc&expires=3600&empty", MediaType.APPLICATION_FORM_URLENCODED_VALUE);

        assertThat(outcome.note()).isEqualTo("form->json");
        assertThat(text(outcome)).isEqualTo("{\"oauth_token\":\"abc\",\"expires\":\"3600\",\"empty\":\"\"}");
    }

    @Test
    void decodesFormEscapesAndCollectsRepeatedNames() {
        var outcome = convert("scope=read+write&id=a%2Cb&id=c", MediaType.APPLICATION_FORM_URLENCODED_VALUE);

        assertThat(text(outcome)).isEqualTo("{\"scope\":\"read write\",\"id\":[\"a,b\",\"c\"]}");
    }

    @Test
    void gathersNdjsonRecordsIntoAnArray() {
        var outcome = convert("{\"a\":1}\n{\"a\":2}\n", "application/x-ndjson");

        assertThat(outcome.note()).isEqualTo("ndjson->json");
        assertThat(text(outcome)).isEqualTo("[{\"a\":1},{\"a\":2}]");
    }

    /** Dropping the bad line would hand back a shorter array and no sign that anything was missing. */
    @Test
    void abandonsNdjsonRatherThanSkippingALine() {
        String body = "{\"a\":1}\nnot json\n";

        var outcome = convert(body, "application/x-ndjson");

        assertThat(outcome.converted()).isFalse();
        assertThat(text(outcome)).isEqualTo(body);
        assertThat(outcome.note()).contains("Line 2");
    }

    // --- what is left alone --------------------------------------------------

    @Test
    void passesJsonThroughUntouched() {
        var outcome = convert("{ \"already\":  \"json\" }", MediaType.APPLICATION_JSON_VALUE);

        assertThat(outcome.converted()).isFalse();
        assertThat(outcome.note()).isNull();
        assertThat(text(outcome)).isEqualTo("{ \"already\":  \"json\" }");
    }

    @Test
    void namesTheFormatItHasNoConverterFor() {
        var outcome = convert("<html></html>", MediaType.TEXT_HTML_VALUE);

        assertThat(outcome.converted()).isFalse();
        assertThat(outcome.note()).contains("no converter for text/html");
    }

    /** Substituting inside a compressed body produces neither valid JSON nor a valid encoding. */
    @Test
    void leavesAnEncodedBodyAlone() {
        var headers = responded(MediaType.APPLICATION_XML_VALUE);
        headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");

        var outcome =
                normalizer.normalize("<root/>".getBytes(StandardCharsets.UTF_8), headers, asked(null), ArrayPaths.NONE);

        assertThat(outcome.converted()).isFalse();
        assertThat(outcome.note()).isEqualTo("none (body is gzip)");
    }

    @Test
    void refusesABodyOverTheCeiling() {
        var small = new JsonNormalizer(new ObjectMapper(), properties(true, 16));

        var outcome = small.normalize(
                "<root><a>aaaaaaaaaaaaaaaaaaaa</a></root>".getBytes(StandardCharsets.UTF_8),
                responded(MediaType.APPLICATION_XML_VALUE),
                asked(null),
                ArrayPaths.NONE);

        assertThat(outcome.converted()).isFalse();
        assertThat(outcome.note()).isEqualTo("none (body over 16 bytes)");
    }

    @Test
    void doesNothingWhenTheDeploymentHasItSwitchedOff() {
        var off = new JsonNormalizer(new ObjectMapper(), properties(false, 2_097_152));

        assertThat(off.isEnabled()).isFalse();
        assertThat(off.normalize(
                                "<root/>".getBytes(StandardCharsets.UTF_8),
                                responded(MediaType.APPLICATION_XML_VALUE),
                                asked(null),
                                ArrayPaths.NONE)
                        .converted())
                .isFalse();
    }

    // --- what the caller asked for -------------------------------------------

    @Test
    void leavesTheOriginalWhenTheCallerNamedIt() {
        var outcome = convert("<root/>", MediaType.APPLICATION_XML_VALUE, ArrayPaths.NONE, asked("application/xml"));

        assertThat(outcome.converted()).isFalse();
        assertThat(outcome.note()).isNull();
    }

    /** Naming both says either is welcome, and the destination's own setting decides between them. */
    @Test
    void convertsWhenTheCallerNamedJsonToo() {
        var outcome = convert(
                "<root/>",
                MediaType.APPLICATION_XML_VALUE,
                ArrayPaths.NONE,
                asked("application/xml, application/json"));

        assertThat(outcome.converted()).isTrue();
    }

    @Test
    void treatsAWildcardAsNamingNothing() {
        var outcome = convert("<root/>", MediaType.APPLICATION_XML_VALUE, ArrayPaths.NONE, asked("*/*"));

        assertThat(outcome.converted()).isTrue();
    }

    @Test
    void ignoresAnAcceptHeaderItCannotParse() {
        var outcome = convert("<root/>", MediaType.APPLICATION_XML_VALUE, ArrayPaths.NONE, asked("not a media type"));

        assertThat(outcome.converted()).isTrue();
    }
}
