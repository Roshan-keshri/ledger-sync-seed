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
}