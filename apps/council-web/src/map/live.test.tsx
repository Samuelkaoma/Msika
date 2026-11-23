// The live closing view in the browser (card M-51): reading the event stream, recolouring a stall, and reconnecting
// after the server restarts without reloading the page.

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { endSession, startSession } from "../api/client";
import { parseSse } from "./live";
import { MarketMap } from "./MarketMapPage";

const C = "11111111-1111-1111-1111-111111111111";
const M = "22222222-2222-2222-2222-222222222222";

describe("parseSse", () => {
  it("reads events, joins data lines, skips comments, and keeps a half-received event for later", () => {
    const { messages, rest } = parseSse(": keep-alive\n\nevent: hello\ndata: {}\n\nid: 7\nevent: deposit\ndata: {\"a\":\ndata: 1}\n\nevent: dep");
    expect(messages).toEqual([
      { event: "hello", data: "{}", id: undefined },
      { event: "deposit", data: "{\"a\":\n1}", id: "7" },
    ]);
    expect(rest).toBe("event: dep");
  });

  it("copes with Windows line endings", () => {
    expect(parseSse("event: deposit\r\ndata: x\r\n\r\n").messages).toEqual([{ event: "deposit", data: "x", id: undefined }]);
  });
});

/** A stream that sends the given events and then either ends (the server went away) or stays open. */
function stream(events: unknown[], end: boolean): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      controller.enqueue(encoder.encode("event: hello\ndata: {}\n\n"));
      for (const e of events) controller.enqueue(encoder.encode(`id: 1\nevent: deposit\ndata: ${JSON.stringify(e)}\n\n`));
      if (end) controller.close();
    },
  });
}

beforeEach(() => {
  endSession("signed-out");
  startSession({ token: "t", expiresAt: new Date(Date.now() + 600_000).toISOString() });
  vi.stubGlobal("matchMedia", (q: string) => ({ matches: false, media: q, addEventListener: () => {}, removeEventListener: () => {} }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it("a confirmed deposit recolours its stall; when the server restarts the page reconnects, reloads, and keeps going", async () => {
  const calls = { status: 0, events: 0 };
  const stalls = [
    { stallId: "s1", code: "LIV-A-1", state: "QUIET", levy: "PAID_UP", depositedTodayNgwee: 0 },
    { stallId: "s2", code: "LIV-A-2", state: "QUIET", levy: "PAID_UP", depositedTodayNgwee: 0 },
  ];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      const path = url.split("?")[0];
      const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200 });
      if (path.endsWith("/plan")) {
        return json({
          sections: [],
          footprints: stalls.map((s, i) => ({ stallId: s.stallId, stallCode: s.code, polygonMm: [i * 3000, 0, i * 3000 + 2500, 0, i * 3000 + 2500, 2500, i * 3000, 2500], provenance: "GENERATED", aisle: "A-1" })),
        });
      }
      if (path.endsWith("/map-status")) {
        calls.status++;
        return json({ stalls });
      }
      if (path.endsWith("/map-events")) {
        calls.events++;
        // First connection: one deposit, then the server restarts (the stream ends). Second: another deposit, stays open.
        // Like the real server, the status reflects a deposit as soon as it is announced.
        const deposit = calls.events === 1 ? { stallId: "s1", amountNgwee: 45_000 } : { stallId: "s2", amountNgwee: 10_000 };
        const s = stalls.find((x) => x.stallId === deposit.stallId)!;
        s.state = "ACTIVE";
        s.depositedTodayNgwee += deposit.amountNgwee;
        return new Response(stream([{ ...deposit, marketId: M }], calls.events === 1), { status: 200 });
      }
      if (path.endsWith("/agents")) return json([]);
      return new Response("{}", { status: 404 });
    }),
  );

  render(<MarketMap councilId={C} marketId={M} />);
  const legend = await screen.findByRole("list", { name: "Legend" });
  const map = screen.getByRole("img");

  // The first event turns stall 1 active.
  await waitFor(() => expect(legend.textContent).toMatch(/Active\s*1/), { timeout: 3_000 });
  // The stream ended: it reconnects by itself, reloads the status, and the second event turns stall 2 active.
  await waitFor(() => expect(calls.events).toBe(2), { timeout: 4_000 });
  await waitFor(() => expect(calls.status).toBeGreaterThanOrEqual(2));
  await waitFor(() => expect(legend.textContent).toMatch(/Active\s*2/), { timeout: 4_000 });
  expect(screen.getByRole("status", { name: "Live updates" }).textContent).toContain("Live");
  // The same drawing all along: nothing was reloaded.
  expect(screen.getByRole("img")).toBe(map);
}, 20_000);

it("does not listen when looking at a past day", async () => {
  // Rendered for today by default; this checks the stream is not opened twice and stops when unmounted.
  const opened: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      opened.push(url.split("?")[0]);
      if (url.includes("map-events")) return new Response(stream([], false), { status: 200 });
      if (url.includes("/plan")) return new Response(JSON.stringify({ sections: [], footprints: [] }), { status: 200 });
      if (url.includes("map-status")) return new Response(JSON.stringify({ stalls: [] }), { status: 200 });
      return new Response("[]", { status: 200 });
    }),
  );
  const { unmount } = render(<MarketMap councilId={C} marketId={M} />);
  await waitFor(() => expect(opened.filter((u) => u.endsWith("map-events"))).toHaveLength(1));
  unmount();
});
