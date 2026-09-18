package in.simplifymoney.ledgersync.store;

import java.util.List;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * We will run your checker against a document store we have deliberately
 * altered. It has to find what we changed and name it. A checker that only
 * compares row counts will not.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }
    public List<Divergence> check() {
        List<Divergence> differences = new java.util.ArrayList<>();

        for (var sqlTxn : sql.all()) {

            for (String messageId : sqlTxn.sourceMessageIds()) {

                var mongoTxn = documents.byMessageId(messageId);

                if (mongoTxn.isEmpty()) {
                    differences.add(new Divergence(
                            "missing transaction for message " + messageId,
                            sqlTxn.toString(),
                            "missing"
                    ));
                    continue;
                }

                var docTxn = mongoTxn.get();

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
        }

        if (documents instanceof MongoDocumentStore mongo) {
            for (var mongoTxn : mongo.all()) {
                boolean exists = sql.all().stream().anyMatch(sqlTxn ->
                        sqlTxn.accountLast4().equals(mongoTxn.accountLast4())
                                && sqlTxn.occurredAt().toInstant()
                                .equals(mongoTxn.occurredAt().toInstant())
                                && sqlTxn.direction() == mongoTxn.direction()
                                && sqlTxn.amount().compareTo(mongoTxn.amount()) == 0
                                && sqlTxn.merchant().equals(mongoTxn.merchant())
                );

                if (!exists) {
                    differences.add(new Divergence(
                            "extra transaction in document store",
                            "missing",
                            mongoTxn.toString()
                    ));
                }
            }
        }

        return differences;
    }

    private void compare(
            List<Divergence> differences,
            String field,
            String messageId,
            Object sqlValue,
            Object documentValue) {

        if (!java.util.Objects.equals(sqlValue, documentValue)) {
            differences.add(new Divergence(
                    field + " for message " + messageId,
                    String.valueOf(sqlValue),
                    String.valueOf(documentValue)
            ));
        }
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
