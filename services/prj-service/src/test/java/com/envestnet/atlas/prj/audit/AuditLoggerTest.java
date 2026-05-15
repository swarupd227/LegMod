package com.envestnet.atlas.prj.audit;

import com.envestnet.atlas.prj.auth.GatewayIdentity;
import com.envestnet.atlas.prj.auth.WorkspaceAccess;
import com.envestnet.atlas.prj.domain.WorkspaceMember;
import com.envestnet.atlas.prj.logging.RequestContextFilter;
import com.envestnet.atlas.prj.repo.WorkspaceMemberRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-unit coverage of {@link AuditLogger}.
 *
 * <p>Tests the writer's contract without booting a database: the
 * {@link PreparedStatementCreator} passed to {@code JdbcTemplate.update}
 * is captured and invoked against a mock {@link PreparedStatement}, so
 * we can assert exactly which parameter slots get set to which values.</p>
 *
 * <p>Three classes of behaviour pinned:
 *  • Happy path: every column populated with the right value, payload
 *    serialised through Jackson.
 *  • Role resolution: ADMIN-bypass vs. real membership vs. anonymous
 *    record different {@code user_role} values so investigations can
 *    tell them apart.
 *  • Failure-tolerance: a JdbcTemplate exception must NOT bubble up
 *    (the audit write is best-effort; the request being audited
 *    succeeds regardless).</p>
 */
class AuditLoggerTest {

    private JdbcTemplate jdbc;
    private WorkspaceMemberRepository members;
    private WorkspaceAccess access;
    private AuditLogger logger;

    private final UUID WS = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private final UUID PR = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        jdbc    = mock(JdbcTemplate.class);
        members = mock(WorkspaceMemberRepository.class);
        access  = new WorkspaceAccess(members);
        logger  = new AuditLogger(jdbc, access, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        MDC.clear();
    }

    /* ---------------- happy path: all fields populated ---------------- */

