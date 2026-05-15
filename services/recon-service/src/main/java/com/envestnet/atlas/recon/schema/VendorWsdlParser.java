package com.envestnet.atlas.recon.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Walks the &lt;xsd:complexType&gt; declarations in any *.wsdl file under the
 * project source path and returns a flat map of element-path → ElementInfo,
 * keyed as "TypeName.fieldName" or "TypeName.parent.fieldName".
 */
public class VendorWsdlParser {
    private static final Logger log = LoggerFactory.getLogger(VendorWsdlParser.class);
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";

    private final DocumentBuilder builder;
    private final XPath xpath;

    public VendorWsdlParser() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        this.builder = f.newDocumentBuilder();
        this.xpath = XPathFactory.newInstance().newXPath();
    }

    public Map<String, ElementInfo> parseAll(Path sourceRoot) {
        Map<String, ElementInfo> out = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(sourceRoot)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".wsdl")).toList()) {
                parseOne(p, out);
            }
        } catch (Exception e) {
            log.warn("vendor wsdl walk failed for {}: {}", sourceRoot, e.toString());
        }
        return out;
    }

    private void parseOne(Path wsdl, Map<String, ElementInfo> out) {
        try {
            Document doc = builder.parse(new InputSource(new StringReader(Files.readString(wsdl))));
            NodeList complexTypes = doc.getElementsByTagNameNS(XSD_NS, "complexType");
            for (int i = 0; i < complexTypes.getLength(); i++) {
                Element ct = (Element) complexTypes.item(i);
                String typeName = ct.getAttribute("name");
                if (typeName == null || typeName.isEmpty()) continue;

                NodeList children = ct.getElementsByTagNameNS(XSD_NS, "element");
                for (int j = 0; j < children.getLength(); j++) {
                    Element ch = (Element) children.item(j);
                    String name = ch.getAttribute("name");
                    String type = ch.getAttribute("type");
                    if (name.isEmpty()) continue;

                    ElementInfo info = new ElementInfo();
                    info.present = true;
                    info.type = type.isEmpty() ? null : stripPrefix(type);
                    String minO = ch.getAttribute("minOccurs");
                    String maxO = ch.getAttribute("maxOccurs");
                    info.minOccurs = parseInt(minO, 1);
                    info.maxOccurs = "unbounded".equals(maxO) ? Integer.MAX_VALUE : parseInt(maxO, 1);

                    String path = typeName + "." + name;
                    out.put(path, info);
                }
            }
            log.info("parsed wsdl {} -> {} fields", wsdl.getFileName(), out.size());
        } catch (Exception e) {
            log.warn("failed to parse wsdl {}: {}", wsdl, e.toString());
        }
    }

    private static String stripPrefix(String qname) {
        int idx = qname.indexOf(':');
        return idx < 0 ? qname : qname.substring(idx + 1);
    }

    private static Integer parseInt(String s, int dflt) {
        if (s == null || s.isEmpty()) return dflt;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return dflt; }
    }
}
