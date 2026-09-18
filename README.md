# ledger-sync

My implementation of the Simplify Money backend take-home.

The service reads bank SMS/email messages, turns them into normalized
transactions, removes duplicate evidence, identifies transfers and micro spends,
reconciles the ledger against bank balance evidence, and supports migration from
the existing SQL ledger to MongoDB.

---

## Quick Start

### Requirements

- JDK 21
- Docker with Docker Compose

Run:

```bash
./verify.sh
```

This starts MongoDB, runs the test suite, and runs the ledger self-check.

Individual commands:

```bash
docker compose up -d
./gradlew test
./gradlew selfCheck
```

On Windows:

```powershell
docker compose up -d
.\gradlew.bat test
.\gradlew.bat selfCheck
```

MongoDB runs on:

```text
localhost:27017
```

The main corpus is:

```text
fixtures/corpus-a.jsonl
```

---

# How the Pipeline Works

```text
Raw SMS / Email
      |
      v
Bank-specific parsers
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

A message is evidence for a transaction, but a message is not necessarily a
transaction.

The same transaction can appear in an SMS, an email, or a repeated bank
notification. The goal of ingestion is therefore to create one ledger entry per
real transaction while preserving every message ID that supports it.

---

# Corpus Result

Running the self-check currently gives:

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
```

Against the supplied checkpoint:

```text
transactions expected 257, produced 256

4821:
  transactions          145 (expected 146)
  ledger balance   48626.34
  bank balance     41126.34
  difference        7500.00

9075:
  transactions           91 (expected 91)
  ledger balance   51210.63
  bank balance     51210.63
  difference           0.00
```

I left these differences visible rather than changing the ledger simply to make
the checkpoint match. The reasons are explained below.

---

# Decision Log

## 1. Deduplicate using the transaction instant, not timestamp text

My first duplicate identity used the parsed transaction timestamp. While
investigating why my result did not agree with the checkpoint, I found a
₹412.67 UBER transaction represented by both SMS and email.

One timestamp used an India offset and the other used UTC. The strings were
different, but:

```java
occurredAt().toInstant()
```

was the same.

I changed duplicate identity to compare the instant.

I rejected counting the SMS and email as two transactions because that would
move my result closer to the expected 257 while knowingly putting the same real
transaction into the ledger twice.

With more time I would build a larger set of cross-channel duplicate fixtures
from additional banks.

---

## 2. Keep all message IDs when transactions are deduplicated

Initially it would have been simpler to keep the first message and ignore later
duplicates.

I rejected that because the later message is still useful evidence.

The final transaction therefore keeps all supporting:

```text
source_message_ids
```

This also became useful during SQL-to-Mongo migration and consistency checking,
because a transaction can be traced back to every message that produced it.

---

## 3. Do not treat `Avl Limit` as balance evidence

While working on reconciliation I initially allowed several "available" values
from bank messages to be interpreted as balances.

The corpus changed my mind.

HDFC card messages contain values such as:

```text
Avl Limit: Rs.196,250.03
```

That is a credit-card limit, not the balance of account `3310`.

Using it as balance evidence created reconciliation differences that were not
real ledger differences.

I removed `Avl Limit` from balance extraction.

With more time I would model credit-card limits separately instead of simply
excluding them from bank-account reconciliation.

---

## 4. Report the ₹7,500 difference instead of creating a transaction

Account `4821` does not fully reconcile.

The ledger gives:

```text
48626.34
```

while the bank evidence gives:

```text
41126.34
```

The difference is:

```text
7500.00
```

I traced the point where this difference appears to balance evidence on
2026-07-29, but I could not find a ₹7,500 transaction in the supplied corpus
that explains it.

One option would have been to create an inferred debit so that the final
balance matched.

I rejected that because the assignment's most important ledger property is that
false transactions must not be created.

The ₹7,500 therefore remains an `unexplained debit` in reconciliation.

With more time I would want another source of evidence, such as the account
statement around that date, before deciding what caused it.

