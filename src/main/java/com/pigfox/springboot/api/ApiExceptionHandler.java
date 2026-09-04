package com.pigfox.springboot.api;

import com.pigfox.springboot.domain.AssetNotFoundException;
import com.pigfox.springboot.domain.InvalidCredentialsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain failures onto RFC 7807 problem details.
 *
 * <p>Note what is absent: there is no handler for {@code Exception} or for
 * {@code AccessDeniedException}. A catch-all here would intercept the authorization
 * failures that {@code @PreAuthorize} raises and answer 500 instead of letting Spring
 * Security's translation filter answer 403, so the security decision is deliberately left
 * to the framework.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * @param e the unknown-asset failure
     * @return 404 problem detail
     */
    @ExceptionHandler(AssetNotFoundException.class)
    public ProblemDetail handleNotFound(AssetNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Asset not found");
        return problem;
    }

    /**
     * @param e the credential failure
     * @return 401 problem detail carrying no detail about which credential was wrong
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("Invalid client credentials");
        return problem;
    }

    /**
     * @param e the bean validation failure
     * @return 400 problem detail listing the offending fields
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("Request validation failed");
        log.debug("Rejected invalid request: {}", detail);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Validation failure");
        return problem;
    }

    /**
     * @param e a malformed argument reaching the service layer, such as a hash of the
     *          wrong length
     * @return 400 problem detail
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }
}
