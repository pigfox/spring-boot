package com.pigfox.ledger.domain;

/** Raised when a caller references an asset this node does not hold. */
public class AssetNotFoundException extends RuntimeException {

    private final String assetId;

    public AssetNotFoundException(String assetId) {
        super("No asset with id " + assetId);
        this.assetId = assetId;
    }

    public String getAssetId() {
        return assetId;
    }
}
