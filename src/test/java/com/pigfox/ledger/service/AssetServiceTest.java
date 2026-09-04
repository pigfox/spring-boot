package com.pigfox.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pigfox.ledger.TestFixtures;
import com.pigfox.ledger.chain.AnchorResult;
import com.pigfox.ledger.chain.AnchorService;
import com.pigfox.ledger.crypto.PayloadHasher;
import com.pigfox.ledger.crypto.SignatureService;
import com.pigfox.ledger.domain.Asset;
import com.pigfox.ledger.domain.AssetEvent;
import com.pigfox.ledger.domain.AssetNotFoundException;
import com.pigfox.ledger.kafka.AssetEventPublisher;
import com.pigfox.ledger.repository.AssetRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The hasher and signer here are the real implementations, not mocks. Registration and
 * verification are two halves of one cryptographic round trip, and stubbing either half
 * would let the pair drift out of agreement without a test noticing.
 */
class AssetServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");
    private static final String TX_HASH = "0xfeed";

    private AssetRepository repository;
    private AnchorService anchors;
    private AssetEventPublisher publisher;
    private SimpleMeterRegistry meterRegistry;
    private SignatureService signatures;
    private PayloadHasher hasher;
    private AssetService service;

    @BeforeEach
    void setUp() {
        repository = new AssetRepository();
        anchors = mock(AnchorService.class);
        publisher = mock(AssetEventPublisher.class);
        meterRegistry = new SimpleMeterRegistry();
        hasher = new PayloadHasher();
        signatures = new SignatureService(TestFixtures.properties());
        when(anchors.anchor(any())).thenReturn(AnchorResult.anchored(TX_HASH));
        when(anchors.signerOf(any())).thenReturn(Optional.empty());
        service = new AssetService(repository, hasher, signatures, anchors, publisher,
                meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC), () -> "asset-1");
    }

    private Asset register() {
        return service.register("Unit 4B", "REAL_ESTATE", "Estate JV", Map.of("jurisdiction", "GB"));
    }

    @Test
    @DisplayName("registration hashes, signs, anchors, stores and stamps the asset")
    void registersAsset() {
        Asset asset = register();

        assertThat(asset.id()).isEqualTo("asset-1");
        assertThat(asset.createdAt()).isEqualTo(NOW);
        assertThat(asset.payloadHash())
                .isEqualTo(hasher.hashHex("Unit 4B", "REAL_ESTATE", "Estate JV",
                        Map.of("jurisdiction", "GB")));
        assertThat(asset.signature()).matches("^0x[0-9a-f]{130}$");
        assertThat(asset.signerAddress()).isEqualTo(TestFixtures.ADDRESS);
        assertThat(asset.anchorTxHash()).isEqualTo(TX_HASH);
        assertThat(asset.anchored()).isTrue();
        assertThat(repository.findById("asset-1")).contains(asset);
    }

    @Test
    @DisplayName("the signature stored at registration verifies against the stored signer")
    void storedSignatureVerifies() {
        Asset asset = register();

        byte[] hash = hasher.hash(asset.name(), asset.assetType(), asset.owner(), asset.metadata());

        assertThat(signatures.verify(hash, asset.signature(), asset.signerAddress())).isTrue();
    }

    @Test
    @DisplayName("registration increments the domain counter")
    void countsRegistrations() {
        register();

        assertThat(meterRegistry.get("ledger.assets.created").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("ledger.assets.anchor.failures").counter().count()).isZero();
    }

    @Test
    @DisplayName("an unavailable chain still yields a stored, signed asset")
    void registersWithoutAnchor() {
        when(anchors.anchor(any())).thenReturn(AnchorResult.none(AnchorResult.Status.UNREACHABLE));

        Asset asset = register();

        assertThat(asset.anchorTxHash()).isNull();
        assertThat(asset.anchored()).isFalse();
        assertThat(asset.signature()).isNotBlank();
        assertThat(meterRegistry.get("ledger.assets.anchor.failures").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("ledger.assets.created").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("registration publishes an ASSET_REGISTERED event describing the asset")
    void publishesEvent() {
        Asset asset = register();

        ArgumentCaptor<AssetEvent> captor = ArgumentCaptor.forClass(AssetEvent.class);
        verify(publisher).publish(captor.capture());
        AssetEvent event = captor.getValue();

        assertThat(event.eventType()).isEqualTo(AssetEvent.ASSET_REGISTERED);
        assertThat(event.assetId()).isEqualTo(asset.id());
        assertThat(event.payloadHash()).isEqualTo(asset.payloadHash());
        assertThat(event.signerAddress()).isEqualTo(asset.signerAddress());
        assertThat(event.anchorTxHash()).isEqualTo(TX_HASH);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.eventId()).isNotBlank().isNotEqualTo(asset.id());
    }

    @Test
    @DisplayName("null metadata is accepted and normalised")
    void registersWithoutMetadata() {
        Asset asset = service.register("n", "t", "o", null);

        assertThat(asset.metadata()).isEmpty();
        assertThat(asset.payloadHash()).isEqualTo(hasher.hashHex("n", "t", "o", Map.of()));
    }

    @Test
    @DisplayName("listing delegates to the repository ordering")
    void listsAssets() {
        register();

        assertThat(service.findAll()).extracting(Asset::id).containsExactly("asset-1");
    }

    @Test
    @DisplayName("an empty node lists nothing")
    void listsNothingWhenEmpty() {
        assertThat(service.findAll()).isEmpty();
    }

    @Test
    @DisplayName("fetching by id returns the stored asset")
    void findsById() {
        Asset asset = register();

        assertThat(service.findById("asset-1")).isEqualTo(asset);
    }

    @Test
    @DisplayName("fetching an unknown id fails with a not-found error")
    void failsForUnknownId() {
        assertThatThrownBy(() -> service.findById("nope"))
                .isInstanceOf(AssetNotFoundException.class)
                .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("verifying an untouched asset recovers the signer and reports valid")
    void verifiesGenuineAsset() {
        Asset asset = register();

        AssetService.Verification verification = service.verify(asset.id());

        assertThat(verification.signatureValid()).isTrue();
        assertThat(verification.recoveredAddress()).isEqualTo(TestFixtures.ADDRESS);
        assertThat(verification.payloadHash()).isEqualTo(asset.payloadHash());
        assertThat(verification.anchored()).isTrue();
        assertThat(verification.anchorTxHash()).isEqualTo(TX_HASH);
        assertThat(verification.asset()).isEqualTo(asset);
    }

    @Test
    @DisplayName("a tampered field changes the recomputed hash and fails verification")
    void detectsTamperedPayload() {
        Asset asset = register();
        repository.save(new Asset(asset.id(), "Tampered name", asset.assetType(), asset.owner(),
                asset.metadata(), asset.payloadHash(), asset.signature(), asset.signerAddress(),
                asset.anchorTxHash(), asset.createdAt()));

        AssetService.Verification verification = service.verify(asset.id());

        assertThat(verification.signatureValid()).isFalse();
        assertThat(verification.payloadHash()).isNotEqualTo(asset.payloadHash());
    }

    @Test
    @DisplayName("tampered metadata fails verification")
    void detectsTamperedMetadata() {
        Asset asset = register();
        repository.save(new Asset(asset.id(), asset.name(), asset.assetType(), asset.owner(),
                Map.of("jurisdiction", "FR"), asset.payloadHash(), asset.signature(),
                asset.signerAddress(), asset.anchorTxHash(), asset.createdAt()));

        assertThat(service.verify(asset.id()).signatureValid()).isFalse();
    }

    @Test
    @DisplayName("a signature swapped for another key's fails verification")
    void detectsForeignSignature() {
        Asset asset = register();
        SignatureService other = new SignatureService(
                TestFixtures.withSigningKey(TestFixtures.OTHER_PRIVATE_KEY, false, ""));
        byte[] hash = hasher.hash(asset.name(), asset.assetType(), asset.owner(), asset.metadata());
        repository.save(new Asset(asset.id(), asset.name(), asset.assetType(), asset.owner(),
                asset.metadata(), asset.payloadHash(), other.sign(hash), asset.signerAddress(),
                asset.anchorTxHash(), asset.createdAt()));

        AssetService.Verification verification = service.verify(asset.id());

        assertThat(verification.signatureValid()).isFalse();
        assertThat(verification.recoveredAddress()).isEqualTo(TestFixtures.OTHER_ADDRESS);
    }

    @Test
    @DisplayName("an unrecoverable signature reports no recovered address")
    void reportsUnrecoverableSignature() {
        Asset asset = register();
        repository.save(new Asset(asset.id(), asset.name(), asset.assetType(), asset.owner(),
                asset.metadata(), asset.payloadHash(), "0x" + "00".repeat(65),
                asset.signerAddress(), asset.anchorTxHash(), asset.createdAt()));

        AssetService.Verification verification = service.verify(asset.id());

        assertThat(verification.signatureValid()).isFalse();
        assertThat(verification.recoveredAddress()).isNull();
    }

    @Test
    @DisplayName("a hash the chain knows counts as anchored even without a stored tx hash")
    void treatsOnChainRecordAsAnchored() {
        when(anchors.anchor(any())).thenReturn(AnchorResult.none(AnchorResult.Status.UNREACHABLE));
        when(anchors.signerOf(any())).thenReturn(Optional.of(TestFixtures.ADDRESS));
        Asset asset = register();

        AssetService.Verification verification = service.verify(asset.id());

        assertThat(verification.anchored()).isTrue();
        assertThat(verification.anchorTxHash()).isNull();
        assertThat(verification.signatureValid()).isTrue();
    }

    @Test
    @DisplayName("an asset neither anchored locally nor on chain reports unanchored")
    void reportsUnanchored() {
        when(anchors.anchor(any())).thenReturn(AnchorResult.none(AnchorResult.Status.DISABLED));
        Asset asset = register();

        AssetService.Verification verification = service.verify(asset.id());

        assertThat(verification.anchored()).isFalse();
        assertThat(verification.signatureValid()).isTrue();
    }

    @Test
    @DisplayName("verifying an unknown id fails with a not-found error and touches no chain")
    void failsVerifyingUnknownAsset() {
        assertThatThrownBy(() -> service.verify("nope"))
                .isInstanceOf(AssetNotFoundException.class);
        verify(anchors, never()).signerOf(any());
    }

    @Test
    @DisplayName("the production constructor wires a real clock and identifier source")
    void productionConstructorGeneratesDistinctIds() {
        AssetService production = new AssetService(
                repository, hasher, signatures, anchors, publisher, meterRegistry);

        Asset first = production.register("a", "t", "o", Map.of());
        Asset second = production.register("a", "t", "o", Map.of());

        assertThat(List.of(first.id(), second.id())).doesNotHaveDuplicates();
        assertThat(first.createdAt()).isAfter(Instant.EPOCH);
        assertThat(first.payloadHash()).isEqualTo(second.payloadHash());
    }
}
