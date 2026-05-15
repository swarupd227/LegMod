package com.envestnet.atlas.uplift.service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demo-grade textual rewrites keyed by recipe FQN. A real implementation would
 * shell out to OpenRewrite via Maven/Gradle; for the local accelerator we emit
 * believable diffs so the user can audit what would change. Each transform is
 * a (regex, replacement) pair applied per line.
 *
 * The transforms intentionally cover the recipes our heatmap scanner suggests
 * — anything else is a no-op so the run still completes cleanly.
 */
public final class RecipeTransforms {

    public record Rule(Pattern pattern, String replacement, String label) {}

    private static final Map<String, List<Rule>> TRANSFORMS = new LinkedHashMap<>();
    static {
        TRANSFORMS.put("org.openrewrite.java.migrate.jakarta.JavaxPersistenceToJakartaPersistence",
            List.of(rule("\\bjavax\\.persistence\\b", "jakarta.persistence", "javax→jakarta")));

        TRANSFORMS.put("org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet",
            List.of(rule("\\bjavax\\.servlet\\b",     "jakarta.servlet",     "javax→jakarta")));

        TRANSFORMS.put("org.openrewrite.java.migrate.jakarta.JavaxValidationToJakartaValidation",
            List.of(rule("\\bjavax\\.validation\\b",  "jakarta.validation",  "javax→jakarta")));

        TRANSFORMS.put("org.openrewrite.java.migrate.jakarta.JavaxXmlBindToJakartaXmlBind",
            List.of(rule("\\bjavax\\.xml\\.bind\\b",  "jakarta.xml.bind",    "javax→jakarta")));

        TRANSFORMS.put("org.openrewrite.java.spring.boot3.SpringBoot2To3", List.of(
            rule("\\bjavax\\.persistence\\b", "jakarta.persistence",
                 "Boot 3: javax→jakarta"),
            rule("\\bjavax\\.servlet\\b",     "jakarta.servlet",
                 "Boot 3: javax→jakarta"),
            rule("@RequestMapping\\(method\\s*=\\s*RequestMethod\\.GET",
                 "@GetMapping(",
                 "Boot 3: shorthand mapping")
        ));

        TRANSFORMS.put("atlas.recipes.IbmWebSphereToStandard", List.of(
            rule("\\bcom\\.ibm\\.websphere\\.security\\b",
                 "jakarta.security.enterprise",
                 "WebSphere → Jakarta Security"),
            rule("\\bcom\\.ibm\\.websphere\\.naming\\b",
                 "javax.naming",
                 "WebSphere → standard JNDI"),
            rule("\\bcom\\.ibm\\.ws\\b",       "org.atlas.compat.ibm",
                 "WebSphere → Atlas compat shim")
        ));

        TRANSFORMS.put("atlas.recipes.Struts1ToSpringMvc", List.of(
            rule("\\borg\\.apache\\.struts\\.action\\b",
                 "org.springframework.web.bind.annotation",
                 "Struts → Spring MVC"),
            rule("extends\\s+ActionForm\\b",
                 "/* TODO: replace ActionForm with @ModelAttribute DTO */ implements java.io.Serializable",
                 "Struts → Spring MVC")
        ));
    }

    private static Rule rule(String regex, String replacement, String label) {
        return new Rule(Pattern.compile(regex), replacement, label);
    }

    /** Apply one recipe to a file body; returns transformed text + change count. */
    public static Result apply(String recipeId, String body) {
        List<Rule> rules = TRANSFORMS.getOrDefault(stripCustomTag(recipeId), List.of());
        if (rules.isEmpty() || body == null || body.isEmpty()) {
            return new Result(body, 0);
        }
        StringBuilder out = new StringBuilder(body.length() + 64);
        int totalChanges = 0;
        int last = 0;
        // Apply line-by-line so changes are localized and diffs are clean.
        int i = 0;
        while (i < body.length()) {
            int nl = body.indexOf('\n', i);
            int end = nl < 0 ? body.length() : nl + 1;
            String line = body.substring(i, end);
            String working = line;
            int lineChanges = 0;
            for (Rule r : rules) {
                Matcher m = r.pattern().matcher(working);
                StringBuffer sb = new StringBuffer();
                int count = 0;
                while (m.find()) { m.appendReplacement(sb, Matcher.quoteReplacement(r.replacement())); count++; }
                m.appendTail(sb);
                if (count > 0) {
                    working = sb.toString();
                    lineChanges += count;
                }
            }
            out.append(working);
            totalChanges += lineChanges;
            i = end;
        }
        return new Result(out.toString(), totalChanges);
    }

    public static boolean handles(String recipeId) {
        return TRANSFORMS.containsKey(stripCustomTag(recipeId));
    }

    private static String stripCustomTag(String s) {
        return s == null ? "" : s.replaceAll("\\s*\\(custom\\)\\s*$", "").trim();
    }

    public record Result(String text, int changes) {}

    private RecipeTransforms() {}
}
