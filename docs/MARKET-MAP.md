# The market map

A council officer opens a market and sees every stall drawn where it stands. The map shows which
stalls are free, who trades at each one, who has paid, and who deposited today. They can search for
a trader and find their stall, look back at who was where on any date, allocate a free stall from
the map, and print a true-scale sheet for the market office wall.

This file is the design. The cards that build it are M-48 to M-60 in `docs/ROADMAP.md`. The rules in
`AGENTS.md` and `docs/ARCHITECTURE.md` §2 apply here too. This file adds rules of its own in §2.

---

## 1. The questions it answers

| Who | Question | Where the answer comes from |
|---|---|---|
| Council | Which stalls are free, and of what class? | Register (stall status) drawn on the plan |
| Council | Who trades where? Where is this trader? | Allocations drawn on the plan, plus search |
| Council | Which allocated stalls are not being used? | Allocations compared with deposit activity and with what surveyors saw |
| Council | Who is trading on space nobody allocated? | Survey observations of stalls with no plate or no allocation |
| Council | Is the register true to the ground? | The survey reconciliation report (§6.5) |
| Council | Who was at stall B-114 on 3 March? | Plan version and allocations valid on that date |
| Market master | Which stalls has the agent not reached tonight? | Live closing view (§8.4) |
| Applicant | Is there a free stall in Soweto market? | Public free-stalls page and the `FREE` SMS (§9), with no personal data |

This answers the problem the proposal starts from: 405 of more than 14,000 trading spaces allocated,
and councils unable to see which stalls are occupied or paid.

---

## 2. Rules the map rests on

These add to ARCHITECTURE §2. Breaking one is a bug.

1. **The register and the ground are two different records.** The register (stalls, allocations)
   says what the council decided. Observations (from surveys and agents) say what someone saw on a
   date. An observation never changes the register by itself. Differences are shown to a person, who
   decides. This is the same idea as keeping declared sales apart from verified ones.
2. **Every footprint says where it came from.** Each stall's shape carries a provenance
   (`GENERATED`, `TRACED`, `SURVEYED`, `STATED`) and, where it was measured, an uncertainty in
   millimetres. The map draws them differently, so nobody mistakes a generated layout for a survey.
3. **A stall with no footprint is unmapped, not missing.** The map lists unmapped stalls beside the
   drawing with a count. A plan that covers 900 of 1,000 stalls says so on screen.
4. **Topology is kept apart from geometry.** Which section, which aisle, which side and in what order
   is often known for certain (from scanning plates in order) when the position in metres is not. A
   stall can have a sure address and an uncertain shape. Both are stored.
5. **Lengths are whole millimetres in a flat plane local to the market.** `int` in Java, `integer` in
   Postgres, the same habit as money in ngwee. Latitude and longitude appear only at the edge, through
   a georeference (§5.3), never as the stored shape.
6. **A published plan never changes.** Changes make a new version. History works because old
   versions and old allocations are both kept. A plan that improves with every walk (§6.6) does so
   by new versions, never by editing a published one.
7. **No photos of people.** Surveys record stall positions and states, not pictures. If a photo
   option is ever added, it needs a data-protection decision first (M-43).
8. **The figures we quote are the figures we measured.** Any accuracy claim for the survey says
   whether it came from the simulation or from a real phone in a real market, and how many runs.

---

## 3. Three levels of "where"

A stall can be known at three levels. Each level is useful on its own, and the map uses the best one
it has for each stall.

| Level | What is known | Where it comes from | What the map can draw |
|---|---|---|---|
| **Address** | Section, aisle, side (left or right), position along the aisle | Stall code (`SOW-B-114`), or plates scanned in walking order | A schematic: rows of equal boxes along straight aisles |
| **Shape** | A polygon in the market's plane, with an uncertainty | A survey walk, tracing over a plan image, or typed dimensions | The stall drawn to scale where it is |
| **Place on Earth** | The market's plane tied to latitude and longitude | Control points (§5.3) | The market on a city map; GeoJSON for the council's GIS |

