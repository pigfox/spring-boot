package com.pigfox.springboot.chain;

/**
 * Outcome of an attempt to anchor a payload hash on chain.
 *
 * @param txHash transaction hash when the chain accepted the anchor, otherwise null
 * @param status why the attempt ended the way it did
 */
public record AnchorResult(String txHash, Status status) {

    /** Classification of an anchor attempt, useful for metrics and for the API layer. */
    public enum Status {
        /** The node accepted the transaction. */
        ANCHORED,
        /** Anchoring is switched off, or no registry address is configured. */
        DISABLED,
        /** The node could not be reached. */
        UNREACHABLE,
        /** The node was reached but refused the transaction. */
        REJECTED
    }

    /**
     * @param txHash accepted transaction hash
     * @return an anchored result
     */
    public static AnchorResult anchored(String txHash) {
        return new AnchorResult(txHash, Status.ANCHORED);
    }

    /**
     * @param status reason anchoring did not happen
     * @return a result carrying no transaction hash
     */
    public static AnchorResult none(Status status) {
        return new AnchorResult(null, status);
    }

    /** @return true when a transaction hash is present */
    public boolean anchored() {
        return txHash != null;
    }
}