    @Test
    void successfulActionWritesEveryColumnAndJsonbPayload() throws Exception {
        bindIdentity("alice@envestnet.local", "ENGINEER");
        MDC.put(RequestContextFilter.MDC_REQUEST_ID, "req-abc-123");
        when(members.findByWorkspaceAndUser(eq(WS), eq("alice@envestnet.local")))
                .thenReturn(Optional.of(new WorkspaceMember(
                        UUID.randomUUID(), WS, "alice@envestnet.local",
                        "EDITOR", OffsetDateTime.now(), null)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", "passed");
        payload.put("attempts", 3);

        logger.record(WS, PR, "gate.advance", "gate", "B", payload);

        PreparedStatement ps = captureAndInvokePreparedStatement();

        // Column order: request_id, workspace_id, project_id, user_email,
        // user_role, action, entity_type, entity_id, outcome, payload.
        verify(ps).setString(1, "req-abc-123");
        verify(ps).setObject(2, WS, java.sql.Types.OTHER);
        verify(ps).setObject(3, PR, java.sql.Types.OTHER);
        verify(ps).setString(4, "alice@envestnet.local");
        verify(ps).setString(5, "EDITOR");          // not ADMIN_BYPASS
        verify(ps).setString(6, "gate.advance");
        verify(ps).setString(7, "gate");
        verify(ps).setString(8, "B");
        verify(ps).setString(9, "success");
        // Payload is jsonb-cast in SQL but the bound parameter is the
        // JSON string; assert it serialised both keys in order.
        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(eq(10), payloadCap.capture());
        assertThat(payloadCap.getValue())
                .contains("\"state\":\"passed\"")
                .contains("\"attempts\":3");
    }

    @Test
    void failureActionRecordsOutcomeFailure() throws Exception {
        bindIdentity("alice@envestnet.local", "ENGINEER");
        when(members.findByWorkspaceAndUser(eq(WS), eq("alice@envestnet.local")))
                .thenReturn(Optional.of(new WorkspaceMember(
                        UUID.randomUUID(), WS, "alice@envestnet.local",
                        "OWNER", OffsetDateTime.now(), null)));

        logger.recordFailure(WS, PR, "gate.advance", "gate", "B",
                Map.of("error", "downstream 500"));

        PreparedStatement ps = captureAndInvokePreparedStatement();
        verify(ps).setString(9, "failure");
    }

    /* ---------------- role resolution ---------------- */

    @Test
    void adminBypassIsRecordedDistinctFromRealMembership() throws Exception {
        // alice has NO membership row but carries the platform ADMIN
        // role. The audit row must record ADMIN_BYPASS, not the
        // resolved Level — investigations need to distinguish
        // "support engineer triaging" from "engineer with real access".
        bindIdentity("alice@envestnet.local", "ADMIN");
        when(members.findByWorkspaceAndUser(eq(WS), eq("alice@envestnet.local")))
                .thenReturn(Optional.empty());

        logger.record(WS, PR, "gate.advance", "gate", "B", Map.of());

        PreparedStatement ps = captureAndInvokePreparedStatement();
        verify(ps).setString(5, AuditLogger.ROLE_ADMIN_BYPASS);
    }

    @Test
    void unauthenticatedRequestRecordsUnauthenticatedRole() throws Exception {
        // No GatewayIdentity bound — should still write the row (for
        // /internal/* paths that legitimately run without identity)
        // with user_role='unauthenticated' so the absence is auditable.
        logger.record(WS, PR, "internal.seed", "workspace", WS.toString(), Map.of());

        PreparedStatement ps = captureAndInvokePreparedStatement();
        verify(ps).setNull(4, java.sql.Types.VARCHAR);
        verify(ps).setString(5, AuditLogger.ROLE_UNAUTHENTICATED);
    }

    /* ---------------- failure tolerance ---------------- */

    @Test
    void jdbcExceptionsAreSwallowedSoTheAuditLayerCannotBreakTheRequest() {
        // The audit write must NEVER bubble — losing an audit row is
        // bad but breaking the user's actual work is worse.
        when(jdbc.update(any(PreparedStatementCreator.class)))
                .thenThrow(new RuntimeException("audit table unavailable"));

        logger.record(WS, PR, "gate.advance", "gate", "B", Map.of("state", "passed"));

        // No assertion needed beyond "didn't throw" — the test passes
        // if execution reaches this point.
    }

    @Test
    void unserialisablePayloadFallsBackToStubPayload() throws Exception {
        // A payload Jackson can't serialise (e.g., a self-referential
        // object) must NOT lose the entire audit row — instead the
        // writer records a sentinel payload that names the failure.
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("self", bad);                       // creates a cycle

        // Use a real Jackson mapper for this test so the failure is real.
        AuditLogger realLogger = new AuditLogger(jdbc, access, new ObjectMapper());
        realLogger.record(WS, PR, "weird.action", "thing", "1", bad);

        PreparedStatement ps = captureAndInvokePreparedStatement();
        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(eq(10), payloadCap.capture());
        assertThat(payloadCap.getValue()).contains("_serialization_error");
    }

    @Test
    void nullPayloadIsSerializedAsEmptyJsonObject() throws Exception {
        bindIdentity("alice@envestnet.local", "ENGINEER");
        when(members.findByWorkspaceAndUser(eq(WS), eq("alice@envestnet.local")))
                .thenReturn(Optional.of(new WorkspaceMember(
                        UUID.randomUUID(), WS, "alice@envestnet.local",
                        "OWNER", OffsetDateTime.now(), null)));

        logger.record(WS, PR, "action.with.no.payload", "thing", "1", null);

        PreparedStatement ps = captureAndInvokePreparedStatement();
        verify(ps).setString(10, "{}");
    }

    /* ---------------- helpers ---------------- */

    /**
     * Pull the {@link PreparedStatementCreator} that the production
     * code passes to {@code JdbcTemplate.update}, invoke it against a
     * mock {@link Connection}, and return the mock {@link PreparedStatement}
     * so tests can verify which slots got set.
     */
    private PreparedStatement captureAndInvokePreparedStatement() throws Exception {
        ArgumentCaptor<PreparedStatementCreator> creator =
                ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbc).update(creator.capture());

        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(con.prepareStatement(any(String.class))).thenReturn(ps);
        creator.getValue().createPreparedStatement(con);
        return ps;
    }

    private static void bindIdentity(String email, String rolesCsv) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(GatewayIdentity.HDR_EMAIL, email);
        req.addHeader(GatewayIdentity.HDR_ROLES, rolesCsv);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }
}
