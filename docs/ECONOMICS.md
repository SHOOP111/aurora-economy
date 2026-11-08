# Economic design

## Units and rounding

A currency's scale is fixed when first persisted. For COIN with two decimals, `1.23` is `123` minor units. Amounts accept plain nonnegative ASCII decimal notation and must be greater than zero for financial commands.

No commas, signs, exponent notation, NaN, infinity, excess meaningful fractional digits or overflow are accepted. Trailing zeros that represent an exact minor-unit amount are harmless. Configured starter amounts and limits may be zero.

The maximum supported issued supply is `Long.MAX_VALUE` minor units per currency. Supply is bounded as well as individual balances, so an accumulation across many accounts cannot silently exceed the representable currency range.

### Transfer fee

```text
fee = ceil(amount × feeBps / 10000)
payer debit = amount + fee
recipient credit = amount
treasury credit = fee
```

Default `25 bps` is `0.25%`, not `25%`. A one-minor-unit payment with a positive fee can owe another minor unit. This intentionally favors explicit accounting over silent fractional fee loss.

Fees are snapshotted into escrows and recurring orders. Ordinary one-off transfers use the configured policy at execution. Committed request replays return the original result even after a policy change.

## Starting grants and issuance

Registration grants configured starting balances through `system:mint`. Registration is one-time per UUID and remains replay-safe across name changes and restarts. A player receiving money before their first registration can still receive the configured first-registration grant later.

Changing starter grants does not retroactively alter old accounts. Adding a currency does not repeat an existing player's global starter grant. Use audited administrative issuance if a deliberate migration grant is required.

Mint and burn are explicit administrator or trusted Vault-plugin operations. There are no automatic participation rewards or economy-stabilizing minting loops. Monitor starter-grant farming with authenticated UUIDs and your server's anti-alt policies.

## Solvency-first savings

A deposit has immutable principal, reserved interest and a maturity instant:

```text
interest = floor(principal × aprBps × termSeconds / (10000 × 365 × 86400))
wallet → deposit custody: principal
treasury → deposit custody: interest
```

The contract opens only if both the player principal and the treasury interest reservation can be funded atomically. Interest is simple, not compounded, and uses a nominal 365-day year. No yield is minted at maturity.

State machine:

```text
LOCKED ── owner claim at/after maturity ──▶ CLAIMED
```

Claim transfers the entire principal plus reserved yield to the owner's wallet. Partial and early claims are unsupported. A frozen owner cannot claim until released. Tiny deposits may round to zero yield and then need no treasury funding.

This is a fully funded in-game time-lock instrument, not a real-world bank deposit, insurance scheme, loan or promise of sustainable external returns.

## Payment escrow

Creation reserves the full amount plus the snapshotted transfer fee from the payer. No recipient credit occurs until acceptance.

```text
OPEN ── beneficiary accepts before expiry ──▶ ACCEPTED
  │
  ├── payer cancels ────────────────────────▶ CANCELLED
  └── maintenance sees expiry ─────────────▶ EXPIRED
```

Acceptance sends principal to the beneficiary and fee to treasury. Cancellation and expiry return **both principal and fee** to the payer. The expiry boundary is strict: acceptance is refused when `now >= expiresAt`. Maintenance processes refunds while players are offline.

**This is intentionally refundable payment custody, not trustless commerce.** The payer can cancel until acceptance. A beneficiary should not hand over valuable goods based solely on an `OPEN` record. The plugin does not control item delivery, evaluate work, support disputes or atomically exchange items for payment.

Incoming acceptance and refunds can credit a frozen wallet; the wallet still cannot spend those credits. Cancelling an escrow does not restore the same-day spending budget, preventing budget-bypass reserve/refund loops.

## Recurring orders

Orders have an owner, beneficiary, amount, snapshotted fee, interval, remaining execution count and next due instant. Funds are not reserved at creation.

```text
ACTIVE ── final successful installment ──▶ COMPLETED
   │
   ├── failed financial precondition ───▶ PAUSED ── owner resume ──▶ ACTIVE
   └── owner cancel ────────────────────▶ CANCELLED
PAUSED ── owner cancel ──────────────────▶ CANCELLED
```

Each successful installment applies an ordinary taxed transfer, updates the spending counter, increments the execution index and advances the next due instant in one transaction.

Insufficient funds, daily spending limits, range errors or wallet freezes pause the order with a visible reason. Storage failures are not treated as customer insufficiency and do not silently pause the order. Paused orders still count toward the active-order quota.

No catch-up burst is performed. One overdue installment can execute after restart, then another full interval must pass. Resume likewise starts a new interval. Intervals are 1 minute–30 days and order totals are 1–10000 installments.

## Reserve-backed exchange

Each sorted currency pair has a treasury-owned pool with actual balances in both currencies. Reversing a trade direction addresses the same pool.

With input reserve `x`, output reserve `y`, user input `dx`, and fee basis points `f`:

```text
effectiveScaled = dx × (10000 - f)
dy = floor(y × effectiveScaled / (x × 10000 + effectiveScaled))
new input reserve = x + dx
new output reserve = y - dy
```

Calculations use `BigInteger`; the effective input is not prematurely rounded. The full input enters the pool, so its fee remains in reserves. For supported positive reserves and input, `0 <= dy < y`, and the post-trade raw constant product does not decrease.

Execution requires:

- distinct configured currencies;
- both funded reserves;
- a strictly positive input and minimum output;
- at least one output minor unit;
- sufficient wallet funds and budget;
- a result meeting `dy >= minimumOutput`;
- no account or reserve overflow.

Quotes are observations, not price reservations. A concurrent trade or administrator liquidity addition can move the result. Set a sensible minimum output; a tiny minimum willingly accepts large slippage. The default whole-unit GEM scale makes very small COIN→GEM trades impossible and can create noticeable rounding loss.

There are no LP tokens, external prices, arbitrage guarantees or automated pool rebalancing. Fees and market inventory belong to the server treasury. Adding liquidity at a different ratio changes prices; it should be a deliberate administrative policy action. v1.0 does not expose automatic LP redemption or pool withdrawal commands.

## UTC spending budgets

Each currency has an independent optional daily outgoing limit. `0` disables the cap but accounting still records daily spending.

Counted: payment principal plus fee, escrow amount plus reserved fee, swaps' input, recurring installments and external Vault withdrawals.

Not counted: incoming payments, treasury administration, first-registration issuance, internal savings lock/claim movements and refunds. Refunding escrow does not reduce the recorded budget. Counters reset by UTC date, not by a rolling 24-hour window or a player's local time zone.

## Suggested server balancing

- Set starter grants conservatively and authenticate player identities.
- Fund treasury before advertising yield. A large APR consumes real server currency reserves.
- Prefer sinks implemented by trusted shop integrations and deliberate treasury policy to hidden balance rewrites.
- Seed pools at intentional ratios, with enough reserves to avoid enormous price impact.
- Consider finer target-currency precision **before first launch** if small exchange trades are important.
- Watch liquid supply, locked custody and treasury separately. Locked money is still issued money.
- Use freezes for incidents, not silent confiscation. Admin burn is irreversible economic policy with an audit trail.
