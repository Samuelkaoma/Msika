// The market map page (card M-50, MARKET-MAP sections 8.1 to 8.3 and 8.8): the plan coloured by a layer, a legend
// with counts, filters, search, the stall panel and the unmapped list. Below 760 px it is a list instead: a drawing of
// thousands of stalls is not usable on a phone.

import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useSearchParams } from "react-router";
import { api } from "../api/client";
import type { AgentResponse, Hit, MarketResponse, MarketStatus, Panel, PlanBody, StallStatus } from "../api/schema";
import { useAuth } from "../auth";
import { kwacha, today } from "../pages";
import type { Footprint } from "./geometry";
import { listen, type LiveState } from "./live";
import { MarketSvg } from "./MarketSvg";
import { legendFor, styleFor, type Layer } from "./style";

function useNarrow(): boolean {
  const query = "(max-width: 760px)";
  const [narrow, setNarrow] = useState(() => typeof window !== "undefined" && !!window.matchMedia?.(query).matches);
  useEffect(() => {
    const m = window.matchMedia?.(query);
    if (!m) return;
    const on = () => setNarrow(m.matches);
    m.addEventListener?.("change", on);
    return () => m.removeEventListener?.("change", on);
  }, []);
  return narrow;
}

export function MarketMapPage() {
  const councilId = useAuth().me!.councilId!;
  const [markets, setMarkets] = useState<MarketResponse[] | null>(null);
  // A link from a request names the market and the stall to show (card M-63).
  const [params] = useSearchParams();
  const wantedMarket = params.get("market");
  const wantedStall = params.get("stall");
  const [marketId, setMarketId] = useState<string | null>(null);
  useEffect(() => {
    api("get", "/api/v1/councils/{councilId}/markets", { path: { councilId } }).then((m) => {
      setMarkets(m);
      if (m.length > 0) setMarketId((cur) => cur ?? m.find((x) => x.id === wantedMarket)?.id ?? m[0].id ?? null);
    });
  }, [councilId, wantedMarket]);
  return (
    <section>
      <div className="heading">
        <h1>Market map</h1>
        {markets && markets.length > 1 && (
          <select aria-label="Market" value={marketId ?? ""} onChange={(e) => setMarketId(e.target.value)}>
            {markets.map((m) => (
              <option key={m.id} value={m.id}>
                {m.code} {m.name}
              </option>
            ))}
          </select>
        )}
      </div>
      {markets && markets.length === 0 && <p className="muted">No markets yet.</p>}
      {marketId && (
        <MarketMap
          key={marketId}
          councilId={councilId}
          marketId={marketId}
          initialStall={marketId === wantedMarket ? wantedStall : null}
        />
      )}
    </section>
  );
}

