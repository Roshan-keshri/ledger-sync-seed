package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MongoDocumentStoreTest {

    @Test
    void supportsRequiredQueries() {
        try (MongoDocumentStore store =
                     new MongoDocumentStore("mongodb://localhost:27017")) {

            NormalizedTxn txn = new NormalizedTxn(
                    "9999",
                    OffsetDateTime.parse("2026-07-10T10:00:00+05:30"),
                    Direction.DEBIT,
                    new BigDecimal("100.00"),
                    Category.SPEND,
                    "TEST MERCHANT",
                    List.of("test-message-1")
            );

            store.save(txn);
            store.save(txn);

            assertEquals(1,
                    store.forAccountMonth("9999", YearMonth.of(2026, 7)).size());

            assertEquals(new BigDecimal("100.00"),
                    store.categoryTotals("9999").get(Category.SPEND));

            assertTrue(store.byMessageId("test-message-1").isPresent());
        }
    }
}