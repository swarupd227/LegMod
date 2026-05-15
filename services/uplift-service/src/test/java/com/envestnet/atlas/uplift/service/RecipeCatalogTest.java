package com.envestnet.atlas.uplift.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeCatalogTest {

    @Test
    void lookupReturnsCuratedMetadataForKnownRecipe() {
        var entry = RecipeCatalog.lookup(
                "org.openrewrite.java.migrate.jakarta.JavaxPersistenceToJakartaPersistence");
        assertThat(entry.label()).contains("javax.persistence");
        assertThat(entry.kind()).isEqualTo("ootb");
        assertThat(entry.description()).isNotBlank();
    }

    @Test
    void lookupSynthesisesEntryForUnknownRecipe() {
        var entry = RecipeCatalog.lookup("com.example.Some.Unknown.Recipe");

        assertThat(entry.label()).isEqualTo("Recipe");          // last FQN segment
        assertThat(entry.kind()).isEqualTo("ootb");
        assertThat(entry.description()).isNotBlank();
    }

    @Test
    void lookupFlagsCustomKindForAtlasNamespace() {
        var entry = RecipeCatalog.lookup("atlas.recipes.SomeNew");
        assertThat(entry.kind()).isEqualTo("custom");
    }

    @Test
    void lookupTreatsCustomSuffixAsCustomKind() {
        var entry = RecipeCatalog.lookup("vendor.X (custom)");
        assertThat(entry.kind()).isEqualTo("custom");
    }

    @Test
    void normaliseStripsCustomSuffix() {
        assertThat(RecipeCatalog.normalize("atlas.X (custom)")).isEqualTo("atlas.X");
        assertThat(RecipeCatalog.normalize("atlas.X")).isEqualTo("atlas.X");
        assertThat(RecipeCatalog.normalize(null)).isNull();
    }
}
