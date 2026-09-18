CREATE TABLE IF NOT EXISTS balance_evidence (
                                                account_last4    VARCHAR(4)     NOT NULL,
    occurred_at      VARCHAR(40)    NOT NULL,
    stated_balance   DECIMAL(14, 2) NOT NULL,
    source_message_id VARCHAR(100)  NOT NULL
    );

CREATE INDEX IF NOT EXISTS idx_balance_evidence_account
    ON balance_evidence (account_last4, occurred_at);