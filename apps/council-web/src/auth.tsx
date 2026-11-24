import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { api, ApiError, currentSession, endSession, onSessionEnd, startSession } from "./api/client";
import type { MeResponse } from "./api/schema";

/** Who is signed in, and what they may do. */
export type Me = MeResponse & { canEdit: boolean };

type Auth = {
  me: Me | null;
  loading: boolean;
  ended: "signed-out" | "expired" | null;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => void;
};

const AuthContext = createContext<Auth | null>(null);

/** What the user may do comes from the server (card M-61); the server checks it again on every call. */
function withRights(me: MeResponse): Me {
  return { ...me, canEdit: me.permissions?.includes("MANAGE_MARKETS") ?? false };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(currentSession() !== null);
  const [ended, setEnded] = useState<"signed-out" | "expired" | null>(null);

  useEffect(
    () =>
      onSessionEnd((reason) => {
        setMe(null);
        setEnded(reason);
      }),
    [],
  );

  // A session kept from before a reload: check it is still good.
  useEffect(() => {
    if (!currentSession()) {
      setLoading(false);
      return;
    }
    api("get", "/api/v1/auth/me").then(
      (m) => setMe(withRights(m)),
      () => endSession("expired"),
    ).finally(() => setLoading(false));
  }, []);

  // Go back to sign-in the moment the token expires, not only on the next click.
  useEffect(() => {
    const s = currentSession();
    if (!me || !s) return;
    const timer = setTimeout(() => endSession("expired"), Math.max(0, Date.parse(s.expiresAt) - Date.now()));
    return () => clearTimeout(timer);
  }, [me]);

  const signIn = useCallback(async (email: string, password: string) => {
    const reply = await api("post", "/api/v1/auth/login", { body: { email, password } });
    if (!reply.token || !reply.expiresAt) throw new ApiError(500, "the sign-in answer had no token");
    startSession({ token: reply.token, expiresAt: reply.expiresAt });
    const m = await api("get", "/api/v1/auth/me");
    setEnded(null);
    setMe(withRights(m));
  }, []);

  const signOut = useCallback(() => endSession("signed-out"), []);

  return <AuthContext.Provider value={{ me, loading, ended, signIn, signOut }}>{children}</AuthContext.Provider>;
}

export function useAuth(): Auth {
  const auth = useContext(AuthContext);
  if (!auth) throw new Error("useAuth outside AuthProvider");
  return auth;
}
