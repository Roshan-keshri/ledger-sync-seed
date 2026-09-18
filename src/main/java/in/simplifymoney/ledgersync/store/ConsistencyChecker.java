package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> differences = new ArrayList<>();
        List<NormalizedTxn> sqlTxns = sql.all();

        // Check every SQL transaction through its source message IDs.
        for (NormalizedTxn sqlTxn : sqlTxns) {
            for (String messageId : sqlTxn.sourceMessageIds()) {
                var found = documents.byMessageId(messageId);

                if (found.isEmpty()) {
                    differences.add(new Divergence(
                            "missing transaction for message " + messageId,
                            sqlTxn.toString(),
                            "missing"
                    ));
                    continue;
                }

                compareTxn(differences, messageId, sqlTxn, found.get());
            }
        }

        // Compare complete account/month results.
        // This also detects unexpected documents inside known account/months.
        var months = sqlTxns.stream()
                .map(t -> t.accountLast4() + "|"
                        + YearMonth.from(t.occurredAt()))
                .distinct()
                .toList();

        for (String value : months) {
            String[] parts = value.split("\\|");
            String account = parts[0];
            YearMonth month = YearMonth.parse(parts[1]);

            List<NormalizedTxn> expected = sqlTxns.stream()
                    .filter(t -> t.accountLast4().equals(account))
                    .filter(t -> YearMonth.from(t.occurredAt()).equals(month))
                    .toList();

            List<NormalizedTxn> actual =
                    documents.forAccountMonth(account, month);

            for (NormalizedTxn docTxn : actual) {
                boolean exists = expected.stream()
                        .anyMatch(sqlTxn -> sameTransaction(sqlTxn, docTxn));

                if (!exists) {
                    differences.add(new Divergence(
                            "extra transaction for account "
                                    + account + " in " + month,
                            "missing",
                            docTxn.toString()
                    ));
                }
            }
        }

        // MongoDB can additionally expose the whole collection,
// allowing extras in completely unknown accounts/months to be detected.
        if (documents instanceof MongoDocumentStore mongo) {
            for (NormalizedTxn docTxn : mongo.all()) {
                boolean exists = sqlTxns.stream()
                        .anyMatch(sqlTxn -> sameTransaction(sqlTxn, docTxn));

                if (!exists) {
                    boolean alreadyReported = differences.stream()
                            .anyMatch(d -> d.inDocuments()
                                    .equals(docTxn.toString()));

                    if (!alreadyReported) {
                        differences.add(new Divergence(
                                "extra transaction in document store",
                                "missing",
                                docTxn.toString()
                        ));
                    }
                }
            }
        }

        return differences;
    }

    private void compareTxn(
            List<Divergence> differences,
            String messageId,
            NormalizedTxn sqlTxn,
            NormalizedTxn docTxn) {

        compare(differences, "account", messageId,
                sqlTxn.accountLast4(), docTxn.accountLast4());

        compare(differences, "time", messageId,
                sqlTxn.occurredAt().toInstant(),
                docTxn.occurredAt().toInstant());

        compare(differences, "direction", messageId,
                sqlTxn.direction(), docTxn.direction());

        if (sqlTxn.amount().compareTo(docTxn.amount()) != 0) {
            differences.add(new Divergence(
                    "amount for message " + messageId,
                    sqlTxn.amount().toPlainString(),
                    docTxn.amount().toPlainString()
            ));
        }

        compare(differences, "category", messageId,
                sqlTxn.category(), docTxn.category());

        compare(differences, "merchant", messageId,
                sqlTxn.merchant(), docTxn.merchant());
    }

    private boolean sameTransaction(
            NormalizedTxn a,
            NormalizedTxn b) {

        return a.accountLast4().equals(b.accountLast4())
                && a.occurredAt().toInstant()
                .equals(b.occurredAt().toInstant())
                && a.direction() == b.direction()
                && a.amount().compareTo(b.amount()) == 0
                && Objects.equals(a.merchant(), b.merchant());
    }

    private void compare(
            List<Divergence> differences,
            String field,
            String messageId,
            Object sqlValue,
            Object documentValue) {

        if (!Objects.equals(sqlValue, documentValue)) {
            differences.add(new Divergence(
                    field + " for message " + messageId,
                    String.valueOf(sqlValue),
                    String.valueOf(documentValue)
            ));
        }
    }

    public record Divergence(
            String what,
            String inSql,
            String inDocuments) {}
}