---

## 5. Detect transfers from both sides of the movement

Direction alone originally made every debit spending and every credit income.

The corpus contains movements where the same amount leaves one user account and
arrives in another shortly afterwards.

Counting those legs as spend and income inflates both numbers.

I pair transactions when they have opposite directions, different user
accounts, matching amount/merchant information, and occur within the transfer
matching window.

Both legs are then classified as:

```text
TRANSFER
```

I rejected merchant-name-only transfer detection because merchant text by itself
was not enough evidence that the money remained with the user.

With more time I would make the matching window/configuration explicit and test
it against more transfer formats.

---

## 6. Treat small UPI debits separately from normal spending

The reporting contract requires a UPI debit of ₹100 or less to be `MICRO`.

I therefore classify these before ordinary debit spending.

They contribute to:

```text
micro_count
micro_total
```

but are not included in normal `SPEND`.

I kept this as categorization rather than changing the transaction amount or
dropping the transaction, because the transaction is still real ledger
activity.

---

## 7. Make persistent SQL ingestion idempotent

In-memory deduplication solved duplicates within one ingestion run, but it did
not solve this case:

```text
run ingestion
run ingestion again
```

The existing SQL table also did not give me a uniqueness guarantee I could rely
on.

I therefore check canonical transaction identity against existing SQL data and
merge source evidence when the transaction already exists.

I rejected using message ID as the SQL uniqueness rule because the corpus had
already shown that several message IDs can represent one transaction.

---

## 8. Use MongoDB and deterministic document identity

The assignment allowed DynamoDB or MongoDB.

I chose MongoDB because I could run the actual document store locally through
Docker Compose and measure its real query execution plans without requiring
cloud credentials.

Each document receives a deterministic `_id` based on transaction identity.

That means rerunning the migration writes the same logical document instead of
creating another one.

I considered random document IDs, but rejected them because retries after a
partial backfill would then need another uniqueness mechanism to avoid duplicate
documents.

---

## 9. Deduplicate dirty SQL rows during backfill

While testing migration I found that the SQL source can contain duplicate
transaction rows with different legacy source message IDs.

Simply skipping the later SQL row removed valid traceability.

I changed the backfill to group rows by canonical transaction identity and merge
their message IDs before saving to MongoDB.

This means repeated or partially completed backfills converge on the same
logical document-store state.

---

## 10. Check actual fields, not only store counts

A consistency checker that says:

```text
SQL count = 256
Mongo count = 256
```

does not prove that the stores agree.

A Mongo document could have the wrong amount or merchant and the counts would
still match.

The checker therefore locates transactions through source message IDs and
compares fields including:

```text
account
transaction instant
direction
amount
category
merchant
```

It also checks for unexpected Mongo transactions.

Tests deliberately alter document-store data and verify that the checker reports
the difference.

---

# What the Data Made Me Decide

These were choices I could not make correctly from the task description alone.

| What I saw in the corpus | What I chose | What I would do with more time |
|---|---|---|
| The ₹412.67 UBER transaction appeared in SMS and email with different timezone offsets | Compare transaction identity using the underlying instant and preserve both message IDs | Add more cross-bank/cross-channel duplicate fixtures |
| `Rs.5` appeared before `Avl Bal: Rs.92,213.10` | Allow whole-rupee transaction amounts instead of requiring two decimal places | Move more parsing rules into explicit bank-format parsers instead of relying on shared regexes |
| HDFC card messages contained `Avl Limit` | Exclude credit-card limit from account balance evidence | Model credit-card state separately |
| Equal opposite movements appeared across the user's accounts | Pair the legs and classify them as `TRANSFER` | Validate the transfer matching window against a larger dataset |
| Account `4821` had a ₹7,500 balance movement with no transaction explaining it | Report an unexplained reconciliation debit | Compare against bank statement data before assigning a cause |
| Dirty SQL contained duplicate transaction rows with different source IDs | Merge evidence during backfill instead of dropping later rows | Move canonical uniqueness closer to the database schema if migration constraints allow |
| Re-running ingestion could write the same transaction again | Make persistence idempotent, not just in-memory ingestion | Add stronger concurrency tests for overlapping ingestion jobs |

