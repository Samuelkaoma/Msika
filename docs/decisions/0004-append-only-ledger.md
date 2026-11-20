# 0004: The ledger is append-only

Status: Accepted

## Context
Msika's value is a record people can trust: councils, lenders and traders will rely on it. A record
that can be quietly edited cannot be trusted, by the team or by anyone outside it. We also want each
day's record to be provable later.

## Decision
Ledger rows are only ever inserted. No `UPDATE` or `DELETE`, ever. A mistake is fixed by a new
`REVERSAL` entry that points at the one it cancels. Only the `RecordEntry` use case writes a ledger
row, and a database trigger refuses `UPDATE` and `DELETE` as a second line of defence. Every entry
carries a provenance (`VERIFIED`, `DEPOSITED`, `DECLARED`). At day close each council's entries are
hashed into a Merkle tree, signed, and chained to the previous day's seal.

## Consequences
- History is complete and auditable. Changing a past entry breaks the chain of seals, and anyone with
  the public key can see it.
- Corrections cost an extra row, and reports must net reversals against the entries they reverse.
- The table only grows. Retention and archiving will need a design (not needed for the pilot).
- A bug in recording cannot be hidden by editing. It has to be fixed forward, visibly.
