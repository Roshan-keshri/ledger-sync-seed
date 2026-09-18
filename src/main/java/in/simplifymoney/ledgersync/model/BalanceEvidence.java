package in.simplifymoney.ledgersync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BalanceEvidence(
        String accountLast4,
        OffsetDateTime occurredAt,
        BigDecimal statedBalance,
        String sourceMessageId) {
}