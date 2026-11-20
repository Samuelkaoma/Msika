// Talks to msika-api (under /api) and zamsim (under /zamsim). In development Vite forwards both (vite.config.ts);
// in a container, the web server in front of the console does the same.

export type Phone = {
  phone: string;
  name: string;
  kind: "TRADER" | "AGENT";
  councilId: string;
  council: string;
  stalls: string[];
};

export type Message = {
  direction: "IN" | "OUT";
  body: string;
  at: string;
  status: string;
  template: string | null;
};

export type Wallet = {
  id: string;
  type: string;
  name: string;
  msisdn: string | null;
  balanceNgwee: number;
  createdAt: string;
};

export type Transaction = {
  id: string;
  kind: string;
  status: string;
  fromWalletId: string | null;
  toWalletId: string | null;
  amountNgwee: number;
  reference: string | null;
  failureReason: string | null;
  createdAt: string;
  completedAt: string | null;
  expiresAt: string | null;
};

export type Faults = {
  failureRate: number;
  callbackDelayMs: number;
  duplicateCallbackRate: number;
  reorderRate: number;
  reorderExtraDelayMs: number;
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

let token: string | null = sessionStorage.getItem("msika-sim-token");

export function signedIn(): boolean {
  return token !== null;
}

export function signOut(): void {
  token = null;
  sessionStorage.removeItem("msika-sim-token");
}

async function call<T>(method: string, url: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  const response = await fetch(url, {
    method,
    headers: {
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(url.startsWith("/api/") && token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (response.status === 401 && url.startsWith("/api/")) {
    signOut();
  }
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const problem = (await response.json()) as { message?: string };
      if (problem.message) message = problem.message;
    } catch {
      // not JSON: keep the status line
    }
    throw new ApiError(response.status, message);
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export async function signIn(email: string, password: string): Promise<void> {
  const reply = await call<{ token: string }>("POST", "/api/v1/auth/login", { email, password });
  token = reply.token;
  sessionStorage.setItem("msika-sim-token", reply.token);
}

export const msika = {
  phones: () => call<Phone[]>("GET", "/api/v1/sim/phones"),
  conversation: (phone: string) => call<Message[]>("GET", `/api/v1/sim/sms?phone=${encodeURIComponent(phone)}`),
  text: (from: string, body: string) => call<{ stored: boolean }>("POST", "/api/v1/sim/sms", { from, body }),
};

function key(): string {
  return `console-${crypto.randomUUID()}`;
}

export const zamsim = {
  wallets: () => call<Wallet[]>("GET", "/zamsim/v1/wallets?limit=500"),
  transactions: () => call<Transaction[]>("GET", "/zamsim/v1/transactions?limit=50"),
  pending: (customer: string) => call<Transaction[]>("GET", `/zamsim/v1/request-to-pay?customer=${customer}`),
  approve: (id: string) => call<Transaction>("POST", `/zamsim/v1/request-to-pay/${id}/approve`),
  reject: (id: string) => call<Transaction>("POST", `/zamsim/v1/request-to-pay/${id}/reject`),
  fund: (walletId: string, amountNgwee: number) =>
    call<Transaction>("POST", "/zamsim/v1/admin/fund", { walletId, amountNgwee, reference: "console" }, { "Idempotency-Key": key() }),
  transfer: (fromWalletId: string, toWalletId: string, amountNgwee: number) =>
    call<Transaction>("POST", "/zamsim/v1/transfers", { fromWalletId, toWalletId, amountNgwee, reference: "console" }, {
      "Idempotency-Key": key(),
    }),
  requestToPay: (merchantWalletId: string, customerWalletId: string, amountNgwee: number) =>
    call<Transaction>(
      "POST",
      "/zamsim/v1/request-to-pay",
      { merchantWalletId, customerWalletId, amountNgwee, reference: "console", ttlSeconds: 600 },
      { "Idempotency-Key": key() },
    ),
  faults: () => call<Faults>("GET", "/zamsim/v1/admin/faults"),
  setFaults: (f: Faults) => call<Faults>("PUT", "/zamsim/v1/admin/faults", f),
};

/** Kwacha from ngwee, the way Msika's texts write it: K1,230.50. Integer arithmetic only. */
export function kwacha(ngwee: number): string {
  const negative = ngwee < 0;
  const abs = Math.abs(ngwee);
  const whole = Math.trunc(abs / 100).toLocaleString("en-US");
  const fraction = abs % 100;
  return `${negative ? "-" : ""}K${whole}${fraction ? "." + String(fraction).padStart(2, "0") : ""}`;
}

/** Ngwee from what someone typed in kwacha ("12.50" is 1250), or null if it is not an amount. */
export function toNgwee(text: string): number | null {
  const m = /^\s*K?(\d{1,9})(?:\.(\d{1,2}))?\s*$/i.exec(text.replace(/,/g, ""));
  if (!m) return null;
  return Number(m[1]) * 100 + Number((m[2] ?? "0").padEnd(2, "0"));
}
