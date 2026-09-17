package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.*;
import in.simplifymoney.ledgersync.parse.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailParserTest {

    @Test
    void parsesDebitEmail() {
        RawMessage msg = new RawMessage(
                "m-test-1", "email", "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-04T16:56:00+05:30"), "dev-test",
                """
                Date: Sat, 04 Jul 2026 13:56:00 +0530
                Your account ending 4821 has been debited with INR 2499.50.
                Merchant / Remarks: SWIGGY
                """
        );

        ParsedTxn txn = new EmailParser().parse(msg).orElseThrow();

        assertEquals("4821", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("2499.50"), txn.amount());
        assertEquals("SWIGGY", txn.merchant());
        assertEquals("m-test-1", txn.sourceMessageId());
    }

    @Test
    void parsesCreditEmail() {
        RawMessage msg = new RawMessage(
                "m-test-2", "email", "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-01T09:47:00+05:30"), "dev-test",
                """
                Date: Wed, 01 Jul 2026 09:02:00 +0530
                Your account ending 4821 has been credited with INR 45,000.
                Merchant / Remarks: SALARY CREDIT
                """
        );

        ParsedTxn txn = new EmailParser().parse(msg).orElseThrow();

        assertEquals(Direction.CREDIT, txn.direction());
        assertEquals(new BigDecimal("45000.00"), txn.amount());
        assertEquals("SALARY CREDIT", txn.merchant());
    }
}