The main principle I followed was that matching the supplied totals is not more
important than preserving transaction truth. When the evidence and checkpoint
disagreed, I kept the disagreement visible.

---

# Incident INC-2026-09-11

The incident reported a ₹5 water-can purchase being recorded as ₹92,213.10.

The message was shaped like:

```text
Rs.5 debited ... Avl Bal: Rs.92,213.10
```

The original amount pattern required exactly two decimal places.

That meant it skipped:

```text
Rs.5
```

and continued until it found:

```text
Rs.92,213.10
```

which was the account balance.

The amount parser now accepts both whole-rupee and two-decimal transaction
amounts.

A regression test covers the incident message shape.

My corpus investigation found:

```text
38 affected messages
24 distinct message bodies
```

The complete investigation is documented in:

```text
incident/INC-2026-09-11-response.md
```

---

# Reconciliation

Balance values found in bank messages are retained as balance evidence.

The reconciliation logic walks that evidence and checks whether known ledger
activity explains the observed movement.

For account `9075`:

```text
ledger closing balance   51210.63
bank closing balance     51210.63
difference                   0.00
```

For account `4821`:

```text
ledger closing balance   48626.34
bank closing balance     41126.34
difference                7500.00
```

The ₹7,500 movement is reported rather than converted into a synthetic
transaction.

---

# Document Store

## Why MongoDB

I used MongoDB 8 for the document-store migration.

It gives this project:

- local Docker Compose startup
- document storage
- compound indexes
- aggregation
- upserts
- query execution statistics

Start it with:

```bash
docker compose up -d
```

The application connects to:

```text
mongodb://localhost:27017
```

---

## Document Model

A transaction is stored as one MongoDB document:

```json
{
  "_id": "canonical-transaction-identity",
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

The deterministic `_id` is built from the transaction identity:

```text
account
transaction instant
direction
amount
merchant
```

The source IDs remain an array because several messages can support the same
transaction.

---

## Indexes

The document store creates these indexes:

```text
(account_last4 ASC, occurred_at DESC)
(account_last4 ASC, category ASC)
(source_message_ids ASC)
```

They correspond to the three access patterns required by `DocumentStore`.

### Query 1

One account's transactions for one month, newest first.

The compound account/time index lets MongoDB restrict the scan to the requested
account and time range while also supporting newest-first ordering.

### Query 2

Running totals per category for one account across its full history.

MongoDB first restricts the data to the account and then groups transactions by
category.

### Query 3

Given a source message ID, find its transaction.

The `source_message_ids` array is indexed for this lookup.

---

# 100,000 Transaction Benchmark

I populated a separate benchmark database with 100,000 generated transactions
and used MongoDB:

```text
executionStats
```

to measure:

```text
totalDocsExamined
nReturned
```

The final measurements were:

| Query | Examined | Returned |
|---|---:|---:|
| Account transactions for one month, newest first | 45 | 45 |
| Category totals for one account across its history | 100 | 4 |
| Transaction lookup by source message ID | 1 | 1 |

These are the requested six numbers:

```text
45 / 45
100 / 4
1 / 1
```

The collection contained 100,000 transactions during the measurement.

For the first query, MongoDB examined only the 45 documents it returned.

For category totals it examined the 100 transactions belonging to the requested
account and produced four grouped category results.

For message-ID lookup it examined and returned one document.

The benchmark can be reproduced with:

```bash
./gradlew benchmark
```

---

# SQL to MongoDB Backfill

The existing SQL ledger cannot be assumed to be clean.

The backfill therefore:

```text
read SQL rows
    |
    v
group by canonical transaction identity
    |
    v
merge source message IDs
    |
    v
