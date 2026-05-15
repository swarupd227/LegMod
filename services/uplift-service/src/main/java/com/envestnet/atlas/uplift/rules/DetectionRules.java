package com.envestnet.atlas.uplift.rules;

import java.util.List;

/**
 * Built-in detection rules for the demo Spring 5 / javax / WebSphere project.
 * Adding a rule means adding one entry here — the scanner consumes them in order.
 */
public final class DetectionRules {

    public static final List<DetectionRule> ALL = List.of(

        new DetectionRule(
            "javax_persistence",
            "javax.persistence → jakarta.persistence",
            "high",
            "^\\s*import\\s+javax\\.persistence(\\..*)?;",
            3,
            "org.openrewrite.java.migrate.jakarta.JavaxPersistenceToJakartaPersistence"
        ),

        new DetectionRule(
            "javax_servlet",
            "javax.servlet → jakarta.servlet",
            "high",
            "^\\s*import\\s+javax\\.servlet(\\..*)?;",
            3,
            "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet"
        ),

        new DetectionRule(
            "javax_validation",
            "javax.validation → jakarta.validation",
            "medium",
            "^\\s*import\\s+javax\\.validation(\\..*)?;",
            2,
            "org.openrewrite.java.migrate.jakarta.JavaxValidationToJakartaValidation"
        ),

        new DetectionRule(
            "javax_xml",
            "javax.xml.bind → jakarta.xml.bind",
            "medium",
            "^\\s*import\\s+javax\\.xml\\.bind(\\..*)?;",
            2,
            "org.openrewrite.java.migrate.jakarta.JavaxXmlBindToJakartaXmlBind"
        ),

        new DetectionRule(
            "ibm_websphere",
            "IBM WebSphere proprietary API",
            "high",
            "^\\s*import\\s+com\\.ibm\\.(websphere|ws)(\\..*)?;",
            5,
            "atlas.recipes.IbmWebSphereToStandard (custom)"
        ),

        new DetectionRule(
            "struts1",
            "Struts 1.x → Spring MVC",
            "high",
            "^\\s*import\\s+org\\.apache\\.struts\\.(action|util|validator)(\\..*)?;",
            5,
            "atlas.recipes.Struts1ToSpringMvc (custom)"
        ),

        new DetectionRule(
            "spring5_extension",
            "Spring 5 deprecation candidate",
            "low",
            "^\\s*import\\s+org\\.springframework\\.web\\.bind\\.annotation\\.RequestMapping;",
            1,
            "org.openrewrite.java.spring.boot3.SpringBoot2To3"
        )
    );

    private DetectionRules() {}
}
