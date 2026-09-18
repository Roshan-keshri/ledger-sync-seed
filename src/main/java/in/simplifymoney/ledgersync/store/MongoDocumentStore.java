package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import org.bson.Document;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MongoDocumentStore implements DocumentStore, AutoCloseable {

    private final MongoClient client;
    private final MongoCollection<Document> collection;

    public MongoDocumentStore(String uri) {
        client = MongoClients.create(uri);

        MongoDatabase db = client.getDatabase("ledger_sync");
        collection = db.getCollection("transactions");

        collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending("account_last4"),
                Indexes.descending("occurred_at")));

        collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending("account_last4"),
                Indexes.ascending("category")));

        collection.createIndex(Indexes.ascending("source_message_ids"));
    }

    @Override
    public void save(NormalizedTxn t) {
        Document doc = new Document("_id", id(t))
                .append("account_last4", t.accountLast4())
                .append("occurred_at", t.occurredAt().toInstant().toString())
                .append("direction", t.direction().name())
                .append("amount", t.amount().toPlainString())
                .append("category", t.category().name())
                .append("merchant", t.merchant())
                .append("source_message_ids", t.sourceMessageIds());

        collection.replaceOne(
                new Document("_id", id(t)),
                doc,
                new ReplaceOptions().upsert(true));
    }

    private String id(NormalizedTxn t) {
        return t.accountLast4() + "|"
                + t.occurredAt().toInstant() + "|"
                + t.direction() + "|"
                + t.amount().toPlainString() + "|"
                + t.merchant();
    }
    @Override
    public List<NormalizedTxn> forAccountMonth(
            String accountLast4, YearMonth month) {

        String start = month.atDay(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .toString();

        String end = month.plusMonths(1)
                .atDay(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .toString();

        List<NormalizedTxn> out = new ArrayList<>();

        collection.find(Filters.and(
                        Filters.eq("account_last4", accountLast4),
                        Filters.gte("occurred_at", start),
                        Filters.lt("occurred_at", end)))
                .sort(Sorts.descending("occurred_at"))
                .forEach(d -> out.add(toTxn(d)));

        return out;
    }
    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> totals = new java.util.EnumMap<>(Category.class);

        collection.find(Filters.eq("account_last4", accountLast4))
                .forEach(d -> {
                    Category category = Category.valueOf(d.getString("category"));
                    BigDecimal amount = new BigDecimal(d.getString("amount"));

                    totals.merge(category, amount, BigDecimal::add);
                });

        return totals;
    }
    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Document d = collection.find(
                Filters.eq("source_message_ids", messageId)
        ).first();

        return d == null
                ? Optional.empty()
                : Optional.of(toTxn(d));
    }

    private NormalizedTxn toTxn(Document d) {
        return new NormalizedTxn(
                d.getString("account_last4"),
                OffsetDateTime.parse(d.getString("occurred_at")),
                Direction.valueOf(d.getString("direction")),
                new BigDecimal(d.getString("amount")),
                Category.valueOf(d.getString("category")),
                d.getString("merchant"),
                d.getList("source_message_ids", String.class)
        );
    }

    @Override
    public void close() {
        client.close();
    }
}