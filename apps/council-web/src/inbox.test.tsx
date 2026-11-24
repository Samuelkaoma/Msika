// The approvals inbox (card M-63), with msika-api played by a fake fetch.

import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { endSession, startSession } from "./api/client";
import type { Detail, Item } from "./api/schema";
import { lusakaDay } from "./inbox";

const C = "11111111-1111-1111-1111-111111111111";
const ME = "22222222-2222-2222-2222-222222222222";
const BASE = `/api/v1/councils/${C}`;

function item(id: string, more: Partial<Item> = {}): Item {
  return {
    id, kind: "STALL_CLASS", subjectType: "STALL", subjectId: "stall-1", marketId: "m-1", raisedByType: "USER", raisedById: "someone",
    raisedByEmail: "master@example.test", reason: "measured", effect: `Change stall ${id}`, status: "OPEN",
    dueAt: "2026-12-01T22:00:00Z", createdAt: "2026-11-24T08:00:00Z", approvals: 0, overdue: false, escalated: false, handedToMe: false,
    policy: { steps: [["MARKET_MASTER"]], deadlineDays: 5, bulkAllowed: true, sampleRate: 0 }, ...more,
  };
}

function fake(options: { queues?: Record<string, Item[]>; detail?: Detail; admin?: boolean; onPost?: (url: string, body: unknown) => [number, unknown] }) {
  const posts: { url: string; body: unknown }[] = [];
  const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === "POST") {
        const body: unknown = init.body ? JSON.parse(String(init.body)) : undefined;
        posts.push({ url, body });
        return json(...(options.onPost?.(url, body) ?? [200, options.detail]));
      }
      const [path, query] = url.split("?");
      if (path === "/api/v1/auth/me") {
        const permissions = options.admin ? ["READ", "USE_INBOX", "MANAGE_POLICIES"] : ["READ", "USE_INBOX"];
        return json(200, { userId: ME, councilId: C, roles: [], permissions, email: "me@example.test" });
      }
      if (path === `${BASE}/markets`) return json(200, [{ id: "m-1", code: "SOW", name: "Soweto" }, { id: "m-2", code: "CHI", name: "Chilenje" }]);
      if (path === `${BASE}/inbox`) return json(200, options.queues?.[new URLSearchParams(query).get("queue") ?? ""] ?? []);
      if (path.startsWith(`${BASE}/requests/`) && options.detail) return json(200, options.detail);
      return json(404, { message: "not found" });
    }),
  );
  return posts;
}

beforeEach(() => {
  endSession("signed-out");
  startSession({ token: "t", expiresAt: new Date(Date.now() + 600_000).toISOString() });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("my queue", () => {
  it("lists what waits for me in the server's order, and filters by kind and market", async () => {
    fake({ queues: { mine: [item("one"), item("two", { marketId: "m-2" }), item("three", { kind: "AGENT_SUSPENSION", marketId: undefined })] } });
    render(<App initialPath="/inbox" />);
    const list = await screen.findByRole("list", { name: "Requests" });
    expect(within(list).getAllByRole("link").map((l) => l.querySelector("strong")!.textContent)).toEqual([
      "Change stall one", "Change stall two", "Change stall three",
    ]);
    expect(within(list).getAllByText(/due 1 Dec 2026/)).toHaveLength(3);
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText("Market"), "m-2");
    expect(screen.getAllByRole("listitem")).toHaveLength(1);
    await user.selectOptions(screen.getByLabelText("Kind"), "AGENT_SUSPENSION");
    expect(screen.getByText("Nothing matches these filters.")).toBeTruthy();
    await user.selectOptions(screen.getByLabelText("Market"), "");
    expect(screen.getByText("Change stall three")).toBeTruthy();
  });

  it("says so when nothing waits, and keeps overdue and escalated for admins", async () => {
    fake({});
    render(<App initialPath="/inbox?view=overdue" />);
    expect(await screen.findByText("Nothing is waiting for you.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Overdue" })).toBeNull();
  });

  it("is counted on the home page", async () => {
    fake({ queues: { mine: [item("one"), item("two")] } });
    render(<App initialPath="/" />);
    expect(await screen.findByRole("link", { name: "Inbox (2 waiting)" })).toBeTruthy();
  });
});

