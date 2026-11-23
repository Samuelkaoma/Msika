// The figure pages (card M-27), with msika-api's /reports answered by a fake fetch.

import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { endSession, startSession } from "./api/client";
import { kwacha } from "./pages";

const C = "11111111-1111-1111-1111-111111111111";

function fake(routes: Record<string, unknown>) {
  const seen: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      seen.push(url);
      const path = url.split("?")[0];
      const body = path === "/api/v1/auth/me" ? { councilId: C, role: "COUNCIL_OFFICER", email: "o@example.test" } : routes[path];
      return body === undefined
        ? new Response(JSON.stringify({ message: "not found" }), { status: 404 })
        : new Response(JSON.stringify(body), { status: 200 });
    }),
  );
  return seen;
}

beforeEach(() => {
  endSession("signed-out");
  startSession({ token: "t", expiresAt: new Date(Date.now() + 600_000).toISOString() });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("kwacha", () => {
  it("writes ngwee as kwacha with integer arithmetic", () => {
    expect(kwacha(45_000)).toBe("K450");
    expect(kwacha(123_050)).toBe("K1,230.50");
    expect(kwacha(-500)).toBe("-K5");
    expect(kwacha(undefined)).toBe("K0");
  });
});

describe("collections", () => {
  it("keeps deposited, digital and declared apart and totals each", async () => {
    fake({
      [`/api/v1/councils/${C}/reports/collections`]: [
        { businessDate: "2026-10-05", marketId: "a", marketCode: "SOW", depositedNgwee: 45_000, digitalNgwee: 2_500, declaredNgwee: 12_000, levyChargedNgwee: 1_000, levyPaidNgwee: 500 },
        { businessDate: "2026-10-06", marketId: "a", marketCode: "SOW", depositedNgwee: 20_000, digitalNgwee: 0, declaredNgwee: 0, levyChargedNgwee: 1_000, levyPaidNgwee: 1_000 },
      ],
    });
    render(<App initialPath="/collections" />);
    const table = await screen.findByRole("table", { name: "Collections by day" });
    const total = within(table).getByText("Total").closest("tr")!;
    expect(within(total).getByText("K650")).toBeTruthy();
    expect(within(total).getByText("K25")).toBeTruthy();
    expect(within(total).getByText("K120")).toBeTruthy();
    expect(within(total).getByText("K15")).toBeTruthy();
  });

  it("asks for the chosen days", async () => {
    const seen = fake({ [`/api/v1/councils/${C}/reports/collections`]: [] });
    render(<App initialPath="/collections" />);
    expect(await screen.findByText("Nothing recorded in these days.")).toBeTruthy();
    expect(seen.some((u) => /reports\/collections\?from=\d{4}-\d\d-\d\d&to=\d{4}-\d\d-\d\d/.test(u))).toBe(true);
  });
});

describe("arrears and agents", () => {
  it("lists who owes levy", async () => {
    fake({
      [`/api/v1/councils/${C}/reports/arrears`]: [
        { traderId: "t", traderName: "Trader Two", stallCode: "SOW-B-1", marketCode: "SOW", unpaidDays: 2, unpaidNgwee: 1_400, oldestUnpaid: "2026-10-05" },
      ],
    });
    render(<App initialPath="/arrears" />);
    expect(await screen.findByText("Trader Two")).toBeTruthy();
    expect(screen.getByText("K14")).toBeTruthy();
  });

  it("flags an agent with disputes and a day with reconciliation differences", async () => {
    fake({
      [`/api/v1/councils/${C}/reports/agents`]: [
        { agentId: "a", name: "Agent Zed", active: true, confirmed: 2, confirmedNgwee: 3_000, pending: 1, failed: 1, cancelled: 1, disputed: 1 },
      ],
      [`/api/v1/councils/${C}/reports/reconciliation`]: [
        { businessDate: "2026-10-05", paymentsCount: 3, msikaCount: 3, differenceCount: 1 },
      ],
    });
    render(<App initialPath="/agents" />);
    expect((await screen.findByText("Agent Zed")).closest("tr")!.className).toBe("flag");
    expect((await screen.findByText("2026-10-05")).closest("tr")!.className).toBe("flag");
  });
});

describe("seals", () => {
  it("verifies on request and says so", async () => {
    fake({
      [`/api/v1/councils/${C}/reports/seals`]: [{ businessDate: "2026-10-06", entryCount: 7, merkleRoot: "ab".repeat(32), sealedAt: "2026-10-06T18:00:00Z" }],
      [`/api/v1/councils/${C}/reports/seals/verification`]: { ok: true, seals: 2, entries: 7, problems: [] },
    });
    render(<App initialPath="/seals" />);
    await screen.findByText("2026-10-06");
    await userEvent.setup().click(screen.getByRole("button", { name: "Verify all seals" }));
    expect((await screen.findByRole("status")).textContent).toBe("All 2 sealed days check out (7 entries).");
  });

  it("shows the problems when a seal does not check out", async () => {
    fake({
      [`/api/v1/councils/${C}/reports/seals`]: [],
      [`/api/v1/councils/${C}/reports/seals/verification`]: { ok: false, seals: 1, entries: 3, problems: ["2026-10-05: root differs"] },
    });
    render(<App initialPath="/seals" />);
    await userEvent.setup().click(await screen.findByRole("button", { name: "Verify all seals" }));
    expect((await screen.findByRole("status")).textContent).toContain("root differs");
  });
});
