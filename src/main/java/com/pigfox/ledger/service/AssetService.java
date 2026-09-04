package com.pigfox.ledger.service;

import com.pigfox.ledger.chain.AnchorResult;
import com.pigfox.ledger.chain.AnchorService;
import com.pigfox.ledger.crypto.PayloadHasher;
import com.pigfox.ledger.crypto.SignatureService;
import com.pigfox.ledger.domain.Asset;
import com.pigfox.ledger.domain.AssetEvent;
import com.pigfox.ledger.domain.AssetNotFoundException;
import com.pigfox.ledger.kafka.AssetEventPublisher;
import com.pigfox.ledger.repository.AssetRepository;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.web3j.utils.Numeric;

/**
 * Registration and verification of assets.
 *
 * <p>Registration is ordered so that the parts that must not be lost happen first:
 * hash, sign, store. Anchoring and event publication follow, and neither can fail the
 * request — a hash whose signature verifies is a complete record on its own, and the
 * chain anchor and the event stream are corroboration layered on top.
 *
 * <p>{@link UUID} and {@link Clock} arrive as collaborators rather than being called
 * statically, which is what lets a test assert on an exact identifier and timestamp.
 */
@Service
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    private final AssetRepository repository;
    private final PayloadHasher hasher;
    private final SignatureService signatures;
    private final AnchorService anchors;
    private final AssetEventPublisher publisher;
    private final Clock clock;
    private final Supplier<String> idGenerator;
    private final Counter created;
    private final Counter anchorFailures;

    @Autowired
    public AssetService(
            AssetRepository repository,
            PayloadHasher hasher,
            SignatureService signatures,
            AnchorService anchors,
            AssetEventPublisher publisher,
            MeterRegistry meterRegistry) {
        this(repository, hasher, signatures, anchors, publisher, meterRegistry,
                Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    /**
     * Test-facing constructor.
     *
     * @param repository    asset store
     * @param hasher        canonicaliser and keccak256 digest
     * @param signatures    secp256k1 signer
     * @param anchors       chain anchor client
     * @param publisher     event publisher
     * @param meterRegistry metrics registry
     * @param clock         clock used for registration timestamps
     * @param idGenerator   supplier of asset identifiers
     */
    AssetService(
            AssetRepository repository,
            PayloadHasher hasher,
            SignatureService signatures,
            AnchorService anchors,
            AssetEventPublisher publisher,
            MeterRegistry meterRegistry,
            Clock clock,
            Supplier<String> idGenerator) {
        this.repository = repository;
        this.hasher = hasher;
        this.signatures = signatures;
        this.anchors = anchors;
        this.publisher = publisher;
        this.clock = clock;
        this.idGenerator = idGenerator;
        this.created = Counter.builder("ledger.assets.created")
                .description("Assets registered by this node")
                .register(meterRegistry);
        this.anchorFailures = Counter.builder("ledger.assets.anchor.failures")
                .description("Registrations that completed without a chain anchor")
                .register(meterRegistry);
    }

    /**
     * Registers an asset: hash, sign, anchor, store, announce.
     *
     * @param name      asset name
     * @param assetType asset classification
     * @param owner     owning party
     * @param metadata  additional attributes, may be null
     * @return the stored asset, including its signature and any anchor transaction
     */
    @Timed(value = "ledger.assets.create", description = "Time to register an asset")
    public Asset register(String name, String assetType, String owner, Map<String, String> metadata) {
        byte[] payloadHash = hasher.hash(name, assetType, owner, metadata);
        String signature = signatures.sign(payloadHash);
        AnchorResult anchor = anchors.anchor(payloadHash);
        if (!anchor.anchored()) {
            anchorFailures.increment();
        }
        Asset asset = new Asset(
                idGenerator.get(),
                name,
                assetType,
                owner,
                metadata,
                Numeric.toHexString(payloadHash),
                signature,
                signatures.signerAddress(),
                anchor.txHash(),
                Instant.now(clock));
        repository.save(asset);
        created.increment();
        log.info("Registered asset {} anchorStatus={}", asset.id(), anchor.status());
        publisher.publish(new AssetEvent(
                UUID.randomUUID().toString(),
                AssetEvent.ASSET_REGISTERED,
                asset.id(),
                asset.payloadHash(),
                asset.signerAddress(),
                asset.anchorTxHash(),
                asset.createdAt()));
        return asset;
    }

    /** @return every asset held by this node, newest first */
    public List<Asset> findAll() {
        return repository.findAll();
    }

    /**
     * @param id asset identifier
     * @return the asset
     * @throws AssetNotFoundException when no such asset exists
     */
    public Asset findById(String id) {
        return repository.findById(id).orElseThrow(() -> new AssetNotFoundException(id));
    }

    /**
     * Re-derives the payload hash, recovers the signer from the stored signature, and
     * reports what the chain holds for the same hash.
     *
     * @param id asset identifier
     * @return the verification outcome
     * @throws AssetNotFoundException when no such asset exists
     */
    @Timed(value = "ledger.assets.verify", description = "Time to verify an asset")
    public Verification verify(String id) {
        Asset asset = findById(id);
        byte[] payloadHash = hasher.hash(asset.name(), asset.assetType(), asset.owner(), asset.metadata());
        String recomputed = Numeric.toHexString(payloadHash);
        Optional<String> recovered = signatures.recover(payloadHash, asset.signature());
        boolean hashMatches = recomputed.equalsIgnoreCase(asset.payloadHash());
        boolean signatureValid = hashMatches
                && recovered.filter(address -> address.equalsIgnoreCase(asset.signerAddress())).isPresent();
        Optional<String> onChainSigner = anchors.signerOf(payloadHash);
        log.info("Verified asset {} signatureValid={} onChain={}", id, signatureValid, onChainSigner.isPresent());
        return new Verification(
                asset,
                recomputed,
                signatureValid,
                recovered.orElse(null),
                onChainSigner.isPresent() || asset.anchored(),
                asset.anchorTxHash());
    }

    /**
     * Result of verifying an asset.
     *
     * @param asset            the asset that was verified
     * @param payloadHash      hash recomputed from the stored fields
     * @param signatureValid   true when the recomputed hash and the recovered signer both
     *                         agree with what was stored
     * @param recoveredAddress address recovered from the signature, null when recovery failed
     * @param anchored         true when the hash is known to the chain or was anchored at
     *                         registration
     * @param anchorTxHash     anchor transaction hash, null when never anchored
     */
    public record Verification(
            Asset asset,
            String payloadHash,
            boolean signatureValid,
            String recoveredAddress,
            boolean anchored,
            String anchorTxHash) {
    }
}
