package com.pigfox.ledger.crypto;

import com.pigfox.ledger.config.LedgerProperties;
import java.security.SignatureException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/**
 * secp256k1 signing and recovery over a 32-byte payload hash.
 *
 * <p>The private key is read once, at construction, from configuration that is bound
 * exclusively to the {@code LEDGER_SIGNING_KEY} process environment variable. It is
 * never logged, never returned by an endpoint, and never written to disk. A malformed
 * or missing key fails startup.
 *
 * <p>Signatures are produced over the hash directly, without the EIP-191 personal
 * message prefix, so an on-chain {@code ecrecover} over the same {@code bytes32}
 * verifies them unchanged.
 */
@Service
public class SignatureService {

    private static final Logger log = LoggerFactory.getLogger(SignatureService.class);
    private static final Pattern PRIVATE_KEY = Pattern.compile("^(0x)?[0-9a-fA-F]{64}$");
    private static final Pattern SIGNATURE = Pattern.compile("^(0x)?[0-9a-fA-F]{130}$");
    private static final int HASH_LENGTH = 32;

    private final Credentials credentials;

    public SignatureService(LedgerProperties properties) {
        String key = properties.crypto().signingKey().trim();
        if (!PRIVATE_KEY.matcher(key).matches()) {
            // Deliberately says nothing about the value itself.
            throw new IllegalStateException(
                    "LEDGER_SIGNING_KEY must be a 32-byte hex secp256k1 private key");
        }
        this.credentials = Credentials.create(ECKeyPair.create(Numeric.toBigInt(key)));
        log.info("Signing key loaded from environment, signer address {}", signerAddress());
    }

    /** @return EIP-55 checksummed address of the node signing key */
    public String signerAddress() {
        return Keys.toChecksumAddress(credentials.getAddress());
    }

    /**
     * Signs a payload hash.
     *
     * @param payloadHash raw 32-byte digest
     * @return 0x-prefixed 65-byte signature, {@code r || s || v}
     * @throws IllegalArgumentException if the digest is not 32 bytes
     */
    public String sign(byte[] payloadHash) {
        requireHash(payloadHash);
        Sign.SignatureData data = Sign.signMessage(payloadHash, credentials.getEcKeyPair(), false);
        byte[] signature = new byte[65];
        System.arraycopy(data.getR(), 0, signature, 0, 32);
        System.arraycopy(data.getS(), 0, signature, 32, 32);
        signature[64] = data.getV()[0];
        return Numeric.toHexString(signature);
    }

    /**
     * Recovers the signing address from a signature.
     *
     * @param payloadHash raw 32-byte digest that was signed
     * @param signatureHex 0x-prefixed 65-byte signature
     * @return the recovered EIP-55 address, or empty when the signature is malformed or
     *         no key can be recovered from it
     * @throws IllegalArgumentException if the digest is not 32 bytes
     */
    public Optional<String> recover(byte[] payloadHash, String signatureHex) {
        requireHash(payloadHash);
        if (signatureHex == null || !SIGNATURE.matcher(signatureHex).matches()) {
            log.warn("Rejecting malformed signature");
            return Optional.empty();
        }
        byte[] raw = Numeric.hexStringToByteArray(signatureHex);
        byte[] r = new byte[32];
        byte[] s = new byte[32];
        System.arraycopy(raw, 0, r, 0, 32);
        System.arraycopy(raw, 32, s, 0, 32);
        Sign.SignatureData data = new Sign.SignatureData(raw[64], r, s);
        try {
            return Optional.of(Keys.toChecksumAddress(
                    Keys.getAddress(Sign.signedMessageHashToKey(payloadHash, data))));
        } catch (SignatureException | RuntimeException e) {
            log.warn("Signature recovery failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Recovers the signer and compares it with the address that is claimed to have
     * signed. This is the verification half of the sign-then-recover round trip.
     *
     * @param payloadHash     raw 32-byte digest that was signed
     * @param signatureHex    0x-prefixed 65-byte signature
     * @param expectedAddress address expected to have produced the signature
     * @return true only when recovery succeeds and yields {@code expectedAddress}
     */
    public boolean verify(byte[] payloadHash, String signatureHex, String expectedAddress) {
        if (expectedAddress == null) {
            return false;
        }
        return recover(payloadHash, signatureHex)
                .filter(recovered -> recovered.equalsIgnoreCase(expectedAddress))
                .isPresent();
    }

    private void requireHash(byte[] payloadHash) {
        if (payloadHash == null || payloadHash.length != HASH_LENGTH) {
            throw new IllegalArgumentException("Payload hash must be exactly 32 bytes");
        }
    }
}
