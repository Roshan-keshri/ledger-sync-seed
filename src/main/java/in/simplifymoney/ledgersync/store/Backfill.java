package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.util.HashSet;
import java.util.Set;

public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        var unique = new java.util.LinkedHashMap<String, NormalizedTxn>();
        long read = 0;
        long skipped = 0;

        for (NormalizedTxn txn : source.all()) {
            read++;

            String key = key(txn);

            if (unique.containsKey(key)) {
                NormalizedTxn old = unique.get(key);

                var ids = new java.util.ArrayList<>(old.sourceMessageIds());
                for (String id : txn.sourceMessageIds()) {
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                }

                txn = new NormalizedTxn(
                        old.accountLast4(),
                        old.occurredAt(),
                        old.direction(),
                        old.amount(),
                        old.category(),
                        old.merchant(),
                        ids
                );

                skipped++;
            }

            unique.put(key, txn);
        }

        unique.values().forEach(target::save);

        return new Result(read, unique.size(), skipped);
    }
    private String key(NormalizedTxn t) {
        return t.accountLast4() + "|"
                + t.occurredAt().toInstant() + "|"
                + t.direction() + "|"
                + t.amount().toPlainString() + "|"
                + t.merchant();
    }

    public record Result(long read, long written, long skipped) {}
}