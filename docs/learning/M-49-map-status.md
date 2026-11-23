# M-49: Map status

Card: `M-49` · Branch: `feature/M-49-map-status` · Depends on: M-48, M-15, M-24

## What we are building, in one paragraph

The map colours each stall. This card works the colours out: free, active (a deposit or verified sale in the last
7 days), quiet (nothing in 7 days) or suspended, plus a levy band (paid up, 1 to 7 days owed, more than 7), money
deposited today and open disputes. It also builds the stall panel (holder, last seven days, levy owed, history) and
"who is where" search by name, phone in any form, or stall code. Officers see phone numbers masked.

## New ideas in this lesson

- **Worked out, never stored**: a stored "state" would go stale the moment a deposit arrived. `StallState` (pure,
  in core) applies the rules; the service feeds it from one query.
- **Edges written down**: "within the last 7 days" means today and the six days before. Last trade exactly 7 days ago
  is quiet. 7 unpaid days is still the 1 to 7 band; 8 is over.
- **One query, and why it was slow**: the first version took 4 s for 5,000 stalls. The plan showed the summaries
  (activity, unpaid levy, disputes) being re-scanned for every stall, because freshly inserted rows had no statistics
  and the planner thought the market had one stall. Two fixes: `MATERIALIZED` summaries (worked out once), and the
  test runs `ANALYZE` after its bulk insert, as autovacuum would in a real database. Result: about 130 to 160 ms on a
  laptop, with hash joins. Always read the plan (`EXPLAIN ANALYZE`) before guessing.
- **Phones by role**: officers read only, so they get `PhoneNumber.masked()`.

## How we know it works

- `StallStateTest`: each state and band with the edges, and a configurable quiet period.
- `MapStatusApiTest`: the same through the API on seeded stalls; masked phones for officers; search by name,
  `0977...`, `260977...` and `+260977...` and by code, never across councils; the panel; and the 5,000-stall market
  timed (best of three after a warm-up, printed with the machine) under 300 ms.

## Mistakes to avoid

- Storing the map state.
- Timing a query on a table with no statistics and concluding the query is slow (or fast).
- Showing officers full phone numbers.
