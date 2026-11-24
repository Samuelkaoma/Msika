# The council admin side: approvals and enforcement

Msika records the trader's day. Councils also have to **decide** things about it: who gets
registered, which new stalls enter the register, which disputes are upheld, when a levy is waived,
and when a trader has broken a by-law and what follows. This file designs how those decisions are
made, approved, carried out, appealed and audited.

The cards that build it are M-61 to M-70 in `docs/ROADMAP.md`. The rules in `AGENTS.md`,
`docs/ARCHITECTURE.md` §2 and `docs/MARKET-MAP.md` §2 apply here too. This file adds its own in §2.

---

## 1. What exists today, and what this adds

What was built before M-61 (cards M-09 and on):

- Three roles: `MSIKA_OPERATOR` (manages councils, belongs to none), `COUNCIL_ADMIN` (may change
  everything in their council) and `COUNCIL_OFFICER` (read-only). One role per user.
- Every endpoint is refused unless a rule allows the role.
- An `audit_log` row (actor, action, target, time) for every change a council user makes.
- Row-level security, so a council sees only its own rows and an operator sees no council's rows.

Already promised in other documents, with nowhere to be decided yet:

- `REG` by SMS creates a registration "for the council to approve" (SMS-PROTOCOL).
- `NO <ref>` disputes a deposit, and `WRONG` disputes a statement.
- Survey findings, "possibly changed" stall flags and plan proposals wait for an officer
  (MARKET-MAP §6.5 and §6.6).

What this adds:

1. **Roles that match how a council works**, scoped to markets, with separation of duties (§3).
2. **One approvals inbox** where every decision waits, with its evidence, policy and deadline (§4).
3. **Enforcement as a case with due process**, never as a button that fines someone (§5).
4. **Limits on what data may be used against a trader**, because Msika only works if traders trust
   it (§2, rule 1).

---

## 2. Rules the admin side rests on

These add to the rules in ARCHITECTURE §2 and MARKET-MAP §2. Breaking one is a bug.

1. **Deposits are never evidence against a trader.** No penalty may rest on deposit amounts, a lack
   of deposits, declared sales, turnover statements, the credit score or ML flags. Those can prompt an
   *inspection*. Only the inspection's findings, the register and levy records can be evidence. If
   traders come to believe that depositing gets them fined, they stop depositing, and Msika has
   nothing. The code enforces this list (§5.3).
2. **Nobody approves their own request.** The person who raised a request or opened a case can never
   decide it. Where a policy needs two approvers, they are two different people, and neither is the
   person who raised it. The database refuses it, not just the page.
3. **Decisions are append-only.** A decision is never edited or deleted. A mistake is corrected by a
   new decision that points at the old one, like a ledger reversal.
4. **Approval changes the register only through the request's effect.** When a request is approved,
   its effect (create the stall, allocate, record the reversal) runs in the same transaction as the
   decision. There is no other way to make those changes, except for setup work by operators, which
   is also audited.
5. **The council's law, not ours.** Offences, fine amounts and deadlines come from each council's
   by-laws and resolutions and are configured per council with a reference to the source. Msika ships
   with no real offences or amounts. Demo data uses an obviously made-up catalogue.
6. **The trader is told, and can answer.** Every case sends the trader a notice with the case
   reference, what is alleged, the evidence in short, the deadline and how to respond or appeal. No
   case is decided before the response deadline unless the trader has already responded.
7. **No cash for fines or fees.** Fines and fees are paid only through the wallet, to the council's
   account, with a receipt. An officer never takes cash. This removes the easiest route to bribery.
8. **Fines are never taken automatically.** The trader's consent to the levy sweep covers levy only.
   A fine is paid by the trader approving a payment request, never swept from a deposit.
9. **Msika staff do not read council data by default.** Support access needs a council admin's
   approval, is limited in time, and every read is logged (§7).

---

## 3. Who does what

### 3.1 Roles

