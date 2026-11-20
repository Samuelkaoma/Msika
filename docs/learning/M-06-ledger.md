# M-06: The ledger module

Card: `M-06` · Branch: `feature/M-06-ledger` · Depends on: M-01

## What we are building, in one paragraph

The heart of Msika: the part that must be trustworthy. A `Money` type that cannot lose or invent a
single ngwee, a `LedgerEntry` that cannot be created in an invalid state, and a `DailySeal` that
fingerprints and signs a council's whole day so that any later change to a past entry is detected.
It is plain Java with no framework, no database and no clock, so it can be tested completely and
trusted.

## New ideas in this lesson

### Value objects and Java records
- **What it is:** a *value object* is a small immutable thing defined only by its contents (a sum of
  money, a date). A Java `record` is the shortest way to write one: `record Money(long minorUnits,
  Currency currency)` gives you the constructor, accessors and `equals` for free.
- **Why here:** `Money` and `LedgerEntry` are records, so they cannot be changed after creation.
  Immutability is half of "the ledger is append-only".

### Making invalid states impossible
- **What it is:** putting the checks in the *constructor*, so an invalid object cannot exist.
- **Why here:** `new LedgerEntry(...)` throws if the amount is zero, a reversal points nowhere, or a
  `DECLARED_SALE` claims to be `VERIFIED`. Code that holds a `LedgerEntry` never has to re-check.
- **Tiny example:** `Money` has no way to be built from a `double`. The bug "0.1 + 0.2" cannot be
  written.

### Exact arithmetic and overflow
- **What it is:** `Math.addExact` throws when a number would overflow instead of silently wrapping
  to a negative one.
- **Why here:** a quietly wrong balance is the worst kind of bug. We would rather crash.

### Hashing
- **What it is:** a function that turns any data into a short fixed-size fingerprint (SHA-256: 32
  bytes). Change one bit of the input and the fingerprint changes completely. You cannot work back
  from the fingerprint to the data.
- **Why here:** the fingerprint of a day's entries lets us notice any change without keeping a second
  copy to compare against.

### Merkle trees
- **What it is:** hash each entry, then hash pairs of hashes, then pairs of those, up to a single
  *root*. The root fingerprints everything.
  ```
            root
           /    \
        h(1,2)  h(3,4)
        /  \     /  \
      e1   e2   e3   e4        (each ei is hashed first)
  ```
- **Why here:** changing, removing or reordering any entry changes the root. A tree also lets you
  prove one entry is in the day by showing only a few hashes (an *inclusion proof*), and prove a
  later tree extends an earlier one without rewriting it (a *consistency proof*). We copied this from
  SKACE; it follows the standard RFC 6962 (the one Certificate Transparency uses). A leaf and a node
  are hashed with different prefixes (`0x00` and `0x01`) so one cannot masquerade as the other.

### Digital signatures
- **What it is:** with a key pair, the private key signs data and the public key checks the
  signature. Only the private key can make a valid signature, and anyone can check it.
- **Why here:** the hash proves nothing by itself, because someone could recompute it after changing
  data. A signature says "the council's key committed to this root". We use ECDSA (a curve called
  P-256), behind a `SignatureScheme` interface so we can change algorithm later.

### Hash chains
- **What it is:** each day's seal contains the hash of the previous day's seal.
- **Why here:** to hide a change to day 5 you would have to rewrite days 6, 7, 8... and re-sign every
  one. The first seal links to 32 zero bytes (the *genesis*).

### Canonical encoding
- **What it is:** turning an entry into bytes in one exact, unambiguous way, so everyone hashes the
  same bytes.
- **Why here:** text is length-prefixed (so "ab"+"c" cannot equal "a"+"bc"), and the kind is
  written by name, not position, so renumbering an enum later cannot change an old entry's bytes.

### Provenance and reversals
- **Provenance:** every entry is `VERIFIED`, `DEPOSITED` or `DECLARED`, and the kind decides which
  it may be. A declared sale can never be recorded as verified.