export function MarketMap({ councilId, marketId, initialStall = null }: { councilId: string; marketId: string; initialStall?: string | null }) {
  const narrow = useNarrow();
  const [plan, setPlan] = useState<PlanBody | null>(null);
  const [planError, setPlanError] = useState<string | null>(null);
  const [status, setStatus] = useState<MarketStatus | null>(null);
  const [layer, setLayer] = useState<Layer>("state");
  const [filters, setFilters] = useState({ section: "", stallClass: "", state: "" });
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<Hit[]>([]);
  const [selected, setSelected] = useState<string | null>(initialStall);
  const [day, setDay] = useState(today());
  const [reloads, setReloads] = useState(0);
  const [live, setLive] = useState<LiveState>("connecting");
  const [agents, setAgents] = useState<AgentResponse[]>([]);
  const [agentId, setAgentId] = useState("");
  const [round, setRound] = useState<{ done: Set<string>; notReached: Set<string> } | null>(null);
  const isToday = day === today();
  const statusRef = useRef<MarketStatus | null>(null);
  statusRef.current = status;

  // The closing round, live (card M-51): each confirmed deposit recolours its stall at once. After a reconnection the
  // whole status is reloaded, because events sent while disconnected are not sent again.
  useEffect(() => {
    if (!isToday) return;
    return listen(`/api/v1/councils/${councilId}/markets/${marketId}/map-events`, {
      onState: setLive,
      onReconnect: () => setReloads((n) => n + 1),
      onMessage: (m) => {
        if (m.event !== "deposit") return;
        const e = JSON.parse(m.data) as { stallId: string; amountNgwee: number; marketId: string };
        if (e.marketId !== marketId) return;
        if (!statusRef.current) {
          // The status has not loaded yet: load it (it will include this deposit) rather than lose the event.
          setReloads((n) => n + 1);
          return;
        }
        setStatus((cur) =>
          cur && {
            ...cur,
            stalls: (cur.stalls ?? []).map((s) =>
              s.stallId === e.stallId
                ? { ...s, state: s.state === "FREE" ? s.state : "ACTIVE", depositedTodayNgwee: (s.depositedTodayNgwee ?? 0) + e.amountNgwee, lastActivity: day }
                : s,
            ),
          },
        );
        setReloads((n) => n + (agentId ? 1 : 0));
      },
    });
  }, [councilId, marketId, isToday, day, agentId]);

  useEffect(() => {
    api("get", "/api/v1/councils/{councilId}/agents", { path: { councilId } }).then(setAgents, () => setAgents([]));
  }, [councilId]);

  useEffect(() => {
    if (!agentId) {
      setRound(null);
      return;
    }
    api("get", "/api/v1/councils/{councilId}/agents/{agentId}/round", { path: { councilId, agentId }, query: { marketId, date: day } }).then(
      (r) => setRound({ done: new Set(r.done ?? []), notReached: new Set(r.notReached ?? []) }),
    );
  }, [councilId, agentId, marketId, day, reloads]);

  useEffect(() => {
    api("get", "/api/v1/councils/{councilId}/markets/{marketId}/plan", { path: { councilId, marketId } }).then(setPlan, (e: unknown) =>
      setPlanError(e instanceof Error ? e.message : String(e)),
    );
  }, [councilId, marketId]);
  useEffect(() => {
    api("get", "/api/v1/councils/{councilId}/markets/{marketId}/map-status", { path: { councilId, marketId }, query: { date: day } }).then(setStatus);
  }, [councilId, marketId, day, reloads]);

  const footprints: Footprint[] = useMemo(
    () =>
      (plan?.footprints ?? []).map((f) => ({
        stallId: f.stallId!,
        code: f.stallCode ?? "?",
        polygon: f.polygonMm ?? [],
        provenance: f.provenance,
        aisle: f.aisle,
      })),
    [plan],
  );
  const byStall = useMemo(() => new Map((status?.stalls ?? []).map((s) => [s.stallId!, s])), [status]);
  const sections = useMemo(() => (plan?.sections ?? []).map((s) => ({ name: s.name ?? "", polygon: s.polygonMm ?? [] })), [plan]);
  const classes = useMemo(() => [...new Set((status?.stalls ?? []).map((s) => s.stallClass ?? ""))].sort(), [status]);

  const visible = useMemo(() => {
    if (!filters.section && !filters.stallClass && !filters.state) return null;
    const keep = new Set<string>();
    for (const f of footprints) {
      const s = byStall.get(f.stallId);
      const section = f.aisle?.split("-")[0] ?? f.code.split("-")[1];
      if (filters.section && section !== filters.section) continue;
      if (filters.stallClass && s?.stallClass !== filters.stallClass) continue;
      if (filters.state && s?.state !== filters.state) continue;
      keep.add(f.stallId);
    }
    return keep;
  }, [filters, footprints, byStall]);

  const highlighted = useMemo(() => new Set(hits.filter((h) => h.marketId === marketId && h.stallId).map((h) => h.stallId!)), [hits, marketId]);

  const mapped = useMemo(() => new Set(footprints.map((f) => f.stallId)), [footprints]);
  const unmapped = useMemo(() => (status?.stalls ?? []).filter((s) => !mapped.has(s.stallId!)), [status, mapped]);

  const counts = useMemo(() => {
    const c = new Map<string, number>();
    for (const s of status?.stalls ?? []) {
      const key = styleFor(layer, s).key;
      c.set(key, (c.get(key) ?? 0) + 1);
    }
    return c;
  }, [status, layer]);

  const search = async (e: FormEvent) => {
    e.preventDefault();
    if (query.trim().length < 2) {
      setHits([]);
      return;
    }
    setHits(await api("get", "/api/v1/councils/{councilId}/search", { path: { councilId }, query: { q: query.trim() } }));
  };

  if (planError) {
    return <p className="muted">This market has no published plan yet. ({planError})</p>;
  }
  if (!plan || !status) return <p className="muted">Loading the map…</p>;

  const generated = footprints.some((f) => f.provenance === "GENERATED");

  return (
    <div className="map-page">
      <div className="map-controls">
        <div className="row wrap">
          <label>
            Layer
            <select aria-label="Layer" value={layer} onChange={(e) => setLayer(e.target.value as Layer)}>
              <option value="state">Stall state</option>
              <option value="levy">Levy</option>
              <option value="today">Deposits today</option>
            </select>
          </label>
          <label>
            Day <input type="date" value={day} onChange={(e) => setDay(e.target.value)} />
          </label>
          <label>
            Section
            <select aria-label="Section" value={filters.section} onChange={(e) => setFilters({ ...filters, section: e.target.value })}>
              <option value="">All</option>
              {sections.map((s) => (
                <option key={s.name}>{s.name}</option>
              ))}
            </select>
          </label>
          <label>
            Class
            <select aria-label="Class" value={filters.stallClass} onChange={(e) => setFilters({ ...filters, stallClass: e.target.value })}>
              <option value="">All</option>
              {classes.map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
          </label>
          <label>
            State
            <select aria-label="State" value={filters.state} onChange={(e) => setFilters({ ...filters, state: e.target.value })}>
              <option value="">All</option>
              {["FREE", "ACTIVE", "QUIET", "SUSPENDED"].map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
          </label>
        </div>
        <div className="row wrap">
          <label>
            Agent's round
            <select aria-label="Agent's round" value={agentId} onChange={(e) => setAgentId(e.target.value)}>
              <option value="">None</option>
              {agents.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.displayName}
                </option>
              ))}
            </select>
          </label>
          {isToday && (
            <span className={`live ${live}`} role="status" aria-label="Live updates">
              {live === "live" ? "● Live" : live === "reconnecting" ? "Reconnecting…" : live === "connecting" ? "Connecting…" : "Not live"}
            </span>
          )}
          {round && (
            <span className="muted small">
              Round: {round.done.size} done, {round.notReached.size} not reached yet
            </span>
          )}
        </div>
        <form className="row" onSubmit={search} role="search">
          <input aria-label="Find a trader, phone or stall" placeholder="Name, phone or stall" value={query} onChange={(e) => setQuery(e.target.value)} />
          <button>Find</button>
          {hits.length > 0 && (
            <button type="button" onClick={() => (setHits([]), setQuery(""))}>
              Clear
            </button>
          )}
        </form>
        {hits.length > 0 && (
          <ul className="hits" aria-label="Search results">
            {hits.map((h, i) => (
              <li key={i}>
                <button className="link" onClick={() => h.stallId && setSelected(h.stallId)}>
                  {h.stallCode ?? "no stall"}
                </button>{" "}
                {h.traderName} <span className="muted">{h.traderPhone}</span>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="map-body">
        {narrow ? (
          <MapList footprints={footprints} status={byStall} layer={layer} visible={visible} highlighted={highlighted} onSelect={setSelected} />
        ) : (
          <MarketSvg
            footprints={footprints}
            sections={sections}
            status={byStall}
            layer={layer}
            visible={visible}
            highlighted={highlighted}
            selected={selected}
            onSelect={setSelected}
            roundDone={round?.done}
            roundNotReached={round?.notReached}
          />
        )}
        <aside className="map-side">
          <ul className="legend" aria-label="Legend">
            {legendFor(layer).map((s) => (
              <li key={s.key}>
                <span className={`swatch p-${s.pattern}`} style={{ background: s.fill }} /> {s.label}{" "}
                <strong>{counts.get(s.key) ?? 0}</strong>
              </li>
            ))}
          </ul>
          {generated && <p className="muted small">Layout generated from stall numbers, not measured (dashed outlines).</p>}
          {unmapped.length > 0 && (
            <details>
              <summary>
                {unmapped.length} stall{unmapped.length === 1 ? "" : "s"} not on the plan
              </summary>
              <ul className="unmapped">
                {unmapped.map((s) => (
                  <li key={s.stallId}>{s.code}</li>
                ))}
              </ul>
            </details>
          )}
          {selected && <StallPanel councilId={councilId} stallId={selected} day={day} onClose={() => setSelected(null)} />}
        </aside>
      </div>
    </div>
  );
}

/** The phone view: stalls grouped by section and aisle, with the same states and filters (MARKET-MAP section 8.8). */
export function MapList(props: {
  footprints: Footprint[];
  status: Map<string, StallStatus>;
  layer: Layer;
  visible: Set<string> | null;
  highlighted: Set<string>;
  onSelect: (id: string) => void;
}) {
  const groups = useMemo(() => {
    const g = new Map<string, Footprint[]>();
    for (const f of props.footprints) {
      if (props.visible && !props.visible.has(f.stallId)) continue;
      const key = f.aisle ?? "Other";
      g.set(key, [...(g.get(key) ?? []), f]);
    }
    return [...g.entries()].sort(([a], [b]) => a.localeCompare(b, undefined, { numeric: true }));
  }, [props.footprints, props.visible]);
  return (
    <div className="map-list" aria-label="Stalls by aisle">
      {groups.map(([aisle, list]) => (
        <section key={aisle}>
          <h3>Aisle {aisle}</h3>
          <ul>
            {list.map((f) => {
              const s = props.status.get(f.stallId);
              const style = styleFor(props.layer, s);
              return (
                <li key={f.stallId} className={props.highlighted.has(f.stallId) ? "hit" : ""}>
                  <button className="stall-row" onClick={() => props.onSelect(f.stallId)}>
                    <span className={`swatch p-${style.pattern}`} style={{ background: style.fill }} />
                    <strong>{f.code}</strong>
                    <span>{style.label}</span>
                    <span className="muted">{s?.traderName ?? ""}</span>
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}

function StallPanel({ councilId, stallId, day, onClose }: { councilId: string; stallId: string; day: string; onClose: () => void }) {
  const [panel, setPanel] = useState<Panel | null>(null);
  useEffect(() => {
    setPanel(null);
    api("get", "/api/v1/councils/{councilId}/stalls/{stallId}/panel", { path: { councilId, stallId }, query: { date: day } }).then(setPanel);
  }, [councilId, stallId, day]);
  if (!panel) return <div className="card panel">Loading…</div>;
  const most = Math.max(1, ...(panel.lastSevenDays ?? []).map((d) => d.depositedNgwee ?? 0));
  return (
    <div className="card panel" aria-label={`Stall ${panel.code}`}>
      <div className="heading">
        <h2>{panel.code}</h2>
        <button className="link" onClick={onClose} aria-label="Close">
          ✕
        </button>
      </div>
      <p className="muted">
        {panel.stallClass} · {panel.status}
        {panel.aisle && ` · aisle ${panel.aisle}, ${panel.side === "L" ? "left" : "right"} side, no. ${panel.position}`}
      </p>
      <p>
        {panel.holder ? (
          <>
            <strong>{panel.holder.name}</strong> <span className="muted">{panel.holder.phone}</span>
          </>
        ) : (
          <span className="muted">Nobody holds this stall.</span>
        )}
      </p>
      <div className="bars" aria-label="Deposits, last seven days">
        {(panel.lastSevenDays ?? []).map((d) => (
          <span key={d.date} title={`${d.date}: ${kwacha(d.depositedNgwee)}`} style={{ height: `${4 + (36 * (d.depositedNgwee ?? 0)) / most}px` }} />
        ))}
      </div>
      <p>
        Today {kwacha(panel.depositedTodayNgwee)} · Levy owed {panel.unpaidLevyDays} day(s), {kwacha(panel.unpaidLevyNgwee)}
        {panel.openDisputes ? ` · ${panel.openDisputes} disputed deposit(s)` : ""}
      </p>
      {(panel.history?.length ?? 0) > 1 && (
        <details>
          <summary>Who held it before</summary>
          <ul>
            {panel.history!.slice(1).map((h, i) => (
              <li key={i}>
                {h.name}: {h.from?.slice(0, 10)} to {h.to?.slice(0, 10)}
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  );
}
