# 0010: One request engine for every council decision

Status: Accepted (card M-62)

## Context
Councils decide many different things (registrations, stalls, disputes, levy changes). Building each with its own
table and screens would mean checking "nobody approves their own request" many times, and forgetting it once.

## Decision
- One `request` table and one `request_decision` table for every kind. A kind adds an entry in `RequestKind` (who
  raises it, its minimum policy) and an effect in `RequestEffects`.
- The effect runs in the transaction of the last approval. If it fails, the approval is undone and the request stays
  open with the reason.
- A request keeps a copy of the policy in force when it was raised.
- The rules that protect people (the raiser cannot decide, one approval each, decisions are append-only, enough
  approvals) are triggers and indexes as well as code.
- Msika operators cannot raise or decide anything.

## Consequences
- A new kind of decision is small, and gets the audit trail, deadlines and bulk approval for free.
- The proposed effect is JSON, so its shape is checked by code and not by the database.
- Escalation adds council admins as deciders. It does not yet message anyone: council users have no phone number or
  email delivery in Msika, so the original decider sees it in the inbox.
- Working days are Monday to Friday; public holidays are not known yet.
