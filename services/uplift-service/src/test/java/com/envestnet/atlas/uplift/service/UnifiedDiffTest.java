package com.envestnet.atlas.uplift.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedDiffTest {

    @Test
    void returnsEmptyStringWhenInputsAreIdentical() {
        String diff = UnifiedDiff.generate("Foo.java", "same\ncontent\n", "same\ncontent\n");
        assertThat(diff).isEmpty();
    }

    @Test
    void emitsHeaderAndHunkAndContextWhenContentDiffers() {
        String original = "package com.foo;\nimport javax.persistence.Entity;\n";
        String revised  = "package com.foo;\nimport jakarta.persistence.Entity;\n";

        String diff = UnifiedDiff.generate("Foo.java", original, revised);

        assertThat(diff)
                .contains("--- a/Foo.java")
                .contains("+++ b/Foo.java")
                .contains("@@")
                .contains(" package com.foo;")            // unchanged context
                .contains("-import javax.persistence.Entity;")
                .contains("+import jakarta.persistence.Entity;");
    }

    @Test
    void distinguishesPureAdditionsAndDeletions() {
        String original = "a\nb\n";
        String revised  = "a\nb\nc\n";

        String diff = UnifiedDiff.generate("F", original, revised);

        assertThat(diff).contains("+c");
        // No corresponding `-c` should appear.
        assertThat(diff.replaceFirst("\\+c", "")).doesNotContain("-c");
    }
}
