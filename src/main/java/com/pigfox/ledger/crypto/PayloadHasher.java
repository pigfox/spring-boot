package com.pigfox.ledger.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

/**
 * Turns an asset payload into a stable byte string and hashes it with keccak256.
 *
 * <p>Canonicalisation matters more than the hash function here: two nodes must agree
 * byte-for-byte on what was signed. Two rules give that guarantee.
 *
 * <ul>
 *   <li>Every segment is length-prefixed, so no value can impersonate a delimiter.
 *       Without this, {@code owner="a:b"} and {@code owner="a"} with {@code type="b"}
 *       could collide onto the same hash.
 *   <li>Metadata keys are sorted, so map iteration order cannot change the hash.
 * </ul>
 */
@Component
public class PayloadHasher {

    private static final char SEPARATOR = ':';

    /**
     * Builds the canonical representation of an asset payload.
     *
     * @param name      asset name
     * @param assetType asset classification
     * @param owner     owning party
     * @param metadata  additional attributes, may be null or empty
     * @return canonical string, stable across JVMs and map implementations
     */
    public String canonicalise(String name, String assetType, String owner, Map<String, String> metadata) {
        StringBuilder canonical = new StringBuilder();
        appendSegment(canonical, name);
        appendSegment(canonical, assetType);
        appendSegment(canonical, owner);
        Map<String, String> sorted = new TreeMap<>(metadata == null ? Map.of() : metadata);
        appendSegment(canonical, String.valueOf(sorted.size()));
        sorted.forEach((key, value) -> {
            appendSegment(canonical, key);
            appendSegment(canonical, value);
        });
        return canonical.toString();
    }

    /**
     * Hashes an asset payload.
     *
     * @param name      asset name
     * @param assetType asset classification
     * @param owner     owning party
     * @param metadata  additional attributes, may be null or empty
     * @return raw 32-byte keccak256 digest
     */
    public byte[] hash(String name, String assetType, String owner, Map<String, String> metadata) {
        return Hash.sha3(canonicalise(name, assetType, owner, metadata).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hashes an asset payload and renders the digest for transport.
     *
     * @param name      asset name
     * @param assetType asset classification
     * @param owner     owning party
     * @param metadata  additional attributes, may be null or empty
     * @return 0x-prefixed lower-case hex digest
     */
    public String hashHex(String name, String assetType, String owner, Map<String, String> metadata) {
        return Numeric.toHexString(hash(name, assetType, owner, metadata));
    }

    private void appendSegment(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append(SEPARATOR).append(safe).append(SEPARATOR);
    }
}
