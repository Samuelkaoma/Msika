# M-62: The request engine

Card: `M-62` · Branch: `feature/M-62-requests` · Depends on: M-61

## What we are building, in one paragraph

Every decision a council makes now has one shape. Somebody raises a request ("suspend stall SOW-B-114"), the request
says in words what will happen, other people approve or reject it, and the last approval carries it out. Two kinds
prove it: changes to a stall (its class, suspending, reinstating) and suspending an agent.

## New ideas in this lesson

- **Effect in the deciding transaction**: the last approval and what it does are one database transaction. If the
  stall cannot be changed after all, the approval is rolled back too, and a second small transaction writes down why.
- **Policy with a minimum in code**: `RequestKind` holds the least strict policy allowed. A council may ask for more
  approvals, fewer roles, shorter deadlines or no bulk approval; `RequestPolicy.requireAtLeast` refuses anything looser.
- **A request keeps the policy it was raised under**, so changing a policy never changes a decision half made.
- **Append-only decisions**: a trigger refuses to update or delete a decision. A mistake is corrected by a new one
  that names the old one.
- **Idempotency key**: raising the same command twice (a double click, a lost reply) makes one request.
- **Sampling**: before many requests are approved together, some must really have been opened. `request_view` remembers
  who opened what.
- **Escalation**: a request past its deadline is marked, and from then a council admin may decide it as well.
- **Hand-over**: a person going on leave passes their open items to a colleague who may decide them.

## How we know it works

- `RequestRulesTest` (no database): policies, deadlines over a weekend, who may decide.
- `RequestApiTest`: each Done item on the card, most of them tried against the database directly as well as the API,
  because the database must refuse even when the page does not.

## Mistakes to avoid

- Running an effect before the status changes, or in another transaction.
- Trusting the role alone: a market master decides only in their own markets.
- Counting how many times pages were opened. Count different requests.
