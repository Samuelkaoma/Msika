# M-48: Market plans and generated layouts

Card: `M-48` · Branch: `feature/M-48-market-plans` · Depends on: M-08, M-10

## What we are building, in one paragraph

The market map starts here. A **plan** is one version of a market's drawing: sections, aisles, features (gates,
toilets, taps) and each stall's footprint with its address (aisle, side, position) and where its shape came from.
On day one nobody has measured anything, so Msika can **generate** a schematic plan from the stall codes alone:
`SOW-B-114` is section B, number 114, laid out along aisles by a per-market configuration. Before a draft is
published it is checked, and every problem is listed at once. Once published, a plan never changes: the database
refuses.

## New ideas in this lesson

- **Geometry in whole millimetres** (decision 0006): a polygon is `[x1, y1, x2, y2, ...]` of integers, like money in
  ngwee. Area uses the shoelace formula on `long`.
- **Polygon validity**: at least three corners, no self-crossing edges (each pair of non-neighbouring edges is
  tested), and some area.
- **Overlap with a tolerance**: measured neighbours sharing a wall come out a few centimetres overlapped, so an
  overlap counts only beyond 50 mm. The overlap is the narrower side of the box around the shared area, found by
  clipping one polygon with the other (Sutherland-Hodgman).
- **Report every blocker at once**: `PlanCheck` collects all problems instead of stopping at the first.
- **Freezing at the database**: triggers refuse any change to a published plan and its contents, as the ledger's do.
- **No PostGIS** (decision 0007): the geometry is small and lives in tested Java.

## How we know it works

- `PlanGeometryTest` (core): valid polygons, a bow tie, too few corners, no area, orientation; overlap with and
  without the 50 mm tolerance (40 mm passes, 60 mm blocks); a draft with four different problems lists all four;
  duplicate stalls and addresses; a 300-stall generated plan with every stall placed, no overlap (not even with zero
  tolerance), no duplicate addresses, each footprint inside its section; numeric ordering.
- `PlanApiTest`: generate and publish a 300-stall market through the API; 422 with all four blockers; the API (409)
  and the database refuse an `UPDATE` or `DELETE` on a published plan's footprints and on the plan itself; versions
  retire; officers read but cannot start a draft; another council cannot see it.

## Mistakes to avoid

- Storing latitude and longitude as the shape.
- Stopping at the first problem: whoever fixes the plan needs the whole list.
- Allowing "just a small fix" to a published plan: history only works if old versions never change.
- Reading stale test reports: always clear old results before rerunning, or a test that did not compile looks green.
