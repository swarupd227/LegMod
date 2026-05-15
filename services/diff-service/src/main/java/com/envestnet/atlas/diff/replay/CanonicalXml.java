package com.envestnet.atlas.diff.replay;

import org.w3c.dom.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.xml.sax.InputSource;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;

/**
 * Canonicalisation that's good enough for byte-comparing two SOAP envelopes
 * for parity-testing purposes.
 *   - whitespace-only text nodes stripped
 *   - children sorted by tag name (so element ordering becomes irrelevant)
 *   - namespace prefixes rewritten to a deterministic alphabet (a, b, c, …)
 *
 * The standalone xml-c14n in the JDK doesn't help much because it preserves
 * prefix shapes; what we want is structural canonicalisation, which is
 * easier to do over the DOM directly.
 */
public class CanonicalXml {

    private final DocumentBuilder builder;

    public CanonicalXml() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        this.builder = f.newDocumentBuilder();
    }

    public Document parse(String xml) throws Exception {
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    public String canonical(Document doc, NamespaceRewriter nr) throws Exception {
        Document out = (Document) doc.cloneNode(true);
        if (out.getDocumentElement() != null) {
            walk(out.getDocumentElement(), nr);
        }
        return serialize(out);
    }

    private void walk(Element e, NamespaceRewriter nr) {
        // 1. drop whitespace-only text nodes
        List<Node> toDrop = new ArrayList<>();
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.TEXT_NODE
                    && n.getNodeValue() != null
                    && n.getNodeValue().trim().isEmpty()) {
                toDrop.add(n);
            }
        }
        toDrop.forEach(e::removeChild);

        // 2. recurse, then sort element children by tag local-name
        List<Element> children = new ArrayList<>();
        NodeList kids2 = e.getChildNodes();
        for (int i = 0; i < kids2.getLength(); i++) {
            Node n = kids2.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) children.add((Element) n);
        }
        for (Element c : children) walk(c, nr);

        // Re-attach in sorted order.
        children.forEach(e::removeChild);
        children.sort(Comparator.comparing(Element::getLocalName,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        children.forEach(e::appendChild);

        // 3. rewrite namespace prefix
        if (nr != null && e.getNamespaceURI() != null) {
            String newPrefix = nr.prefixFor(e.getNamespaceURI());
            String currentPrefix = e.getPrefix();
            if (newPrefix != null && !newPrefix.equals(currentPrefix == null ? "" : currentPrefix)) {
                String ns = e.getNamespaceURI();
                e.setPrefix(newPrefix.isEmpty() ? null : newPrefix);

                Element root = e.getOwnerDocument().getDocumentElement();

                // Drop any pre-existing xmlns declaration mapped to the same
                // namespace URI under a different prefix; otherwise the
                // canonical output has a dead `xmlns:oldPrefix="ns"` alongside
                // the new `xmlns:newPrefix="ns"`, which breaks byte equality.
                pruneObsoleteXmlnsForNamespace(root, ns, newPrefix);

                // Re-declare under the canonical prefix on the root so the
                // serializer can resolve it cleanly.
                if (newPrefix.isEmpty()) {
                    root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", ns);
                } else {
                    root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                            "xmlns:" + newPrefix, ns);
                }
            }
        }
    }

    private static void pruneObsoleteXmlnsForNamespace(Element root, String ns, String keepPrefix) {
        NamedNodeMap attrs = root.getAttributes();
        List<Attr> toRemove = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String name = a.getName();
            if (!"xmlns".equals(name) && !name.startsWith("xmlns:")) continue;
            if (!ns.equals(a.getValue())) continue;
            String declaredPrefix = name.equals("xmlns") ? "" : name.substring("xmlns:".length());
            if (!declaredPrefix.equals(keepPrefix)) toRemove.add(a);
        }
        for (Attr a : toRemove) root.removeAttributeNode(a);
    }

    public static String serialize(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        t.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    /** Maps a namespace URI to a deterministic short prefix. */
    public static class NamespaceRewriter {
        private final Map<String, String> table = new LinkedHashMap<>();
        private int n = 0;

        public NamespaceRewriter() {
            // Keep SOAP envelope prefix stable so the structure stays readable.
            table.put("http://schemas.xmlsoap.org/soap/envelope/", "soapenv");
            table.put("http://www.w3.org/2003/05/soap-envelope",   "soapenv");
        }

        public String prefixFor(String ns) {
            return table.computeIfAbsent(ns, k -> "p" + (n++));
        }
    }
}
