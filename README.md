# ledger-sync

Simplify Money **Software Engineering Intern (Backend, Java)** take-home implementation.

This service reads bank SMS/email messages and converts them into a normalized,
deduplicated ledger that can be used to understand where a user's money went.

---

## Quick Start

### Requirements

- JDK 21
- Docker with Docker Compose

### Run verification

```bash
./verify.sh
```

The verification script:

1. Starts MongoDB using Docker Compose.
2. Runs the automated test suite.
3. Runs the ledger self-check against the supplied corpus.

The individual commands are:

```bash
docker compose up -d
./gradlew test
./gradlew selfCheck
```

On Windows PowerShell, the Gradle commands can be run as:

```powershell
.\gradlew.bat test
.\gradlew.bat selfCheck
```

MongoDB runs locally on port `27017`.

---

## What the Service Does

The input is:

```text
fixtures/corpus-a.jsonl
```

Each line contains one SMS or email uploaded from the user's device.

The service parses these messages and produces normalized financial
transactions while handling:

- multiple bank message formats
- SMS and email sources
- duplicate messages
- the same transaction appearing through different channels
- micro transactions
- transfers between the user's own accounts
- balance evidence
- reconciliation discrepancies
- SQL persistence
- migration from SQL to MongoDB

The reporting pipeline produces:

```text
ledger.json
summary.json
reconciliation.json
```

---

## Architecture

The ingestion flow is:

```text
Raw SMS / Email
      |
      v
Message Parser
      |
      v
ParsedTxn
      |
      v
Normalization + Categorization
      |
      v
Deduplication + Transfer Detection
      |
      v
NormalizedTxn
      |
      v
SQL Ledger
      |
      +------> Reports / Reconciliation
      |
      +------> MongoDB Backfill
                    |
                    v
             Document Store
                    |
                    v
            Consistency Checker
```

Every real transaction is represented once while all source message IDs that
provide evidence for the transaction are retained for traceability.

---

## Transaction Model

Each normalized transaction contains:

```text
account_last4
occurred_at
direction
amount
category
merchant
source_message_ids
```

`occurred_at` represents when the bank says the transaction occurred rather
than when the message was received.

Amounts are represented using `BigDecimal` and remain positive. The transaction
direction (`DEBIT` or `CREDIT`) carries the sign.

---

## Categories

Every transaction belongs to exactly one of four categories.

| Category | Meaning |
|---|---|
| `SPEND` | Money left the user and is ordinary spending |
| `INCOME` | Money arrived and belongs to the user |
| `MICRO` | UPI debit of ₹100 or less |
| `TRANSFER` | Money moved between the user's own accounts |

`MICRO` transactions are not included in normal `SPEND` totals.

Similarly, `TRANSFER` transactions are excluded from both spending and income
because counting them as either would inflate the user's financial activity.

---

## Parsing

The corpus contains multiple message formats.

The implementation supports:

- HDFC SMS
- ICICI SMS
- alternate ICICI SMS formats
- transaction emails

Parsing extracts information such as:

```text
account
transaction time
direction
amount
merchant
stated balance
source message ID
```

The parser uses the transaction time contained in the bank message rather than
the message delivery time whenever transaction time is available.

---

## Deduplication

One bank transaction does not necessarily correspond to one message.

The same transaction may appear:

- in an SMS
- in an email
- in repeated messages

Transactions are therefore deduplicated using their transaction identity rather
than only their message ID.

The identity uses:

```text
account
transaction instant
direction
amount
merchant
```

Timestamp comparisons use the underlying instant.

This is important because two messages can contain different timezone offsets
while still describing exactly the same point in time.

When duplicate evidence is found, the transaction remains one ledger entry and
the source message IDs are combined.

---

## Transfer Detection

Transfers between the user's own accounts should not appear as spending or
income.

The implementation looks for transactions that have:

- different user accounts
- opposite directions
- the same amount
- matching merchant information
- timestamps within the transfer matching window

Matching legs are classified as:

```text
TRANSFER
```

