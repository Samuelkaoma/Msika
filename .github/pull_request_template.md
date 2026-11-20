## Card
<!-- The card and branch, for example: M-07 Councils, markets and stalls (`feature/M-07-stalls`) -->

## What this does
<!-- A few lines. Only what the card's "Do" says. -->

## Done items and proof
<!-- One row per "Done" item on the card, with the test that proves it. A Done item with no test (or a written manual check) is not done. -->
| Done item | Proof |
|---|---|

## Checks
<!-- Paste the last lines of the full check output. "Tests pass" means you ran them and saw them pass. -->
```
./scripts/check.ps1
```

- [ ] I ran the full checks (`scripts/check`, not `--fast`) and they passed
- [ ] No test is skipped, ignored or loosened
- [ ] The card is ticked in `docs/ROADMAP.md`
- [ ] The lesson for this card is in `docs/learning/`

## Rules from AGENTS.md
- [ ] Money is `long` ngwee, never `double`, `float` or `BigDecimal` (rule 1)
- [ ] Nothing updates or deletes a ledger row; only `RecordEntry` writes (rule 2)
- [ ] Ledger entries keep their provenance (rule 3)
- [ ] New council tables have `council_id` and a row-level security policy (rule 4)
- [ ] Anything from outside is idempotent, with a duplicate test (rule 5)
- [ ] Outside systems only through ports; nothing imports `zamsim` or `elevysim` (rule 6)
- [ ] `msika-core` and `msika-ledger` import no Spring, JPA, Jackson or HTTP clients (rule 7)
- [ ] Msika never holds money and never lends (rule 8)
- [ ] No secrets, keys, real names or real phone numbers anywhere (rule 9)
- [ ] Every SMS reply fits 160 GSM-7 characters and comes from the templates (rule 10)

## Not verified
<!-- Say plainly what you did not finish or could not verify. -->

## Dependencies added
<!-- One sentence on why for each new dependency. -->

## Found while working
<!-- Bugs outside this card. Leave them alone; list them here. -->

## Follow-ups
<!-- Things needed but not built in this card. -->
