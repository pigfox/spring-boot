package com.pigfox.springboot.api;

import com.pigfox.springboot.api.dto.AssetResponse;
import com.pigfox.springboot.api.dto.CreateAssetRequest;
import com.pigfox.springboot.api.dto.VerificationResponse;
import com.pigfox.springboot.service.AssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asset routes from the contract.
 *
 * <p>Each method carries its own {@link PreAuthorize} check. That is deliberate
 * duplication of the URL rules in {@code SecurityConfig}: the two gates fail
 * independently, so a mistyped path matcher does not by itself expose a write.
 */
@RestController
@RequestMapping(value = "/api/v1/assets", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Assets", description = "Asset registration, retrieval and cryptographic verification.")
public class AssetController {

    /** Scope required to register or verify. */
    static final String WRITE = "hasAuthority('SCOPE_ledger.write')";
    /** Scope required to read. */
    static final String READ = "hasAuthority('SCOPE_ledger.read')";

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private final AssetService assetService;
    private final Clock clock;

    @Autowired
    public AssetController(AssetService assetService) {
        this(assetService, Clock.systemUTC());
    }

    /** Test-facing constructor, allowing an exact verification timestamp. */
    AssetController(AssetService assetService, Clock clock) {
        this.assetService = assetService;
        this.clock = clock;
    }

    /**
     * @param request asset to register
     * @return 201 with the stored asset and a Location header
     */
    @PostMapping(consumes = JSON)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WRITE)
    @Operation(operationId = "createAsset", summary = "Register an asset",
            description = "Hashes and signs the payload, attempts a chain anchor, "
                    + "and publishes an asset.events record.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Asset registered.",
                    content = @Content(mediaType = JSON,
                            schema = @Schema(implementation = AssetResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failure.",
                    content = @Content(mediaType = JSON,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = JSON,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Token lacks the ledger.write scope.",
                    content = @Content(mediaType = JSON,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<AssetResponse> createAsset(@Valid @RequestBody CreateAssetRequest request) {
        AssetResponse response = AssetResponse.from(assetService.register(
                request.name(), request.assetType(), request.owner(), request.metadata()));
        return ResponseEntity
                .created(URI.create("/api/v1/assets/" + response.id()))
                .body(response);
    }

    /** @return every asset held by this node, newest first */
    @GetMapping
    @PreAuthorize(READ)
    @Operation(operationId = "listAssets", summary = "List registered assets",
            description = "Returns every asset known to this node, newest first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assets returned.",
                    content = @Content(mediaType = JSON,
                            array = @ArraySchema(schema = @Schema(implementation = AssetResponse.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = JSON,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<AssetResponse> listAssets() {
        return assetService.findAll().stream().map(AssetResponse::from).toList();
    }

    /**
     * @param id asset identifier
     * @return the asset
     */
    @GetMapping("/{id}")
    @PreAuthorize(READ)
    @Operation(operationId = "getAsset", summary = "Fetch one asset")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Asset found.",
                    content = @Content(mediaType = JSON,
                            schema = @Schema(implementation = AssetResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = JSON,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such asset.",
                    content = @Content(mediaType = JSON,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AssetResponse getAsset(
            @Parameter(description = "Asset identifier returned at registration.")
            @PathVariable String id) {
        return AssetResponse.from(assetService.findById(id));
    }

    /**
     * @param id asset identifier
     * @return the verification outcome; a failed check is a 200 with
     *         {@code signatureValid=false}, not an error status
     */
    @PostMapping("/{id}/verify")
    @PreAuthorize(WRITE)
    @Operation(operationId = "verifyAsset", summary = "Verify an asset signature and anchor",
            description = "Recomputes the payload hash, recovers the signer from the signature, "
                    + "and reports anchor status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Verification completed. Check signatureValid for the outcome.",
                    content = @Content(mediaType = JSON,
                            schema = @Schema(implementation = VerificationResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = JSON,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Token lacks the ledger.write scope.",
                    content = @Content(mediaType = JSON,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such asset.",
                    content = @Content(mediaType = JSON,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    public VerificationResponse verifyAsset(
            @Parameter(description = "Asset identifier returned at registration.")
            @PathVariable String id) {
        return VerificationResponse.from(assetService.verify(id), Instant.now(clock));
    }
}
