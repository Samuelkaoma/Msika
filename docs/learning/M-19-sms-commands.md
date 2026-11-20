# M-19: Trader and agent commands

Card: `M-19` · Branch: `feature/M-19-sms-commands` · Depends on: M-15, M-18

## What we are building, in one paragraph

M-17 taught Msika to read a text and M-18 to receive and answer one. This card makes each command do its job: an
agent's `DEP B114 450` starts a real deposit through the payments system, the trader is texted when the money is
confirmed and can reply `NO` + ref, `SALE 120` records a declared sale, `TODAY` adds up the day, `REG` asks the
market office for a stall and `YES` records consent. Every command in `docs/SMS-PROTOCOL.md` now has an answer,
including honest "not available yet" answers for levy, statements and loans, which later cards fill in.

## New ideas in this lesson

- **Work after the commit**
  - **What it is:** a text is processed in one database transaction. Calling the payments system inside it would
    hold the transaction open while the network is slow, and the callback could arrive before the deposit row is
    even visible. So a command returns a `Later`: a piece of work the processor runs after the commit.
  - **Why we need it here:** `DEP` must save the deposit first, then send it, then tell the agent "sent" or "not
    sent". `BAL` and `ROUND` read balances from the payments system the same way.
  - **Tiny example:**
    ```java
    return new Result("DEP", List.of(), List.of(() -> {
        payments.send(request, agentWallet, traderWallet);   // after commit, outside any transaction
        return List.of(Reply.of(from, SmsTemplate.DEPOSIT_SENT, ...));
    }));
    ```

- **Savepoints**
  - **What it is:** a mark inside a transaction you can roll back to without losing everything before it
    (`PROPAGATION_NESTED` in Spring).
  - **Why we need it here:** when a use case refuses half-way (`RequestDeposit` saves a payment request, then finds
    the stall has no wallet), we roll back to the savepoint, keep the text marked as processed, and reply with the
    reason. Without it, the half-written request would be committed.

- **Domain events**
  - **What it is:** the core says "a deposit was confirmed" through an interface (`DepositEvents`); whoever cares
    implements it.
  - **Why we need it here:** the deposit rules stay in `msika-core` with no idea SMS exists (rule 7), and the SMS
    code queues the trader's confirmation in the same transaction as the change.

- **Crossing the tenant wall on purpose, narrowly**
  - **What it is:** `REG SOW-B-114` from a stranger names a stall, not a council. A security-definer function
    `sms_find_stall(code)` answers only "which council has this exact code". The request is then written as that
    council.

## Where it sits in the architecture

```
SmsProcessor (tx as sender's council)
  └─ SmsCommands.handle(command)
       ├─ DEP  → RequestDeposit (core, in a savepoint) → Later: PaymentService.send → "sent" / "not sent"
       ├─ NO   → ChangeDeposit.dispute (core)
       ├─ UNDO → ChangeDeposit.cancel  (core) → DepositEvents.cancelled → text to trader
       ├─ SALE → RecordEntry DECLARED_SALE (core) + a reference
       ├─ REG  → registration_request (council approves → RegisterTrader + AllocateStall)
       └─ TODAY, BAL, ROUND, STOP, START, YES ...
payment callback → DepositOutcomes → DepositEvents.confirmed / failed → DepositSmsNotifier → sms_outbound
```

## Step by step

1. Core: `DepositEvents` (confirmed, failed, cancelled), called by `DepositOutcomes` and `ChangeDeposit` only when
   their status change actually happened, so a repeated callback tells nobody twice.
2. `PaymentsWallet` gained an optional balance; `DepositRepository.findByReference`.
3. Migration `V11`: `registration_request`, `trader.daily_summaries`, `declared_sale` (the sale's reference),
   and `sms_find_stall`.
4. `SmsCommandHandler` gained `Later` work; `SmsProcessor` runs it after the commit and queues its replies.
5. `SmsCommands`: one method per command. `SmsQueries`: the reads (stall short codes, day totals by kind, an
   agent's day). `DepositSmsNotifier`: the texts for deposit outcomes. `RegistrationController`: list, approve,
   reject.

## Files touched

| File | What changed | Why |
|---|---|---|
| `msika-core/.../deposit/DepositEvents.java`, `DepositOutcomes.java`, `ChangeDeposit.java` | events | tell people about outcomes |
| `msika-api/.../db/migration/V11__sms_commands.sql` | new | registration, preferences, sale references |
| `msika-api/.../sms/SmsCommands.java`, `SmsQueries.java` | new | every command |
| `msika-api/.../sms/DepositSmsNotifier.java` | new | confirmation, failure and cancel texts |
| `msika-api/.../sms/RegistrationController.java` | new | the market office decides `REG` |
| `msika-api/.../sms/SmsProcessor.java`, `SmsCommandHandler.java` | `Later` work | no network inside a transaction |

## How we know it works

- `SmsCommandsApiTest.depositEndToEnd`: the card's Done item. `DEP B114 450` from an agent, the payments system
  is called, the signed callback confirms, and the deposit is `CONFIRMED`, the ledger has `DEPOSIT:DEPOSITED:45000`,
  and the trader's outbox has the confirmation word for word.
- The same class: `NO` flags a deposit; a failed payment texts the agent "not enough float"; `UNDO` within 10
  minutes cancels and tells the trader (another agent cannot); a refused deposit leaves no deposit and no payment
  request; `ROUND`; `SALE` and `TODAY` keep deposited, digital and declared apart; `BAL`, `STOP`, `START`; `REG`,
  approval (an officer gets 403, a second approval 409), `YES` once; rejection.
- `DepositLifecycleTest.Told`: each outcome is told exactly once, including money that arrives after a cancel.

## Rebuild it yourself

1. Write a handler that records a declared sale from `SALE 120` and replies with the day's total.
2. Make `DEP` call a fake payments system after the commit. Prove with a test that the deposit row exists before
   the call happens.
3. Make a refusal half-way through roll back what it wrote, using a savepoint.

## Mistakes to avoid

- Calling the payments system inside the processing transaction.
- Catching a use case's exception and carrying on in the same transaction: what it wrote before failing is still
  there. Use a savepoint.
- Telling the trader on every callback: a repeated callback must not send a second text.
- Matching `B114` with `like '%B114'`: that also matches `AB114`. Match the whole `-B-114` ending.
