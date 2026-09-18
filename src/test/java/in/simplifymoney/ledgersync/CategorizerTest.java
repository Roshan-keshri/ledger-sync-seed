package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.Categorizer;
import in.simplifymoney.ledgersync.model.*;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CategorizerTest {

    @Test
    void smallUpiDebitIsMicro() {
        ParsedTxn txn = new ParsedTxn(
                "9075",
                OffsetDateTime.parse("2026-07-01T10:22:00+05:30"),
                Direction.DEBIT,
                new BigDecimal("22.50"),
                "UPI/VEGETABLE VENDOR",
                null,
                "m-1"
        );

        assertEquals(Category.MICRO, Categorizer.category(txn));
    }

    @Test
    void upiDebitOfHundredIsMicro() {
        ParsedTxn txn = new ParsedTxn(
                "9075",
                OffsetDateTime.parse("2026-07-01T10:22:00+05:30"),
                Direction.DEBIT,
                new BigDecimal("100.00"),
                "UPI/KIRANA STORE",
                null,
                "m-2"
        );

        assertEquals(Category.MICRO, Categorizer.category(txn));
    }

    @Test
    void upiDebitAboveHundredIsSpend() {
        ParsedTxn txn = new ParsedTxn(
                "9075",
                OffsetDateTime.parse("2026-07-01T10:22:00+05:30"),
                Direction.DEBIT,
                new BigDecimal("100.01"),
                "UPI/KIRANA STORE",
                null,
                "m-3"
        );

        assertEquals(Category.SPEND, Categorizer.category(txn));
    }
}