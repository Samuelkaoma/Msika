# M-10: Traders, agents and stall QR plates

Card: `M-10` · Branch: `feature/M-10-traders` · Depends on: M-07

## What we are building, in one paragraph

The people and the paper. A council registers its **traders** and its deposit **agents** by phone number,
and prints a **plate** for every stall: an A6 page with the stall code in big letters and a QR code an
agent can scan later. Phone numbers are personal data, so this card is mostly about handling them
carefully: one canonical form, checked by the code and again by the database, and never written to a log
in full.

## New ideas in this lesson

### Normalisation: one value, many spellings
- **What it is:** people write the same number as `0977 000 739`, `260977000739`, `+260977000739` or
  `(097) 7000-739`. We turn all of them into one canonical form, **E.164**: `+260977000739`.
- **Why here:** if we stored what people typed, the same trader could be registered five times and an
  incoming SMS from `+260977000739` would not match `0977000739`. Normalising once, at the edge, makes
  every later comparison a simple string equality.
- **Tiny example:**
  ```java
  PhoneNumber.parse("0977 000 739").e164()    // "+260977000739"
  PhoneNumber.parse("260977000739").e164()    // "+260977000739"
  ```
  A test checks that `0977...`, `260977...` and `+260977...` give the same number (the card's Done item).

### Data minimisation and masking (ARCHITECTURE.md rule 8)
- A phone number is **personal data**. We keep only what we need (number, name, language) and never put
  it in a log in full. In logs we show `0977***739` (first four, last three).
- **The safest design is to make the right thing automatic.** `PhoneNumber.toString()` returns the masked
  form. So even if a programmer writes `LOG.info("registered {}", trader)`, only the masked number can
  appear. A record that contains a phone number (a `Trader`, an `Agent`) prints it masked too, because it
  prints its fields with their own `toString()`. To get the full number you must call `.e164()` on purpose.
- The request records also override `toString()` so the number cannot appear if one is logged.
- The audit log stores ids, never numbers.
- A test captures **every log line the server writes** while people are registered and checks that no line
  contains the middle digits of either number.

### Defence in depth: the database checks too
The application normalises numbers, but a future bug could skip that. So the database has a `check`
constraint, `msisdn ~ '^\+260[79][0-9]{8}$'`, on both tables. A test inserts `0977000739`, `260977000739`,
a South African number and a too-short one straight into the database, and each is refused.

### Generating a PDF (PDFBox)
- **What it is:** Apache PDFBox is a Java library that builds PDF files. A PDF is a list of pages, and each
  page is a list of drawing instructions ("put this text here", "fill this rectangle").
- **A6:** 105 mm by 148 mm, a quarter of an A4 sheet. The page size in PDF units is 297.6 by 419.5 points
  (a point is 1/72 inch).
- **The port again:** `PlateRenderer` (in `msika-core`) says "give me plates, I give you PDF bytes".
  `PdfPlateRenderer` (in `msika-api`) is the adapter that uses PDFBox. The rule "msika-core imports no
  third-party framework" stays true, and the use case `GenerateMarketPlates` is tested with a fake renderer.

### QR codes (ZXing)
- **What it is:** a QR code is a grid of black and white squares ("modules") that encodes text. Part of the
  grid is **error correction**, so a code still reads when it is dirty or torn. We use level Q, which
  repairs up to about a quarter of the code. A market is a dirty place, so that matters.
- **What ours says:** `msika://stall/SOW-B-114?t=<token>`: the stall code, and the stall's **secret
  token** (random, 128 bits, from M-07). The token is the part that proves someone holds the printed
  plate. `StallPlate.parseQr` reads it back, and the agent app (M-40) will use it.
- **Drawn as vectors:** we draw each dark square as a rectangle in the PDF, not as a picture, so it stays
  razor sharp at any size. Neighbouring squares in a row become one rectangle, and each square is a whole
  number of points wide.
- **The token is a secret:** `StallPlate.toString()` deliberately omits it.

### Testing something you cannot see: read the QR code back
The strongest test here prints a market's plates, renders the PDF pages to images, and **decodes the QR
code with a scanner library**, comparing the result with the stall code and token in the database. That
proves the plate says the right thing, not merely that "a PDF came out".

## A debugging story worth reading

While testing, an occasional plate failed to decode. We did not shrug it off: a plate that cannot be
scanned is a real defect, and these would be printed in their hundreds.

1. We measured: about 1 in 300 plates failed, always the denser QR versions.
2. We changed the drawing (whole-point squares, merged runs, more clear space). Failures dropped but did
   not vanish.
3. We tested the **scanner itself**: ZXing was asked to read its *own* perfectly drawn images of the same
   texts. It failed on about 0.5% of those too, at every error-correction level and size.
4. So the fault was never our drawing. ZXing's reader is erratic on individual images. A second look
   showed it is erratic per **image**, not per text: the same code that fails at 150 dpi reads at 200 dpi.
5. A real scanner reads many video frames, so a printed code only needs to read in one frame. Our test now
   requires each page to read at one of four resolutions, **fails if any resolution reads the wrong text**,
   and fails if none reads. Tokens come from a fixed seed, so the test gives the same answer every run.
6. We then **broke the drawing on purpose** to be sure the test could fail. Shifting one row did not fail
   it: error correction repaired it, which is the point of error correction. Encoding the wrong text, and
   wrecking a band of rows beyond repair, both failed it.

**Lessons:** measure before guessing; test your test tool; a flaky test is a bug in the test or in the
product, never something to ignore; and check that a test can fail with a corruption big enough to matter.
(We also corrected a comment in the renderer that had claimed a wrong cause.)

## Where it sits in the architecture

```
msika-core   phone/ (PhoneNumber, Language)   trader/   agent/   plate/ (StallPlate, PlateRenderer, GenerateMarketPlates)
msika-api    admin/ (PeopleController, PeopleAdminService)   plate/ (PdfPlateRenderer)   persistence/ (Jdbc adapters)
database     V5: agent table + RLS policy, E.164 check on trader and agent
```
The `agent` table has a row-level security policy like every council table, and M-08's coverage test
checks it. Council officers can list people and print plates (read-only), admins can register people.

## Step by step

1. Branched from M-09, marked the card ◐. Looked up PDFBox 3.0.8 and ZXing 3.5.4.
2. **`PhoneNumber`** with `parse`, `masked`, and a masked `toString`; `Language`.
3. `Trader`, `Agent`, their repositories and `RegisterTrader` / `RegisterAgent`.
4. `StallPlate` (what is printed and what the QR says), the `PlateRenderer` port, `GenerateMarketPlates`.
   100 unit tests, including a parameterised test for phone formats.
5. **Migration `V5`:** the `agent` table with RLS, and the E.164 check constraints.
6. **Adapters and endpoints:** JDBC repositories, `PdfPlateRenderer`, `PeopleController`.
7. **Integration tests** through HTTP: normalisation, councils kept apart, officer read-only, the database
   check, masked logs, audit without numbers, and the PDF (page count, A6 size, printed text, QR read-back).
8. The QR investigation above, and its test.

## Files touched

| File | What changed | Why |
|---|---|---|
| `platform/msika-core/.../phone/*`, `trader/*`, `agent/*`, `plate/*` | New | Domain, use cases, ports |
| `platform/msika-api/.../db/migration/V5__agents_and_phone_format.sql` | New | Agents, E.164 check |
| `platform/msika-api/.../plate/PdfPlateRenderer.java` | New | Draws the PDF |
| `platform/msika-api/.../persistence/JdbcTraderRepository, JdbcAgentRepository` | New | Adapters |
| `platform/msika-api/.../admin/PeopleController, PeopleAdminService, CoreConfiguration` | New / extended | Endpoints and wiring |
| `platform/msika-api/build.gradle.kts` | PDFBox, ZXing (and ZXing javase for tests) | PDF and QR |
| tests | `PhoneNumberTest`, `PeopleAndPlatesTest`, `PeopleApiTest`, `QrScanabilityTest`, `QrTestSupport` | Proof |

## How we know it works

| Done item | Proof |
|---|---|
| Phone normalisation: `0977...`, `260977...`, `+260977...` give the same number | `PhoneNumberTest` (all three equal; 9 spellings; 16 refusals) and `PeopleApiTest.phoneNormalisationThroughTheApi` |
| Masking | `PhoneNumberTest` (`0977***739`, `toString` is masked) and `phoneNumbersAreMaskedInLogs` (every server log line) |
| A market's plates download as one PDF | `platesDownloadAsOnePdf`: one A6 page per stall with its code and market name, and the QR read back; `aWholeMarketOfPlates`: 300 stalls, 300 pages; `QrScanabilityTest`: all 300 read back |

## Rebuild it yourself

1. Write `PhoneNumber.parse` and make three spellings equal. Add a test for each refused input.
2. Override `toString()` to mask, and put the number in a record. Print the record.
3. Add a database `check` constraint and try to insert a bad value.
4. Use PDFBox to make a one-page PDF with a line of text, then draw a rectangle.
5. Use ZXing to encode text as a QR matrix, draw it as rectangles, render the PDF to an image and decode it.
6. Corrupt your drawing and check the test notices.

## Mistakes to avoid

- **Storing what the user typed.** Normalise at the edge, once.
- **Logging a whole request or object that contains a phone number.** Make `toString` safe by default.
- **Trusting only the application to validate.** Add the database constraint too.
- **Putting the secret token anywhere but the QR code.** It is hidden from `toString` and the audit log.
- **Ignoring a flaky test.** Find out whether the test or the product is wrong.
- **A test that passes when you corrupt the thing it tests.** Corrupt it harder, then restore it.

## Not done here (on purpose)

- **Deactivating an agent or editing a trader.** The card only registers them. Consent capture (the
  `YES` SMS) comes in M-19.
- **Matching an incoming SMS number to a council.** A number may belong to traders in more than one
  council; that decision belongs with the SMS cards (M-18, M-19).
