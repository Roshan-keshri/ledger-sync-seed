package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.BalanceEvidence;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The two reports the assignment asks for.
 *
 * summary() below is a first cut: it adds up what is in the ledger. It does not
 * know that a transfer is not spending, and it does not roll micro spends up.
 /**
 * Builds ledger, summary and reconciliation reports.
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();

        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            BigDecimal microTotal = ZERO;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;
            int microCount = 0;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;

                if (t.category() == Category.SPEND)
                    spend = spend.add(t.amount());

                else if (t.category() == Category.INCOME)
                    income = income.add(t.amount());

                else if (t.category() == Category.MICRO) {
                    microCount++;
                    microTotal = microTotal.add(t.amount());
                }

                else if (t.category() == Category.TRANSFER) {
                    if (t.direction() == Direction.DEBIT)
                        transferredOut = transferredOut.add(t.amount());
                    else
                        transferredIn = transferredIn.add(t.amount());
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            a.put("micro_count", microCount);
            a.put("micro_total", microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in", transferredIn.toPlainString());

            accounts.put(acct, a);
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    public static Map<String, Object> reconciliation(
            List<NormalizedTxn> ledger,
            List<BalanceEvidence> evidence) {

        List<Object> discrepancies = new ArrayList<>();

        for (String account : new TreeSet<>(
                evidence.stream().map(BalanceEvidence::accountLast4).toList())) {

            var balances = evidence.stream()
                    .filter(e -> e.accountLast4().equals(account))
                    .collect(Collectors.toMap(
                            BalanceEvidence::occurredAt,
                            e -> e,
                            (a, b) -> a,
                            TreeMap::new));

            BalanceEvidence previous = null;

            for (BalanceEvidence current : balances.values()) {
                if (previous != null) {
                    BigDecimal expected = previous.statedBalance();

                    for (NormalizedTxn t : ledger) {
                        if (!t.accountLast4().equals(account)) continue;

                        if (t.occurredAt().isAfter(previous.occurredAt())
                                && !t.occurredAt().isAfter(current.occurredAt())) {

                            expected = t.direction() == Direction.DEBIT
                                    ? expected.subtract(t.amount())
                                    : expected.add(t.amount());
                        }
                    }

                    BigDecimal difference =
                            expected.subtract(current.statedBalance());

                    if (difference.compareTo(BigDecimal.ZERO) != 0) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("account_last4", account);
                        d.put("occurred_at", current.occurredAt().toString());
                        d.put("amount", difference.abs().toPlainString());
                        d.put("note", difference.signum() > 0
                                ? "unexplained debit"
                                : "unexplained credit");

                        discrepancies.add(d);
                    }
                }

                previous = current;
            }
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discrepancies);
        return doc;
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
