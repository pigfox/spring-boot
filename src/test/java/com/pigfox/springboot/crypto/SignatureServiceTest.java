package com.pigfox.springboot.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pigfox.springboot.TestFixtures;
import com.pigfox.springboot.config.LedgerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.web3j.crypto.Hash;

class SignatureServiceTest {

    private final SignatureService service = new SignatureService(TestFixtures.properties());
    private final byte[] payloadHash = Hash.sha3("payload".getBytes());

    @Test
    @DisplayName("derives the EIP-55 address of the configured key")
    void exposesSignerAddress() {
        assertThat(service.signerAddress()).isEqualTo(TestFixtures.ADDRESS);
    }

    @Test
    @DisplayName("a signature recovers to the signing address")
    void signAndRecoverRoundTrip() {
        String signature = service.sign(payloadHash);

        assertThat(signature).matches("^0x[0-9a-f]{130}$");
        assertThat(service.recover(payloadHash, signature)).contains(TestFixtures.ADDRESS);
        assertThat(service.verify(payloadHash, signature, TestFixtures.ADDRESS)).isTrue();
    }

    @Test
    @DisplayName("signing is deterministic, as RFC 6979 requires")
    void signingIsDeterministic() {
        assertThat(service.sign(payloadHash)).isEqualTo(service.sign(payloadHash));
    }

    @Test
    @DisplayName("verification accepts the expected address in any letter case")
    void verifyIsCaseInsensitive() {
        String signature = service.sign(payloadHash);

        assertThat(service.verify(payloadHash, signature, TestFixtures.ADDRESS.toLowerCase())).isTrue();
    }

    @Test
    @DisplayName("verification rejects a different signer")
    void rejectsWrongSigner() {
        String signature = service.sign(payloadHash);

        assertThat(service.verify(payloadHash, signature, TestFixtures.OTHER_ADDRESS)).isFalse();
    }

    @Test
    @DisplayName("verification rejects a signature made over a different hash")
    void rejectsWrongPayload() {
        String signature = service.sign(payloadHash);
        byte[] otherHash = Hash.sha3("other payload".getBytes());

        assertThat(service.verify(otherHash, signature, TestFixtures.ADDRESS)).isFalse();
    }

    @Test
    @DisplayName("verification rejects a null expected address")
    void rejectsNullExpectedAddress() {
        assertThat(service.verify(payloadHash, service.sign(payloadHash), null)).isFalse();
    }

    @ParameterizedTest
    @DisplayName("malformed signatures are rejected rather than throwing")
    @ValueSource(strings = {"", "0x", "not-hex", "0xabcd", "0xzz"})
    void rejectsMalformedSignature(String malformed) {
        assertThat(service.recover(payloadHash, malformed)).isEmpty();
        assertThat(service.verify(payloadHash, malformed, TestFixtures.ADDRESS)).isFalse();
    }

    @Test
    @DisplayName("a signature of the wrong length is rejected")
    void rejectsWrongLengthSignature() {
        assertThat(service.recover(payloadHash, "0x" + "ff".repeat(64))).isEmpty();
        assertThat(service.recover(payloadHash, "0x" + "ff".repeat(66))).isEmpty();
    }

    @Test
    @DisplayName("a null signature is rejected rather than throwing")
    void rejectsNullSignature() {
        assertThat(service.recover(payloadHash, null)).isEmpty();
    }

    @Test
    @DisplayName("a well-formed signature that recovers no key yields empty")
    void rejectsUnrecoverableSignature() {
        assertThat(service.recover(payloadHash, "0x" + "00".repeat(65))).isEmpty();
    }

    @Test
    @DisplayName("a signature whose r and s are out of range yields empty")
    void rejectsOutOfRangeSignature() {
        assertThat(service.recover(payloadHash, "0x" + "ff".repeat(65))).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("a hash that is not 32 bytes is a programming error, not a bad request")
    @ValueSource(ints = {0, 1, 31, 33, 64})
    void rejectsWrongLengthHash(int length) {
        byte[] wrongLength = new byte[length];

        assertThatThrownBy(() -> service.sign(wrongLength))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> service.recover(wrongLength, "0x" + "11".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a null hash is rejected")
    void rejectsNullHash() {
        assertThatThrownBy(() -> service.sign(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recover(null, "0x" + "11".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @DisplayName("startup fails when the signing key is missing or malformed")
    @ValueSource(strings = {"", "   ", "0x", "deadbeef", "not-a-key",
            "0xZZ1234567890123456789012345678901234567890123456789012345678901234"})
    void rejectsBadSigningKey(String key) {
        LedgerProperties bad = TestFixtures.withSigningKey(key, false, "");

        assertThatThrownBy(() -> new SignatureService(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEDGER_SIGNING_KEY");
    }

    @Test
    @DisplayName("the rejection message never echoes the key it rejected")
    void doesNotLeakRejectedKey() {
        String key = "0f".repeat(31) + "0f0f";

        assertThatThrownBy(() -> new SignatureService(TestFixtures.withSigningKey(key + "ff", false, "")))
                .hasMessageNotContaining(key);
    }

    @Test
    @DisplayName("a key without the 0x prefix is accepted")
    void acceptsUnprefixedKey() {
        SignatureService unprefixed = new SignatureService(
                TestFixtures.withSigningKey(TestFixtures.PRIVATE_KEY.substring(2), false, ""));

        assertThat(unprefixed.signerAddress()).isEqualTo(TestFixtures.ADDRESS);
    }

    @Test
    @DisplayName("surrounding whitespace in the environment variable is tolerated")
    void trimsKey() {
        SignatureService padded = new SignatureService(
                TestFixtures.withSigningKey("  " + TestFixtures.PRIVATE_KEY + "  ", false, ""));

        assertThat(padded.signerAddress()).isEqualTo(TestFixtures.ADDRESS);
    }

    @Test
    @DisplayName("two keys sign independently and do not verify against each other")
    void keysAreIndependent() {
        SignatureService other = new SignatureService(
                TestFixtures.withSigningKey(TestFixtures.OTHER_PRIVATE_KEY, false, ""));

        String otherSignature = other.sign(payloadHash);

        assertThat(other.signerAddress()).isEqualTo(TestFixtures.OTHER_ADDRESS);
        assertThat(service.verify(payloadHash, otherSignature, TestFixtures.ADDRESS)).isFalse();
        assertThat(other.verify(payloadHash, otherSignature, TestFixtures.OTHER_ADDRESS)).isTrue();
    }
}
