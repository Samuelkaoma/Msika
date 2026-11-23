# 0006: Market plans are integer millimetres on a flat plane local to each market

Status: Accepted (card M-48)

## Context
The market map needs stall shapes. Latitude and longitude are awkward for this: tiny decimals, a curved surface,
and floating-point rounding that makes "the same point" slightly different each time it is stored. A market is at
most a few hundred metres across, where the Earth's curve does not matter.

## Decision
Every length and coordinate on a plan is a whole number of millimetres (`int` in Java, `integer` in Postgres) in the
market's own flat plane, x to the right and y up. Polygons are stored as `integer[]` of `x1, y1, x2, y2, ...`,
counter-clockwise. Arithmetic on them uses `long`. Latitude and longitude appear only at the edge, through a
georeference (card M-55), never as the stored shape. It is the same habit as money in whole ngwee (0003).

## Consequences
- Equality and storage are exact; a published plan reads back exactly as it was written.
- 1 mm is far finer than any measurement we can make, so nothing is lost.
- Showing a plan on a city map needs a transform (M-55); that is the only place degrees appear.
