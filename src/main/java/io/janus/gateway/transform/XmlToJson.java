package io.janus.gateway.transform;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.*;

import javax.xml.stream.*;

import org.springframework.http.MediaType;

/**
 * XML restated as JSON, which is most of the reason this package exists: Plex answers in it, so do
 * Newznab indexers, podcast feeds, sitemaps, and every SOAP endpoint still in service.
 *
 * <p>The mapping is deliberately mechanical, because a converter that guesses is a converter whose
 * output changes when the data does:
 *
 * <pre>
 *   &lt;Directory key="4" type="movie"&gt;      {"Directory": {"@key": "4", "@type": "movie",
 *     &lt;Location path="/movies"/&gt;                        "Location": {"@path": "/movies"}}}
 *   &lt;/Directory&gt;
 * </pre>
 *
 * <ul>
 *   <li>an attribute becomes a key prefixed {@code @}
 *   <li>the text of an element that also has attributes or children becomes {@code #text}
 *   <li>an element with neither becomes its text alone, so {@code <path>/movies</path>} is a string
 *   <li>a repeated element becomes an array — and see {@link ArrayPaths} for why that is not enough
 * </ul>
 *
 * <p><strong>Every value stays a string.</strong> {@code size="6"} comes out {@code "6"}, not
 * {@code 6}. XML does not carry types, so inferring them means guessing, and the guess is wrong in
 * the case that matters: an identifier like {@code "0123"} would become {@code 123} and stop
 * matching the thing it identifies.
 *
 * <p>Namespaces are dropped down to local names — {@code soap:Body} becomes {@code Body} — which is
 * what makes a SOAP response readable without a client knowing the envelope's prefixes.
 *
 * <p>Two things are refused rather than converted. Entity resolution of every kind is switched off,
 * so a response cannot make Janus open a file or a URL of its choosing: a gateway that may reach
 * private addresses is exactly the process an XXE is worth aiming at. And a document that nests or
 * branches past the bounds below is abandoned, because the failure it would otherwise cause is a
 * stack overflow or an allocation the heap cannot take.
 */
final class XmlToJson implements BodyTransformer {

    static final String ATTRIBUTE_PREFIX = "@";
    static final String TEXT_KEY = "#text";

    /** Deeper than any document an API returns, shallower than what recursion here can survive. */
    private static final int MAX_DEPTH = 100;

    /** A ceiling on branching, for a body that is small enough to pass but expands on being read. */
    private static final int MAX_NODES = 200_000;

    /**
     * Configured once and shared. Readers are created per call and are not; the factory itself is
     * only read after construction, which is how Spring's own XML support treats it.
     */
    private static final XMLInputFactory FACTORY = hardened();

    private static XMLInputFactory hardened() {
        var factory = XMLInputFactory.newInstance();
        // No DTD, so no entity to expand: this closes both XXE and the billion-laughs expansion,
        // rather than bounding them.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        // Text arrives whole instead of in fragments, so a CDATA section and a plain run of
        // characters produce the same string.
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    @Override
    public String name() {
        return "xml";
    }

    @Override
    public boolean handles(MediaType contentType) {
        if (contentType == null) return false;
        return contentType.isCompatibleWith(MediaType.APPLICATION_XML)
                || contentType.isCompatibleWith(MediaType.TEXT_XML)
                // application/atom+xml, application/rss+xml, application/soap+xml, and the rest.
                || contentType.getSubtype().endsWith("+xml");
    }

    @Override
    public Object read(byte[] body, MediaType contentType, ArrayPaths arrays) throws BodyTransformException {
        XMLStreamReader reader = null;
        try {
            reader = open(body, contentType);
            while (reader.hasNext() && reader.next() != XMLStreamConstants.START_ELEMENT) {
                // Prologue: the declaration, comments, whitespace. Nothing of it survives conversion.
            }
            if (!reader.isStartElement()) throw new BodyTransformException("XML document has no root element");

            String root = reader.getLocalName();
            var document = new LinkedHashMap<String, Object>();
            document.put(root, element(reader, root, arrays, 1, new int[1]));
            return document;
        } catch (XMLStreamException ex) {
            throw new BodyTransformException("XML document could not be parsed", ex);
        } finally {
            close(reader);
        }
    }

    /**
     * The charset the response declared, when it declared one. Otherwise the parser is left to work
     * it out from the XML declaration or the byte order mark, which is what an {@code application/*}
     * type means and what the document itself is the authority on.
     */
    private static XMLStreamReader open(byte[] body, MediaType contentType) throws XMLStreamException {
        var stream = new ByteArrayInputStream(body);
        Charset charset = contentType == null ? null : contentType.getCharset();
        return charset == null
                ? FACTORY.createXMLStreamReader(stream)
                : FACTORY.createXMLStreamReader(stream, charset.name());
    }

    /**
     * Reads one element, from the start tag the reader is sitting on to its matching end tag.
     *
     * @param path where this element sits, counted from the root, for {@link ArrayPaths}
     * @param nodes a single mutable counter shared by the whole document, so branching is bounded
     *     across it rather than per level
     */
    private static Object element(XMLStreamReader reader, String path, ArrayPaths arrays, int depth, int[] nodes)
            throws XMLStreamException, BodyTransformException {
        if (depth > MAX_DEPTH)
            throw new BodyTransformException("XML document nests deeper than " + MAX_DEPTH + " elements");
        if (++nodes[0] > MAX_NODES)
            throw new BodyTransformException("XML document holds more than " + MAX_NODES + " elements");

        var content = new LinkedHashMap<String, Object>();
        for (int i = 0; i < reader.getAttributeCount(); i++)
            content.put(ATTRIBUTE_PREFIX + reader.getAttributeLocalName(i), reader.getAttributeValue(i));

        var text = new StringBuilder();
        while (reader.hasNext()) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    String name = reader.getLocalName();
                    String childPath = path + "." + name;
                    add(
                            content,
                            name,
                            element(reader, childPath, arrays, depth + 1, nodes),
                            arrays.forcesArray(childPath));
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> text.append(
                        reader.getText());
                case XMLStreamConstants.END_ELEMENT -> {
                    return finish(content, text);
                }
                default -> {
                    // Comments and processing instructions carry no data a caller asked for.
                }
            }
        }
        throw new BodyTransformException("XML document ended inside an element");
    }

    /**
     * An element with nothing but text is that text; an element with structure keeps its text under
     * {@code #text}, and only when it holds something other than the whitespace used for layout.
     */
    private static Object finish(Map<String, Object> content, StringBuilder text) {
        String value = text.toString().trim();
        if (content.isEmpty()) return value;
        if (!value.isEmpty()) content.put(TEXT_KEY, value);
        return content;
    }

    @SuppressWarnings("unchecked")
    private static void add(Map<String, Object> content, String name, Object child, boolean forced) {
        Object existing = content.get(name);
        if (existing == null) {
            content.put(name, forced ? new ArrayList<>(List.of(child)) : child);
        } else if (existing instanceof List<?> list) {
            ((List<Object>) list).add(child);
        } else {
            // Seen twice, so it was a list all along and the first one was read as a lone object.
            var list = new ArrayList<>();
            list.add(existing);
            list.add(child);
            content.put(name, list);
        }
    }

    private static void close(XMLStreamReader reader) {
        if (reader == null) return;
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // Closing a reader over a byte array releases nothing that matters, and the response has
            // already been read; a failure here must not mask what is being returned.
        }
    }
}
