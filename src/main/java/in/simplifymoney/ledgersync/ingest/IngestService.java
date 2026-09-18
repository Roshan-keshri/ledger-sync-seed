package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import in.simplifymoney.ledgersync.model.BalanceEvidence;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.LinkedHashMap;
import java.time.Duration;
/**
 * Reads raw messages, parses transactions and merges duplicate
 * messages that represent the same transaction.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        Map<String, NormalizedTxn> unique = new LinkedHashMap<>();
        int skipped = 0;

        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);

            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            ParsedTxn parsed = p.get();

            if (parsed.statedBalance() != null && store instanceof SqlLedgerStore sqlStore) {
                sqlStore.saveBalanceEvidence(new BalanceEvidence(
                        parsed.accountLast4(),
                        parsed.occurredAt(),
                        parsed.statedBalance(),
                        parsed.sourceMessageId()
                ));
            }

            NormalizedTxn txn = toTransaction(parsed);
            String key = txn.accountLast4() + "|" + txn.occurredAt().toInstant() + "|"
                    + txn.direction() + "|" + txn.amount() + "|" + txn.merchant();

            if (unique.containsKey(key)) {
                NormalizedTxn old = unique.get(key);

                List<String> ids = new ArrayList<>(old.sourceMessageIds());
                ids.addAll(txn.sourceMessageIds());
                ids.sort(String::compareTo);

                txn = new NormalizedTxn(
                        old.accountLast4(), old.occurredAt(), old.direction(),
                        old.amount(), old.category(), old.merchant(), ids);
            }

            unique.put(key, txn);
        }

        List<NormalizedTxn> txns = new ArrayList<>(unique.values());

        for (int i = 0; i < txns.size(); i++) {
            for (int j = i + 1; j < txns.size(); j++) {
                NormalizedTxn a = txns.get(i);
                NormalizedTxn b = txns.get(j);

                if (isTransferPair(a, b)) {
                    txns.set(i, asTransfer(a));
                    txns.set(j, asTransfer(b));
                }
            }
        }

        txns.forEach(store::save);

        return new Stats(messages.size(), unique.size(), skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    private NormalizedTxn toTransaction(ParsedTxn p) {
        Category c = Categorizer.category(p);

        return new NormalizedTxn(p.accountLast4(), p.occurredAt(), p.direction(),
                p.amount(), c, p.merchant(), List.of(p.sourceMessageId()));
    }
    private boolean isTransferPair(NormalizedTxn a, NormalizedTxn b) {
        return !a.accountLast4().equals(b.accountLast4())
                && a.direction() != b.direction()
                && a.amount().equals(b.amount())
                && a.merchant().equals(b.merchant())
                && Math.abs(Duration.between(a.occurredAt(), b.occurredAt()).toMinutes()) <= 2;
    }

    private NormalizedTxn asTransfer(NormalizedTxn t) {
        return new NormalizedTxn(
                t.accountLast4(), t.occurredAt(), t.direction(),
                t.amount(), Category.TRANSFER, t.merchant(), t.sourceMessageIds());
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
