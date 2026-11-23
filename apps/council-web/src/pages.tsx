// The dashboard pages with figures (card M-27). Every figure comes from /reports, which works it out from the
// ledger and the register each time. Deposited, digital and declared money are always separate columns (rule 3).

import { useEffect, useState, type ReactNode } from "react";
import { api } from "./api/client";
import type {
  AgentActivity,
  Arrears,
  DayCollections,
  MarketOccupancy,
  ReconciliationDay,
  Seal,
  SealCheck,
} from "./api/schema";
import { useAuth } from "./auth";

/** Kwacha from whole ngwee: K1,230.50. Integer arithmetic only. */
export function kwacha(ngwee: number | undefined): string {
  const n = ngwee ?? 0;
  const abs = Math.abs(n);
  const fraction = abs % 100;
  return `${n < 0 ? "-" : ""}K${Math.trunc(abs / 100).toLocaleString("en-US")}${fraction ? "." + String(fraction).padStart(2, "0") : ""}`;
}

/** Today in Lusaka, as YYYY-MM-DD. */
export function today(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Africa/Lusaka" }).format(new Date());
}

function daysBefore(date: string, days: number): string {
  const d = new Date(`${date}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() - days);
  return d.toISOString().slice(0, 10);
}

/** Loads with `load` whenever `deps` change; shows loading and errors the same way on every page. */
function useLoad<T>(load: () => Promise<T>, deps: unknown[]): { data: T | null; error: string | null; reload: () => void } {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let live = true;
    setError(null);
    load().then(
      (d) => live && setData(d),
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  return { data, error, reload: () => setTick((t) => t + 1) };
}

function Range({ from, to, onChange }: { from: string; to: string; onChange: (from: string, to: string) => void }) {
  return (
    <div className="row filters">
      <label>
        From <input type="date" value={from} max={to} onChange={(e) => onChange(e.target.value, to)} />
      </label>
      <label>
        To <input type="date" value={to} min={from} onChange={(e) => onChange(from, e.target.value)} />
      </label>
    </div>
  );
}

function Status({ error, loading }: { error: string | null; loading: boolean }): ReactNode {
  if (error) return <p className="error">{error}</p>;
  if (loading) return <p className="muted">Loading…</p>;
  return null;
}

function useCouncil(): string {
  return useAuth().me!.councilId!;
}

// ------------------------------------------------------------------------------------------------- markets

export function Occupancy() {
  const councilId = useCouncil();
  const [day, setDay] = useState(today());
  const { data, error } = useLoad<MarketOccupancy[]>(
    () => api("get", "/api/v1/councils/{councilId}/reports/markets", { path: { councilId }, query: { date: day } }),
    [councilId, day],
  );
  return (
    <>
      <div className="row filters">
        <label>
          Day <input type="date" value={day} onChange={(e) => setDay(e.target.value)} />
        </label>
      </div>
      <Status error={error} loading={!data} />
      {data && (
        <table aria-label="Stalls by market">
          <thead>
            <tr>
              <th>Market</th>
              <th className="num">Stalls</th>
              <th className="num">Vacant</th>
              <th className="num">Allocated</th>
              <th className="num">Suspended</th>
              <th className="num">Active on the day</th>
            </tr>
          </thead>
          <tbody>
            {data.map((m) => (
              <tr key={m.marketId}>
                <td>
                  {m.code} <span className="muted">{m.name}</span>
                </td>
                <td className="num">{m.stalls}</td>
                <td className="num">{m.vacant}</td>
                <td className="num">{m.allocated}</td>
                <td className="num">{m.suspended}</td>
                <td className="num">{m.activeOnDay}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}

// ------------------------------------------------------------------------------------------------- collections

export function Collections() {
  const councilId = useCouncil();
  const [range, setRange] = useState({ from: daysBefore(today(), 6), to: today() });
  const { data, error } = useLoad<DayCollections[]>(
    () => api("get", "/api/v1/councils/{councilId}/reports/collections", { path: { councilId }, query: range }),
    [councilId, range.from, range.to],
  );
  const total = (pick: (r: DayCollections) => number | undefined) => (data ?? []).reduce((s, r) => s + (pick(r) ?? 0), 0);
  return (
    <section>
      <h1>Collections</h1>
      <p className="muted">Deposited cash, verified digital sales and declared sales are kept apart: they are not equally sure.</p>
      <Range from={range.from} to={range.to} onChange={(from, to) => setRange({ from, to })} />
      <Status error={error} loading={!data} />
      {data && data.length === 0 && <p className="muted">Nothing recorded in these days.</p>}
      {data && data.length > 0 && (
        <table aria-label="Collections by day">
          <thead>
            <tr>
              <th>Day</th>
              <th>Market</th>
              <th className="num">Deposited</th>
              <th className="num">Digital (verified)</th>
              <th className="num">Declared</th>
              <th className="num">Levy charged</th>
              <th className="num">Levy paid</th>
            </tr>
          </thead>
          <tbody>
            {data.map((r) => (
              <tr key={`${r.businessDate}-${r.marketId}`}>
                <td>{r.businessDate}</td>
                <td>{r.marketCode}</td>
                <td className="num">{kwacha(r.depositedNgwee)}</td>
                <td className="num">{kwacha(r.digitalNgwee)}</td>
                <td className="num">{kwacha(r.declaredNgwee)}</td>
                <td className="num">{kwacha(r.levyChargedNgwee)}</td>
                <td className="num">{kwacha(r.levyPaidNgwee)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th colSpan={2}>Total</th>
              <th className="num">{kwacha(total((r) => r.depositedNgwee))}</th>
              <th className="num">{kwacha(total((r) => r.digitalNgwee))}</th>
              <th className="num">{kwacha(total((r) => r.declaredNgwee))}</th>
              <th className="num">{kwacha(total((r) => r.levyChargedNgwee))}</th>
              <th className="num">{kwacha(total((r) => r.levyPaidNgwee))}</th>
            </tr>
          </tfoot>
        </table>
      )}
    </section>
  );
}

// ------------------------------------------------------------------------------------------------- arrears

export function ArrearsPage() {
  const councilId = useCouncil();
  const { data, error } = useLoad<Arrears[]>(
    () => api("get", "/api/v1/councils/{councilId}/reports/arrears", { path: { councilId } }),
    [councilId],
  );
  return (
    <section>
      <h1>Arrears</h1>
      <p className="muted">Levy days not yet paid, most owed first.</p>
      <Status error={error} loading={!data} />
      {data && data.length === 0 && <p className="muted">No one is in arrears.</p>}
      {data && data.length > 0 && (
        <table aria-label="Arrears">
          <thead>
            <tr>
              <th>Trader</th>
              <th>Stall</th>
              <th className="num">Unpaid days</th>
              <th className="num">Owed</th>
              <th>Oldest unpaid</th>
            </tr>
          </thead>
          <tbody>
            {data.map((r) => (
              <tr key={`${r.traderId}-${r.stallCode}`}>
                <td>{r.traderName}</td>
                <td>{r.stallCode}</td>
                <td className="num">{r.unpaidDays}</td>
                <td className="num">{kwacha(r.unpaidNgwee)}</td>
                <td>{r.oldestUnpaid}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

// ------------------------------------------------------------------------------------------------- agents

export function Agents() {
  const councilId = useCouncil();
  const [range, setRange] = useState({ from: daysBefore(today(), 6), to: today() });
  const agents = useLoad<AgentActivity[]>(
    () => api("get", "/api/v1/councils/{councilId}/reports/agents", { path: { councilId }, query: range }),
    [councilId, range.from, range.to],
  );
  const runs = useLoad<ReconciliationDay[]>(
    () => api("get", "/api/v1/councils/{councilId}/reports/reconciliation", { path: { councilId }, query: range }),
    [councilId, range.from, range.to],
  );
  return (
    <section>
      <h1>Agents</h1>
      <Range from={range.from} to={range.to} onChange={(from, to) => setRange({ from, to })} />
      <Status error={agents.error} loading={!agents.data} />
      {agents.data && (
        <table aria-label="Agents">
          <thead>
            <tr>
              <th>Agent</th>
              <th className="num">Confirmed</th>
              <th className="num">Amount</th>
              <th className="num">Pending</th>
              <th className="num">Failed</th>
              <th className="num">Cancelled</th>
              <th className="num">Disputed</th>
            </tr>
          </thead>
          <tbody>
            {agents.data.map((a) => (
              <tr key={a.agentId} className={a.disputed ? "flag" : ""}>
                <td>
                  {a.name} {!a.active && <span className="muted">(inactive)</span>}
                </td>
                <td className="num">{a.confirmed}</td>
                <td className="num">{kwacha(a.confirmedNgwee)}</td>
                <td className="num">{a.pending}</td>
                <td className="num">{a.failed}</td>
                <td className="num">{a.cancelled}</td>
                <td className="num">{a.disputed}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <h2>Nightly reconciliation</h2>
      <p className="muted">Each day's ledger compared with the payments system's statements.</p>
      <Status error={runs.error} loading={!runs.data} />
      {runs.data && runs.data.length === 0 && <p className="muted">No reconciliation has run for these days.</p>}
      {runs.data && runs.data.length > 0 && (
        <table aria-label="Reconciliation">
          <thead>
            <tr>
              <th>Day</th>
              <th className="num">Payments</th>
              <th className="num">On Msika's ledger</th>
              <th className="num">Differences</th>
            </tr>
          </thead>
          <tbody>
            {runs.data.map((r) => (
              <tr key={r.businessDate} className={r.differenceCount ? "flag" : ""}>
                <td>{r.businessDate}</td>
                <td className="num">{r.paymentsCount}</td>
                <td className="num">{r.msikaCount}</td>
                <td className="num">{r.differenceCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

// ------------------------------------------------------------------------------------------------- seals

export function Seals() {
  const councilId = useCouncil();
  const { data, error } = useLoad<Seal[]>(
    () => api("get", "/api/v1/councils/{councilId}/reports/seals", { path: { councilId } }),
    [councilId],
  );
  const [check, setCheck] = useState<SealCheck | null>(null);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<string | null>(null);
  const verify = async () => {
    setChecking(true);
    setCheckError(null);
    try {
      setCheck(await api("get", "/api/v1/councils/{councilId}/reports/seals/verification", { path: { councilId } }));
    } catch (e) {
      setCheckError(e instanceof Error ? e.message : String(e));
    } finally {
      setChecking(false);
    }
  };
  return (
    <section>
      <div className="heading">
        <h1>Daily seals</h1>
        <button className="primary" onClick={verify} disabled={checking}>
          {checking ? "Checking…" : "Verify all seals"}
        </button>
      </div>
      <p className="muted">
        Each day's entries are sealed with a signed fingerprint chained to the day before. Verifying recomputes every
        fingerprint from the ledger and checks every signature.
      </p>
      {checkError && <p className="error">{checkError}</p>}
      {check && (
        <p className={check.ok ? "ok" : "error"} role="status">
          {check.ok
            ? `All ${check.seals} sealed day${check.seals === 1 ? "" : "s"} check out (${check.entries} ${check.entries === 1 ? "entry" : "entries"}).`
            : `${check.problems?.length} problem(s): ${check.problems?.join("; ")}`}
        </p>
      )}
      <Status error={error} loading={!data} />
      {data && data.length === 0 && <p className="muted">No day has been sealed yet.</p>}
      {data && data.length > 0 && (
        <table aria-label="Seals">
          <thead>
            <tr>
              <th>Day</th>
              <th className="num">Entries</th>
              <th>Fingerprint</th>
              <th>Sealed at</th>
            </tr>
          </thead>
          <tbody>
            {data.map((s) => (
              <tr key={s.businessDate}>
                <td>{s.businessDate}</td>
                <td className="num">{s.entryCount}</td>
                <td>
                  <code>{s.merkleRoot?.slice(0, 16)}…</code>
                </td>
                <td>{s.sealedAt ? new Date(s.sealedAt).toLocaleString() : ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
