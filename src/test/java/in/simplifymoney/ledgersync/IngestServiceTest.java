package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngestServiceTest {

    @Test
    void mergesDuplicateMessages(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("corpus.jsonl");

        String body = "Dear Customer, Acct XX9075 is debited with INR 25 on 05/08/2026 07:51. Info: UPI/VEGETABLE VENDOR.";

        Files.writeString(file,
                json("m-1", body) + "\n" +
                        json("m-2", body));

        InMemoryLedgerStore store = new InMemoryLedgerStore();
        new IngestService(new Parsers(), store).ingestFile(file);

        assertEquals(1, store.count());
        assertEquals(2, store.all().get(0).sourceMessageIds().size());
    }

    private String json(String id, String body) {
        return "{\"message_id\":\"" + id
                + "\",\"channel\":\"sms\""
                + ",\"sender\":\"VM-ICICIB-T\""
                + ",\"received_at\":\"2026-08-05T07:52:00+05:30\""
                + ",\"device_id\":\"dev-test\""
                + ",\"body\":\"" + body + "\"}";
    }
    @Test
    void mergesSameTransactionWithDifferentTimezones(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("corpus.jsonl");

        String sms = "{\"message_id\":\"m-sms\",\"channel\":\"sms\","
                + "\"sender\":\"AD-HDFCBK-S\","
                + "\"received_at\":\"2026-07-19T00:20:00+05:30\","
                + "\"device_id\":\"dev-test\","
                + "\"body\":\"Rs 412.67 debited from a/c **4821 on 19-07-26 at 00:20 to UBER INDIA. Avl Bal: Rs.70,891.55.\"}";

        String email = "{\"message_id\":\"m-email\",\"channel\":\"email\","
                + "\"sender\":\"alerts@hdfcbank.net\","
                + "\"received_at\":\"2026-07-18T18:50:00Z\","
                + "\"device_id\":\"dev-test\","
                + "\"body\":\"Date: Sat, 18 Jul 2026 18:50:00 +0000\\n"
                + "Subject: Transaction alert on your account\\n\\n"
                + "Dear Customer,\\n\\n"
                + "Your account ending 4821 has been debited with INR 412.67.\\n"
                + "Merchant / Remarks: UBER INDIA\\n"
                + "Transaction reference: 1234567890\\n\\n"
                + "This is a system generated email.\"}";

        Files.writeString(file, sms + "\n" + email);

        InMemoryLedgerStore store = new InMemoryLedgerStore();
        new IngestService(new Parsers(), store).ingestFile(file);

        assertEquals(1, store.count());
        assertEquals(2, store.all().get(0).sourceMessageIds().size());
    }
}