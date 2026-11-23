import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { BrowserRouter, MemoryRouter, Navigate, NavLink, Outlet, Route, Routes, useLocation, useNavigate } from "react-router";
import { api, ApiError } from "./api/client";
import type { MarketResponse } from "./api/schema";
import { AuthProvider, useAuth } from "./auth";

/** The pages, in the order of the navigation. M-27 fills in the ones marked as coming. */
const PAGES = [
  { path: "/", label: "Overview" },
  { path: "/markets", label: "Markets" },
  { path: "/collections", label: "Collections" },
  { path: "/arrears", label: "Arrears" },
  { path: "/agents", label: "Agents" },
  { path: "/seals", label: "Daily seals" },
];

export default function App({ initialPath }: { initialPath?: string }) {
  const routes = (
    <AuthProvider>
      <AppRoutes />
    </AuthProvider>
  );
  // Tests start at a chosen page in memory; the real app follows the address bar.
  return initialPath ? <MemoryRouter initialEntries={[initialPath]}>{routes}</MemoryRouter> : <BrowserRouter>{routes}</BrowserRouter>;
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/sign-in" element={<SignIn />} />
      <Route element={<RequireAuth />}>
        <Route element={<Layout />}>
          <Route index element={<Overview />} />
          <Route path="markets" element={<Markets />} />
          <Route path="collections" element={<Coming what="Deposits and levy per day and per market" />} />
          <Route path="arrears" element={<Coming what="Traders in levy arrears" />} />
          <Route path="agents" element={<Coming what="Agents: deposits, disputes and reconciliation differences" />} />
          <Route path="seals" element={<Coming what="The daily seals, each with a verify button" />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function RequireAuth() {
  const { me, loading } = useAuth();
  const location = useLocation();
  if (loading) return <p className="page muted">Loading…</p>;
  if (!me) return <Navigate to="/sign-in" replace state={{ from: location.pathname }} />;
  if (!me.councilId) return <OperatorNotice />;
  return <Outlet />;
}

function SignIn() {
  const { me, ended, signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const from = (location.state as { from?: string } | null)?.from ?? "/";

  useEffect(() => {
    if (me) navigate(from, { replace: true });
  }, [me, from, navigate]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signIn(email, password);
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401 ? "Wrong email or password." : "Could not sign in. Try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="signin">
      <form className="card" onSubmit={submit} aria-label="Sign in">
        <div className="brand">
          <span className="logo">M</span>
          <strong>Msika for councils</strong>
        </div>
        {ended === "expired" && <p className="notice">Your session ended. Please sign in again.</p>}
        {ended === "signed-out" && <p className="notice">You are signed out.</p>}
        <label>
          Email
          <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          Password
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <button className="primary" disabled={busy}>
          Sign in
        </button>
      </form>
    </main>
  );
}

function Layout() {
  const { me, signOut } = useAuth();
  const [open, setOpen] = useState(false);
  const location = useLocation();
  useEffect(() => setOpen(false), [location.pathname]);
  return (
    <div className="layout">
      <header className="topbar">
        <button className="menu" aria-label="Menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
          ☰
        </button>
        <div className="brand">
          <span className="logo">M</span>
          <strong>Msika</strong>
        </div>
        <div className="who">
          <span>{me?.email}</span>
          <span className="role">{me?.role === "COUNCIL_OFFICER" ? "Officer (read only)" : "Council admin"}</span>
          <button onClick={signOut}>Sign out</button>
        </div>
      </header>
      <nav className={open ? "side open" : "side"} aria-label="Pages">
        {PAGES.map((p) => (
          <NavLink key={p.path} to={p.path} end={p.path === "/"}>
            {p.label}
          </NavLink>
        ))}
      </nav>
      <main className="page">
        <Outlet />
      </main>
    </div>
  );
}

function Overview() {
  const { me } = useAuth();
  return (
    <section>
      <h1>Overview</h1>
      <p className="muted">
        Signed in as {me?.email}. {me?.canEdit ? "You can make changes for your council." : "You can see everything and change nothing."}
      </p>
      <div className="tiles">
        {PAGES.slice(1).map((p) => (
          <NavLink key={p.path} to={p.path} className="tile">
            {p.label}
          </NavLink>
        ))}
      </div>
    </section>
  );
}

function Markets() {
  const { me } = useAuth();
  const [markets, setMarkets] = useState<MarketResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const councilId = me!.councilId!;

  const load = () =>
    api("get", "/api/v1/councils/{councilId}/markets", { path: { councilId } }).then(setMarkets, (e: unknown) =>
      setError(e instanceof Error ? e.message : String(e)),
    );
  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [councilId]);

  return (
    <section>
      <div className="heading">
        <h1>Markets</h1>
        {me?.canEdit && (
          <button className="primary" onClick={() => setAdding(true)}>
            Add market
          </button>
        )}
      </div>
      {adding && <AddMarket councilId={councilId} onDone={() => (setAdding(false), load())} />}
      {error && <p className="error">{error}</p>}
      {markets && markets.length === 0 && <p className="muted">No markets yet.</p>}
      {markets && markets.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Code</th>
              <th>Name</th>
              <th>Location</th>
            </tr>
          </thead>
          <tbody>
            {markets.map((m) => (
              <tr key={m.id}>
                <td>{m.code}</td>
                <td>{m.name}</td>
                <td>{m.location}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function AddMarket({ councilId, onDone }: { councilId: string; onDone: () => void }) {
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [location, setLocation] = useState("");
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      await api("post", "/api/v1/councils/{councilId}/markets", { path: { councilId }, body: { code, name, location } });
      onDone();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  };
  return (
    <form className="card inline" onSubmit={submit} aria-label="Add market">
      <label>
        Code <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="SOW" required />
      </label>
      <label>
        Name <input value={name} onChange={(e) => setName(e.target.value)} required />
      </label>
      <label>
        Location <input value={location} onChange={(e) => setLocation(e.target.value)} />
      </label>
      {error && <p className="error">{error}</p>}
      <div className="row">
        <button className="primary">Save</button>
        <button type="button" onClick={onDone}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function Coming({ what }: { what: string }) {
  return (
    <section>
      <h1>{PAGES.find((p) => window.location.pathname.endsWith(p.path))?.label ?? "Coming soon"}</h1>
      <p className="muted">{what}. This page is built in card M-27.</p>
    </section>
  );
}

function OperatorNotice(): ReactNode {
  const { signOut } = useAuth();
  return (
    <main className="signin">
      <div className="card">
        <h2>This dashboard is for council users</h2>
        <p className="muted">Msika operators manage councils through the API. Sign in with a council admin or officer account.</p>
        <button onClick={signOut}>Sign out</button>
      </div>
    </main>
  );
}
