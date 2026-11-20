# 0003: Money is a `long` number of ngwee

Status: Accepted

## Context
Floating point numbers cannot represent most decimal amounts exactly. `0.1 + 0.2` is not `0.3`.
In a ledger a one-ngwee error is a bug, and errors add up. `BigDecimal` is exact but easy to misuse
(scale, rounding mode) and awkward to store and compare.

## Decision
Every stored amount is a whole number of ngwee (1/100 of a kwacha): K12.50 is `1250`. It is a `long`
in Java (through the `Money` type in `msika-ledger`) and a `bigint` in Postgres. `double`, `float`
and `BigDecimal` are never used for stored amounts. `Money` refuses to mix currencies and fails on
overflow instead of wrapping around.

## Consequences
- Addition, comparison and equality are exact and cheap.
- Conversion to kwacha happens only at the edges: parsing an SMS amount and formatting a reply.
- Anything that needs a fraction of an ngwee (percentages, averages) must decide its rounding rule
  explicitly and in one place.
- The range of `long` (about 92 quadrillion kwacha in ngwee) is far beyond any real amount here.