A user can hold more than one role, and roles that act in markets are scoped to the markets named.

| Role | Scope | Typical person | May |
|---|---|---|---|
| `COUNCIL_ADMIN` | Council | Director of markets, or someone the town clerk delegates to | Manage users and roles, policies, the offence catalogue, levy rules; decide any request their policy allows |
| `MARKET_MASTER` | Markets | The officer in charge of a market | Raise and decide registrations, allocations, stall changes and disputes in their markets |
| `REVENUE_OFFICER` | Council | Revenue section | Decide levy adjustments and statement disputes; see payments |
| `ENFORCEMENT_OFFICER` | Markets | Market police, inspectors | Record inspections, open cases, propose outcomes. Never decide their own cases |
| `COUNCIL_OFFICER` | Council | Any other staff | Read only |
| `AUDITOR` | Council | Internal audit, Auditor General | Read everything in the council, including the audit log and every decision; change nothing |
| `MSIKA_OPERATOR` | None | Msika staff | Create councils and their first admin. Nothing inside a council without support access (§7) |

Agents are not council users. They act only through SMS and the agent app.

### 3.2 Permissions

Permissions are a matrix in code: role × action → allowed, and scope. Built-in roles are defined in
code and re-seeded on every start, so code is the source of truth. This follows the approach of
default-deny matrices used in earlier projects; no code is copied.

A test walks every endpoint and fails if one has no permission mapped, so a forgotten mapping is a
failed build and not an open door.

### 3.3 Separation of duties

- The raiser of a request or opener of a case cannot decide it (rule 2).
- For policies needing two approvals, the second approver must hold a role the policy names and be a
  third person.
- An `ENFORCEMENT_OFFICER` cannot hold `REVENUE_OFFICER` in the same council: the person who finds an
  offence should not also handle the money from it.
- A user cannot change their own roles.

---

## 4. The approvals inbox

### 4.1 One shape for every decision

Every decision the council makes is a **request** with the same shape:

- **Kind**: what is being decided (§4.2).
- **Subject**: the stall, trader, agent, plan or case it is about.
- **Raised by**: a council user, a trader (by SMS), an agent, or the system (a survey finding).
- **Evidence**: links to the records that support it (a survey observation, a ledger entry, a levy
  account, a photo of a document brought to the office).
- **Proposed effect**: exactly what will happen if approved, shown in words before anyone decides.
- **Policy**: who may decide, how many approvals, the deadline, whether it can be approved in bulk,
  and who is told.
- **Decisions**: each approval or rejection, by whom, when, and the reason (required for a rejection
  and for any second approval).
- **Status**: `OPEN`, `APPROVED`, `REJECTED`, `WITHDRAWN`, `EXPIRED`.

When the last required approval arrives, the effect runs in the same transaction (rule 4). If the
effect cannot run (the stall was allocated meanwhile, say), the approval fails and the request stays
open with the reason shown.

### 4.2 The kinds

Each council can tighten a policy (more approvers, shorter deadline). It cannot loosen it below the
minimum shown here.

