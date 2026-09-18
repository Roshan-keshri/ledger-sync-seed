package in.simplifymoney.ledgersync;

import com.mongodb.ExplainVerbosity;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import org.bson.Document;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class Benchmark {

    public static void main(String[] args) {
        try (MongoDocumentStore store =
                     new MongoDocumentStore(
                             "mongodb://localhost:27017",
                             "benchmark"
                     )) {

            store.collection().deleteMany(new Document());

            for (int i = 0; i < 100_000; i++) {
                NormalizedTxn txn = new NormalizedTxn(
                        String.format("%04d", i % 1000),
                        OffsetDateTime.parse("2026-01-01T00:00:00Z")
                                .plusMinutes(i),
                        i % 4 == 0 ? Direction.CREDIT : Direction.DEBIT,
                        new BigDecimal((i % 5000 + 1) + ".00"),
                          Category.values()[(i / 1000) % Category.values().length],
                                  "MERCHANT-" + (i % 100),
                        List.of("benchmark-message-" + i)
                );

                store.save(txn);
            }

            System.out.println("100000 transactions loaded");

            Document q1 = store.collection()
                    .find(new Document("account_last4", "0001")
                            .append("occurred_at",
                                    new Document("$gte", "2026-01-01T00:00:00Z")
                                            .append("$lt", "2026-02-01T00:00:00Z")))
                    .sort(new Document("occurred_at", -1))
                    .explain(ExplainVerbosity.EXECUTION_STATS);

            printStats("Q1", q1);

            Document q2 = store.collection()
                    .aggregate(List.of(
                            new Document("$match",
                                    new Document("account_last4", "0001")),
                            new Document("$group",
                                    new Document("_id", "$category")
                                            .append("total",
                                                    new Document("$sum",
                                                            new Document("$toDecimal", "$amount"))))
                    ))
                    .explain(ExplainVerbosity.EXECUTION_STATS);

            System.out.println("Q2:");
            System.out.println(q2.toJson());

            Document q3 = store.collection()
                    .find(new Document(
                            "source_message_ids",
                            "benchmark-message-50001"
                    ))
                    .explain(ExplainVerbosity.EXECUTION_STATS);

            printStats("Q3", q3);
        }
    }

    private static void printStats(String name, Document explain) {
        Document stats = explain.get("executionStats", Document.class);

        System.out.println(name
                + " examined=" + stats.get("totalDocsExamined")
                + ", returned=" + stats.get("nReturned"));
    }
}