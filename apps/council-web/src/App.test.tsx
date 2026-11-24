// The dashboard shell (card M-26): sign in, sign out, an expired session goes back to sign-in, and an officer sees
// no edit buttons. msika-api is played by a fake fetch that answers like the real endpoints.

import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { endSession } from "./api/client";

const COUNCIL = "11111111-1111-1111-1111-111111111111";

type Role = "COUNCIL_ADMIN" | "COUNCIL_OFFICER";

/** A fake msika-api. `expiresInMs` sets how long a token lives; `revoked` makes every call 401. */
function fakeApi(role: Role, expiresInMs = 15 * 60_000) {
  const state = { revoked: false, calls: [] as string[] };
  const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      state.calls.push(`${method} ${url}`);
      if (url === "/api/v1/auth/login") {
        const body = JSON.parse(String(init?.body)) as { password: string };
        if (body.password !== "right-password") return json(401, { error: "unauthenticated", message: "wrong" });
        return json(200, { token: "t-1", tokenType: "Bearer", expiresAt: new Date(Date.now() + expiresInMs).toISOString() });
      }
      if (state.revoked) return json(401, { error: "unauthenticated", message: "token" });
      if (url === "/api/v1/auth/me") return json(200, { userId: "u-1", councilId: COUNCIL, roles: [{ role, marketIds: [] }], permissions: role === "COUNCIL_ADMIN" ? ["READ", "MANAGE_MARKETS"] : ["READ"], email: "user@example.test" });
      if (url === `/api/v1/councils/${COUNCIL}/markets`) {
        return json(200, [{ id: "m-1", councilId: COUNCIL, code: "SOW", name: "Soweto Market", location: "Lusaka" }]);
      }
      return json(404, { error: "not_found", message: url });
    }),
  );
  return state;
}

async function signIn(password = "right-password") {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText("Email"), "user@example.test");
  await user.type(screen.getByLabelText("Password"), password);
  await user.click(screen.getByRole("button", { name: "Sign in" }));
  return user;
}

beforeEach(() => {
  sessionStorage.clear();
  endSession("signed-out");
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("signing in and out", () => {
  it("signs in and shows the dashboard", async () => {
    fakeApi("COUNCIL_ADMIN");
    render(<App initialPath="/" />);
    await signIn();
    expect(await screen.findByRole("heading", { name: "Overview" })).toBeTruthy();
    expect(screen.getByText("user@example.test")).toBeTruthy();
  });

  it("says so when the password is wrong, and stays on sign-in", async () => {
    fakeApi("COUNCIL_ADMIN");
    render(<App initialPath="/" />);
    await signIn("wrong-password");
    expect(await screen.findByRole("alert")).toHaveProperty("textContent", "Wrong email or password.");
    expect(screen.queryByRole("heading", { name: "Overview" })).toBeNull();
  });

  it("signs out back to the sign-in page and forgets the session", async () => {
    fakeApi("COUNCIL_ADMIN");
    render(<App initialPath="/" />);
    const user = await signIn();
    await screen.findByRole("heading", { name: "Overview" });
    await user.click(screen.getByRole("button", { name: "Sign out" }));
    expect(await screen.findByText("You are signed out.")).toBeTruthy();
    expect(sessionStorage.getItem("msika-council-session")).toBeNull();
  });
});

describe("an expired session", () => {
  it("goes back to sign-in when the token runs out, without a click", async () => {
    fakeApi("COUNCIL_ADMIN", 300);
    render(<App initialPath="/" />);
    await signIn();
    await screen.findByRole("heading", { name: "Overview" });
    expect(await screen.findByText("Your session ended. Please sign in again.", {}, { timeout: 3000 })).toBeTruthy();
  });

  it("goes back to sign-in when the API refuses the token (a deactivated user, say)", async () => {
    const api = fakeApi("COUNCIL_ADMIN");
    render(<App initialPath="/" />);
    const user = await signIn();
    await screen.findByRole("heading", { name: "Overview" });
    api.revoked = true;
    const nav = screen.getByRole("navigation", { name: "Pages" });
    await user.click(within(nav).getByRole("link", { name: "Markets" }));
    expect(await screen.findByText("Your session ended. Please sign in again.")).toBeTruthy();
  });

  it("does not use a stored session that has already expired", async () => {
    sessionStorage.setItem("msika-council-session", JSON.stringify({ token: "old", expiresAt: "2020-01-01T00:00:00Z" }));
    const api = fakeApi("COUNCIL_ADMIN");
    render(<App initialPath="/markets" />);
    expect(await screen.findByRole("button", { name: "Sign in" })).toBeTruthy();
    expect(api.calls.filter((c) => c.includes("/markets"))).toEqual([]);
  });
});

describe("what each role can do", () => {
  it("an officer sees the markets but no edit buttons", async () => {
    fakeApi("COUNCIL_OFFICER");
    render(<App initialPath="/markets" />);
    await signIn();
    expect(await screen.findByText("Soweto Market")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Add market" })).toBeNull();
    expect(screen.getByText("Officer (read only)")).toBeTruthy();
  });

  it("a council admin sees the edit button", async () => {
    fakeApi("COUNCIL_ADMIN");
    render(<App initialPath="/markets" />);
    await signIn();
    expect(await screen.findByText("Soweto Market")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Add market" })).toBeTruthy();
  });
});

describe("layout", () => {
  it("returns to the page asked for after signing in", async () => {
    fakeApi("COUNCIL_ADMIN");
    render(<App initialPath="/markets" />);
    await signIn();
    await waitFor(() => expect(screen.getByRole("heading", { name: "Markets" })).toBeTruthy());
  });
});
