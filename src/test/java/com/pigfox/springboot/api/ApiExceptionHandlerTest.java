package com.pigfox.springboot.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.pigfox.springboot.api.dto.CreateAssetRequest;
import com.pigfox.springboot.domain.AssetNotFoundException;
import com.pigfox.springboot.domain.InvalidCredentialsException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("an unknown asset becomes a 404 problem detail")
    void mapsNotFound() {
        ProblemDetail problem = handler.handleNotFound(new AssetNotFoundException("a1"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Asset not found");
        assertThat(problem.getDetail()).contains("a1");
    }

    @Test
    @DisplayName("bad credentials become a 401 that names no specific credential")
    void mapsInvalidCredentials() {
        ProblemDetail problem = handler.handleInvalidCredentials(new InvalidCredentialsException());

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(problem.getTitle()).isEqualTo("Invalid client credentials");
        assertThat(problem.getDetail()).isEqualTo("Invalid client credentials");
    }

    @Test
    @DisplayName("validation failures become a 400 listing every offending field, in order")
    void mapsValidationFailure() throws Exception {
        ProblemDetail problem = handler.handleValidation(validationFailure(
                Map.of("name", "must not be blank", "assetType", "must not be blank")));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Validation failure");
        assertThat(problem.getDetail())
                .isEqualTo("assetType must not be blank; name must not be blank");
    }

    @Test
    @DisplayName("a single field failure reads cleanly")
    void mapsSingleValidationFailure() throws Exception {
        ProblemDetail problem =
                handler.handleValidation(validationFailure(Map.of("owner", "must not be blank")));

        assertThat(problem.getDetail()).isEqualTo("owner must not be blank");
    }

    @Test
    @DisplayName("a validation failure with no field errors still produces a usable message")
    void mapsEmptyValidationFailure() throws Exception {
        ProblemDetail problem = handler.handleValidation(validationFailure(Map.of()));

        assertThat(problem.getDetail()).isEqualTo("Request validation failed");
    }

    @Test
    @DisplayName("an illegal argument reaching the handler becomes a 400, not a 500")
    void mapsIllegalArgument() {
        ProblemDetail problem =
                handler.handleIllegalArgument(new IllegalArgumentException("Payload hash must be 32 bytes"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Invalid request");
        assertThat(problem.getDetail()).isEqualTo("Payload hash must be 32 bytes");
    }

    private MethodArgumentNotValidException validationFailure(Map<String, String> fieldErrors)
            throws NoSuchMethodException {
        CreateAssetRequest target = new CreateAssetRequest(null, null, null, Map.of());
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(target, "createAssetRequest");
        fieldErrors.forEach((field, message) -> binding.rejectValue(field, "Invalid", message));
        MethodParameter parameter = new MethodParameter(
                AssetController.class.getDeclaredMethod("createAsset", CreateAssetRequest.class), 0);
        return new MethodArgumentNotValidException(parameter, binding);
    }
}
