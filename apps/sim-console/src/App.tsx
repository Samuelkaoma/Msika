import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import {
  ApiError,
  kwacha,
  msika,
  signIn,
  signOut,
  signedIn,
  toNgwee,
  zamsim,
  type Faults,
  type Message,
  type Phone,
  type Transaction,
  type Wallet,
} from "./api";

type Tab = "phones" | "payments" | "faults";

/** Runs `load` now and every `ms` while mounted; returns its latest result and error. */
function usePoll<T>(load: () => Promise<T>, ms: number, deps: unknown[]): [T | null, string | null, () => void] {
  const [value, setValue] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(load, deps);
  useEffect(() => {
    let live = true;
    const go = () =>
      run().then(
        (v) => live && (setValue(v), setError(null)),
        (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
      );
    go();
    const timer = setInterval(go, ms);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, [run, ms, tick]);
  return [value, error, () => setTick((t) => t + 1)];
}

export default function App() {
  const [tab, setTab] = useState<Tab>("phones");
  const [authed, setAuthed] = useState(signedIn());

  return (
    <div className="shell">
      <header className="top">
        <div className="brand">
          <span className="logo">M</span>
          <div>
            <strong>Msika simulator console</strong>
            <small>Fake phones, simulated Zamtel Money, fault settings. Development and demos only.</small>
          </div>
        </div>
        <nav>
          {(["phones", "payments", "faults"] as Tab[]).map((t) => (
            <button key={t} className={tab === t ? "tab on" : "tab"} onClick={() => setTab(t)}>
              {t === "phones" ? "Phones" : t === "payments" ? "Wallets & payments" : "Faults"}
            </button>
          ))}
        </nav>
      </header>
      <main>
        {tab === "phones" &&
          (authed ? (
            <Phones
              onSignOut={() => {
                signOut();
                setAuthed(false);
              }}
            />
          ) : (
            <SignIn onDone={() => setAuthed(true)} />
          ))}
        {tab === "payments" && <Payments />}
        {tab === "faults" && <FaultsPanel />}
      </main>
    </div>
  );
}

function SignIn({ onDone }: { onDone: () => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await signIn(email, password);
      onDone();
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401 ? "Wrong email or password." : String(err));
    }
  };
  return (
    <form className="card narrow" onSubmit={submit}>
      <h2>Sign in as a Msika operator</h2>
      <p className="muted">The fake phones speak to Msika as its operator. Use the operator set up for this environment.</p>
      <label>
        Email
        <input value={email} onChange={(e) => setEmail(e.target.value)} type="email" autoComplete="username" required />
      </label>
      <label>
        Password
        <input value={password} onChange={(e) => setPassword(e.target.value)} type="password" autoComplete="current-password" required />
      </label>
      {error && <p className="error">{error}</p>}
      <button className="primary">Sign in</button>
    </form>
  );
}

function Phones({ onSignOut }: { onSignOut: () => void }) {
  const [phones, error] = usePoll(() => msika.phones(), 10_000, []);
  const [selected, setSelected] = useState<string | null>(null);
  const [filter, setFilter] = useState("");
  const [other, setOther] = useState("");

  const shown = useMemo(() => {
    const f = filter.trim().toLowerCase();
    return (phones ?? []).filter(
      (p) => !f || p.name.toLowerCase().includes(f) || p.phone.includes(f) || p.stalls.some((s) => s.toLowerCase().includes(f)),
    );
  }, [phones, filter]);
  const current = phones?.find((p) => p.phone === selected) ?? null;

  return (
    <div className="phones">
      <aside className="card list">
        <div className="row">
          <input placeholder="Find a name, number or stall" value={filter} onChange={(e) => setFilter(e.target.value)} />
          <button className="link" onClick={onSignOut}>
            Sign out
          </button>
        </div>
        {error && <p className="error">{error}</p>}
        {phones && phones.length === 0 && <p className="muted">No traders or agents yet. Register some in a council first.</p>}
        <ul>
          {shown.map((p) => (
            <li key={p.councilId + p.phone}>
              <button className={selected === p.phone ? "person on" : "person"} onClick={() => setSelected(p.phone)}>
                <span className={p.kind === "AGENT" ? "badge agent" : "badge trader"}>{p.kind === "AGENT" ? "Agent" : "Trader"}</span>
                <span className="who">
                  <strong>{p.name}</strong>
                  <small>
                    {p.phone}
                    {p.stalls.length > 0 && ` · ${p.stalls.join(", ")}`}
                  </small>
                </span>
              </button>
            </li>
          ))}
        </ul>
        <form
          className="row"
          onSubmit={(e) => {
            e.preventDefault();
            if (other.trim()) setSelected(other.trim());
          }}
        >
          <input placeholder="Any other number, like 0977000123" value={other} onChange={(e) => setOther(e.target.value)} />
          <button>Open</button>
        </form>
      </aside>
      {selected ? <PhoneScreen key={selected} phone={selected} person={current} /> : <EmptyPhone />}
    </div>
  );
}

