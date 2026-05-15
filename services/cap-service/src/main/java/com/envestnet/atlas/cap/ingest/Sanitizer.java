package com.envestnet.atlas.cap.ingest;

import com.envestnet.atlas.cap.domain.SanitizationRule;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Applies a list of SanitizationRules to a SOAP envelope body. Returns the
 * sanitized text plus the total number of rule hits — the per-envelope hit
 * count is stored alongside the envelope; per-rule aggregate counts are
 * tracked separately.
 */
public class Sanitizer {

    private final List<Compiled> compiled;

    public Sanitizer(List<SanitizationRule> rules) {
        this.compiled = rules.stream()
                .map(Compiled::of)
                .filter(c -> c != null)
                .toList();
    }

    public Result apply(String text) {
        int totalHits = 0;
        int[] perRule = new int[compiled.size()];
        for (int i = 0; i < compiled.size(); i++) {
            Compiled c = compiled.get(i);
            Matcher m = c.pattern.matcher(text);
            StringBuilder sb = new StringBuilder();
            int hits = 0;
            while (m.find()) {
                hits++;
                m.appendReplacement(sb, Matcher.quoteReplacement(c.replace(m.group())));
            }
            m.appendTail(sb);
            text = sb.toString();
            perRule[i] = hits;
            totalHits += hits;
        }
        return new Result(text, totalHits, perRule, compiled.stream().map(c -> c.rule.id()).toList());
    }

    public record Result(String text, int totalHits, int[] perRuleHits, List<java.util.UUID> ruleIds) {}

    private static class Compiled {
        final SanitizationRule rule;
        final Pattern pattern;
        Compiled(SanitizationRule rule, Pattern pattern) {
            this.rule = rule; this.pattern = pattern;
        }
        static Compiled of(SanitizationRule r) {
            try { return new Compiled(r, Pattern.compile(r.pattern())); }
            catch (PatternSyntaxException e) { return null; }
        }
        String replace(String matched) {
            return switch (rule.strategy()) {
                case "redact"   -> "***";
                case "tokenize" -> "<tok:" + Integer.toHexString(matched.hashCode()) + ">";
                case "hash"     -> "#h:" + Integer.toHexString(matched.hashCode());
                default         -> matched;
            };
        }
    }
}
