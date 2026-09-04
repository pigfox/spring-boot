package com.pigfox.ledger.api;

import com.pigfox.ledger.api.dto.TokenRequest;
import com.pigfox.ledger.api.dto.TokenResponse;
import com.pigfox.ledger.auth.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one unauthenticated write route, implementing {@code POST /api/v1/auth/token} from
 * the contract. {@link SecurityRequirements} with no entries is what tells springdoc this
 * operation takes no bearer token, matching {@code security: []} in the spec.
 */
@RestController
@RequestMapping(value = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Authentication", description = "Token issuance for the zero-trust perimeter.")
public class AuthController {

    private final TokenService tokenService;

    public AuthController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * @param request client credentials
     * @return a bearer token
     */
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements
    @Operation(operationId = "issueToken", summary = "Issue an access token",
            description = "Exchanges client credentials for a short-lived HS256 JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token issued.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TokenResponse.class))),
            @ApiResponse(responseCode = "400", description = "Malformed request.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Invalid client credentials.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TokenResponse> issueToken(@Valid @RequestBody TokenRequest request) {
        TokenService.IssuedToken issued = tokenService.issue(request.clientId(), request.clientSecret());
        return ResponseEntity.ok(
                TokenResponse.bearer(issued.token(), issued.expiresIn(), issued.scope()));
    }
}