function EmptyPhone() {
  return (
    <section className="card handset empty">
      <p className="muted">Pick a trader or an agent to use their phone.</p>
      <p className="muted">
        Try <code>DEP B114 450</code> as an agent, then <code>TODAY</code> as the trader.
      </p>
    </section>
  );
}

function PhoneScreen({ phone, person }: { phone: string; person: Phone | null }) {
  const [messages, error, refresh] = usePoll(() => msika.conversation(phone), 1500, [phone]);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => {
    // A block body on purpose: newer browsers return a Promise from scrollIntoView, and an effect may only return a
    // clean-up function.
    end.current?.scrollIntoView({ block: "end" });
  }, [messages?.length]);

  const send = async (e: FormEvent) => {
    e.preventDefault();
    if (!draft.trim()) return;
    setSending(true);
    setSendError(null);
    try {
      await msika.text(phone, draft);
      setDraft("");
      refresh();
    } catch (err) {
      setSendError(err instanceof Error ? err.message : String(err));
    } finally {
      setSending(false);
    }
  };

  return (
    <section className="card handset">
      <header>
        <strong>{person?.name ?? "Unregistered number"}</strong>
        <small>
          {phone}
          {person && ` · ${person.kind === "AGENT" ? "Agent" : "Trader"} · ${person.council}`}
        </small>
      </header>
      <div className="thread" aria-live="polite">
        {error && <p className="error">{error}</p>}
        {(messages ?? []).map((m: Message, i) => (
          <div key={i} className={m.direction === "IN" ? "bubble mine" : "bubble theirs"} title={`${m.status}${m.template ? " · " + m.template : ""}`}>
            <span>{m.body}</span>
            <time>
              {new Date(m.at).toLocaleTimeString()}
              {m.direction === "OUT" && m.status !== "SENT" && ` · ${m.status.toLowerCase()}`}
            </time>
          </div>
        ))}
        <div ref={end} />
      </div>
      <form className="compose" onSubmit={send}>
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={person?.kind === "AGENT" ? "DEP B114 450" : "TODAY"}
          maxLength={160}
          autoFocus
        />
        <button className="primary" disabled={sending}>
          Send
        </button>
      </form>
      {sendError && <p className="error">{sendError}</p>}
    </section>
  );
}

