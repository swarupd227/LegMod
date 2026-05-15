package com.envestnet.atlas.gateway;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit coverage of {@link SecurityConfig#readClaim} — the helper
 * that turns a dotted-path claim path into a value pulled from the
 * (possibly nested) JWT claims map.
 *
 * <p>No Spring context, no Netty, no Docker — just the parser.</p>
 */
class SecurityConfigUnitTest {

    @Test
    void simplePathReturnsTopLevelClaim() {
        Map<String, Object> claims = Map.of("roles", List.of("ENGINEER"));
        Object out = SecurityConfig.readClaim(claims, "roles");
        assertThat(out).isEqualTo(List.of("ENGINEER"));
    }

    @Test
    void nestedPathWalksTheMap() {
        Map<String, Object> claims = Map.of(
                "realm_access", Map.of("roles", List.of("ENGINEER", "TECH_LEAD")));
        Object out = SecurityConfig.readClaim(claims, "realm_access.roles");
        assertThat(out).isEqualTo(List.of("ENGINEER", "TECH_LEAD"));
    }

    @Test
    void deeplyNestedPathWalksAllSegments() {
        Map<String, Object> claims = Map.of(
                "resource_access", Map.of(
                    "atlas-spa", Map.of(
                        "roles", List.of("ADMIN"))));
        Object out = SecurityConfig.readClaim(claims, "resource_access.atlas-spa.roles");
        assertThat(out).isEqualTo(List.of("ADMIN"));
    }

    @Test
    void missingTopLevelClaimReturnsNull() {
        Map<String, Object> claims = Map.of("sub", "alice");
        assertThat(SecurityConfig.readClaim(claims, "roles")).isNull();
    }

    @Test
    void missingIntermediateSegmentReturnsNull() {
        Map<String, Object> claims = Map.of("foo", Map.of("bar", "baz"));
        assertThat(SecurityConfig.readClaim(claims, "foo.missing.deep")).isNull();
    }

    @Test
    void leafThatIsNotAMapShortCircuitsAtNextSegment() {
        Map<String, Object> claims = Map.of("foo", "string-not-map");
        assertThat(SecurityConfig.readClaim(claims, "foo.deeper")).isNull();
    }

    @Test
    void blankOrNullPathReturnsNull() {
        Map<String, Object> claims = Map.of("roles", List.of("X"));
        assertThat(SecurityConfig.readClaim(claims, null)).isNull();
        assertThat(SecurityConfig.readClaim(claims, "")).isNull();
        assertThat(SecurityConfig.readClaim(claims, "   ")).isNull();
    }

    @Test
    void nullClaimsReturnNull() {
        assertThat(SecurityConfig.readClaim(null, "roles")).isNull();
    }

    @Test
    void claimValueOfNullIsTreatedAsAbsent() {
        // Java's Map.of() doesn't allow null values, so use HashMap.
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("realm_access", null);
        assertThat(SecurityConfig.readClaim(claims, "realm_access.roles")).isNull();
    }

    @Test
    void stringClaimReturnsTheStringForCsvHandling() {
        // Some IdPs emit roles as a comma-separated string. The converter
        // handles that shape — readClaim just returns whatever the claim
        // is, the type-discrimination is in jwtAuthConverter().
        Map<String, Object> claims = Map.of("roles", "ENGINEER,TECH_LEAD,ADMIN");
        assertThat(SecurityConfig.readClaim(claims, "roles"))
                .isEqualTo("ENGINEER,TECH_LEAD,ADMIN");
    }
}
