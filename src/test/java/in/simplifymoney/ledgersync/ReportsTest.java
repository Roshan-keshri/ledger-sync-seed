package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.*;
import in.simplifymoney.ledgersync.report.Reports;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportsTest {

    @Test
    void summarySeparatesCategories() {
        var time = OffsetDateTime.parse("2026-07-01T10:00:00+05:30");

        List<NormalizedTxn> ledger = List.of(
                txn(time, Direction.DEBIT, "500.00", Category.SPEND),
                txn(time, Direction.CREDIT, "1000.00", Category.INCOME),
                txn(time, Direction.DEBIT, "50.00", Category.MICRO),
                txn(time, Direction.DEBIT, "2000.00", Category.TRANSFER),
                txn(time, Direction.CREDIT, "3000.00", Category.TRANSFER)
        );

        Map<String, Object> accounts =
                (Map<String, Object>) Reports.summary(ledger).get("accounts");

        Map<String, Object> account =
                (Map<String, Object>) accounts.get("4821");

        assertEquals("500.00", account.get("spend"));
        assertEquals("1000.00", account.get("income"));
        assertEquals(1, account.get("micro_count"));
        assertEquals("50.00", account.get("micro_total"));
        assertEquals("2000.00", account.get("transferred_out"));
        assertEquals("3000.00", account.get("transferred_in"));
    }

    private NormalizedTxn txn(OffsetDateTime time, Direction direction,
                              String amount, Category category) {
        return new NormalizedTxn(
                "4821", time, direction, new BigDecimal(amount),
                category, "TEST", List.of("m-1"));
    }
}