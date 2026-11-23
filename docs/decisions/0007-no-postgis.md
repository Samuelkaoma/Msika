# 0007: Plain Postgres for plan geometry, no PostGIS for now

Status: Accepted (card M-48)

## Context
PostGIS is the standard spatial extension for Postgres. Our geometry is small: is a polygon valid, do two footprints
overlap, what is an area, and later a similarity transform and a least-squares adjustment. All of it is per market,
a few thousand polygons at most.

## Decision
Geometry runs in Java, in `zm.msika.core.map`, with no outside library, and is tested there. Postgres stores
polygons as `integer[]`. No PostGIS.

## Consequences
- One less extension to install, upgrade and secure on every server, and no second geometry model to keep in step.
- The checks are plain Java and easy to read and test.
- We would switch if the database itself must answer a spatial question across markets, such as "every stall within
  50 m of this drain in all markets". That would be a new decision record.