This prevents internal account movements from artificially increasing spending
and income totals.

---

## Corpus Result

Processing:

```text
fixtures/corpus-a.jsonl
```

produces:

```text
messages read         522
unique transactions   256
messages skipped       43
```

Category totals are:

```text
SPEND       142567.64
INCOME      142791.16
MICRO         4443.85
TRANSFER     62000.00
```

---

## Checkpoint Difference

The supplied checkpoint expects:

```text
257 transactions
```

while the implementation produces:

```text
256 unique transactions
```

This difference is intentional and documented rather than hidden.

A ₹412.67 UBER transaction appears through both SMS and email. The timestamps
use different timezone offsets but represent the same instant.

These messages therefore provide multiple pieces of evidence for one real
transaction.

Counting both as separate transactions would violate the requirement that each
real transaction appears exactly once.

For this reason, the implementation keeps the deduplicated count of 256 instead
of manufacturing or retaining another transaction simply to make the checkpoint
equal 257.

---

## Reconciliation

Balance observations from bank messages are stored separately as evidence and
used to determine whether ledger activity explains observed account balances.

### Account 9075

```text
Calculated closing balance: 51210.63
Bank closing balance:       51210.63
Difference:                     0.00
```

The account reconciles exactly.

### Account 4821

```text
Calculated closing balance: 48626.34
Bank closing balance:       41126.34
Difference:                  7500.00
```

The balance evidence shows an unexplained ₹7,500 debit appearing on
2026-07-29.

The supplied corpus does not contain a transaction that can honestly explain
this movement.

The implementation therefore reports:

```text
7500.00 unexplained debit
```

in reconciliation rather than creating a synthetic transaction to force the
balances to match.

This follows the requirement that reconciliation must be honest and that false
transactions must never be introduced.

---

## Incident INC-2026-09-11

The reported incident showed a ₹5 water-can purchase appearing as:

```text
₹92,213.10
```

### Root Cause

The original amount regex required exactly two decimal places.

For a message shaped like:

```text
Rs.5 debited ... Avl Bal: Rs.92,213.10
```

the parser rejected:

```text
Rs.5
```

because it did not contain two decimal places.

It continued searching the message and incorrectly matched:

```text
Rs.92,213.10
```

which was the available balance.

### Fix

The amount parser was changed to accept both:

```text
Rs.5
Rs.5.00
```

while still taking the first valid transaction amount.

A regression test covers the exact incident message shape.

### Blast Radius

Corpus investigation found:

```text
38 affected messages
24 distinct message bodies
```

The detailed incident investigation is available at:

```text
incident/INC-2026-09-11-response.md
```

---

## SQL Ledger

The existing SQL ledger remains the source used for migration.

Persistent ingestion is idempotent.

Before inserting a transaction, canonical transaction identity is checked so
that rerunning ingestion or processing overlapping input does not create
additional ledger transactions.

When duplicate transaction evidence is found, source message IDs are combined
rather than creating another transaction.

---

# Document Store Migration

## Why MongoDB

MongoDB 8 was selected as the document store.

It was chosen because it:

- runs locally through Docker Compose
- provides native document storage
- supports compound indexes
- supports aggregation
- supports deterministic upserts
- provides query execution statistics
- is straightforward to reproduce locally

The database starts with:

```bash
docker compose up -d
```

The application uses:

```text
mongodb://localhost:27017
```

---

## MongoDB Document Model

Each transaction is represented as one document.

Conceptually:

```json
{
  "_id": "deterministic-transaction-identity",
  "account_last4": "4821",
  "occurred_at": "2026-07-04T20:24:00Z",
  "direction": "DEBIT",
  "amount": "2499.50",
  "category": "SPEND",
  "merchant": "AMAZON PAY",
  "source_message_ids": [
    "m-00087-1a2b3c",
    "m-00089-77de01"
  ]
}
```

The deterministic `_id` is based on transaction identity.

This allows repeated writes of the same transaction to converge on the same
document instead of creating duplicates.

---

## MongoDB Indexes

Three indexes support the service's required access patterns.

