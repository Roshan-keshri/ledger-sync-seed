CREATE UNIQUE INDEX IF NOT EXISTS idx_balance_evidence_source
    ON balance_evidence (source_message_id);