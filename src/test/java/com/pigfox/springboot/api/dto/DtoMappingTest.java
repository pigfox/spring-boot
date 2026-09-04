package com.pigfox.springboot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.pigfox.springboot.domain.Asset;
import com.pigfox.springboot.service.AssetService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DtoMappingTest {

    private static final Instant CREATED = Instant.parse("2026-09-03T12:00:00Z");
    private static final Instant VERIFIED = Instant.parse("2026-09-03T12:05:00Z");

    private Asset asset(String anchorTxHash) {
        return new Asset("a1", "Unit 4B", "REAL_ESTATE", "Estate JV", Map.of("k", "v"),
                "0xhash", "0xsig", "0xsigner", anchorTxHash, CREATED);
    }

    @Test
    @DisplayName("an anchored asset maps every field onto the response")
    void mapsAnchoredAsset() {
        AssetResponse response = AssetResponse.from(asset("0xtx"));

        assertThat(response.id()).isEqualTo("a1");
        assertThat(response.name()).isEqualTo("Unit 4B");
        assertThat(response.assetType()).isEqualTo("REAL_ESTATE");
        assertThat(response.owner()).isEqualTo("Estate JV");
        assertThat(response.metadata()).containsExactly(Map.entry("k", "v"));
        assertThat(response.payloadHash()).isEqualTo("0xhash");
        assertThat(response.signature()).isEqualTo("0xsig");
        assertThat(response.signerAddress()).isEqualTo("0xsigner");
        assertThat(response.anchored()).isTrue();
        assertThat(response.anchorTxHash()).isEqualTo("0xtx");
        assertThat(response.createdAt()).isEqualTo(CREATED);
    }

    @Test
    @DisplayName("an unanchored asset maps to anchored=false with no transaction hash")
    void mapsUnanchoredAsset() {
        AssetResponse response = AssetResponse.from(asset(null));

        assertThat(response.anchored()).isFalse();
        assertThat(response.anchorTxHash()).isNull();
    }

    @Test
    @DisplayName("a verification maps onto the response, including the stored signer")
    void mapsVerification() {
        AssetService.Verification verification = new AssetService.Verification(
                asset("0xtx"), "0xhash", true, "0xrecovered", true, "0xtx");

        VerificationResponse response = VerificationResponse.from(verification, VERIFIED);

        assertThat(response.assetId()).isEqualTo("a1");
        assertThat(response.payloadHash()).isEqualTo("0xhash");
        assertThat(response.signatureValid()).isTrue();
        assertThat(response.recoveredAddress()).isEqualTo("0xrecovered");
        assertThat(response.signerAddress()).isEqualTo("0xsigner");
        assertThat(response.anchored()).isTrue();
        assertThat(response.anchorTxHash()).isEqualTo("0xtx");
        assertThat(response.verifiedAt()).isEqualTo(VERIFIED);
    }

    @Test
    @DisplayName("a failed verification maps to signatureValid=false with no recovered address")
    void mapsFailedVerification() {
        AssetService.Verification verification = new AssetService.Verification(
                asset(null), "0xother", false, null, false, null);

        VerificationResponse response = VerificationResponse.from(verification, VERIFIED);

        assertThat(response.signatureValid()).isFalse();
        assertThat(response.recoveredAddress()).isNull();
        assertThat(response.anchored()).isFalse();
        assertThat(response.anchorTxHash()).isNull();
    }

    @Test
    @DisplayName("a token response is always of type Bearer")
    void buildsBearerToken() {
        TokenResponse response = TokenResponse.bearer("token-value", 900L, "ledger.read");

        assertThat(response.accessToken()).isEqualTo("token-value");
        assertThat(response.tokenType()).isEqualTo(TokenResponse.BEARER).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900L);
        assertThat(response.scope()).isEqualTo("ledger.read");
    }

    @Test
    @DisplayName("request records expose what was submitted")
    void exposesRequestFields() {
        TokenRequest token = new TokenRequest("client", "secret");
        CreateAssetRequest create = new CreateAssetRequest("n", "t", "o", Map.of("k", "v"));

        assertThat(token.clientId()).isEqualTo("client");
        assertThat(token.clientSecret()).isEqualTo("secret");
        assertThat(create.name()).isEqualTo("n");
        assertThat(create.assetType()).isEqualTo("t");
        assertThat(create.owner()).isEqualTo("o");
        assertThat(create.metadata()).containsExactly(Map.entry("k", "v"));
    }
}
