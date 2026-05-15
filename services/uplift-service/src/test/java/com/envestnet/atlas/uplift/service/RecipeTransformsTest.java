package com.envestnet.atlas.uplift.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeTransformsTest {

    @Test
    void rewritesJavaxPersistenceImportsToJakarta() {
        String source = """
                package com.foo;
                import javax.persistence.Entity;
                import javax.persistence.Id;
                @Entity
                class Bar {}
                """;

        var result = RecipeTransforms.apply(
                "org.openrewrite.java.migrate.jakarta.JavaxPersistenceToJakartaPersistence",
                source);

        assertThat(result.changes()).isEqualTo(2);
        assertThat(result.text())
                .contains("import jakarta.persistence.Entity;")
                .contains("import jakarta.persistence.Id;")
                .doesNotContain("import javax.persistence");
    }

    @Test
    void springBoot2to3RecipeAppliesMultipleRewriteRules() {
        String source = """
                import javax.persistence.Entity;
                import javax.servlet.http.HttpServletRequest;
                @RequestMapping(method = RequestMethod.GET, value = "/x")
                """;

        var result = RecipeTransforms.apply(
                "org.openrewrite.java.spring.boot3.SpringBoot2To3", source);

        assertThat(result.changes()).isGreaterThanOrEqualTo(3);
        assertThat(result.text())
                .contains("jakarta.persistence")
                .contains("jakarta.servlet")
                .contains("@GetMapping(");
    }

    @Test
    void unhandledRecipeReturnsBodyUnchangedWithZeroChanges() {
        String source = "import javax.persistence.Entity;";

        var result = RecipeTransforms.apply("vendor.unknown.Recipe", source);

        assertThat(result.text()).isEqualTo(source);
        assertThat(result.changes()).isZero();
    }

    @Test
    void emptyOrNullBodyShortCircuits() {
        var nullResult  = RecipeTransforms.apply("anything", null);
        var emptyResult = RecipeTransforms.apply("anything", "");

        assertThat(nullResult.changes()).isZero();
        assertThat(emptyResult.changes()).isZero();
        assertThat(emptyResult.text()).isEmpty();
    }

    @Test
    void handlesAcceptsCustomTagSuffixOnRecipeId() {
        String source = "import com.ibm.websphere.security.auth.Principal;";

        var result = RecipeTransforms.apply(
                "atlas.recipes.IbmWebSphereToStandard (custom)", source);

        assertThat(result.text()).contains("jakarta.security.enterprise");
        assertThat(result.text()).doesNotContain("com.ibm.websphere.security");
    }

    @Test
    void handlesIdentifiesRecognisedRecipes() {
        assertThat(RecipeTransforms.handles(
                "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet")).isTrue();
        assertThat(RecipeTransforms.handles("vendor.unknown")).isFalse();
        assertThat(RecipeTransforms.handles(null)).isFalse();
    }

    @Test
    void preservesLineCountSoUnifiedDiffStaysAlignedPerLine() {
        String source = "line one\nline two\nline three\n";

        var result = RecipeTransforms.apply(
                "org.openrewrite.java.migrate.jakarta.JavaxPersistenceToJakartaPersistence",
                source);

        // No matches; output should byte-equal input.
        assertThat(result.text()).isEqualTo(source);
    }
}
