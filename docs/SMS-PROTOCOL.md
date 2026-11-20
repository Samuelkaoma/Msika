# SMS protocol

Traders and agents use Msika by text message. This file is the contract for what they can send and
what they get back. The parser in `msika-core` (`zm.msika.core.sms`) implements exactly this, and
`SmsCommandParserTest` has a test for every example below.

## General rules

- **Case and spacing do not matter.** `dep b114 450`, `DEP  B114  450` and `Dep B114 K450` are the
  same message. Commas in amounts are ignored (`1,200`). A leading `K` is allowed.
- **Amounts are kwacha**, with up to two decimals: `450`, `450.5`, `K12.50`. They are stored as ngwee.
  Amounts must be greater than zero and no more than K50,000 for one message (configurable).
- **Stall codes** are written the way they appear on the stall plate. Within a market a short form is
  accepted: `B114` matches `SOW-B-114` when the agent works in Soweto market.
- **Every reply fits one SMS**: at most 160 characters, plain GSM-7 characters only (no emoji, no
  curly quotes). A test checks every reply template against this.
- **Every reply that records something carries a reference**, four characters from
  `23456789ABCDEFGHJKMNPQRSTUVWXYZ` (no 0/O or 1/I/L confusion), unique per council per day.
- **Unknown sender:** if the phone number is not registered, the only reply is
  `Msika: this number is not registered. Ask your market office or agent to register you, or send REG and your stall number.`
- **Unknown command:** `Msika: sorry, we did not understand. Send HELP for the list of commands.`
- **Rate limit:** more than 20 messages from one number in 10 minutes are stored but not answered.
- **Languages:** English replies first. Each command also accepts Nyanja and Bemba keywords
  (Phase 9). The trader's language is stored, and replies use it once translations exist.

## Trader commands

| Send | Meaning | Recorded as | Example reply |
|---|---|---|---|
| `REG SOW-B-114` | Ask to be registered at a stall | Registration request for the council to approve | `Msika: request sent for stall SOW-B-114. The market office will confirm.` |
| `YES` (after approval) | Accept the terms and the daily levy sweep | Consent with timestamp | `Msika: you are registered at SOW-B-114. Levy will be paid from your deposits. Send HELP for commands.` |
| `SALE 120` or `S 120` | Record a cash sale that was not deposited | `DECLARED_SALE`, provenance `DECLARED` | `Msika: sale K120 noted. Today declared K340. Ref 7KQ2.` |
| `TODAY` | Today's totals | none | `Msika SOW-B-114 today: deposited K450, digital K200, declared K340, levy paid K5.` |
| `BAL` | Wallet balance and levy status | none | `Msika: wallet K1,230.00. Levy paid to 20 Nov. No arrears.` |
| `LEVY` | Levy details | none | `Msika: levy K5 a day at SOW-B-114. Paid to 20 Nov. Arrears K0.` |
| `MONTH` | Last month's statement summary | none | `Msika Oct: deposited K9,800, digital K2,100, declared K4,300, levy K150. Reply OK or WRONG.` |
| `OK` / `WRONG` | Accept or dispute the latest statement | Statement status | `Msika: thank you. Your October statement is confirmed.` |
| `NO 8F2K` | Dispute a deposit an agent recorded | Deposit flagged `DISPUTED` | `Msika: deposit 8F2K is flagged. The market office will contact you.` |
| `LOAN` | Ask for a stock loan (Phase 7) | Loan referral | `Msika: you can get up to K800 for stock. Reply LOAN 800 and your supplier code.` |
| `HELP` | List commands | none | `Msika: SALE 120, TODAY, BAL, LEVY, MONTH, NO + ref, STOP. Questions? Call your market office.` |
| `STOP` | Stop non-essential messages | Preference | `Msika: daily summaries stopped. Send START to turn them back on.` |

## Agent commands

Agents are registered by the council. An agent's number can only use agent commands.

| Send | Meaning | Result |
|---|---|---|
| `DEP B114 450` | Cash deposit for a stall | Creates a pending deposit and a cash-in request in the payments system. The agent gets `Msika: deposit K450 for SOW-B-114 sent. Ref 8F2K.` When the payments system confirms it, the trader gets the message in ARCHITECTURE §4.1. If it fails, the agent gets `Msika: deposit 8F2K FAILED: not enough float. Cash not recorded.` |
| `UNDO 8F2K` | Cancel the agent's own deposit within 10 minutes, if not yet confirmed | Deposit cancelled, and the trader is told |
| `ROUND` | The agent's totals for today | `Msika agent Mwila today: 23 deposits, K11,450. Float K2,300.` |

## Inbound webhook

`POST /api/v1/sms/inbound`

```json
{
  "gatewayMessageId": "a1b2c3",
  "from": "+260977000001",
  "body": "DEP B114 450",
  "receivedAt": "2026-11-23T17:42:10+02:00"
}
```

- Header `X-Msika-Timestamp`: Unix seconds; rejected if more than 5 minutes off.
- Header `X-Msika-Signature: sha256=<hex HMAC-SHA256, with the gateway secret, of the timestamp, a dot, and the
  raw body>`. The timestamp is inside the signature (the same scheme as payment callbacks), so a captured message
  cannot be sent again later with a fresh timestamp. A bad or missing signature gets `401`.
- A body that is not shaped like the example, or a `from` that is not a Zambian mobile number, gets `400`.
- Response `200 {"stored": true}` as soon as the row is saved. The message is processed after the answer, so a slow
  use case cannot make the gateway time out and send it again. A repeated `gatewayMessageId`
  returns `200 {"stored": false, "duplicate": true}` and does nothing else.
- Each gateway turns its own format into this one. The relay (ARCHITECTURE §7) posts this format
  directly. If an Android SMS-gateway app is used as the relay and cannot, a small mapping endpoint
  translates its webhook format.

Phone numbers are normalised to E.164 (`+260...`) before any lookup. `0977...` and `260977...` are
both accepted.
