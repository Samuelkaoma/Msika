# M-51: Live closing view

Card: `M-51` · Branch: `feature/M-51-live-closing` · Depends on: M-50, M-19

## What we are building, in one paragraph

At closing time agents go stall to stall taking cash. With this card the council's map changes while it happens:
each deposit, the moment the payments system confirms it, turns its stall active on every open map of that market.
Choosing an agent shows their round: the stalls they have done tonight (green) and the held stalls in the same aisles
they have not reached yet (orange, dashed). If the server restarts, the page reconnects by itself.

## New ideas in this lesson

- **Server-sent events (SSE)**: one long HTTP response the server keeps writing to, in a simple text format
  (`event: deposit`, `data: {...}`, a blank line). Simpler than WebSockets when only the server talks.
- **Send after commit**: the event is queued with `TransactionSynchronization.afterCommit`, so the map never shows a
  deposit that was rolled back.
- **Events are a nudge, not the record**: a browser that was disconnected misses events, so after reconnecting it
  reloads the status, which is always worked out from the database (M-49).
- **Reading SSE with fetch**: `EventSource` cannot send the `Authorization` header, and a token in the URL ends up in
  logs. `live.ts` reads the stream with `fetch`, parses it, and reconnects after 1, 2, 5, then every 10 seconds.
- **Async dispatch and security**: Spring finishes an SSE response on an "async" dispatch, after the request was
  already authorised; that dispatch is permitted explicitly.
- **A race found by a test**: an event could arrive before the map's status had loaded, and was lost. Now the page
  reloads the status in that case.

## How we know it works

- `LiveClosingApiTest`: the map feed is open; three `DEP` texts (with their payment callbacks) produce three
  `deposit` events in order with increasing ids; the agent's round lists the three stalls done and the fourth not
  reached; another council cannot open the feed.
- `live.test.tsx`: the parser (comments, multi-line data, half-received events, Windows line endings); a deposit
  recolours its stall; the stream ends as if the server restarted, the page reconnects without reloading (the same
  drawing element throughout), reloads the status, and keeps applying events.

## Mistakes to avoid

- Sending the event before the transaction commits.
- Treating the event stream as the source of truth.
- Putting the token in the event-stream URL.
