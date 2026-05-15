package com.envestnet.atlas.diff.replay;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalXmlTest {

    @Test
    void identicalDocumentsCanonicaliseToTheSameString() throws Exception {
        var c = new CanonicalXml();
        String xml = "<a><b>1</b><b>2</b></a>";

        String first  = c.canonical(c.parse(xml), null);
        String second = c.canonical(c.parse(xml), null);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void whitespaceOnlyTextNodesAreStripped() throws Exception {
        var c = new CanonicalXml();
        String pretty  = "<a>\n  <b>1</b>\n</a>";
        String compact = "<a><b>1</b></a>";

        assertThat(c.canonical(c.parse(pretty),  null))
                .isEqualTo(c.canonical(c.parse(compact), null));
    }

    @Test
    void siblingElementOrderingIsCanonicalisedByLocalName() throws Exception {
        // The two documents differ only in element ordering. After
        // canonicalisation they should be byte-identical.
        var c = new CanonicalXml();
        String a = "<root><b>1</b><a>2</a><c>3</c></root>";
        String b = "<root><c>3</c><a>2</a><b>1</b></root>";

        assertThat(c.canonical(c.parse(a), null))
                .isEqualTo(c.canonical(c.parse(b), null));
    }

    @Test
    void namespacePrefixIsRewrittenToDeterministicAlphabet() throws Exception {
        var c = new CanonicalXml();
        var nr = new CanonicalXml.NamespaceRewriter();

        // Two functionally-equivalent docs that just use different prefixes
        // for the same namespace.
        String first  = "<ex:root xmlns:ex='urn:x'><ex:child>v</ex:child></ex:root>";
        String second = "<x:root xmlns:x='urn:x'><x:child>v</x:child></x:root>";

        String c1 = c.canonical(c.parse(first),  nr);
        String c2 = c.canonical(c.parse(second), new CanonicalXml.NamespaceRewriter());

        // After rewriting they should match: both end up using prefix `p0`
        // (since 'urn:x' is the first non-soap namespace seen).
        assertThat(c1).isEqualTo(c2);
        assertThat(c1).contains("p0:root").contains("p0:child");
    }

    @Test
    void soapEnvelopeNamespaceKeepsItsCanonicalSoapenvPrefix() throws Exception {
        var c = new CanonicalXml();
        var nr = new CanonicalXml.NamespaceRewriter();
        String xml =
            "<env:Envelope xmlns:env='http://schemas.xmlsoap.org/soap/envelope/'>" +
            "<env:Body><Hello/></env:Body></env:Envelope>";

        String out = c.canonical(c.parse(xml), nr);
        // The SOAP envelope namespace gets the stable `soapenv` prefix even when
        // the input used a different prefix.
        assertThat(out).contains("soapenv:Envelope").contains("soapenv:Body");
    }

    @Test
    void differingValuesDoNotCanonicaliseEqual() throws Exception {
        var c = new CanonicalXml();
        String a = "<root><b>1</b></root>";
        String b = "<root><b>2</b></root>";

        assertThat(c.canonical(c.parse(a), null))
                .isNotEqualTo(c.canonical(c.parse(b), null));
    }

    @Test
    void parserDisablesExternalEntityResolution() throws Exception {
        var c = new CanonicalXml();
        // FEATURE_SECURE_PROCESSING is on; XXE-style external DTD references
        // should fail rather than try to resolve.
        String xxe =
            "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
            "<root>&xxe;</root>";

        // The parser may either reject this outright or expand to nothing —
        // both behaviours are safe; the unsafe behaviour would be reading
        // /etc/passwd. We just confirm we don't get the file contents back.
        try {
            String out = c.canonical(c.parse(xxe), null);
            assertThat(out).doesNotContain("root:x:0:0");
        } catch (Exception ignored) {
            // explicit rejection is also acceptable
        }
    }
}
