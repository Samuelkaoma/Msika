# 00: The big picture

Read this once before any lesson. It is the map. The full detail is in `docs/ARCHITECTURE.md`.

## What Msika does

A market trader sells for cash all day. At closing time an agent takes the cash, and it is deposited
into the trader's mobile money wallet. Msika records that against the trader's **stall**, pays the
council **levy** from it, and builds a monthly **turnover statement**. Later, that history can help
the trader get credit.

Msika never holds money and never lends. It records, and it asks for transfers the trader agreed to.

## The pieces

```
 Trader / agent          Msika                          Outside systems (simulated)
 --------------          -----                          ---------------------------
 SMS "DEP B114 450" -->  msika-api  -- PaymentsPort -->  zamsim   (fake Zamtel Money)
 Council web page   -->  (Spring)   -- LevyAuthorityPort> elevysim (fake eLevy)
                           |
                      msika-core  (business rules, plain Java)
                           |
                      msika-ledger (money, sealed daily record, plain Java)
                           |
                        Postgres
```

| Piece | What it is | Why it is separate |
|---|---|---|
| `msika-ledger` | Money type, ledger entries, Merkle tree, daily signed seal | The part that must be trustworthy, so it has no framework in it |
| `msika-core` | The rules: stalls, deposits, levy, SMS command parsing, and the ports | Business logic you can test without a database or web server |
| `msika-api` | Spring Boot app: REST API, security, database, adapters, scheduled jobs | Everything that touches the outside world lives here |
| `zamsim`, `elevysim` | Our fake Zamtel Money and eLevy, as separate services | We have no real access yet. Msika treats them exactly like real systems |
| `council-web`, `sim-console` | React apps: council dashboard, fake phones for demos | The screens people see |

## Five ideas everything rests on

1. **Money is a whole number of ngwee.** K12.50 is `1250`. Decimals like `double` can round wrongly,
   and in accounting a one-ngwee error is a bug.
2. **The ledger only ever grows.** We never edit or delete a row. A mistake is fixed by adding a
   reversing entry. That is how you keep a record people can trust.
3. **Every entry says how sure we are** (provenance): `VERIFIED` (the payment system confirmed a
   digital payment), `DEPOSITED` (confirmed cash deposit), `DECLARED` (the trader said so by SMS).
4. **Each day is sealed.** The day's entries go into a Merkle tree (a fingerprint of all entries at
   once), the fingerprint is signed, and chained to yesterday's. Changing any old entry breaks it.
5. **Ports and adapters.** `msika-core` says "I need something that can make a payment" (a **port**).
   `msika-api` supplies the thing that talks to `zamsim` (an **adapter**). Swapping in the real Zamtel
   later is a config change, not a rewrite.

Two more that protect people: each council sees only its own rows (enforced by the database itself,
row-level security), and anything arriving from outside is **idempotent** (processing the same SMS or
callback twice has the same effect as once).

## How we work

One roadmap card at a time, one branch per card, one pull request per card, checks must pass, a person
merges. Each card also adds a lesson to this pack.
