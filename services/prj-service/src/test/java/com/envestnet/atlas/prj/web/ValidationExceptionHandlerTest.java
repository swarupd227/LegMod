package com.envestnet.atlas.prj.web;

import com.fasterxml.jackson.core.JsonParseException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit coverage of {@link ValidationExceptionHandler}.
 *
 * <p>Three handlers, three test groups:
 *  - Bean-Validation failures (@Valid @RequestBody)
 *  - Malformed JSON bodies
 *  - Path/query type-conversion failures
 *
 * <p>Each group pins the contract the SPA / customer SDK consumers
 * depend on — status 400, `error` discriminator, `fieldErrors` array,
 * timestamp.</p>
 */
class ValidationExceptionHandlerTest {

    private final ValidationExceptionHandler handler = new ValidationExceptionHandler();

    /* ---------------- @Valid @RequestBody failures ---------------- */

    @Test
    void validationFailureReturns400WithFieldLevelErrorMap() throws Exception {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "createProjectRequest");
        bindingResult.addError(new FieldError("createProjectRequest", "name", "ab",
                false, null, null, "size must be between 3 and 200"));
        bindingResult.addError(new FieldError("createProjectRequest", "mode", "xyz",
                false, null, null, "mode must be one of SOAP, UPLIFT"));

        var ex = new MethodArgumentNotValidException(stubParameter(), bindingResult);

        ResponseEntity<Map<String, Object>> resp = handler.onValidation(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("error")).isEqualTo("validation_failed");
        assertThat(body.get("status")).isEqualTo(400);
        assertThat(body.get("message")).isEqualTo("request body failed validation");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fieldErrors = (List<Map<String, Object>>) body.get("fieldErrors");
        assertThat(fieldErrors).hasSize(2);
        assertThat(fieldErrors).extracting(m -> m.get("field"))
                .containsExactlyInAnyOrder("name", "mode");
        assertThat(fieldErrors).allSatisfy(m -> {
            assertThat(m).containsKeys("field", "rejected", "message");
        });
        assertThat(body.get("timestamp").toString()).isNotBlank();
    }

    @Test
    void validationFailureWithSingleErrorEmitsSingletonArray() throws Exception {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "attachSourceRequest");
        bindingResult.addError(new FieldError("attachSourceRequest", "path", "",
                false, null, null, "must not be blank"));

        var ex = new MethodArgumentNotValidException(stubParameter(), bindingResult);

        Map<String, Object> body = handler.onValidation(ex).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fieldErrors = (List<Map<String, Object>>) body.get("fieldErrors");
        assertThat(fieldErrors).hasSize(1);
        assertThat(fieldErrors.get(0)).containsEntry("field", "path");
        assertThat(fieldErrors.get(0)).containsEntry("rejected", "");
        assertThat(fieldErrors.get(0).get("message").toString()).isEqualTo("must not be blank");
    }

    /* ---------------- malformed bodies ---------------- */

    @Test
    void malformedJsonReturns400WithoutLeakingParserDetails() throws Exception {
        // The wrapped Jackson exception includes the line/column of the
        // bad token; the handler must NOT surface that.
        InputStream raw = new ByteArrayInputStream("{not-json".getBytes());
        var cause = new JsonParseException(null, "Unexpected character ('n' (code 110)) in JSON at line 1, column 2");
        var ex = new HttpMessageNotReadableException("JSON parse error", cause, stubInputMessage(raw));

        ResponseEntity<Map<String, Object>> resp = handler.onUnreadable(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("error")).isEqualTo("malformed_body");
        assertThat(body.get("message").toString())
                .isEqualTo("request body could not be parsed as JSON");
        // Critically: the parser's "line X, column Y" detail does NOT
        // appear in the response. Customers' security scanners flag
        // information leakage on this exact pattern.
        assertThat(body.toString())
                .doesNotContain("line 1")
                .doesNotContain("Unexpected character")
                .doesNotContain("code 110");
    }

    /* ---------------- type-mismatch on path / query params ---------------- */

    @Test
    void typeMismatchOnUuidPathParamReturns400() throws Exception {
        var ex = new MethodArgumentTypeMismatchException(
                "not-a-uuid", UUID.class, "id", stubParameter(),
                new IllegalArgumentException("bad uuid"));

        ResponseEntity<Map<String, Object>> resp = handler.onTypeMismatch(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("error")).isEqualTo("type_mismatch");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errs = (List<Map<String, Object>>) body.get("fieldErrors");
        assertThat(errs).hasSize(1);
        assertThat(errs.get(0)).containsEntry("field", "id");
        assertThat(errs.get(0)).containsEntry("rejected", "not-a-uuid");
        assertThat(errs.get(0).get("message").toString()).contains("UUID");
    }

    /* ---------------- helpers ---------------- */

    private MethodParameter stubParameter() throws Exception {
        Method m = StubController.class.getDeclaredMethod("doNothing", String.class);
        return new MethodParameter(m, 0);
    }

    private HttpInputMessage stubInputMessage(InputStream body) {
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return body; }
            @Override public org.springframework.http.HttpHeaders getHeaders() {
                return new org.springframework.http.HttpHeaders();
            }
        };
    }

    /** Bound to ParameterRegistry — used only to give the test a
     *  MethodParameter to feed back into the validation exceptions. */
    @SuppressWarnings({"unused", "EmptyMethod"})
    static class StubController {
        public void doNothing(String s) {}
    }
}
