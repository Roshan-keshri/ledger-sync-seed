package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmailParser implements MessageParser {

    private static final Pattern TXN = Pattern.compile(
            "account ending (\\d{4}) has been (debited|credited) with (?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.\\d{2})?).*?Merchant / Remarks:\\s*([^\\r\\n]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher txn = TXN.matcher(m.body());
        Matcher date = Pattern.compile("(?m)^Date:\\s*(.+)$").matcher(m.body());

        if (!txn.find() || !date.find()) return Optional.empty();

        return Optional.of(new ParsedTxn(
                txn.group(1),
                OffsetDateTime.parse(date.group(1).trim(), DATE),
                txn.group(2).equalsIgnoreCase("debited") ? Direction.DEBIT : Direction.CREDIT,
                new BigDecimal(txn.group(3).replace(",", "")).setScale(2),
                txn.group(4).trim(),
                null,
                m.messageId()
        ));
    }
}