// How a stall is drawn on each layer (MARKET-MAP section 7). Every style has a pattern as well as a colour, so the
// map reads in black-and-white print and for colour-blind users, and a label for the legend.

import type { StallStatus } from "../api/schema";

export type Layer = "state" | "levy" | "today";
export type Pattern = "none" | "stripes" | "dots" | "cross";

export type Style = {
  /** Stable key: stalls with the same key are drawn as one path. */
  key: string;
  label: string;
  fill: string;
  pattern: Pattern;
};

const STATE: Record<string, Style> = {
  FREE: { key: "state-free", label: "Free", fill: "#e3ece6", pattern: "none" },
  ACTIVE: { key: "state-active", label: "Active", fill: "#1f8a5f", pattern: "none" },
  QUIET: { key: "state-quiet", label: "Quiet (no trade in 7 days)", fill: "#8fc9a8", pattern: "stripes" },
  SUSPENDED: { key: "state-suspended", label: "Suspended", fill: "#9aa3a0", pattern: "cross" },
};

const LEVY: Record<string, Style> = {
  PAID_UP: { key: "levy-paid", label: "Levy paid up", fill: "#1f8a5f", pattern: "none" },
  OWED_1_TO_7: { key: "levy-owed", label: "1 to 7 days owed", fill: "#e0a43a", pattern: "dots" },
  OWED_OVER_7: { key: "levy-over", label: "More than 7 days owed", fill: "#c8463c", pattern: "stripes" },
  NONE: { key: "levy-none", label: "Nobody holds it", fill: "#e3ece6", pattern: "none" },
};

const TODAY = {
  deposited: { key: "today-yes", label: "Deposited today", fill: "#2b6cb0", pattern: "none" } as Style,
  notYet: { key: "today-no", label: "Not yet today", fill: "#bcd3ea", pattern: "dots" } as Style,
  free: { key: "today-free", label: "Free", fill: "#e3ece6", pattern: "none" } as Style,
};

/** A stall the filters leave out: still drawn, faintly, so the market keeps its shape. */
export const FILTERED_OUT: Style = { key: "filtered", label: "Not in the filter", fill: "#f1f1ef", pattern: "none" };

/** A footprint with no status (a stall added since the plan): outlined only. */
export const UNKNOWN: Style = { key: "unknown", label: "No status", fill: "#ffffff", pattern: "none" };

export function styleFor(layer: Layer, s: StallStatus | undefined): Style {
  if (!s || !s.state) return UNKNOWN;
  switch (layer) {
    case "state":
      return STATE[s.state] ?? UNKNOWN;
    case "levy":
      return LEVY[s.levy ?? "NONE"] ?? UNKNOWN;
    case "today":
      if (s.state === "FREE") return TODAY.free;
      return (s.depositedTodayNgwee ?? 0) > 0 ? TODAY.deposited : TODAY.notYet;
  }
}

/** The styles a layer can use, in legend order. */
export function legendFor(layer: Layer): Style[] {
  switch (layer) {
    case "state":
      return Object.values(STATE);
    case "levy":
      return Object.values(LEVY);
    case "today":
      return [TODAY.deposited, TODAY.notYet, TODAY.free];
  }
}

/** Generated footprints are drawn dashed in every layer: they are a schematic, not a measurement (rule 2). */
export function outlineFor(provenance: string | undefined): "dashed" | "solid" {
  return provenance === "GENERATED" ? "dashed" : "solid";
}
