package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlLedgerStoreTest {

    @Test
    void doesNotSaveSameTransactionTwice(@TempDir Path dir) {
        NormalizedTxn txn = new NormalizedTxn(
                "4821",
                OffsetDateTime.parse("2026-07-19T00:20:00+05:30"),
                Direction.DEBIT,
                new BigDecimal("412.67"),
                Category.SPEND,
                "UBER INDIA",
                List.of("m-1")
        );

        try (SqlLedgerStore store = new SqlLedgerStore(dir.resolve("ledger"))) {
            store.migrate(Path.of("db/migration"));
            store.save(txn);
            store.save(txn);

            assertEquals(16, store.count());
        }
    }
}