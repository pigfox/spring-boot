package com.pigfox.ledger.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.pigfox.ledger.domain.Asset;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AssetRepositoryTest {

    private final AssetRepository repository = new AssetRepository();

    private Asset asset(String id, Instant createdAt) {
        return new Asset(id, "name", "type", "owner", Map.of(), "0xhash", "0xsig",
                "0xsigner", null, createdAt);
    }

    @Test
    @DisplayName("a saved asset can be read back by id")
    void savesAndReads() {
        Asset saved = repository.save(asset("a", Instant.EPOCH));

        assertThat(saved.id()).isEqualTo("a");
        assertThat(repository.findById("a")).contains(saved);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown id yields empty")
    void returnsEmptyForUnknownId() {
        assertThat(repository.findById("nope")).isEmpty();
    }

    @Test
    @DisplayName("saving the same id twice replaces rather than duplicates")
    void replacesOnSameId() {
        repository.save(asset("a", Instant.EPOCH));
        repository.save(asset("a", Instant.EPOCH.plusSeconds(10)));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById("a")).get()
                .extracting(Asset::createdAt)
                .isEqualTo(Instant.EPOCH.plusSeconds(10));
    }

    @Test
    @DisplayName("listing returns newest first")
    void listsNewestFirst() {
        repository.save(asset("old", Instant.EPOCH));
        repository.save(asset("new", Instant.EPOCH.plusSeconds(60)));
        repository.save(asset("middle", Instant.EPOCH.plusSeconds(30)));

        assertThat(repository.findAll()).extracting(Asset::id)
                .containsExactly("new", "middle", "old");
    }

    @Test
    @DisplayName("assets sharing a timestamp fall back to id order, so listing is stable")
    void breaksTimestampTiesById() {
        repository.save(asset("b", Instant.EPOCH));
        repository.save(asset("a", Instant.EPOCH));

        assertThat(repository.findAll()).extracting(Asset::id).containsExactly("a", "b");
    }

    @Test
    @DisplayName("an empty repository lists nothing")
    void startsEmpty() {
        assertThat(repository.findAll()).isEmpty();
        assertThat(repository.count()).isZero();
    }
}
