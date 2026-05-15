package com.envestnet.atlas.recon.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Pulls a sample of captured envelopes from MinIO and derives per-element
 * empirical facts: observed type, observed format, sample frequency, and
 * (where the values look enum-like) the set of seen values.
 *
 * Intentionally heuristic — Phase 1c is about producing decisions that the
 * engineer has to review. Trang-style structural inference is a Phase 1d
 * upgrade once we move to a proper XSD library.
 */
@Component
public class EmpiricalInferrer {
    private static final Logger log = LoggerFactory.getLogger(EmpiricalInferrer.class);

    private static final Pattern MMDDYYYY = Pattern.compile("^\\d{2}/\\d{2}/\\d{4}$");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern DECIMAL  = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern UPPER_ENUM = Pattern.compile("^[A-Z][A-Z0-9_]+$");

    private final S3Client s3;
    private final String bucket;
    private final DocumentBuilder builder;

    public EmpiricalInferrer(S3Client s3,
                             @Value("${MINIO_BUCKET_CORPUS:atlas-corpus}") String bucket)
            throws Exception {
        this.s3 = s3;
        this.bucket = bucket;
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        this.builder = f.newDocumentBuilder();
    }

    public Result infer(UUID projectId, int sampleSize) {
        // Pull up to sampleSize envelopes for this project.
        Map<String, ElementStats> stats = new LinkedHashMap<>();
        long visited = 0;

        String prefix = projectId.toString() + "/";
        String continuation = null;
        outer:
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).maxKeys(Math.min(sampleSize, 1000));
            if (continuation != null) req.continuationToken(continuation);
            ListObjectsV2Response resp;
            try {
                resp = s3.listObjectsV2(req.build());
            } catch (Exception e) {
                log.warn("MinIO list failed: {}", e.toString());
                break;
            }
            for (S3Object o : resp.contents()) {
                if (visited++ >= sampleSize) break outer;
                try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                        .bucket(bucket).key(o.key()).build())) {
                    Document doc = builder.parse(new InputSource(
                            new InputStreamReader(in, StandardCharsets.UTF_8)));
                    walk(doc.getDocumentElement(), null, null, stats);
                } catch (Exception e) {
                    // skip malformed envelopes
                }
            }
            continuation = resp.nextContinuationToken();
        } while (continuation != null && visited < sampleSize);

        log.info("empirical inference · project={} envelopes={} fields={}",
                projectId, visited, stats.size());

        return new Result(visited, statsToInfo(stats));
    }

    /* ---------------- DOM walker ---------------- */

    /**
     * Walks SOAP body descendants. Tracks paths in the form "TypeName.field"
     * by treating the FIRST element child of soap:Body's first child as the
     * type root, and using parent-element local-names beneath it.
     */
    private void walk(Element root, String parentName, String pathPrefix,
                      Map<String, ElementStats> stats) {
        // Find the SOAP body and dive into the message wrapper.
        Element body = firstChildLocal(root, "Body");
        if (body == null) return;
        Element wrapper = firstElementChild(body);                 // e.g. submitAllocation
        if (wrapper == null) return;
        Element message = firstElementChild(wrapper);              // e.g. request | submitAllocationReturn
        if (message == null) return;

        // Use the wrapper local name as the type-root identifier.
        // For request: "AllocationRequest" (we'd really want to look at the
        // message's first complex child). Simpler heuristic: walk children
        // and synthesize TypeName from the first complex element child.
        Element typeRoot = firstElementChild(message);
        if (typeRoot == null) {
            // The message itself is the body of interest.
            walkChildren(message, capitalize(message.getLocalName()), stats);
        } else {
            walkChildren(typeRoot, capitalize(typeRoot.getLocalName()), stats);
            // Also record the wrapping container.
            walkChildren(message, capitalize(message.getLocalName()), stats);
        }
    }

    private void walkChildren(Element parent, String typeName, Map<String, ElementStats> stats) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ch = (Element) n;
            String fieldName = ch.getLocalName();
            String path = typeName + "." + fieldName;

            // If the element has element children, recurse and record presence.
            ElementStats st = stats.computeIfAbsent(path, k -> new ElementStats());
            st.observed++;

            String text = directText(ch);
            if (text != null && !text.isBlank()) {
                st.recordValue(text.trim());
            }

            if (hasElementChildren(ch)) {
                walkChildren(ch, capitalize(fieldName), stats);
            }
        }
    }

    private static String directText(Element e) {
        NodeList kids = e.getChildNodes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.TEXT_NODE) sb.append(n.getNodeValue());
        }
        return sb.toString();
    }

    private static boolean hasElementChildren(Element e) {
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i).getNodeType() == Node.ELEMENT_NODE) return true;
        }
        return false;
    }

    private static Element firstElementChild(Element e) {
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) return (Element) n;
        }
        return null;
    }

    private static Element firstChildLocal(Element e, String localName) {
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && localName.equals(((Element) n).getLocalName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /* ---------------- aggregation → ElementInfo ---------------- */

    private Map<String, ElementInfo> statsToInfo(Map<String, ElementStats> stats) {
        Map<String, ElementInfo> out = new LinkedHashMap<>();
        for (Map.Entry<String, ElementStats> e : stats.entrySet()) {
            ElementStats st = e.getValue();
            ElementInfo info = new ElementInfo();
            info.present = true;
            info.observed = (long) st.observed;
            info.sampleValues = st.sampleValues();

            if (st.values.isEmpty()) {
                info.type = "complex";
            } else {
                info.type = inferType(st);
                info.format = inferFormat(st);
                if (looksLikeEnum(st)) {
                    info.type = "enum";
                    info.enumValues = new ArrayList<>(st.distinct());
                }
            }
            out.put(e.getKey(), info);
        }
        return out;
    }

    private String inferType(ElementStats st) {
        boolean allDate = !st.values.isEmpty() && st.values.stream().allMatch(MMDDYYYY.asPredicate());
        if (allDate) return "string";       // it's xsd:string on the wire (not xsd:date) — that's the point
        boolean allIso = !st.values.isEmpty() && st.values.stream().allMatch(ISO_DATE.asPredicate());
        if (allIso) return "xsd:date";
        boolean allDec = !st.values.isEmpty() && st.values.stream().allMatch(DECIMAL.asPredicate());
        if (allDec) return "xsd:decimal";
        return "xsd:string";
    }

    private String inferFormat(ElementStats st) {
        if (st.values.stream().allMatch(MMDDYYYY.asPredicate())) return "MM/dd/yyyy";
        if (st.values.stream().allMatch(ISO_DATE.asPredicate()))  return "yyyy-MM-dd";
        return null;
    }

    private boolean looksLikeEnum(ElementStats st) {
        Set<String> distinct = st.distinct();
        if (distinct.size() > 12 || distinct.size() < 2) return false;
        return distinct.stream().allMatch(UPPER_ENUM.asPredicate());
    }

    /* ---------------- types ---------------- */

    public record Result(long envelopesScanned, Map<String, ElementInfo> elements) {}

    private static class ElementStats {
        int observed = 0;
        final List<String> values = new ArrayList<>(64);
        Set<String> distinct() { return new LinkedHashSet<>(values); }
        void recordValue(String v) {
            if (values.size() < 200) values.add(v);
        }
        List<String> sampleValues() {
            return values.stream().distinct().limit(5).toList();
        }
    }
}
