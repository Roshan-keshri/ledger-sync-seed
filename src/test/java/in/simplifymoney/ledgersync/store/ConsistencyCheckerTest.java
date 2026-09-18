package in.simplifymoney.ledgersync.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistencyCheckerTest {

    @Test
    void matchingStoresHaveNoDifferences(@TempDir Path dir) {
        try (SqlLedgerStore sql = new SqlLedgerStore(dir.resolve("ledger"));
             MongoDocumentStore mongo = new MongoDocumentStore(
                     "mongodb://localhost:27017",
                     "consistency_test"
             )) {

            sql.migrate(Path.of("db/migration"));

            new Backfill(sql, mongo).run();

            ConsistencyChecker checker =
                    new ConsistencyChecker(sql, mongo);

            var differences = checker.check();

            differences.forEach(System.out::println);

            assertTrue(differences.isEmpty());        }
    }
    @Test
    void findsChangedAmount(@TempDir Path dir) {
        try (SqlLedgerStore sql = new SqlLedgerStore(dir.resolve("ledger2"));
             MongoDocumentStore mongo = new MongoDocumentStore(
                     "mongodb://localhost:27017",
                     "consistency_changed_test"
             )) {

            sql.migrate(Path.of("db/migration"));
            new Backfill(sql, mongo).run();

            try (var client = MongoClients.create("mongodb://localhost:27017")) {
                var collection = client
                        .getDatabase("consistency_changed_test")
                        .getCollection("transactions");

                collection.updateOne(
                        new Document("source_message_ids", "m-legacy-0001"),
                        new Document("$set",
                                new Document("amount", "9999.00"))
                );
            }

            var differences =
                    new ConsistencyChecker(sql, mongo).check();

            assertFalse(differences.isEmpty());

            assertTrue(differences.stream().anyMatch(d ->
                    d.what().contains("amount")
                            && d.inDocuments().equals("9999.00")
            ));
        }
    }

    @Test
    void findsExtraMongoTransaction(@TempDir Path dir) {
        try (SqlLedgerStore sql = new SqlLedgerStore(dir.resolve("ledger3"));
             MongoDocumentStore mongo = new MongoDocumentStore(
                     "mongodb://localhost:27017",
                     "consistency_extra_test"
             )) {

            sql.migrate(Path.of("db/migration"));
            new Backfill(sql, mongo).run();

            mongo.save(new NormalizedTxn(
                    "9999",
                    OffsetDateTime.parse("2026-07-15T10:00:00+05:30"),
                    Direction.DEBIT,
                    new BigDecimal("123.00"),
                    Category.SPEND,
                    "EXTRA MERCHANT",
                    List.of("extra-message")
            ));

            var differences =
                    new ConsistencyChecker(sql, mongo).check();

            assertTrue(differences.stream().anyMatch(d ->
                    d.what().equals("extra transaction in document store")
            ));
        }
    }

}