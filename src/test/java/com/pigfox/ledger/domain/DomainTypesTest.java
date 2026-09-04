package com.pigfox.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DomainTypesTest {

    private Asset asset(String anchorTxHash, Map<String, String> metadata) {
        return new Asset("id", "name", "type", "owner", metadata, "0xhash", "0xsig",
                "0xsigner", anchorTxHash, Instant.EPOCH);
    }

    @Test
    @DisplayName("an asset with a transaction hash reports itself anchored")
    void reportsAnchored() {
        assertThat(asset("0xtx", Map.of()).anchored()).isTrue();
    }

    @ParameterizedTest
    @DisplayName("an asset without a usable transaction hash reports itself unanchored")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void reportsUnanchored(String anchorTxHash) {
        assertThat(asset(anchorTxHash, Map.of()).anchored()).isFalse();
    }

    @Test
    @DisplayName("metadata is copied, so a caller cannot mutate a stored asset")
    void copiesMetadata() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("k", "v");
        Asset stored = asset("0xtx", mutable);

        mutable.put("injected", "value");

        assertThat(stored.metadata()).containsExactly(Map.entry("k", "v"));
        assertThatThrownBy(() -> stored.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null metadata becomes an empty map")
    void defaultsNullMetadata() {
        assertThat(asset("0xtx", null).metadata()).isEmpty();
    }

    @Test
    @DisplayName("the registration event type is the documented constant")
    void exposesEventType() {
        AssetEvent event = new AssetEvent("e1", AssetEvent.ASSET_REGISTERED, "a1", "0xhash",
                "0xsigner", "0xtx", Instant.EPOCH);

        assertThat(AssetEvent.ASSET_REGISTERED).isEqualTo("ASSET_REGISTERED");
        assertThat(event.eventType()).isEqualTo(AssetEvent.ASSET_REGISTERED);
        assertThat(event.eventId()).isEqualTo("e1");
        assertThat(event.assetId()).isEqualTo("a1");
        assertThat(event.payloadHash()).isEqualTo("0xhash");
        assertThat(event.signerAddress()).isEqualTo("0xsigner");
        assertThat(event.anchorTxHash()).isEqualTo("0xtx");
        assertThat(event.occurredAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    @DisplayName("a not-found failure names the asset it could not find")
    void notFoundCarriesAssetId() {
        AssetNotFoundException e = new AssetNotFoundException("missing-id");

        assertThat(e.getAssetId()).isEqualTo("missing-id");
        assertThat(e).hasMessage("No asset with id missing-id");
    }

    @Test
    @DisplayName("the credential failure says nothing about which credential was wrong")
    void credentialFailureIsGeneric() {
        assertThat(new InvalidCredentialsException()).hasMessage("Invalid client credentials");
    }
}