| Kind | Raised by | Decided by (minimum) | Approvals | Effect when approved |
|---|---|---|---|---|
| Trader registration | Trader (`REG`), market office | Market master | 1 | Trader linked to the stall, then asked for consent (`YES`) |
| New stall | Survey finding, plan editor, market office | Market master, then admin | 2 | Stall added to the register, plate queued for printing |
| Stall change (class, suspend, reinstate, retire) | Market master | Market master, then admin for suspend and retire | 1 or 2 | Register updated, trader told by SMS |
| Allocation | Waiting list offer, market office | Market master | 1 | Allocation created, trader told |
| Transfer to another trader | Both traders (by SMS) or the office | Market master, then admin | 2 | Old allocation ended, new one created, both told |
| Surrender | Trader | Market master | 1 | Allocation ended, stall becomes free |
| Revocation | Only a case outcome (§5) or long abandonment | Admin, plus a second admin or revenue officer | 2 | Allocation ended, trader told with the reason and how to appeal |
| Deposit dispute (`NO <ref>`) | Trader | Market master | 1 | Upheld: a reversal entry and an agent case. Rejected: trader told why |
| Statement dispute (`WRONG`) | Trader | Revenue officer | 1 | Correction entries or an explanation to the trader |
| Levy adjustment (waiver, exemption, closed market day) | Revenue officer, market master | Revenue officer, then admin | 2 | Compensating levy entries in Msika and elevysim; nothing edited |
| Levy rule change | Revenue officer | Admin, with the council resolution reference | 2 | New rule from a future date; traders told at least 14 days before (configurable) |
| Agent registration or suspension | Market master | Admin | 1 | Agent may or may not use agent commands |
| Plan publication (not refinement-only) | System or officer | Market master | 1 | New plan version published (MARKET-MAP §4, §6.6) |
| Stall change flag | System | Market master | 1 | Change confirmed with a date, or readings marked bad (MARKET-MAP §6.6) |
| Survey finding | System | Market master | 1 | Whatever action the officer picks; some open other requests (a new stall, a case) |
| Fine waiver or reduction | Enforcement or revenue | Admin, then revenue officer | 2 | Compensating entry on the case |
| Support access | Msika operator | Admin | 1 | Time-limited access (§7) |

### 4.3 Deadlines, escalation and absence

- Each kind has a deadline in Lusaka working days. The inbox orders by deadline.
- An overdue request is escalated to the next role up, and the original decider is told.
- A user going on leave can hand their open items to a named colleague who holds the needed role.
- Requests raised by traders that are not decided by the deadline are never silently dropped. The
  trader is told the request is still open.

### 4.4 Bulk decisions

Some kinds may be approved in bulk (for example 200 new stalls from a survey). The decider sees the
list, may untick items, and the system records one decision per item. A council can require that a
sample (say 1 in 10) is opened before a bulk approval is allowed.

### 4.5 What the inbox looks like

In `council-web`:

- **My queue**: what I can decide, by deadline, with filters by kind and market.
- **Raised by me**: what I am waiting on.
- **Request page**: the subject, the proposed effect in words, the evidence (with a link to the stall
  on the market map), the history of decisions, and approve or reject with a reason.
- **Overdue** and **escalated** views for admins.
- Counts on the dashboard home page.

---

## 5. Enforcement: cases, not buttons

### 5.1 The offence catalogue

Each council keeps its own catalogue. Every entry has:

- a code and a plain description;
- **the by-law or resolution it rests on**, quoted by section, required;
- the outcomes it allows (warning, fine, suspension, revocation) and the amount for a fine;
- what applies to a first, second and later offence within a period;
- the response deadline for the trader (at least 7 days, default 14);
- which roles may open it and which may decide it.

The catalogue changes only through a request approved by an admin with the resolution reference, and
old versions are kept, because a case is judged by the catalogue in force on the day of the offence.

Finding the real by-laws and amounts is people's work (MP), like the levy research in M-23. Claude
can build the catalogue but cannot supply its content.

### 5.2 A case, step by step

```
OPENED ──► NOTICE_SENT ──► (response or deadline) ──► PROPOSED ──► DECIDED ──► (appeal?) ──► CLOSED
                                                                       │
                                                                       └── fine ──► PAYABLE ──► PAID / OVERDUE
```

1. **Opened.** An enforcement officer or market master opens a case: the trader or agent, the stall,
   the offence code, the date, and at least one piece of allowed evidence (§5.3). The case number is
   short and unique per council (`C-0417`).
2. **Notice sent.** The trader gets an SMS (within 160 characters, so with the essentials):
   `Msika: case C-0417, trading outside stall SOW-B-114 on 20 Nov. Reply APPEAL C-0417 and your reason by 4 Dec, or visit the market office.`
   The notice is recorded, and the case cannot move on until it has been sent.