describe("the other views", () => {
  it("raised by me shows how each stands", async () => {
    fake({ queues: { raised: [item("one", { status: "REJECTED" }), item("two", { problem: "stall taken" })] } });
    render(<App initialPath="/inbox" />);
    await userEvent.setup().click(await screen.findByRole("button", { name: "Raised by me" }));
    expect(await screen.findByText("rejected")).toBeTruthy();
    expect(screen.getByText("could not be done")).toBeTruthy();
  });

  it("an admin sees overdue, and the escalated ones among them", async () => {
    fake({ admin: true, queues: { overdue: [item("late", { overdue: true }), item("later", { overdue: true, escalated: true })] } });
    render(<App initialPath="/inbox" />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Overdue" }));
    expect(await screen.findByText("Change stall late")).toBeTruthy();
    expect(screen.getByText("Change stall later")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Escalated" }));
    expect(await screen.findByText("Change stall later")).toBeTruthy();
    expect(screen.queryByText("Change stall late")).toBeNull();
  });
});

describe("a request", () => {
  it("shows the effect in words, the evidence, the history and the stall on the map", async () => {
    fake({
      detail: {
        request: item("one", { approvals: 1, policy: { steps: [["MARKET_MASTER"], ["COUNCIL_ADMIN"]] } }),
        evidence: [{ type: "SURVEY", id: "S-1", note: "twice the size" }],
        decisions: [{ id: "d", decision: "APPROVE", decidedByEmail: "other@example.test", at: "2026-11-25T08:00:00Z", reason: "seen it" }],
      },
    });
    render(<App initialPath="/inbox/one" />);
    expect(await screen.findByRole("heading", { name: "Change stall one" })).toBeTruthy();
    expect(screen.getByText("1 of 2")).toBeTruthy();
    expect(screen.getByText("SURVEY S-1: twice the size")).toBeTruthy();
    expect(screen.getByText(/by other@example.test on 25 Nov 2026: seen it/)).toBeTruthy();
    expect(screen.getByRole("link", { name: "Show on the market map" }).getAttribute("href")).toBe("/map?market=m-1&stall=stall-1");
  });

  it("cannot be rejected without a reason; a first approval needs none", async () => {
    const posts = fake({ detail: { request: item("one"), evidence: [], decisions: [] } });
    render(<App initialPath="/inbox/one" />);
    const reject = await screen.findByRole<HTMLButtonElement>("button", { name: "Reject" });
    const approve = screen.getByRole<HTMLButtonElement>("button", { name: "Approve" });
    const user = userEvent.setup();
    expect(reject.disabled).toBe(true);
    expect(approve.disabled).toBe(false);
    await user.type(screen.getByLabelText("Reason"), "   ");
    await user.click(reject);
    expect(posts).toEqual([]);
    await user.type(screen.getByLabelText("Reason"), "wrong stall");
    await user.click(reject);
    await waitFor(() => expect(posts).toEqual([{ url: `${BASE}/requests/one/decisions`, body: { decision: "REJECT", reason: "wrong stall" } }]));
  });

  it("a second approval needs a reason", async () => {
    fake({ detail: { request: item("one", { approvals: 1 }), evidence: [], decisions: [] } });
    render(<App initialPath="/inbox/one" />);
    const approve = await screen.findByRole<HTMLButtonElement>("button", { name: "Approve" });
    expect(approve.disabled).toBe(true);
    await userEvent.setup().type(screen.getByLabelText("Reason"), "agreed");
    expect(approve.disabled).toBe(false);
  });

  it("says why I cannot decide, offers no buttons, and lets me withdraw my own", async () => {
    const posts = fake({
      detail: { request: item("one", { raisedById: ME, refusal: "the person who raised a request cannot decide it" }), evidence: [], decisions: [] },
      onPost: () => [200, { request: item("one", { raisedById: ME, status: "WITHDRAWN" }), evidence: [], decisions: [] }],
    });
    render(<App initialPath="/inbox/one" />);
    expect(await screen.findByText("You cannot decide this: the person who raised a request cannot decide it.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
    await userEvent.setup().click(screen.getByRole("button", { name: "Withdraw" }));
    expect(await screen.findByText("withdrawn")).toBeTruthy();
    expect(posts.map((p) => p.url)).toEqual([`${BASE}/requests/one/withdraw`]);
  });

  it("shows the server's reason when an approval could not be carried out, and stays open", async () => {
    fake({
      detail: { request: item("one", { problem: "stall taken" }), evidence: [], decisions: [] },
      onPost: () => [409, { message: "not approved: stall taken. The request stays open" }],
    });
    render(<App initialPath="/inbox/one" />);
    await userEvent.setup().click(await screen.findByRole("button", { name: "Approve" }));
    expect((await screen.findByRole("alert")).textContent).toBe("not approved: stall taken. The request stays open");
    expect(screen.getByText("It could not be carried out last time: stall taken")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Approve" })).toBeTruthy();
  });
});

describe("dates", () => {
  it("a deadline at Lusaka midnight belongs to the day that ends", () => {
    expect(lusakaDay("2026-12-01T22:00:00Z", true)).toBe("1 Dec 2026");
    expect(lusakaDay("2026-12-01T22:00:00Z")).toBe("2 Dec 2026");
  });
});
