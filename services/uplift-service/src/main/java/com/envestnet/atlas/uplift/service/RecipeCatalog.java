package com.envestnet.atlas.uplift.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Curated metadata for the recipes our scanner suggests. Map key = OpenRewrite
 * FQN (or atlas.* for our custom ones); the values are user-facing label and
 * description. Anything not in the catalog still seeds, with a label derived
 * from the recipe id.
 */
public final class RecipeCatalog {

    public record Entry(String label, String description, String kind) {}

    public static final Map<String, Entry> CATALOG = new LinkedHashMap<>() {{
        put("org.openrewrite.java.migrate.jakarta.JavaxPersistenceToJakartaPersistence",
            new Entry(
                "javax.persistence → jakarta.persistence",
                "Rewrites all javax.persistence imports and references to jakarta.persistence. " +
                "Required for Jakarta EE 9+ / Spring Boot 3 / Hibernate 6.",
                "ootb"));

        put("org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet",
            new Entry(
                "javax.servlet → jakarta.servlet",
                "Rewrites Servlet API imports to the jakarta namespace. Pairs with the " +
                "container upgrade (Tomcat 10, Jetty 11, WildFly 27).",
                "ootb"));

        put("org.openrewrite.java.migrate.jakarta.JavaxValidationToJakartaValidation",
            new Entry(
                "javax.validation → jakarta.validation",
                "Bean Validation annotations move to the jakarta package; recipe handles " +
                "@NotNull, @Size, @Valid, etc. across imports and Class.forName references.",
                "ootb"));

        put("org.openrewrite.java.migrate.jakarta.JavaxXmlBindToJakartaXmlBind",
            new Entry(
                "javax.xml.bind → jakarta.xml.bind",
                "JAXB classes migrate to jakarta.xml.bind. Adds the runtime dependency " +
                "(jakarta.xml.bind-api + glassfish-jaxb) since it is no longer JDK-bundled.",
                "ootb"));

        put("org.openrewrite.java.spring.boot3.SpringBoot2To3",
            new Entry(
                "Spring Boot 2.x → 3.0",
                "Aggregate recipe: dependency upgrades, deprecated property keys, " +
                "configuration class updates, security DSL adjustments, and Java 17 baseline.",
                "ootb"));

        put("atlas.recipes.IbmWebSphereToStandard",
            new Entry(
                "IBM WebSphere → Jakarta / Spring",
                "Custom Atlas recipe: replace WebSphere proprietary APIs " +
                "(com.ibm.websphere.*, com.ibm.ws.*) with Jakarta + Spring equivalents — " +
                "JNDI lookups, JAAS, transaction manager hooks.",
                "custom"));

        put("atlas.recipes.Struts1ToSpringMvc",
            new Entry(
                "Struts 1.x → Spring MVC",
                "Custom Atlas recipe: convert Action / ActionForm / Validator into " +
                "@Controller / @RequestMapping / Bean Validation. Manual review required " +
                "for tiles and action chains.",
                "custom"));
    }};

    public static Entry lookup(String recipeId) {
        Entry e = CATALOG.get(recipeId);
        if (e != null) return e;

        // Strip "(custom)" suffix the scanner sometimes emits.
        String trimmed = recipeId.replaceAll("\\s*\\(custom\\)\\s*$", "");
        e = CATALOG.get(trimmed);
        if (e != null) return e;

        // Synthesize a label from the FQN.
        String label = trimmed;
        int dot = trimmed.lastIndexOf('.');
        if (dot >= 0) label = trimmed.substring(dot + 1);
        boolean custom = trimmed.startsWith("atlas.")
                       || recipeId.contains("(custom)");
        return new Entry(label, "Recipe metadata pending — review affected findings.", custom ? "custom" : "ootb");
    }

    public static String normalize(String recipeId) {
        if (recipeId == null) return null;
        return recipeId.replaceAll("\\s*\\(custom\\)\\s*$", "").trim();
    }

    private RecipeCatalog() {}
}
