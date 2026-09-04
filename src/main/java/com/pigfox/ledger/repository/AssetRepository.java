package com.pigfox.ledger.repository;

import com.pigfox.ledger.domain.Asset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * In-memory asset store.
 *
 * <p>The authoritative record for this node is the chain anchor plus the signature, so
 * the local store is a projection rather than a system of record. Keeping it in memory
 * means the demo has no database dependency and the unit tests need no fixtures.
 */
@Repository
public class AssetRepository {

    private final Map<String, Asset> assets = new ConcurrentHashMap<>();

    /**
     * Stores an asset, replacing any existing entry with the same id.
     *
     * @param asset asset to store
     * @return the stored asset
     */
    public Asset save(Asset asset) {
        assets.put(asset.id(), asset);
        return asset;
    }

    /**
     * @param id asset identifier
     * @return the asset, if present
     */
    public Optional<Asset> findById(String id) {
        return Optional.ofNullable(assets.get(id));
    }

    /** @return every stored asset, newest first */
    public List<Asset> findAll() {
        return assets.values().stream()
                .sorted(Comparator.comparing(Asset::createdAt).reversed()
                        .thenComparing(Asset::id))
                .toList();
    }

    /** @return number of stored assets */
    public long count() {
        return assets.size();
    }
}
