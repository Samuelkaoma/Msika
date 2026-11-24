// The approvals inbox (card M-63, COUNCIL-ADMIN section 4.5): my queue, raised by me, overdue and escalated, and one
// page per request. The server decides what each person may do; `refusal` says why not, and the page shows it.

import { useEffect, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router";
import { api } from "./api/client";
import type { Detail, Item, MarketResponse } from "./api/schema";
import { useAuth } from "./auth";

export const KINDS: Record<string, string> = {
  STALL_CLASS: "Stall class",
  STALL_REINSTATE: "Stall reinstated",
  STALL_SUSPEND: "Stall suspended",
  AGENT_SUSPENSION: "Agent suspension",
};

const VIEWS = [
  { key: "mine", label: "My queue", empty: "Nothing is waiting for you." },
  { key: "raised", label: "Raised by me", empty: "You have not raised anything." },
  { key: "overdue", label: "Overdue", empty: "Nothing is overdue." },
  { key: "escalated", label: "Escalated", empty: "Nothing has been escalated." },
] as const;

type View = (typeof VIEWS)[number]["key"];

/** A moment as a Lusaka date. Deadlines end as a Lusaka day does, so midnight belongs to the day before. */
export function lusakaDay(iso: string | undefined, deadline = false): string {
  if (!iso) return "";
  const at = new Date(Date.parse(iso) - (deadline ? 1000 : 0));
  return new Intl.DateTimeFormat("en-GB", { timeZone: "Africa/Lusaka", day: "numeric", month: "short", year: "numeric" }).format(at);
}

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** How many requests wait for this user, for the home page. Null until known, or if it cannot be read. */
export function useWaiting(councilId: string): number | null {
  const [count, setCount] = useState<number | null>(null);
  useEffect(() => {
    let live = true;
    api("get", "/api/v1/councils/{councilId}/inbox", { path: { councilId }, query: { queue: "mine" } }).then(
      (items) => live && setCount(items.length),
      () => live && setCount(null),
    );
    return () => {
      live = false;
    };
  }, [councilId]);
  return count;
}

function Badges({ item }: { item: Item }) {
  return (
    <>
      {item.status !== "OPEN" && <span className="badge">{(item.status ?? "").toLowerCase()}</span>}
      {item.overdue && <span className="badge late">overdue</span>}
      {item.escalated && <span className="badge late">escalated</span>}
      {item.handedToMe && <span className="badge">handed to you</span>}
      {item.problem && <span className="badge late">could not be done</span>}
    </>
  );
}

export function Inbox() {
  const { me } = useAuth();
  const councilId = me!.councilId!;
  const isAdmin = me?.permissions?.includes("MANAGE_POLICIES") ?? false;
  const views = VIEWS.filter((v) => isAdmin || v.key === "mine" || v.key === "raised");
  const [params, setParams] = useSearchParams();
  const view: View = views.find((v) => v.key === params.get("view"))?.key ?? "mine";
  const [items, setItems] = useState<Item[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [markets, setMarkets] = useState<MarketResponse[]>([]);
  const [kind, setKind] = useState("");
  const [marketId, setMarketId] = useState("");

  useEffect(() => {
    api("get", "/api/v1/councils/{councilId}/markets", { path: { councilId } }).then(setMarkets, () => setMarkets([]));
  }, [councilId]);
  useEffect(() => {
    let live = true;
    setItems(null);
    setError(null);
    // Everything escalated is overdue.
    const queue = view === "escalated" ? "overdue" : view;
    api("get", "/api/v1/councils/{councilId}/inbox", { path: { councilId }, query: { queue } }).then(
      (all) => live && setItems(view === "escalated" ? all.filter((i) => i.escalated) : all),
      (e: unknown) => live && setError(message(e)),
    );
    return () => {
      live = false;
    };
  }, [councilId, view]);

  const shown = (items ?? []).filter((i) => (!kind || i.kind === kind) && (!marketId || i.marketId === marketId));
  return (
    <section>
      <h1>Inbox</h1>
      <nav className="tabs" aria-label="Inbox views">
        {views.map((v) => (
          <button key={v.key} aria-current={v.key === view ? "page" : undefined} onClick={() => setParams({ view: v.key })}>
            {v.label}
          </button>
        ))}
      </nav>
      <div className="row filters">
        <label>
          Kind
          <select value={kind} onChange={(e) => setKind(e.target.value)}>
            <option value="">All kinds</option>
            {Object.entries(KINDS).map(([k, label]) => (
              <option key={k} value={k}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label>
          Market
          <select value={marketId} onChange={(e) => setMarketId(e.target.value)}>
            <option value="">All markets</option>
            {markets.map((m) => (
              <option key={m.id} value={m.id}>
                {m.code} {m.name}
              </option>
            ))}
          </select>
        </label>
      </div>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {!items && !error && <p className="muted">Loading…</p>}
      {items && shown.length === 0 && (
        <p className="muted">{items.length > 0 ? "Nothing matches these filters." : VIEWS.find((v) => v.key === view)!.empty}</p>
      )}
      {shown.length > 0 && (
        <ul className="requests" aria-label="Requests">
          {shown.map((i) => (
            <li key={i.id}>
              <Link to={`/inbox/${i.id}`} className="card">
                <strong>{i.effect}</strong>
                <span className="muted">
                  {KINDS[i.kind ?? ""] ?? i.kind} · due {lusakaDay(i.dueAt, true)}
                  {i.raisedByEmail ? ` · raised by ${i.raisedByEmail}` : ""}
                </span>
                <span>
                  <Badges item={i} />
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function RequestPage() {
  const { me } = useAuth();
  const councilId = me!.councilId!;
  const { id = "" } = useParams();
  const [detail, setDetail] = useState<Detail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);

  const load = () => api("get", "/api/v1/councils/{councilId}/requests/{id}", { path: { councilId, id } }).then(setDetail);
  useEffect(() => {
    setDetail(null);
    setError(null);
    setReason("");
    load().catch((e: unknown) => setError(message(e)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [councilId, id]);

  const run = async (action: () => Promise<Detail>) => {
    setBusy(true);
    setError(null);
    try {
      setDetail(await action());
      setReason("");
    } catch (e) {
      setError(message(e));
      // The request may have changed meanwhile, or now says why it could not be done.
      await load().catch(() => undefined);
    } finally {
      setBusy(false);
    }
  };
  const decide = (decision: "APPROVE" | "REJECT") =>
    run(() =>
      api("post", "/api/v1/councils/{councilId}/requests/{id}/decisions", {
        path: { councilId, id },
        body: { decision, reason: reason.trim() || undefined },
      }),
    );

  const r = detail?.request;
  const steps = r?.policy?.steps ?? [];
  const hasReason = reason.trim() !== "";
  const approveNeedsReason = (r?.approvals ?? 0) > 0;
  return (
    <section>
      <p>
        <Link to="/inbox">← Inbox</Link>
      </p>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {!r && !error && <p className="muted">Loading…</p>}
      {r && (
        <>
          <h1>{r.effect}</h1>
          <p>
            <Badges item={r} />
          </p>
          <dl className="facts">
            <dt>Kind</dt>
            <dd>{KINDS[r.kind ?? ""] ?? r.kind}</dd>
            <dt>Raised by</dt>
            <dd>
              {r.raisedByEmail ?? (r.raisedByType ?? "").toLowerCase()} on {lusakaDay(r.createdAt)}
            </dd>
            <dt>Why</dt>
            <dd>{r.reason}</dd>
            <dt>Due</dt>
            <dd>{lusakaDay(r.dueAt, true)}</dd>
            <dt>Approvals</dt>
            <dd>
              {r.approvals} of {steps.length}
            </dd>
            {r.subjectType === "STALL" && (
              <>
                <dt>Stall</dt>
                <dd>
                  <Link to={`/map?market=${r.marketId}&stall=${r.subjectId}`}>Show on the market map</Link>
                </dd>
              </>
            )}
          </dl>
          {r.problem && <p className="notice">It could not be carried out last time: {r.problem}</p>}

          <h2>Evidence</h2>
          {(detail?.evidence ?? []).length === 0 && <p className="muted">None given.</p>}
          <ul>
            {(detail?.evidence ?? []).map((e, i) => (
              <li key={i}>
                {e.type === "STALL" ? <Link to={`/map?market=${r.marketId}&stall=${e.id}`}>Stall on the market map</Link> : `${e.type} ${e.id}`}
                {e.note ? `: ${e.note}` : ""}
              </li>
            ))}
          </ul>

          <h2>Decisions</h2>
          {(detail?.decisions ?? []).length === 0 && <p className="muted">None yet.</p>}
          <ol className="history">
            {(detail?.decisions ?? []).map((d) => (
              <li key={d.id}>
                <strong>{d.decision === "APPROVE" ? "Approved" : "Rejected"}</strong> by {d.decidedByEmail} on {lusakaDay(d.at)}
                {d.corrects ? " (corrects an earlier decision)" : ""}
                {d.reason ? `: ${d.reason}` : ""}
              </li>
            ))}
          </ol>

          {r.status === "OPEN" && r.refusal && <p className="muted">You cannot decide this: {r.refusal}.</p>}
          {r.status === "OPEN" && !r.refusal && (
            <form className="card inline" aria-label="Decide" onSubmit={(e) => e.preventDefault()}>
              <label>
                Reason
                <textarea
                  rows={3}
                  maxLength={500}
                  value={reason}
                  aria-describedby="reason-help"
                  onChange={(e) => setReason(e.target.value)}
                />
              </label>
              <p id="reason-help" className="muted">
                {approveNeedsReason ? "Needed for a second approval and for a rejection." : "Needed to reject."}
              </p>
              <div className="row">
                <button type="button" className="primary" disabled={busy || (approveNeedsReason && !hasReason)} onClick={() => decide("APPROVE")}>
                  Approve
                </button>
                <button type="button" disabled={busy || !hasReason} onClick={() => decide("REJECT")}>
                  Reject
                </button>
              </div>
            </form>
          )}
          {r.status === "OPEN" && r.raisedByType === "USER" && r.raisedById === me?.userId && (
            <button
              disabled={busy}
              onClick={() => run(() => api("post", "/api/v1/councils/{councilId}/requests/{id}/withdraw", { path: { councilId, id } }))}
            >
              Withdraw
            </button>
          )}
        </>
      )}
    </section>
  );
}