### Account transactions by month

```text
(account_last4 ASC, occurred_at DESC)
```

This supports retrieving one account's transactions for a month in newest-first
order.

### Account/category access

```text
(account_last4 ASC, category ASC)
```

This supports account-level category access and aggregation.

### Message ID lookup

```text
(source_message_ids ASC)
```

This supports finding the transaction produced by a particular source message.

---

## Required Document Store Queries

`DocumentStore` exposes three service queries.

### Query 1

One account's transactions for one month, newest first.

### Query 2

Running totals per category for one account across its entire history.

The totals are calculated using MongoDB aggregation.

### Query 3

Given a source message ID, find the transaction it produced.

---

## 100,000 Transaction Benchmark

A benchmark database was populated with:

```text
100,000 transactions
```

MongoDB `executionStats` was used to measure:

```text
totalDocsExamined
nReturned
```

The measured results were:

| Query | Documents Examined | Results Returned |
|---|---:|---:|
| Account transactions for one month | 45 | 45 |
| Category totals for one account | 100 | 4 |
| Transaction lookup by message ID | 1 | 1 |

The first query therefore examines only the documents needed for the requested
account/month rather than scanning the 100,000-document collection.

The category aggregation examines the 100 transactions belonging to the target
account and returns four category totals.

Message-ID lookup examines one document and returns one document.

The benchmark can be reproduced with:

```bash
./gradlew benchmark
```

The benchmark uses a separate MongoDB database from the application data.

---

## SQL to MongoDB Backfill

`Backfill` migrates existing SQL ledger transactions into MongoDB.

The SQL ledger may contain historical duplicate rows, so the backfill first
groups transactions using canonical transaction identity.

If duplicate SQL rows represent the same transaction, their source message IDs
are merged.

The resulting transaction is then written using deterministic MongoDB identity.

This makes the migration rerunnable.

Running the backfill again produces the same logical document-store state
instead of creating another copy of every transaction.

The same behavior also allows the migration to recover safely after a partial
failure: successfully written transactions can be written again without
creating duplicates.

---

## Consistency Checker

The consistency checker verifies that SQL and MongoDB represent the same
transactions.

It does not simply compare row counts.

For source transactions it uses source message IDs to locate the corresponding
document and compares fields including:

```text
account
transaction instant
direction
amount
category
merchant
```

When a value differs, the checker reports the transaction and the field that
does not match.

It also checks for transactions present in the MongoDB store that do not have a
corresponding SQL transaction.

Tests deliberately modify document-store data and verify that the checker
detects the alteration.

---

# Decision Log

## 1. Use BigDecimal for Money

All financial values use `BigDecimal`.

Binary floating-point types such as `double` are avoided because financial
calculations require exact decimal arithmetic.

---

## 2. Deduplicate Transactions, Not Messages

Message ID cannot be used as transaction identity because one transaction can
produce several bank messages.

Transaction identity therefore uses account, time, direction, amount and
merchant information.

---

## 3. Compare Transaction Times by Instant

Two timestamps with different timezone offsets may describe exactly the same
moment.

Transaction identity therefore compares the underlying instant rather than the
text representation of the offset.

This was necessary to correctly deduplicate the SMS/email representation of the
₹412.67 UBER transaction.

---

## 4. Preserve Source Evidence

Deduplication must not remove traceability.

When several messages describe the same transaction, their source message IDs
are combined into the single normalized transaction.

---

## 5. Detect Internal Transfers from Both Legs

Equal opposite-direction movements between the user's own accounts within the
matching time window are classified as `TRANSFER`.

This prevents internal money movement from being counted as spending or income.

---

## 6. Separate MICRO from SPEND

UPI debit transactions of ₹100 or less are classified as `MICRO`.

They contribute to `micro_count` and `micro_total` rather than ordinary spend.

---

## 7. Do Not Treat Credit-Card Available Limit as Balance

Messages can contain values such as:

```text
Avl Limit
```

for a credit card.

An available credit limit is not an account balance and therefore is not used
as reconciliation evidence.