upsert deterministic Mongo document
```

This handles historical SQL duplicates and makes migration retries safe.

If migration stops after writing part of the ledger, rerunning it writes the
same deterministic documents again instead of creating duplicates.

---

# Consistency Checking

The consistency checker does more than compare the number of rows/documents.

For every SQL transaction it uses the source message evidence to find the
corresponding document and checks:

```text
account
time
direction
amount
category
merchant
```

It also checks account/month results for unexpected documents, and the MongoDB
implementation performs an additional full-store check for extra documents.

The test suite includes cases where MongoDB data is deliberately changed so the
checker must report the altered field rather than simply reporting a count
difference.

---

# AI Disclosure

I used **ChatGPT** while working on this assignment.

I used it mainly for:

- discussing implementation alternatives
- debugging failing tests
- reviewing small pieces of Java code
- suggesting edge cases to test
- understanding MongoDB `explain()` output
- reviewing documentation

I did not treat generated code as correct without running it against the tests
and corpus.

## A case where the AI was wrong

While implementing reconciliation, an AI suggestion effectively returned the
result from inside the account-processing loop.

The problematic structure was:

```java
for (String account : accounts) {
    // calculate discrepancies for this account

    return document;
}
```

That meant reconciliation stopped after the first account.

The self-check output made the problem visible because reconciliation was not
processing all accounts.

I changed it to:

```java
for (String account : accounts) {
    // calculate discrepancies for this account
}

return document;
```

The difference is small in code but important in behavior: the first version
can only reconcile one account, while the final version finishes processing all
accounts before returning the report.

I reran the self-check after the change rather than assuming the generated
control flow was correct.

## Another AI mistake I caught during benchmarking

The first benchmark generator suggested during AI-assisted development created
amounts using:

```java
new BigDecimal((i % 5000) + ".00")
```

For `i = 0`, that generated:

```text
0.00
```

which violates the frozen `NormalizedTxn` contract requiring a positive amount.

The benchmark failed immediately.

I changed it to:

```java
new BigDecimal((i % 5000 + 1) + ".00")
```

before recording any benchmark results.

The initial category generator also accidentally tied category selection to the
same modulo pattern as account selection. That meant an account repeatedly
received only one category.

I changed category generation so transactions for the same account cycle across
the four categories, then cleared the benchmark collection and reran all
100,000 transactions before recording the final six numbers.

---

# What's Unfinished

There are two data questions I cannot resolve from the supplied corpus alone.

### 1. Checkpoint says 257 while I produce 256

The remaining difference is the ₹412.67 UBER transaction represented through
SMS and email.

The two messages use different timezone offsets but resolve to the same instant
and describe the same transaction.

I therefore keep one transaction with multiple source message IDs.

I have not added a special case to force the count to 257.

### 2. Account 4821 has an unexplained ₹7,500 movement

The balance evidence shows the difference, but I cannot find a corresponding
transaction in the corpus.

I report it as:

```text
unexplained debit
```

rather than inventing a ledger entry.

With more time, the next thing I would request is additional bank evidence
around 2026-07-29.

### 3. Transfer matching is heuristic

Transfer detection currently relies on the evidence available in the messages:
account pair, opposite direction, amount, merchant information, and timing.

That works for the supplied corpus, but with more time I would validate the
matching rules against a larger bank/message dataset and make the matching
window configurable.

I would rather document this limitation than claim that a heuristic derived
from one corpus is universally correct.

---

# Important Files

```text
src/main/java/in/simplifymoney/ledgersync/
  ingest/       ingestion and deduplication
  json/         JSON parsing/writing
  model/        transaction domain model
  parse/        SMS/email parsers
  report/       ledger, summary and reconciliation
  store/        SQL store, MongoDB store, backfill and consistency checker
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

The supplied frozen contract was kept intact:

```text
model/NormalizedTxn.java
model/Category.java
NormalizedTxnContractTest.java
```

The implementation was changed behind the contract rather than changing the
contract to make the corpus results fit.