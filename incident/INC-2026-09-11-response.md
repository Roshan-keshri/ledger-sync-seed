# INC-2026-09-11 — Incident Response

## Reproduction

The incident was reproduced using the WATER CAN transaction from the affected account. The message contains a ₹5 debit followed by an available balance of ₹92,213.10, while the existing extraction logic returned ₹92,213.10 as the transaction amount.

A regression test was added at `src/test/java/in/simplifymoney/ledgersync/AmountsTest.java:45`. Before the fix, `readsWholeRupeeAmountBeforeBalance()` failed because the parser returned ₹92,213.10 instead of ₹5.00.

## Root Cause

The issue was traced to the transaction amount extraction regex in `src/main/java/in/simplifymoney/ledgersync/parse/Amounts.java:18`.

The previous regex required transaction amounts to contain exactly two decimal places. As a result, whole-rupee amounts such as `Rs.5` and `INR 18,000` were not matched. The search then continued through the message and could incorrectly select a later decimal-formatted account balance as the transaction amount.

## Blast Radius

Comparing the original and corrected amount extraction logic across `fixtures/corpus-a.jsonl` identified 38 affected records, representing 24 distinct message bodies after accounting for repeated copies.

A message is affected when the transaction amount is expressed as a whole-rupee value without decimal places and a later balance or available-limit value is present in a decimal format that matches the old regex.

## Why Existing Tests Stayed Green

The existing amount tests covered decimal-formatted transaction amounts such as `Rs.2,499.50`, `INR 333.33`, and `Rs.45,000.00`. They did not cover a whole-rupee transaction amount followed by a decimal-formatted balance.

Therefore, the existing test suite remained green even though this case could fail in production.

## Fix and Verification

The amount extraction regex was updated to accept both whole-rupee and two-decimal amounts. A regression test covering the ₹5 WATER CAN case was added and fails with the previous implementation but passes with the corrected implementation.

After the fix, the complete test suite passes with all 12 tests green.

## Incident Channel Update

- **Impact:** Whole-rupee transactions could be recorded using a later account balance; the reported ₹5 WATER CAN transaction was incorrectly stored as ₹92,213.10.
- **Detection:** Reproduced the production failure with a regression test and traced it to the transaction amount extraction regex in `Amounts.java:18`.
- **Blast Radius:** Corpus analysis identified 38 affected records across 24 distinct message bodies with the same parsing condition.
- **Root Cause:** The regex required two decimal places, causing whole-rupee transaction amounts to be skipped and a later decimal-formatted balance to be selected.
- **Resolution:** The parser now supports both whole-rupee and decimal amounts, with a dedicated WATER CAN regression test preventing this specific failure from recurring.