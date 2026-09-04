package com.pigfox.ledger.chain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AnchorResultTest {

    @Test
    @DisplayName("an anchored result carries its transaction hash")
    void anchoredCarriesTxHash() {
        AnchorResult result = AnchorResult.anchored("0xabc");

        assertThat(result.status()).isEqualTo(AnchorResult.Status.ANCHORED);
        assertThat(result.txHash()).isEqualTo("0xabc");
        assertThat(result.anchored()).isTrue();
    }

    @ParameterizedTest
    @DisplayName("every non-anchored status carries no transaction hash")
    @EnumSource(value = AnchorResult.Status.class,
            names = {"DISABLED", "UNREACHABLE", "REJECTED"})
    void noneCarriesNoTxHash(AnchorResult.Status status) {
        AnchorResult result = AnchorResult.none(status);

        assertThat(result.status()).isEqualTo(status);
        assertThat(result.txHash()).isNull();
        assertThat(result.anchored()).isFalse();
    }

    @Test
    @DisplayName("the status enum covers every outcome the service can report")
    void statusValuesAreStable() {
        assertThat(AnchorResult.Status.values())
                .containsExactly(
                        AnchorResult.Status.ANCHORED,
                        AnchorResult.Status.DISABLED,
                        AnchorResult.Status.UNREACHABLE,
                        AnchorResult.Status.REJECTED);
        assertThat(AnchorResult.Status.valueOf("ANCHORED")).isEqualTo(AnchorResult.Status.ANCHORED);
    }
}
