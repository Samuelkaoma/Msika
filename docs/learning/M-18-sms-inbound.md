# M-18: Inbound webhook and outbox

Card: `M-18` · Branch: `feature/M-18-sms-inbound` · Depends on: M-09, M-17

## What we are building, in one paragraph

A text from a trader or agent reaches Msika as an HTTP call from the SMS gateway. This card builds that door:
check the call really came from our gateway, store the text once (even if the gateway sends it twice), answer
the gateway at once, then work out what the text means and queue the reply. A separate worker sends queued
replies and keeps trying if the gateway is down. Every reply comes from one list of templates, and a test proves
each one fits a single 160-character SMS even with the longest stall code, amount and name.

## New ideas in this lesson

- **Webhook**
  - **What it is:** an HTTP endpoint another system calls when something happens (here: a text arrived).
  - **Why we need it here:** the gateway, not Msika, knows when a text arrives.
  - **Tiny example:** `POST /api/v1/sms/inbound` with `{"gatewayMessageId": "a1", "from": "+260977000001", ...}`.

- **Store, then process**
  - **What it is:** save the message and answer `200` first; do the real work afterwards, on another thread.
  - **Why we need it here:** if a slow use case made the gateway time out, it would send the text again. Answering
    quickly, and deduplicating on `gatewayMessageId`, means a text is acted on once (AGENTS.md rule 5).

- **Outbox**
  - **What it is:** replies are rows in `sms_outbound`, written in the same transaction as the thing they report.
    A worker sends them later.
  - **Why we need it here:** if the gateway is down, the deposit is still recorded and the reply waits. Nothing is
    lost, and a reply is never sent for something that was rolled back.
  - **Tiny example:** `insert into sms_outbound (..., status) values (..., 'PENDING')`, then the worker marks it
    `SENT` or tries again after 10 s, 30 s, 2 min, 10 min, 30 min, then `FAILED`.

- **GSM-7 and the 160-character limit**
  - **What it is:** a plain SMS carries 160 characters from a fixed alphabet. One emoji or curly quote switches
    the whole message to a 70-character encoding, so one reply becomes three paid messages.
  - **Why we need it here:** AGENTS.md rule 10. Each placeholder has a longest possible value (a stall code is at
    most 16 characters), so we can test every template once with the longest values.

- **A tenant for "no council yet"**
  - **What it is:** a text from an unregistered number belongs to no council. Its row has `council_id` null and is
    visible only to a transaction that says it is handling unassigned messages (`app.sms_unassigned = 'on'`).
  - **Why we need it here:** rule 4 still holds: no transaction ever sees two councils' messages, and a council
    never sees strangers' texts.

## Where it sits in the architecture

```
gateway ── POST /api/v1/sms/inbound (signed) ──► SmsInboundController
                                                  ├─ verify HMAC + timestamp
                                                  ├─ sms_find_sender(phone) → council or none
                                                  ├─ insert sms_inbound (unique gatewayMessageId)
                                                  └─ answer 200, then on another thread:
                                                     SmsProcessor: rate limit → parse (M-17) → SmsRouting
                                                       → answer, or SmsCommandHandler (M-19)
                                                       → insert sms_outbound
OutboxWorker (every 2 s) ── SmsGatewayPort.send ──► ConsoleSmsGateway (dev) / RelaySmsGateway (M-21)
```

`msika-core` holds the parts with rules and no I/O: `SmsTemplate`, `Gsm7`, `SmsFormat`, `SmsRouting` and the port
`SmsGatewayPort` (rule 6). `msika-api` holds the HTTP, the tables and the workers.

## Step by step

1. `SmsTemplate`: every reply in one enum, with slots like `{AMOUNT}` and `{STALL}` and their longest values.
2. `Gsm7` and `SmsFormat`: the alphabet check and one way of writing kwacha (`K1,230.00`) and dates (`20 Nov`).
3. `SmsRouting`: unknown sender, wrong audience, `HELP` and every parse error get their reply here.
4. Migration `V10`: `sms_inbound` (unique gateway id), `sms_outbound`, RLS with the unassigned tenant, and the
   narrow `sms_find_sender` lookup.
5. `SmsInboundController`, `SmsProcessor` (with the rate limit and a sweeper for texts left behind),
   `OutboxWorker` with back-off, and `ConsoleSmsGateway`.
6. The webhook signs the timestamp together with the body, like payment callbacks. The protocol said "HMAC of the
   raw body"; that would let a captured text be re-sent with a new timestamp, so the protocol file was updated.

## Files touched

| File | What changed | Why |
|---|---|---|
| `msika-core/.../sms/SmsTemplate.java`, `Gsm7.java`, `SmsFormat.java` | new | every reply, checked to fit |
| `msika-core/.../sms/SmsRouting.java` | new | who may send what; replies to mistakes |
| `msika-core/.../sms/SmsGatewayPort.java` | new | the port to the gateway |
| `msika-api/.../db/migration/V10__sms_inbound_and_outbox.sql` | new | the two tables, RLS, sender lookup |
| `msika-api/.../sms/*` | new | webhook, processor, outbox, console gateway |
| `msika-api/.../tenancy/TenantContext.java` | unassigned SMS tenant | strangers' texts without breaking RLS |
| `docs/SMS-PROTOCOL.md` | signature covers the timestamp | stops replays |

## How we know it works

- `SmsTemplateTest`: every template, filled with the longest value of every slot, is GSM-7 and at most 160; the
  protocol's example replies come out word for word; too-long values are refused and free text is cleaned.
- `SmsRoutingTest`: unknown sender, a number in two councils, wrong audience, `HELP`, each parse error.
- `SmsInboundApiTest`: a repeated `gatewayMessageId` is stored and processed once; bad, missing, old and
  wrong-body signatures get 401; badly shaped messages get 400; an agent is answered in their council; the 21st
  to 23rd texts in 10 minutes are stored and not answered; a refused send is retried and finally `FAILED`; a text
  left behind is processed by the sweeper.

## Rebuild it yourself

1. Write an endpoint that stores a message with a unique id and answers `{"stored": false}` on a repeat.
2. Add an outbox table and a worker; make the worker survive a gateway that fails twice.
3. Write a template with `{NAME}` and a test that a 50-character name still fits 160 characters.

## Mistakes to avoid

- Processing before answering the gateway: a slow reply means a second delivery.
- Sending the reply inside the transaction that records the deposit: if the gateway hangs, the database waits.
- Signing only the body: an old message can be replayed with a new timestamp.
- Putting strangers' texts in some council's rows, or switching RLS off for them.
