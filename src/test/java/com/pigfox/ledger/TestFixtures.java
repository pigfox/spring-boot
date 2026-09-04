package com.pigfox.ledger;

import com.pigfox.ledger.config.LedgerProperties;
import java.math.BigInteger;
import java.time.Duration;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

/**
 * Shared test material.
 *
 * <p>The signing key is derived from a small integer seed at runtime rather than pasted
 * in as a hex literal. That keeps the repository free of anything shaped like a real
 * private key, so the secret scanner in CI has nothing to trip over and no reader can
 * mistake a fixture for a live credential.
 */
public final class TestFixtures {

    /** Seed for the deterministic test key. Any non-zero value below the curve order works. */
    public static final BigInteger SEED = BigInteger.valueOf(0x5EED1234L);
    /** Deterministic secp256k1 key pair used across the suite. */
    public static final ECKeyPair KEY_PAIR = ECKeyPair.create(SEED);
    /** The seed rendered as a 32-byte hex private key, as the env var would supply it. */
    public static final String PRIVATE_KEY = "0x" + Numeric.toHexStringNoPrefixZeroPadded(SEED, 64);
    /** EIP-55 address of {@link #KEY_PAIR}. */
    public static final String ADDRESS = Keys.toChecksumAddress(Keys.getAddress(KEY_PAIR));

    /** A second key, for asserting that verification rejects the wrong signer. */
    public static final ECKeyPair OTHER_KEY_PAIR = ECKeyPair.create(BigInteger.valueOf(0xBEEF01L));
    /** The second key as hex. */
    public static final String OTHER_PRIVATE_KEY =
            "0x" + Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(0xBEEF01L), 64);
    /** EIP-55 address of {@link #OTHER_KEY_PAIR}. */
    public static final String OTHER_ADDRESS = Keys.toChecksumAddress(Keys.getAddress(OTHER_KEY_PAIR));

    /** Non-secret HMAC material, long enough for HS256. */
    public static final String JWT_SECRET = "ledger-node-unit-test-hmac-material-not-a-live-value";
    /** Test client identifier. */
    public static final String CLIENT_ID = "test-client";
    /** Test client credential. */
    public static final String CLIENT_SECRET = "test-client-credential";
    /** Registry address used when a test wants anchoring switched on. */
    public static final String REGISTRY_ADDRESS = "0x5FbDB2315678afecb367f032d93F642f64180aa3";
    /** Topic under test. */
    public static final String TOPIC = "asset.events";

    private TestFixtures() {
    }

    /**
     * @return properties with anchoring disabled, which is the default for unit tests
     */
    public static LedgerProperties properties() {
        return properties(false, "");
    }

    /**
     * @param chainEnabled    value for {@code ledger.chain.enabled}
     * @param registryAddress value for {@code ledger.chain.registry-address}
     * @return fully populated properties
     */
    public static LedgerProperties properties(boolean chainEnabled, String registryAddress) {
        return withSigningKey(PRIVATE_KEY, chainEnabled, registryAddress);
    }

    /**
     * @param signingKey      value for {@code ledger.crypto.signing-key}
     * @param chainEnabled    value for {@code ledger.chain.enabled}
     * @param registryAddress value for {@code ledger.chain.registry-address}
     * @return fully populated properties
     */
    public static LedgerProperties withSigningKey(
            String signingKey, boolean chainEnabled, String registryAddress) {
        return new LedgerProperties(
                new LedgerProperties.Auth(
                        CLIENT_ID, CLIENT_SECRET, JWT_SECRET, Duration.ofMinutes(15),
                        "ledger.read ledger.write"),
                new LedgerProperties.Crypto(signingKey),
                new LedgerProperties.Chain(
                        chainEnabled, "http://127.0.0.1:8545", registryAddress, 31337L,
                        BigInteger.valueOf(150_000), BigInteger.valueOf(1_000_000_000L)),
                new LedgerProperties.Events(TOPIC));
    }
}
