package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.parse.ParsedTxn;

import java.math.BigDecimal;

public final class Categorizer {

    private Categorizer() {}

    public static Category category(ParsedTxn txn) {
        if (txn.direction() == Direction.DEBIT
                && txn.amount().compareTo(new BigDecimal("100.00")) <= 0
                && txn.merchant().toUpperCase().startsWith("UPI")) {
            return Category.MICRO;
        }

        return txn.direction() == Direction.DEBIT
                ? Category.SPEND
                : Category.INCOME;
    }
}