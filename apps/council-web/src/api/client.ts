// The typed API client (card M-26). Paths, parameters, bodies and responses all come from schema.ts, which is
// generated from msika-api's OpenAPI description: calling an endpoint that does not exist, or with the wrong body,
// is a compile error.

import type { Paths } from "./schema";

type Method = "get" | "post" | "put" | "delete";
type Op<P extends keyof Paths, M extends keyof Paths[P]> = Paths[P][M] extends {
  path: infer PP;
  query: infer Q;
  body: infer B;
  response: infer R;
}
  ? { path: PP; query: Q; body: B; response: R }
  : never;

type Options<P extends keyof Paths, M extends keyof Paths[P]> = ([Op<P, M>["path"]] extends [never]
  ? { path?: undefined }
  : { path: Op<P, M>["path"] }) &
  ([Op<P, M>["query"]] extends [never] ? { query?: undefined } : { query?: Op<P, M>["query"] }) &
  ([Op<P, M>["body"]] extends [never] ? { body?: undefined } : { body: Op<P, M>["body"] });

/** An error answer from the API: its status and the message msika-api gave. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

// ------------------------------------------------------------------------------------------------- the session

export type Session = { token: string; expiresAt: string };

const KEY = "msika-council-session";
let session: Session | null = load();
const listeners = new Set<(reason: "signed-out" | "expired") => void>();

function load(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export function currentSession(): Session | null {
  if (session && Date.parse(session.expiresAt) <= Date.now()) {
    endSession("expired");
  }
  return session;
}

export function startSession(s: Session): void {
  session = s;
  try {
    sessionStorage.setItem(KEY, JSON.stringify(s));
  } catch {
    // storage blocked: the session lasts until the page is closed
  }
}

/** Ends the session and tells whoever listens (the app goes back to sign-in). */
export function endSession(reason: "signed-out" | "expired"): void {
  const had = session !== null;
  session = null;
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // nothing stored
  }
  if (had) listeners.forEach((l) => l(reason));
}

export function onSessionEnd(listener: (reason: "signed-out" | "expired") => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

// ------------------------------------------------------------------------------------------------- the calls

function fill(path: string, params: Record<string, unknown> | undefined): string {
  return path.replace(/\{(\w+)\}/g, (_, name: string) => encodeURIComponent(String(params?.[name])));
}

export async function api<P extends keyof Paths & string, M extends keyof Paths[P] & Method>(
  method: M,
  path: P,
  options: Options<P, M> = {} as Options<P, M>,
): Promise<Op<P, M>["response"]> {
  const s = currentSession();
  let url = fill(path, options.path as Record<string, unknown> | undefined);
  if (options.query) {
    const q = new URLSearchParams();
    Object.entries(options.query as Record<string, unknown>).forEach(([k, v]) => v !== undefined && q.set(k, String(v)));
    url += `?${q}`;
  }
  const response = await fetch(url, {
    method: method.toUpperCase(),
    headers: {
      ...(options.body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(s ? { Authorization: `Bearer ${s.token}` } : {}),
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  if (response.status === 401 && s) {
    endSession("expired");
  }
  if (!response.ok) {
    let message = `${response.status}`;
    try {
      const problem = (await response.json()) as { message?: string };
      if (problem.message) message = problem.message;
    } catch {
      // no JSON body
    }
    throw new ApiError(response.status, message);
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as Op<P, M>["response"];
}
