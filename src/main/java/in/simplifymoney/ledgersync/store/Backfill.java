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
        long read = 0;
        long written = 0;
        long skipped = 0;

        Set<String> seen = new HashSet<>();

        for (NormalizedTxn txn : source.all()) {
            read++;

            String key = key(txn);

            if (!seen.add(key)) {
                skipped++;
                continue;
            }

            target.save(txn);
            written++;
        }

        return new Result(read, written, skipped);
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