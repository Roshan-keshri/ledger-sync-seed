package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS.
 *
 * TODO(ops): this only reads the "Dear Customer, Acct XX.... is debited with"
 * shape. There is at least one other ICICI format in the corpus that falls
 * straight through and is lost. Finish this.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    private static final Pattern V2 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) .*? "
                    + "on (?<when>\\d{2}-[A-Za-z]{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>.*?) ref no");

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher match = V1.matcher(m.body());

        if (!match.find()) {
            match = V2.matcher(m.body());
            if (!match.find()) return Optional.empty();
        }

        BigDecimal amount = Amounts.first(m.body());
        OffsetDateTime at = Dates.ist(match.group("when"));
        if (amount == null || at == null) return Optional.empty();

        String dir = match.group("dir");
        Direction d = dir.equals("debited") || dir.equals("Dr")
                ? Direction.DEBIT : Direction.CREDIT;

        return Optional.of(new ParsedTxn(
                match.group("acct"), at, d, amount,
                match.group("merchant").trim(),
                Amounts.statedBalance(m.body()),
                m.messageId()));
    }
}
