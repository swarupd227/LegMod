package com.envestnet.atlas.uplift.rules;

import java.util.regex.Pattern;

/**
 * One detection rule, applied to each line of every Java file. The pattern is
 * deliberately simple — line-level regex over imports and qualified class
 * references. Phase 1h covers the patterns the bundled Spring 5/javax demo
 * exercises; new rules slot in without code changes elsewhere.
 */
public class DetectionRule {

    public final String  id;                 // stable id for filtering
    public final String  label;              // human-readable
    public final String  severity;           // low | medium | high
    public final Pattern pattern;
    public final int     weight;             // contribution to module difficulty (1-5)
    public final String  suggestedRecipe;    // OpenRewrite recipe hint

    public DetectionRule(String id, String label, String severity,
                         String regex, int weight, String suggestedRecipe) {
        this.id = id;
        this.label = label;
        this.severity = severity;
        this.pattern = Pattern.compile(regex);
        this.weight = weight;
        this.suggestedRecipe = suggestedRecipe;
    }
}