A generated plan (address only) is enough for the first demo and for most daily questions ("is B-114
free, and who is next to it?"). Measured shapes matter for the printed sheet, for finding structures
nobody registered, and for planning new stalls.

---

## 4. The plan

A **market plan** is one version of one market's drawing. It holds:

- **Sections**: named areas (`B`, "Vegetables", "Second-hand clothes") as polygons.
- **Aisles**: named walkways as polylines. Stalls face them.
- **Footprints**: one per mapped stall: polygon, provenance, uncertainty, and the stall's address
  (aisle, side, position).
- **Features**: things that help people find their way: gates, toilets, taps, the market office,
  drains. Each a point or polygon with a kind and a label.
- **Background** (optional): an image (a scanned council drawing, a photo of a paper plan, a drone
  or satellite image) with the transform from image pixels to plan millimetres and how its scale was
  set.
- **Control points** (optional): plan positions with known latitude and longitude.

A plan is `DRAFT` until published, then `PUBLISHED`, then `RETIRED` when a newer one is published. Only
drafts can be edited. Publishing checks the draft and **reports every problem at once**, rather than
one per attempt:

- a polygon that crosses itself, has fewer than three corners or no area;
- two footprints that overlap by more than a tolerance (default 50 mm, because a measured shared wall
  between two stalls comes out slightly overlapped as often as slightly apart);
- a footprint for a stall that does not exist in that market, or two footprints for one stall;
- a stall of the market with no footprint, unless the draft lists it as deliberately unmapped;
- two stalls with the same address.

---

## 5. How a plan gets made

Four ways, which can be mixed in one plan. Each footprint keeps its own provenance.

### 5.1 Generated (provenance `GENERATED`)

From the stall codes alone. `SOW-B-114` is section `B`, number `114`. A small configuration per
market says how numbers run (for example 1 to 60 on the left of aisle 1, 61 to 120 on the right, then
aisle 2), a standard stall size per class, and the aisle width. The generator lays out sections as
blocks of aisles. The result is a schematic, honest about being one: drawn with dashed outlines and
labelled "Layout generated from stall numbers, not measured".

This is what the first demo uses, and what any market gets on day one.

### 5.2 Traced (provenance `TRACED`)

A council officer uploads a background (a scan of the market's drawing, or a phone photo of a paper
plan taken square-on), sets its scale by clicking both ends of a known length and typing it (two
gates 40 m apart, say), and then draws footprints over it. The editor has a tool for "a row of N
stalls of this size along this line", because markets are built in rows. The uncertainty recorded is
the scale's uncertainty times the distance from the reference line, plus half a pixel.

### 5.3 Georeferenced (the plan's place on Earth)

Two or more control points tie the plan to latitude and longitude: points whose plan position is
known and whose position on Earth comes from a phone GPS reading (good to about 5 m in the open) or
from a recognisable spot on satellite imagery. With two points the plan is placed by a similarity
transform (move, turn, scale). With three or more it is a best fit, and the residual at each point is
shown, so a bad point is visible. Fewer than two points: no place on Earth, and the city map shows the
market as a pin at its typed address instead.

### 5.4 Surveyed (provenance `SURVEYED`): the market walk

The measured way. A surveyor (a council officer, or an agent before trading starts) walks the market
with a phone. §6 describes it.

### 5.5 Stated (provenance `STATED`)

Dimensions typed in by hand, for example from a lease ("3 m by 2 m, at the corner of aisle 4").
Used rarely, recorded honestly.

---

## 6. The market walk

### 6.1 Where it comes from

Samuel's SKACE Architect project already has a phone survey kit, `capture-kit`, built for walking
through a house and producing a floor plan of its real rooms. Its README already names "walk through
a market" as one configuration of the same session. It is plain JavaScript modules with no
dependencies and has been tested in simulation: in tracked mode, a corner after the best fit of the
whole plan is out by 0.14 m at the median and 0.24 m at the 90th percentile, over 100 simulated walks
of an 8 m house. **That is a simulation of a house, not a measurement of a market.** It tells us the
method can work; it does not tell us how well it works in Soweto market.

We **copy** the modules we need into `apps/market-survey/src/kit/`, record the SKACE Architect commit
they came from (`c39ccb5`) in `apps/market-survey/SOURCE.md`, bring their tests with them, and adapt
them freely after that. Probably needed: `aim.js`, `tracked.js`, `source.js`, `xr.js`, `quality.js`,
`motion.js`, `messages.js`, `zip.js`, `raster.js`, and `plan.js` for the live drawing. Not needed:
`room.js` and `house.js` (rooms joined through doors), which the market walk replaces with aisles.

### 6.2 What the surveyor does

The walk is a state machine like `walk.js`, with the phone always saying what to do next.

1. **Start at an anchor.** A gate or a marked corner. If the phone has a GPS fix in the open, it is
   recorded as a control point (§5.3).
2. **Walk an aisle.** The phone asks the aisle's name, then leads the surveyor along it.
3. **At each stall:**
   1. **Scan the stall's QR plate.** This ties the reading to the stall's identity, which is the thing
      no photo method can do. A plate whose code the register does not know is recorded as such.
   2. **Mark its front corners** on the ground where the stall meets the aisle (two readings). The
      side (left or right) comes from the corners' position relative to the walking direction.
   3. **Depth:** mark a back corner if it is visible, or accept the class's standard depth, recorded
      as assumed.
   4. **Say what is there:** occupied (trading or set up), empty structure, no structure, or a
      structure with no plate. One tap.
4. **A stall with no plate** gets the same corner readings and becomes an *unregistered structure*
   in the survey, with no stall identity.
5. **End the aisle** at an anchor or at a point already surveyed (another aisle's end). This closes
   a loop, and the mismatch is how drift is measured and spread back along the aisle.
6. **Topology-only mode.** Scan the plates in walking order and tap the state, with no corner
   readings. About 3 seconds a stall instead of about 20. It gives the address level (aisle, side,
   order) for certain and no shapes. This is the mode for iPhones (no WebXR AR in Safari) and for a
   first pass over a big market.
7. **Patrol mode.** The officer's normal inspection walk with the app open: scan plates when passing,
   nothing else asked. It adds low-weight evidence that improves the map over time (§6.6).

Readings are taken the two ways capture-kit supports: **tracked** (Android Chrome with WebXR hit-test,
preferred) or **compass and tilt** (the fallback). Each reading is accepted only when the phone is
steady and the point is close enough to be measured well, using the kit's existing gates. The survey
is stored on the phone as it goes (the market may have no signal) and uploaded as one JSON file with
its reading log.

### 6.3 Turning readings into a plan

The server, not the phone, does the solving, so it can be tested in Java:

- Each aisle's readings are a chain from its start anchor. The closing mismatch at the end anchor is
  spread back along the chain in proportion to distance walked.
- Aisles that meet are joined at their shared points. Where a market has loops (a ring of aisles),
  the whole network is adjusted together by least squares, and each point's uncertainty comes out of
  that adjustment.
- Footprint corners on a shared side between neighbouring stalls are snapped together when they are
  within their uncertainties, so the drawing has no slivers and no overlaps that are only noise.
- Stalls along one aisle are squared to the aisle's line when every stall is within a tolerance of
  it, as `room.js` squares rooms. Stalls that are not get left exactly as measured and flagged.

### 6.4 How accurate it must be

Stalls are about 1.5 m to 3 m wide. The map is useful when no stall can be confused with its
neighbour:

- **Address:** 100% right. Order along the aisle comes from the order plates were scanned, so this
  is a matter of the walk being done properly, and the report flags anything odd (a plate scanned
  twice, two plates far out of numbering order).
- **Shape:** within one section, a stall corner within 0.75 m (half the narrowest stall) at the 95th
  percentile, after the loop adjustment.
- **Whole market:** reported, not required. Drift over hundreds of metres is the method's known weak
  point (capture-kit's own README says so), and the loop closures are what control it.

Card M-52 builds a **simulated market and a simulated surveyor** first, in the style of capture-kit's
simulated house: made-up sections, aisles and stalls, a walker with noise in each reading, drift along
the path, and 100 seeded runs. It reports median, 90th, 95th percentile and worst error for stall
corners, stall widths and aisle lengths, for tracked, compass-and-tilt and topology-only modes, with
and without loop closure. With zero noise it must reproduce the market to within 2 cm, which proves
the errors belong to the method and not to bugs. Only after that does anyone walk a real market, and
the real-market figures are published separately with how many stalls were checked against a tape.

### 6.5 The reconciliation report

The point of surveying is comparing the ground with the register. After each survey the server
produces a report for the council, every item linking to the stall on the map:

| Finding | Meaning | What a person might do |
|---|---|---|
| Register stall not seen | Its plate was never scanned in a section that was fully walked | Check whether the stall exists or lost its plate |
| Unknown plate | A scanned code the register does not have | A forged or old plate |
| Same plate in two places | One code scanned at two positions far apart | A moved or copied plate |
| Unregistered structure | A stall with no plate | Register it, then it can be allocated |
| Allocated, found empty | Allocated stall with no structure or empty on the survey day | Follow up with the trader; repeated, consider freeing it |
| Vacant, found occupied | Someone trading at a stall the register says is free | Allocate it to them, or enforce |
| Overlap | Two footprints overlapping beyond uncertainty | Re-survey or correct by hand |

The report is evidence with a date and a surveyor. It changes nothing in the register (rule 1). An
officer acts on each item, and the action is audited.

### 6.6 The map gets better with every walk

One survey gives one noisy drawing. The map should not stop there. Every time an officer or agent
walks the market with the app (a full survey, a routine patrol, or a closing round), what they
record is kept as **evidence**, and the plan is re-estimated from all the evidence so far. A stall
walked past ten times is drawn better than one walked past once, and the map shows which is which.

**Nothing is thrown away.** Each walk keeps its raw readings (every corner mark, every plate scan,
and the path the phone travelled, all in that walk's own frame) with their uncertainties. A footprint
on the plan is a *result worked out from* readings, not a reading itself. That is what lets the
estimate improve, and it means the whole plan can be worked out again from scratch if the method
improves.

**Stalls already on the map anchor each new walk.** A phone's tracking drifts, so a new walk does not
line up with the plan by itself. But every plate the walker scans is a stall whose position the plan
already knows, with an uncertainty. Those stalls are tie points: the walk is fitted onto the plan
through them (a best fit weighted by how certain each one is, with outliers left out), which removes
most of the walk's drift. This is better than loop closure alone, because a busy market has
hundreds of known points and a gate has only one. The first walk of a market has no tie points
and relies on anchors and loops (§6.3). Every walk after it has plenty.

**Estimates combine by weight.** After a walk is fitted, each stall corner is re-estimated from all
the readings of it, each weighted by one over its variance. With *n* readings of similar quality the
uncertainty falls roughly as one over the square root of *n*: four walks halve it. The whole network
(corners, shared stall sides, aisle lines, anchors) is adjusted together, as in §6.3, so improving one
stall also tightens its neighbours.

**Old evidence counts for less.** Markets change: stalls are rebuilt, widened, moved. Each reading's
weight halves after a set time (default 180 days, configurable per market), so the plan follows the
market as it is now. A stall nobody has walked past for a long time shows its uncertainty growing on
the map, which is the honest signal that it needs a visit.

**A change is not noise.** When new readings of a stall keep disagreeing with its estimate by more
than their uncertainties allow (three walks in a row beyond three standard deviations, say), the
stall is flagged *possibly changed* rather than quietly averaged into a wrong shape halfway between
old and new. An officer looks and either confirms the change (evidence from before the change date is
then set aside for that stall) or marks the readings as bad. A plate found in a new place goes the
same way, through the "same plate in two places" finding in §6.5.

**The walked paths draw the aisles.** The phone's path is recorded on every walk. Paths from many
walks show where people can actually walk: the middle of the bundle of paths is the aisle's line, and
the stall fronts on either side give its width. Aisles drawn this way replace generated ones as the
evidence builds up. Places nobody has ever walked are shown as "never walked", not as empty space.

**Patrol mode records evidence without a survey.** A full survey asks for corner marks at every
stall. Patrol mode asks for nothing extra: the officer walks their normal inspection with the app
open, scans plates as they pass, and the phone records its path. That gives topology for certain,
rough positions for every scanned stall, and path evidence for the aisles, all at low weight. When
the agent app (M-40) scans a plate for a deposit, it adds the same kind of evidence on every closing
round. Many low-weight readings add up.

**The app says where to walk next.** The survey and patrol modes can show the stalls with the highest
uncertainty, the oldest evidence, open *possibly changed* flags and never-walked aisles, and lead the
walker to them, the way capture-kit's interior walk leads a person to the next unvisited room. Effort
goes where it improves the map most.

**Better estimates become new plan versions, not edits.** Published plans still never change
(rule 6). After each walk the server works out a *proposed* version and shows what it would change:
which stalls moved and by how much, how much each uncertainty shrank, any new or changed aisles, and
any topology change. A council can let proposals publish themselves when they are refinements only:
every stall moved by less than its previous uncertainty, nothing added, removed or reordered, and no
open *possibly changed* flags. Anything else waits for an officer to publish it. Either way the
version history shows the plan sharpening over time.

**How much better it gets is measured, not assumed.** The survey simulation (M-52) runs repeated
walks of the same simulated market, including patrols and a stall that is moved halfway through, and
reports the corner error after 1, 2, 5 and 10 walks, and how many walks it takes to flag the moved
stall. The target: after five walks of the same quality, the 95th percentile corner error is at most
about half what it was after one; and a stall moved by 1 m is flagged within three walks with no
unchanged stall flagged in the same run. If the simulation does not reach those numbers, its note
says so and by how much.

---

## 7. What a stall's colour means

A stall's state on the map is worked out, not stored, from the register and recent activity:

| State | Rule | Drawn |
|---|---|---|
| Free | Status `VACANT` | Light fill |
| Active | Allocated, and a deposit or verified sale within the last 7 days | Solid fill |
| Quiet | Allocated, nothing in 7 days (configurable) | Solid fill with stripes |
| Suspended | Status `SUSPENDED` | Grey with a cross |
| Unregistered | Seen by a survey, no plate, not in the register | Outline only, dotted |

Other layers recolour the same shapes: **levy** (paid up, 1 to 7 days owed, more than 7 days owed),
**today** (deposited today, not yet), **flags** (disputes and Phase 7 anomalies). Every layer uses a
pattern as well as a colour, so it reads in black-and-white print and for colour-blind users, and the
legend shows the count in each state. Generated footprints are drawn with dashed outlines in every
layer (rule 2).

---

## 8. The views

### 8.1 Market map

The main view in `council-web`. The plan drawn as SVG, true to scale, with pan and zoom; stall codes
appear once zoomed in far enough to read them. Layer switcher, legend with counts, filters (class,
section, state), and the unmapped-stalls list. Clicking a stall opens its panel.

### 8.2 Stall panel

Code, class, section and address; the trader (name, masked phone, allocated since); today's deposits
and the last 7 days as a small bar row; levy status; open disputes; the last survey observation with
its date; the history of who held the stall; and, for admins, actions: allocate, free, suspend, print
plate.

### 8.3 Search ("who is where")

One box: trader name, phone number, or stall code. Results highlight on the map, and the map pans to
them. A trader with more than one stall shows all of them.

### 8.4 Live closing view

During the closing round the map updates as deposits are confirmed (server-sent events), so stalls
change colour one by one. Choosing an agent shows their round: the stalls they have deposited for
tonight and the allocated stalls in their aisles they have not reached yet. This is also the
strongest moment of the demo.

### 8.5 History

A date control redraws the map as it was on that date: the plan version in force, the allocations
valid then, and the states worked out from activity up to then.

### 8.6 Print sheet

A3 or A4, at a true scale (1:200 or 1:500), with a title block (council, market, plan version, date
printed, provenance summary), legend with counts, north arrow if georeferenced, and scale bar. The
arithmetic for true-scale printing follows SKACE Architect's `sheet.ts` (pixels per millimetre at
96 dpi, standard scales, margins). The register version prints a list of stalls with state and
trader beside the drawing for the market office wall.

### 8.7 City overview

All of a council's markets on an OpenStreetMap base map, each drawn as a circle sized by its number
of stalls and split by state. Clicking opens the market map. A market without a georeference is
placed at its typed location and marked "approximate". We follow the OpenStreetMap tile usage policy
(attribution shown, no bulk downloading, low volume); if usage grows, we switch to a paid tile
provider or host our own tiles.

### 8.8 Phones

At phone width, the market map becomes a list grouped by section and aisle with the same states and
filters, and a "show on map" button for one stall. A drawing of 2,000 stalls is not usable on a
small screen, and pretending otherwise helps nobody.

---

## 9. Allocation and the waiting list

- An applicant joins the waiting list by SMS (`APPLY SOW B`: market, and stall class if they care)
  or at the market office. The list is first come, first served within each class, and the applicant
  can ask their place with `PLACE`.
- An officer selects free stalls on the map and allocates them to the next applicants (or anyone,
  with a reason recorded). Each allocation sends the trader an SMS and goes in the audit log.
- `FREE SOW` by SMS replies with the number of free stalls by class. A public page shows the same,
  per market, with no personal data at all: codes, class and section only.
- The new SMS commands go into `docs/SMS-PROTOCOL.md` in the card that builds them (M-58).

---

## 10. Data model additions

All tables have `council_id` and row-level security. Lengths are `integer` millimetres. Polygons are
`integer[]` holding `x1, y1, x2, y2, ...` in plan millimetres, closed implicitly, counter-clockwise.

| Table | Key columns |
|---|---|
| `market_plan` | id, council_id, market_id, version, status (DRAFT, PUBLISHED, RETIRED), published_at, published_by, unmapped_stall_ids, georef (transform and residuals, null if none), notes |
| `plan_section` | id, council_id, plan_id, name, polygon_mm |
| `plan_aisle` | id, council_id, plan_id, name, path_mm (polyline) |
| `plan_feature` | id, council_id, plan_id, kind (GATE, TOILET, WATER, OFFICE, DRAIN, OTHER), label, shape_mm |
| `stall_footprint` | council_id, plan_id, stall_id, polygon_mm, provenance, uncertainty_mm (null when not measured), aisle_id, side (L, R), position, evidence_count, last_evidence_at; unique (plan_id, stall_id) and unique (plan_id, aisle_id, side, position) |
| `plan_background` | council_id, plan_id, file key, pixel-to-plan transform, scale source and uncertainty |
| `control_point` | id, council_id, plan_id, x_mm, y_mm, lat_e7, lon_e7 (degrees times 10⁷ as `integer`), source (GPS, IMAGERY), accuracy_mm |
| `survey` | id, council_id, market_id, surveyor (council user or agent), kind (FULL, PATROL, ROUND), method (TRACKED, COMPASS, TOPOLOGY_ONLY), started_at, finished_at, raw file key, fit to plan (transform, tie points used and rejected, residuals), report (JSON) |
| `survey_observation` | id, council_id, survey_id, stall_id (null for unregistered), scanned_code, state (OCCUPIED, EMPTY, NO_STRUCTURE, NO_PLATE), observed_at, polygon_mm, uncertainty_mm |
| `survey_reading` | id, council_id, survey_id, stall_id (null for unregistered), corner (FRONT_LEFT, FRONT_RIGHT, BACK_LEFT, BACK_RIGHT, or null for a plate scan), x_mm and y_mm in the walk's own frame, sigma_mm, taken_at, status (USED, OUTLIER, SET_ASIDE) |
| `survey_path` | council_id, survey_id, path_mm (the phone's travelled path in the walk's own frame, thinned to one point per 0.5 m), sigma_mm |
| `stall_change_flag` | id, council_id, stall_id, raised_at, evidence (survey ids), status (OPEN, CONFIRMED_CHANGE, BAD_READINGS), decided_by, change_date |
| `plan_proposal` | id, council_id, market_id, based_on_plan_id, created_at, trigger_survey_id, diff (JSON: moved stalls and by how much, uncertainty changes, aisle and topology changes), refinement_only (bool), status (OPEN, PUBLISHED, DISCARDED) |
| `allocation_application` | id, council_id, market_id, msisdn, stall_class, applied_at, status (WAITING, OFFERED, ALLOCATED, WITHDRAWN) |

A database trigger refuses changes to a `PUBLISHED` or `RETIRED` plan and to its sections, aisles,
features and footprints, as the ledger's trigger does.

Plain Postgres, no PostGIS. The geometry we need (point in polygon, overlap, area, a similarity
transform, a least-squares adjustment) is small, runs in Java, and is tested there. A decision record
says so, and what would make us switch: a spatial query the database must answer, such as "every
stall within 50 m of this drain across all markets".

In Java the geometry lives in `zm.msika.core.map` with no outside library: `PointMm`, `PolygonMm`,
`Footprint`, `Provenance`, `StallAddress`, `MarketPlan`, `PlanCheck` (the publish checks),
`SimilarityTransform`, `NetworkAdjustment`, `TiePointFit` (fitting a walk onto the plan),
`EvidenceFusion` (weights, ageing, outliers) and `ChangeDetector`. If a robust polygon library turns out to be needed, JTS
is the candidate, and adding it needs a decision record because `msika-core` takes no dependencies
lightly.

---

## 11. API

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/markets/{id}/plan?version=current` | The plan: sections, aisles, features, footprints. Cached by version with an `ETag`, because it rarely changes |
| `GET /api/v1/markets/{id}/map-status?date=` | One small record per stall: state, trader display name (by role), levy band, deposited today, flags. Kept separate from the plan so polling is cheap |
| `GET /api/v1/markets/{id}/map-events` | Server-sent events for the live closing view |
| `GET /api/v1/stalls/{id}/panel` | Everything the stall panel shows |
| `GET /api/v1/search?q=` | Traders and stalls matching a name, phone or code |
| `POST /api/v1/markets/{id}/plans` | Start a draft (empty, generated, or a copy of the current plan) |
| `PUT /api/v1/plans/{id}/...` | Edit a draft's sections, aisles, features, footprints, background, control points |
| `POST /api/v1/plans/{id}/publish` | Publish, or `422` with every blocker listed |
| `POST /api/v1/markets/{id}/surveys` | Upload a survey file. Returns the reconciliation report |
| `POST /api/v1/surveys/{id}/apply` | Put a survey's footprints into a draft plan |
| `GET /api/v1/markets/{id}/proposals` | Proposed plan versions worked out from new evidence, each with its diff |
| `POST /api/v1/proposals/{id}/publish` | Publish a proposal as the next plan version (also done automatically for refinement-only proposals where the council allows it) |
| `POST /api/v1/stall-change-flags/{id}/decide` | Confirm a change (with its date) or mark the readings as bad |
| `GET /api/v1/markets/{id}/walk-next` | The stalls and aisles where a walk would improve the map most |
| `GET /api/v1/plans/{id}/geojson` | Export for a GIS, only for georeferenced plans |
| `GET /api/v1/councils/current/markets-overview` | The city overview |
| `GET /public/v1/markets/{id}/free-stalls` | No sign-in, no personal data |

The status endpoint must answer for a 5,000-stall market in under 300 ms on a laptop, with one query.

---

## 12. How it is drawn

- **Market plans: plain SVG in React**, no map library, because a market plan is a drawing in metres,
  not a map in degrees, and SKACE Architect's web workspace already draws plans this way. Pan and zoom
  change the `viewBox`.
- **Performance:** a 5,000-stall market must first draw in under one second and pan smoothly on an
  ordinary laptop. If one SVG element per stall is too slow, the fallback is one `<path>` per state
  for drawing, with a grid index on the client for finding the clicked stall. A test with a generated
  5,000-stall market measures it.
- **City overview:** Leaflet with OpenStreetMap tiles (§8.7).
- **The survey app** (`apps/market-survey`) is a phone-first page built with Vite. It must be served
  over HTTPS to use the camera and WebXR; capture-kit's README explains the ways round that during
  development (USB port forwarding on Android, or a tunnel).

---

## 13. Honest limits

- **Nothing about the survey's accuracy in a real market is known yet.** The capture-kit figures
  are a simulated house. Markets add crowds moving through the camera's view, tarpaulin roofs,
  uneven ground and repeating structures. The real figures come from M-53's field test.
- **WebXR AR does not exist in iPhone Safari.** iPhone surveyors get compass and tilt (worse) or
  topology-only mode.
- **Drift grows with distance walked.** A long aisle without a loop closure will bend. The walk
  must end aisles at anchors, and the report shows each loop's mismatch. Once a market has a plan,
  scanned stalls act as tie points and take out most of the drift (§6.6).
- **Averaging only helps against random error.** If every phone of one model is off in the same
  direction, a hundred walks with it are still off. Walks with different phones and methods, and the
  tape-measure checks from M-53, are what catch a shared bias.
- **A whole section rebuilt at once fools the tie points.** If most stalls in an area move together,
  the fit may line the walk up with the old layout and see nothing as changed. The fit reports how
  many tie points it rejected; when that is a large share, the walk is not used for refinement and
  the area is flagged for a full survey.
- **Phone GPS is good to about 5 m in the open and worse among roofs.** It places the market on the
  city map; it cannot place stalls.
- **A plate scanned is not a plate in the right place.** A plate moved to another stall is caught
  only if its old position is also known (the "same plate in two places" finding).
- **The quiet state is a guess about inactivity.** A trader who sells but never deposits looks quiet.
  The map says "no deposits in 7 days", not "abandoned".

---

## 14. Decisions to record

Each gets a short record in `docs/decisions/` in the card that first needs it:

- Plan geometry in integer millimetres on a flat plane local to each market (M-48).
- Plain Postgres for geometry, no PostGIS for now (M-48).
- Plans drawn as SVG without a map library; Leaflet only for the city view (M-50).
- Survey modules copied from SKACE Architect's capture-kit, not shared as a package (M-52).
- Survey observations are evidence and never change the register by themselves (M-54).
- Footprints are estimates worked out from all walks' readings, kept raw, weighted, aged and
  re-estimated; better estimates arrive as new plan versions (M-59).
