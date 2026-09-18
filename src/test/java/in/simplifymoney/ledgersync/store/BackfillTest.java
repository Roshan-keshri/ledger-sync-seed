package in.simplifymoney.ledgersync.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackfillTest {

    @Test
    void backfillCanBeRunTwice(@TempDir Path dir) {
        try (SqlLedgerStore sql = new SqlLedgerStore(dir.resolve("ledger"));
             MongoDocumentStore mongo = new MongoDocumentStore(
                     "mongodb://localhost:27017",
                     "backfill_test"
             )) {

            sql.migrate(Path.of("db/migration"));

            Backfill backfill = new Backfill(sql, mongo);

            Backfill.Result first = backfill.run();
            Backfill.Result second = backfill.run();

            assertEquals(first.read(), second.read());
            assertEquals(first.written(), second.written());
            assertEquals(first.skipped(), second.skipped());

            int before = mongo.forAccountMonth(
                    "4821", YearMonth.of(2026, 7)).size();

            backfill.run();

            int after = mongo.forAccountMonth(
                    "4821", YearMonth.of(2026, 7)).size();

            assertEquals(before, after);
        }
    }
}