function Payments() {
  const [wallets, walletError, refreshWallets] = usePoll(() => zamsim.wallets(), 3000, []);
  const [txs, txError, refreshTxs] = usePoll(() => zamsim.transactions(), 3000, []);
  const [selected, setSelected] = useState<string | null>(null);
  const names = useMemo(() => new Map((wallets ?? []).map((w) => [w.id, `${w.name} (${w.type.toLowerCase()})`])), [wallets]);
  const refresh = () => {
    refreshWallets();
    refreshTxs();
  };

  return (
    <div className="payments">
      <section className="card">
        <h2>Wallets</h2>
        {walletError && <p className="error">{walletError}</p>}
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Phone</th>
              <th className="num">Balance</th>
            </tr>
          </thead>
          <tbody>
            {(wallets ?? []).map((w: Wallet) => (
              <tr key={w.id} className={selected === w.id ? "on" : ""} onClick={() => setSelected(w.id)}>
                <td>{w.name}</td>
                <td>{w.type}</td>
                <td>{w.msisdn ?? ""}</td>
                <td className="num">{kwacha(w.balanceNgwee)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <div className="stack">
        {selected && wallets && (
          <WalletActions key={selected} wallet={wallets.find((w) => w.id === selected)!} wallets={wallets} onChange={refresh} />
        )}
        <section className="card">
          <h2>Recent transactions</h2>
          {txError && <p className="error">{txError}</p>}
          <table>
            <thead>
              <tr>
                <th>When</th>
                <th>Kind</th>
                <th>From</th>
                <th>To</th>
                <th className="num">Amount</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {(txs ?? []).map((t: Transaction) => (
                <tr key={t.id}>
                  <td>{new Date(t.createdAt).toLocaleTimeString()}</td>
                  <td>{t.kind}</td>
                  <td>{t.fromWalletId ? names.get(t.fromWalletId) ?? (t.kind === "FUND" ? "Outside (new float)" : "…") : ""}</td>
                  <td>{t.toWalletId ? names.get(t.toWalletId) ?? (t.kind === "WITHDRAW" ? "Outside (cash out)" : "…") : ""}</td>
                  <td className="num">{kwacha(t.amountNgwee)}</td>
                  <td className={`status ${t.status.toLowerCase()}`}>{t.status}{t.failureReason ? ` (${t.failureReason})` : ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>
    </div>
  );
}

function WalletActions({ wallet, wallets, onChange }: { wallet: Wallet; wallets: Wallet[]; onChange: () => void }) {
  const [pending, pendingError, refreshPending] = usePoll(() => zamsim.pending(wallet.id), 3000, [wallet.id]);
  const [amount, setAmount] = useState("");
  const [to, setTo] = useState("");
  const [message, setMessage] = useState<string | null>(null);

  const act = async (what: () => Promise<Transaction>) => {
    setMessage(null);
    try {
      const t = await what();
      setMessage(`${t.kind} ${t.status}${t.failureReason ? " (" + t.failureReason + ")" : ""}`);
      onChange();
      refreshPending();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e));
    }
  };
  const ngwee = toNgwee(amount);
  const others = wallets.filter((w) => w.id !== wallet.id);

  return (
    <section className="card">
      <h2>
        {wallet.name} <small className="muted">{wallet.type} · {kwacha(wallet.balanceNgwee)}</small>
      </h2>
      <div className="row wrap">
        <input placeholder="Amount, like 450" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <select value={to} onChange={(e) => setTo(e.target.value)}>
          <option value="">Choose the other wallet</option>
          {others.map((w) => (
            <option key={w.id} value={w.id}>
              {w.name} ({w.type.toLowerCase()})
            </option>
          ))}
        </select>
      </div>
      <div className="row wrap">
        <button disabled={ngwee === null} onClick={() => act(() => zamsim.fund(wallet.id, ngwee!))}>
          Add float
        </button>
        <button disabled={ngwee === null || !to} onClick={() => act(() => zamsim.transfer(wallet.id, to, ngwee!))}>
          Pay the other wallet
        </button>
        <button disabled={ngwee === null || !to} onClick={() => act(() => zamsim.requestToPay(wallet.id, to, ngwee!))}>
          Ask the other wallet to pay
        </button>
      </div>
      {message && <p className="muted">{message}</p>}
      <h3>Waiting for this wallet's approval</h3>
      {pendingError && <p className="error">{pendingError}</p>}
      {pending && pending.length === 0 && <p className="muted">Nothing waiting.</p>}
      <ul className="pending">
        {(pending ?? []).map((t) => (
          <li key={t.id}>
            <span>
              {kwacha(t.amountNgwee)} to {wallets.find((w) => w.id === t.toWalletId)?.name ?? "a merchant"}
            </span>
            <button className="primary" onClick={() => act(() => zamsim.approve(t.id))}>
              Approve
            </button>
            <button onClick={() => act(() => zamsim.reject(t.id))}>Reject</button>
          </li>
        ))}
      </ul>
    </section>
  );
}

function FaultsPanel() {
  const [faults, setFaults] = useState<Faults | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  useEffect(() => {
    zamsim.faults().then(setFaults, (e: unknown) => setError(String(e)));
  }, []);
  if (!faults) return <section className="card narrow">{error ? <p className="error">{error}</p> : <p>Loading…</p>}</section>;

  const field = (name: keyof Faults, label: string, hint: string, percent: boolean) => (
    <label>
      {label}
      <input
        type="number"
        min={0}
        max={percent ? 100 : 600000}
        step={percent ? 1 : 100}
        value={percent ? Math.round(faults[name] * 100) : faults[name]}
        onChange={(e) => {
          setSaved(false);
          setFaults({ ...faults, [name]: percent ? Number(e.target.value) / 100 : Number(e.target.value) });
        }}
      />
      <small className="muted">{hint}</small>
    </label>
  );

  return (
    <form
      className="card narrow"
      onSubmit={async (e) => {
        e.preventDefault();
        setError(null);
        try {
          setFaults(await zamsim.setFaults(faults));
          setSaved(true);
        } catch (err) {
          setError(err instanceof Error ? err.message : String(err));
        }
      }}
    >
      <h2>Make the payments system misbehave</h2>
      <p className="muted">Msika must still end with the right ledger. Turn these on during a demo to show it.</p>
      {field("failureRate", "Failed payments (%)", "Share of payments that fail, as if the payer had no money.", true)}
      {field("callbackDelayMs", "Callback delay (ms)", "How long zamsim waits before telling Msika.", false)}
      {field("duplicateCallbackRate", "Duplicate callbacks (%)", "Share of callbacks sent twice.", true)}
      {field("reorderRate", "Out-of-order callbacks (%)", "Share of callbacks held back so later ones overtake them.", true)}
      {field("reorderExtraDelayMs", "Hold-back time (ms)", "How long an out-of-order callback is held back.", false)}
      {error && <p className="error">{error}</p>}
      <div className="row">
        <button className="primary">Save</button>
        <button
          type="button"
          onClick={() => setFaults({ failureRate: 0, callbackDelayMs: 0, duplicateCallbackRate: 0, reorderRate: 0, reorderExtraDelayMs: 2000 })}
        >
          All off
        </button>
        {saved && <span className="muted">Saved.</span>}
      </div>
    </form>
  );
}
