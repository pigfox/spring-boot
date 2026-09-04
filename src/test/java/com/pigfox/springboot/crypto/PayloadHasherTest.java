package com.pigfox.springboot.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.utils.Numeric;

class PayloadHasherTest {

    private final PayloadHasher hasher = new PayloadHasher();

    @Test
    @DisplayName("hashes are 32 bytes and render as 0x-prefixed lower-case hex")
    void producesKeccak256() {
        byte[] hash = hasher.hash("Unit 4B", "REAL_ESTATE", "Estate JV", Map.of("a", "1"));

        assertThat(hash).hasSize(32);
        assertThat(hasher.hashHex("Unit 4B", "REAL_ESTATE", "Estate JV", Map.of("a", "1")))
                .isEqualTo("0x" + Numeric.toHexStringNoPrefix(hash))
                .matches("^0x[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("the same payload always hashes identically")
    void isDeterministic() {
        Map<String, String> metadata = Map.of("jurisdiction", "GB", "titleNumber", "NGL1");

        assertThat(hasher.hashHex("n", "t", "o", metadata))
                .isEqualTo(hasher.hashHex("n", "t", "o", metadata));
    }

    @Test
    @DisplayName("metadata iteration order does not change the hash")
    void sortsMetadataKeys() {
        Map<String, String> insertionOrdered = new LinkedHashMap<>();
        insertionOrdered.put("z", "26");
        insertionOrdered.put("a", "1");
        Map<String, String> reverseOrdered = new TreeMap<>(Comparator.reverseOrder());
        reverseOrdered.putAll(insertionOrdered);

        assertThat(hasher.canonicalise("n", "t", "o", insertionOrdered))
                .isEqualTo(hasher.canonicalise("n", "t", "o", reverseOrdered));
    }

    @Test
    @DisplayName("a value containing the separator cannot impersonate a field boundary")
    void resistsSeparatorInjection() {
        String withSeparator = hasher.canonicalise("a:b", "c", "o", Map.of());
        String withoutSeparator = hasher.canonicalise("a", "b:c", "o", Map.of());

        assertThat(withSeparator).isNotEqualTo(withoutSeparator);
    }

    @Test
    @DisplayName("a metadata key smuggled into another field changes the hash")
    void resistsMetadataInjection() {
        String asKeyValue = hasher.canonicalise("n", "t", "o", Map.of("k", "v"));
        String smuggledIntoName = hasher.canonicalise("nk", "t", "o", Map.of("", "v"));

        assertThat(asKeyValue).isNotEqualTo(smuggledIntoName);
    }

    @Test
    @DisplayName("null and empty metadata are treated alike, and null fields are tolerated")
    void handlesNulls() {
        assertThat(hasher.hashHex("n", "t", "o", null))
                .isEqualTo(hasher.hashHex("n", "t", "o", Map.of()));
        assertThatCode(() -> hasher.canonicalise(null, null, null, null)).doesNotThrowAnyException();
        assertThat(hasher.canonicalise(null, "t", "o", Map.of()))
                .isEqualTo(hasher.canonicalise("", "t", "o", Map.of()));
    }

    @Test
    @DisplayName("metadata cardinality is part of the canonical form")
    void encodesMetadataSize() {
        assertThat(hasher.canonicalise("n", "t", "o", Map.of("a", "1")))
                .isNotEqualTo(hasher.canonicalise("n", "t", "o", Map.of("a", "1", "b", "2")));
    }

    @Test
    @DisplayName("changing any single field changes the hash")
    void isSensitiveToEveryField() {
        String base = hasher.hashHex("n", "t", "o", Map.of("k", "v"));

        assertThat(base)
                .isNotEqualTo(hasher.hashHex("N", "t", "o", Map.of("k", "v")))
                .isNotEqualTo(hasher.hashHex("n", "T", "o", Map.of("k", "v")))
                .isNotEqualTo(hasher.hashHex("n", "t", "O", Map.of("k", "v")))
                .isNotEqualTo(hasher.hashHex("n", "t", "o", Map.of("k", "V")))
                .isNotEqualTo(hasher.hashHex("n", "t", "o", Map.of("K", "v")));
    }

    @Test
    @DisplayName("the canonical form is length-prefixed, so segments are self-delimiting")
    void canonicalFormIsLengthPrefixed() {
        assertThat(hasher.canonicalise("ab", "c", "d", Map.of()))
                .isEqualTo("2:ab:1:c:1:d:1:0:");
    }
}
