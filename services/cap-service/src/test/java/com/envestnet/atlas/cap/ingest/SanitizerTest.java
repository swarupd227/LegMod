package com.envestnet.atlas.cap.ingest;

import com.envestnet.atlas.cap.domain.SanitizationRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizerTest {

    private static SanitizationRule rule(String name, String pattern, String strategy) {
        return new SanitizationRule(UUID.randomUUID(), null, name, pattern, strategy, 0L, true);
    }

    @Test
    void redactStrategyReplacesMatchesWithStars() {
        var s = new Sanitizer(List.of(rule("ssn", "\\d{3}-\\d{2}-\\d{4}", "redact")));

        var result = s.apply("name=Alice ssn=123-45-6789 email=a@b.co");

        assertThat(result.text()).isEqualTo("name=Alice ssn=*** email=a@b.co");
        assertThat(result.totalHits()).isEqualTo(1);
        assertThat(result.perRuleHits()).containsExactly(1);
    }

    @Test
    void tokenizeStrategyReplacesWithStableTokenForMatch() {
        var s = new Sanitizer(List.of(rule("acct", "ACC-\\d+", "tokenize")));

        var first  = s.apply("acct=ACC-9182");
        var second = s.apply("acct=ACC-9182");

        // Both calls produce the same token (deterministic — uses hashCode).
        assertThat(first.text()).isEqualTo(second.text());
        // Token format is `<tok:hex>`.
        assertThat(first.text()).matches("acct=<tok:[0-9a-fA-F]+>");
    }

    @Test
    void hashStrategyReplacesWithHashPrefixedDigest() {
        var s = new Sanitizer(List.of(rule("acct", "ACC-\\d+", "hash")));

        var result = s.apply("acct=ACC-9182");
        assertThat(result.text()).matches("acct=#h:[0-9a-fA-F]+");
    }

    @Test
    void unknownStrategyLeavesMatchUntouched() {
        var s = new Sanitizer(List.of(rule("acct", "ACC-\\d+", "leave")));

        var result = s.apply("acct=ACC-9182 secondary=ACC-1");
        assertThat(result.text()).isEqualTo("acct=ACC-9182 secondary=ACC-1");
        // Hits still counted; the rule fired even if the strategy didn't transform.
        assertThat(result.totalHits()).isEqualTo(2);
    }

    @Test
    void invalidRegexIsSkippedNotRaised() {
        var bad  = rule("bad",  "[unclosed", "redact");
        var good = rule("ssn",  "\\d{3}",     "redact");
        var s    = new Sanitizer(List.of(bad, good));

        var result = s.apply("123 456");
        // Only the working rule registers — bad rule was filtered at construction.
        assertThat(result.perRuleHits()).hasSize(1);
        assertThat(result.text()).isEqualTo("*** ***");
    }

    @Test
    void multipleRulesApplyInOrderAndAccumulateHits() {
        var rules = List.of(
                rule("ssn",   "\\d{3}-\\d{2}-\\d{4}", "redact"),
                rule("email", "[a-z0-9]+@[a-z0-9.]+", "redact")
        );
        var s = new Sanitizer(rules);

        var result = s.apply("ssn=123-45-6789 contact=alice@example.com");
        assertThat(result.text()).isEqualTo("ssn=*** contact=***");
        assertThat(result.perRuleHits()).containsExactly(1, 1);
        assertThat(result.totalHits()).isEqualTo(2);
    }

    @Test
    void emptyRuleListIsAPassThrough() {
        var s = new Sanitizer(List.of());
        var result = s.apply("hello");
        assertThat(result.text()).isEqualTo("hello");
        assertThat(result.totalHits()).isZero();
    }

    @Test
    void replacementSpecialCharsAreQuotedNotInterpretedAsRegexBackrefs() {
        // The matched substring may contain '$' or '\\'; if `appendReplacement`
        // saw it raw it would interpret as backreferences and throw.
        var s = new Sanitizer(List.of(rule("dollar", "\\$\\d+", "redact")));
        var result = s.apply("price=$50 fee=$2");
        assertThat(result.text()).isEqualTo("price=*** fee=***");
    }
}