Treating it as a balance produced false reconciliation discrepancies, so only
actual supported balance fields are collected.

---

## 8. Never Manufacture Reconciliation Transactions

When balance evidence contains a movement that cannot be explained by a corpus
transaction, the difference is reported.

The ₹7,500 discrepancy on account `4821` is therefore kept in
`reconciliation.json` rather than being turned into a fake debit.

---

## 9. Use MongoDB for the Document Store

MongoDB provides the required document model, indexes, aggregation, upserts and
execution statistics while remaining simple to run locally through Docker
Compose.

---

## 10. Make Migration Rerunnable

MongoDB document identity is deterministic and the backfill deduplicates dirty
SQL rows before writing.

As a result, retries and overlapping migration runs converge on the same
logical state.

---

# AI Usage

AI tools were used during implementation for:

- discussing implementation approaches
- debugging assistance
- reviewing code
- generating test ideas
- investigating edge cases
- improving documentation

AI-generated suggestions were treated as suggestions rather than assumed to be
correct. Changes were verified through automated tests, self-check output and
direct inspection of the supplied corpus.

### Example of an incorrect AI-generated implementation

During reconciliation work, an AI-generated version placed a return statement
inside the account-processing loop.

That caused reconciliation to stop after processing the first account.

Running the self-check exposed the incomplete reconciliation result. The
control flow was corrected so that all accounts were processed before the
reconciliation document was returned.

### Benchmark validation

AI assistance was also used while creating the 100,000-transaction benchmark.

The initial benchmark generator allowed:

```text
0.00
```

as an amount.

`NormalizedTxn` correctly rejected this because transaction amounts must be
positive.

The benchmark failed immediately, the generated data was corrected to start at
`1.00`, and only successful measurements were recorded.

The benchmark's initial category generator also correlated account number and
category, causing each benchmark account to receive only one category. The
generator was corrected so each account receives transactions across all four
categories before the final benchmark numbers were recorded.

---

# Verification

The primary verification command is:

```bash
./verify.sh
```

It starts MongoDB and runs:

```text
automated tests
ledger self-check
```

The current self-check result is:

```text
INGEST
  messages read          522
  transactions written   256
  messages skipped        43

BY CATEGORY
  SPEND        142567.64
  INCOME       142791.16
  MICRO          4443.85
  TRANSFER      62000.00

AGAINST fixtures/corpus-a-totals.json
  transactions expected 257, produced 256

  4821:
    transactions 145 (expected 146)
    ledger balance 48626.34
    bank balance   41126.34
    difference      7500.00

  9075:
    transactions 91 (expected 91)
    ledger balance 51210.63
    bank balance   51210.63
    difference         0.00
```

The checkpoint difference is intentionally visible.

---

# Known Discrepancy / Unfinished Data Issue

The supplied checkpoint expects 257 transactions while the implementation
produces 256 unique transactions.

The evidence in the corpus supports treating the SMS and email representation
of the ₹412.67 UBER transaction as the same real transaction.

Account `4821` additionally contains an unexplained ₹7,500 balance movement.

Both differences are reported rather than hidden.

No synthetic transaction or balancing adjustment is introduced simply to make
the checkpoint match.

---

# Important Project Files

```text
src/main/java/in/simplifymoney/ledgersync/
  ingest/       ingestion and transaction processing
  json/         JSON reading/writing
  model/        normalized domain model
  parse/        bank SMS/email parsers
  report/       summary and reconciliation generation
  store/        SQL store, MongoDB store, backfill and consistency checking
  App.java
  SelfCheck.java
  Benchmark.java

fixtures/
  corpus-a.jsonl
  corpus-a-totals.json

incident/
  INC-2026-09-11.md
  INC-2026-09-11-response.md

docker-compose.yml
verify.sh
```

---

# Frozen Contract

The following supplied contract files were not modified:

```text
model/NormalizedTxn.java
model/Category.java
NormalizedTxnContractTest.java
```

The implementation works behind these contracts rather than changing them to
make the data fit.