3. **Response.** The trader can reply `APPEAL C-0417 <text>` (stored in full, even if it runs over
   several messages), visit the office (the officer records the response), or not respond.
4. **Proposed.** After the response or the deadline, the opener proposes an outcome from those the
   catalogue allows, with reasons.
5. **Decided.** A different person with the deciding role decides: dismissed, warning, fine, or
   suspension. Suspension and revocation need a second approver (§4.2). The trader is told the
   outcome, the reason, and how to appeal.
6. **Appeal.** Within the appeal period the trader can appeal (`APPEAL C-0417` again, or at the
   office). The appeal goes to someone senior who took no part in the case, and their decision is
   final within Msika.
7. **Payable and paid.** A fine becomes a payment request to the trader's wallet (rule 8). Paying it
   records a `FINE_PAYMENT` ledger entry, kept apart from levy in statements. An unpaid fine past its
   due date becomes `OVERDUE`. What follows (a further case, suspension) is a new decision by a
   person, never automatic.

Agents can be the subject of a case too (an upheld deposit dispute, a missed round). Their outcomes
are warnings or suspension from Msika; anything about their Zamtel agency is for Zamtel.

### 5.3 What counts as evidence

| Allowed as evidence | Not allowed (may only prompt an inspection) |
|---|---|
| An inspection record: who, when, where on the map, what was seen, in the officer's own words | Deposit amounts, or no deposits |
| A survey observation (MARKET-MAP §6.5) | Declared sales, statements, estimated turnover |
| The register: allocations, stall status | The credit score, ML anomaly flags |
| Levy arrears in the levy account | A trader's dispute of a deposit or statement |
| A document brought to the office, recorded by the officer | Anything a trader said in a support or help message |

Inspection records come from the survey app's patrol mode (MARKET-MAP §6.2), so they carry a time,
a position and the stall's plate scan.

### 5.4 Watching the watchers

Enforcement in markets can be abused: an officer threatening fines to get paid privately. Msika
cannot stop that alone, but it can make patterns visible to admins and auditors:

- cases opened, dismissed and overturned on appeal, per officer and per market;
- cases where the trader says in their response that money was asked for (a tick-box the office
  records, and the `APPEAL` text);
- officers whose cases cluster on a few traders.

Those reports go to the `AUDITOR` role and to admins, never to the officers themselves.

---

## 6. What the trader can always do by SMS

Added to `docs/SMS-PROTOCOL.md` by the cards that build them:

| Send | Meaning |
|---|---|
| `CASES` | List my open cases and requests with their references and deadlines |
| `APPEAL <case> <reason>` | Respond to a notice, or appeal a decision |
| `PAY <case>` | Ask for the payment request for a fine, so it can be approved in the wallet |
| `STATUS <ref>` | Where is my registration, dispute or transfer |
| `TRANSFER <stall> <phone>` | Start a transfer to another trader, who must confirm with `YES` |
| `SURRENDER <stall>` | Give up a stall |

---

## 7. Support access for Msika staff

Operators have no access to council data (row-level security already ensures this). When a council
needs help:

1. The operator raises a support access request: which council, why, for how long (at most 72
   hours), and read-only or not.
2. A council admin approves it.
3. For that window, the operator's requests run with that council set, and every request, reads
   included, writes an audit row marked as support access.
4. The window ends by itself. The council can end it early.

---

## 8. Audit

The existing `audit_log` keeps working for every change. This adds:

- `request_decision` and `case_event` rows that are never updated or deleted (a trigger refuses it,
  as for the ledger), so the history of every decision is complete;
- reads by support access logged (§7);
- an export of a council's requests, decisions and cases for a date range, as CSV and PDF, for the
  `AUDITOR` role.

---

## 9. Data model additions

All tables have `council_id` and row-level security.

