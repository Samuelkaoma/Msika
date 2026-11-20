# M-17: SMS command parser

Card: `M-17` · Branch: `feature/M-17-sms-parser` · Depends on: M-01

## What we are building, in one paragraph

Traders and agents talk to Msika by text message. Before Msika can act on `DEP B114 450` it has to
understand it: which command, which stall, how much money. This card builds the piece that turns the
text into a typed command object, or says exactly what is wrong with it, without touching the database.
Everything people really type is handled: lower case, double spaces, `K450`, `1,200`, `12.50`, a full stop
at the end. Anything that could be a mistake about money (`DEP B114 450 500`) is refused, never guessed.

## New ideas in this lesson

- **Parser**
  - **What it is:** code that reads free text and produces structured data, or an error.
  - **Why we need it here:** every SMS command starts as a string. One careful parser means every later
    use case receives clean, typed input.
  - **Tiny example:** `"SALE 120"` becomes `new DeclareSale(12_000)` (ngwee).

- **Sealed interfaces and records (Java 21)**
  - **What it is:** `sealed interface SmsCommand permits ...` lists every kind of command, each a small
    immutable `record`. A `switch` over them must handle every case, or the code does not compile.
  - **Why we need it here:** when M-19 wires each command to its use case, forgetting one is a compile error,
    not a silent bug.
  - **Tiny example:**
    ```java
    sealed interface Shape permits Circle, Square {}
    record Circle(int r) implements Shape {}
    record Square(int side) implements Shape {}
    ```

- **A result type instead of exceptions**
  - **What it is:** `parse` returns `Parsed(command)` or `Failed(error, command)`. Bad input is normal for a
    parser, so it is a value, not an exception.
  - **Why we need it here:** the reply depends on the error (`SALE` with no amount gets a different hint
    from an unknown word), so the caller needs the reason as data.

- **Money without floating point**
  - **What it is:** `"12.5"` is split at the dot and turned into `12 * 100 + 50` ngwee with whole numbers.
  - **Why we need it here:** AGENTS.md rule 1. `Double.parseDouble("0.1") * 100` is not exactly 10.

## Where it sits in the architecture

`msika-core`, package `zm.msika.core.sms`: no Spring, no database, no clock (AGENTS.md rule 7). The
webhook (M-18) stores the message; M-19 calls this parser and sends the command to its use case.

## Step by step

1. Listed every command in `docs/SMS-PROTOCOL.md` and made one record per command in `SmsCommand`, with the
   audience (trader, agent, or anyone for `HELP`).
2. Wrote `SmsCommandParser.parse`: clean the text (spaces, a closing full stop), upper-case it, split on
   spaces, and switch on the first word.
3. Wrote the amount reader with a regular expression and integer arithmetic, with a configurable maximum
   (K50,000 by default).
4. Wrote a test for every example in the protocol file, then the amounts people type and the mistakes.

## Files touched

| File | What changed | Why |
|---|---|---|
| `msika-core/.../sms/SmsCommand.java` | new | one record per command |
| `msika-core/.../sms/SmsCommandParser.java` | new | text to command, or a reason |
| `msika-core/.../sms/SmsCommandParserTest.java` | new | every protocol example and edge case |

## How we know it works

`SmsCommandParserTest`:

- `ProtocolExamples`: every example in SMS-PROTOCOL.md, including the "same message" forms.
- `Amounts`: commas, `K` prefix, decimals, leading zeros, zero, negative, just over the limit, absurdly
  large (more digits than a `long` holds), things that are not numbers, and the configurable limit.
- `Mistakes`: empty, unknown, missing arguments, extra words, bad references (including the confusable
  `0`, `O`, `1`, `L`), bad stall codes, and tolerated punctuation and spaces.

## Rebuild it yourself

1. Write `parse("SALE 120")` returning a record. Add a test first.
2. Make `"sale  K1,200.5"` work. Do not use `double` anywhere.
3. Make `"SALE 120 tomatoes"` fail, and explain in a comment why failing is safer than ignoring.

## Mistakes to avoid

- Parsing money with `double` or `BigDecimal` (rule 1).
- Ignoring extra words: a mistyped deposit would be recorded with the wrong amount.
- Accepting `0` and `O` in references: people confuse them when reading an SMS.
