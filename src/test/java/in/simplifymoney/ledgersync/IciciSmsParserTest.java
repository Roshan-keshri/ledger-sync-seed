package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.*;
import in.simplifymoney.ledgersync.parse.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IciciSmsParserTest {

    @Test
    void parsesNewIciciFormat() {
        RawMessage msg = new RawMessage(
                "m-test", "sms", "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-30T17:13:00+05:30"), "dev-test",
                "ICICI Bank Acct XX9075 Dr INR 30.00 on 30-Jul-2026 17:12; UPI/CHAIWALA ref no 367772832587. BalAvl Rs 48,063.52"
        );

        ParsedTxn txn = new IciciSmsParser().parse(msg).orElseThrow();

        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("30.00"), txn.amount());
        assertEquals("UPI/CHAIWALA", txn.merchant());
    }
}