| Table | Key columns |
|---|---|
| `user_role` | council_id, user_id, role, market_ids (null for council-wide roles), granted_by, granted_at, revoked_at. Replaces the single `council_user.role` column |
| `request_policy` | council_id, kind, decider_roles, approvals_required, deadline_days, bulk_allowed, sample_rate. Seeded from code with the minimums in §4.2; councils can only tighten |
| `request` | id, council_id, kind, subject_type, subject_id, raised_by_type (USER, TRADER, AGENT, SYSTEM), raised_by_id, proposed_effect (JSON), status, due_at, created_at |
| `request_evidence` | request_id, council_id, evidence_type, evidence_id, note |
| `request_decision` | id, council_id, request_id, decided_by, decision (APPROVE, REJECT), reason, decided_at, corrects_decision_id. Append-only. A check stops the raiser deciding, and a unique index stops one person approving twice |
| `offence` | council_id, code, version, description, legal_reference, outcomes and amounts (JSON), repeat rules, response_days, open_roles, decide_roles, valid_from, valid_to |
| `enforcement_case` | id, council_id, number, subject_type (TRADER, AGENT), subject_id, stall_id, offence_code, offence_version, offence_date, opened_by, status |
| `case_event` | id, council_id, case_id, type (OPENED, EVIDENCE_ADDED, NOTICE_SENT, RESPONSE, PROPOSED, DECIDED, APPEALED, APPEAL_DECIDED, FINE_REQUESTED, FINE_PAID, OVERDUE, CLOSED), actor, body (JSON), at. Append-only |
| `inspection` | id, council_id, officer_id, stall_id, observed_at, position (plan mm, from patrol mode), finding text, survey_id |
| `support_access` | id, council_id, operator_id, reason, read_only, starts_at, ends_at, approved_by, ended_early_at |

New ledger kind: `FINE_PAYMENT`, provenance `VERIFIED`. ARCHITECTURE §5 is updated in the card that
adds it (M-68).

---

## 10. API (outline)

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/inbox?queue=mine|raised|overdue` | The inbox |
| `GET /api/v1/requests/{id}` | A request with evidence, proposed effect and decisions |
| `POST /api/v1/requests` | Raise a request of a kind the caller may raise |
| `POST /api/v1/requests/{id}/decisions` | Approve or reject, with a reason |
| `POST /api/v1/requests/bulk-decisions` | Bulk approval where the policy allows it |
| `GET/PUT /api/v1/policies` | Read and tighten request policies (admin) |
| `GET/POST /api/v1/offences` | The offence catalogue (changes go through a request) |
| `POST /api/v1/cases` / `GET /api/v1/cases/{id}` | Open and read cases |
| `POST /api/v1/cases/{id}/events` | Add evidence, record a response, propose, decide |
| `POST /api/v1/inspections` | Record an inspection (also from the survey app) |
| `GET /api/v1/oversight` | §5.4 reports, for auditors and admins |
| `POST /api/v1/support-access` | Raise, approve, end support access |

---

## 11. Honest limits

- **None of this has been checked by a lawyer or a council.** The steps in §5 follow ordinary ideas of
  fairness (notice, a chance to answer, a different decider, an appeal), not a reading of the Local
  Government Act or any council's by-laws. That review must happen before a pilot (M-43 and M-47).
- **Msika is not a court.** A decision in Msika is the council's administrative decision, recorded.
  Anything beyond that (prosecution, a court-imposed fine) happens outside Msika.
- **SMS notices may not reach the trader.** A phone can be off, lost or shared. The notice is also
  shown at the market office, and the response deadline starts when the notice is sent, not when it
  is read. A council may decide that is not enough for revocation and require a notice on paper too.
- **Oversight reports find patterns, not proof.** A high dismissal rate may mean an officer is careless
  or that their market is hard. They point an auditor somewhere to look.

---

## 12. Decisions to record

- Roles held per user and scoped to markets, replacing one role per user (M-61).
- One request engine for every council decision, with effects run in the decision's transaction (M-62).
- Deposits and income are never evidence for enforcement (M-66).
- Fines only through the wallet, never swept (M-68).