- **Reversal:** to correct a mistake you add a `REVERSAL` entry with the opposite amount that points
  at the original. The original is never edited (rule 2).

## Where it sits in the architecture

`msika-ledger` is the bottom layer. It imports nothing but the Java standard library, which the
architecture test from M-05 enforces. `msika-core` will use it, and `msika-api` will store the
entries and run the seal job (M-11).

```
msika-api  -->  msika-core  -->  msika-ledger   (Money, LedgerEntry, DailySeal)
```

## Step by step

1. Branched from M-05, marked the card ◐.
2. Read the SKACE classes, then **copied** `Money`, `Currency`, `MerkleAccumulator`,
   `SignedTreeHead`, `SignatureScheme`, `EcdsaSignatureScheme`, `InclusionProof`,
   `ConsistencyProof` and their three test classes. Renamed the package and rewrote SKACE-specific
   comments. No logic was changed.
3. Ran the copied tests in their new home. They passed unchanged.
4. Wrote `SOURCE.md` (where the code came from).
5. Wrote the new types: `Provenance`, `EntryKind`, `LedgerEntry` (with `reversalOf`), `DailySeal`.
6. Wrote `LedgerEntryTest` and `DailySealTest`.
7. **Broke the code on purpose** (skipped the root comparison in `verify`) and saw the tamper tests
   fail, then restored it. A test you have never seen fail proves little.

## Files touched

| File | What changed | Why |
|---|---|---|
| `platform/msika-ledger/src/main/.../Money, Currency` | Copied from SKACE | Exact money |
| `.../MerkleAccumulator, InclusionProof, ConsistencyProof` | Copied | The tree and its proofs |
| `.../SignedTreeHead, SignatureScheme, EcdsaSignatureScheme` | Copied | Signing |
| `.../Provenance, EntryKind, LedgerEntry, DailySeal` | New | Msika's ledger |
| `platform/msika-ledger/src/test/...` | 3 copied test classes, 2 new | Proof |
| `platform/msika-ledger/SOURCE.md` | New | Records the origin (commit `5fb7524`) |
| `platform/msika-ledger/build.gradle.kts` | Added AssertJ (test) | Readable assertions |

## How we know it works

| Done item | Proof |
|---|---|
| Changing any entry in a sealed day makes `verify` fail | `DailySealTest`: changes each of five entries in turn, and a changed provenance |
| Removing any entry makes `verify` fail | `DailySealTest`: removes each of five entries in turn |
| Reordering makes `verify` fail | `DailySealTest`: swapped entries, and swapped content under renumbered ids |
| A broken chain between two days is detected | `DailySealTest > the chain of days`: wrong link, missing day, rewritten past day |
| `Money` refuses to mix currencies or overflow | Copied `MoneyTest` (currency safety; overflow in ofMajor, addition and subtraction) |

The module has 91 tests, all passing.

## Rebuild it yourself

1. Write a `Money` record from `long` minor units, with `plus` that uses `Math.addExact`. Write
   a test showing the overflow case throws.
2. Hash a list of strings with SHA-256 (`MessageDigest`), then build a root by hashing pairs.
   Change one string and watch the root change.
3. Sign the root with ECDSA (`Signature`), then verify. Flip one byte of the data and verify again.
4. Make a second "day" that includes the first day's hash in what it signs. Change day 1 and see what
   fails.
5. Write a constructor that rejects an invalid combination of fields.

## Mistakes to avoid

- **Hashing a text description instead of canonical bytes.** `toString()` can change between
  versions, silently invalidating old seals.
- **Using `double` for money, even "just for display".** Format from `long` instead.
- **Sorting inside `verify`.** If `verify` sorted the entries first, a reordering would go
  unnoticed. It checks them in the order given.
- **Trusting a test that has never failed.** Break the code and watch.
- **Editing a past entry "just this once".** Add a reversal.
