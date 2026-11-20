# Where this code came from

The ledger and integrity classes in `msika-ledger` started from **SKACE Central Banking**, a Java 21 project owned by the team lead
(SK in the roadmap). They were copied, not linked: there is no git submodule and no
dependency on SKACE. From the copy onwards they belong to Msika and are changed freely.

Nothing here comes from Arcus (it is Go, and client work).

## Origin

- Project: SKACE Central Banking, module `skace-core/domain`
- Commit: `5fb7524` ("Add an impostor mode to the portal demo")
- Copied on: 2026-11-20

## What was copied

| SKACE class (package `zm.skace.domain.ledger` or `.integrity`) | Msika class (package `zm.msika.ledger`) |
|---|---|
| `Money` | `Money` |
| `Currency` | `Currency` (needed by `Money`) |
| `MerkleAccumulator` | `MerkleAccumulator` |
| `SignedTreeHead` | `SignedTreeHead` |
| `SignatureScheme` | `SignatureScheme` |
| `EcdsaSignatureScheme` | `EcdsaSignatureScheme` |
| `InclusionProof` | `InclusionProof` |
| `ConsistencyProof` | `ConsistencyProof` |

Their unit tests came with them: `MoneyTest`, `MerkleAccumulatorTest`, `SignedTreeHeadTest`.

## What was changed in the copy

- Package renamed to `zm.msika.ledger` (SKACE keeps ledger and integrity in two packages; Msika
  keeps them in one).
- Comments rewritten for Msika. SKACE-specific references (feature numbers, use-case numbers,
  invariant numbers, "participant", "national settlement system") were removed or reworded, and the
  words "transfer" and "settle" became "entry" and "record" in comments and test names.
- Nothing in the logic was changed. The copied tests pass unchanged.
- Everything else in SKACE (`Ledger`, `Transfer`, `Posting`, accounts, quorum, incident log, and so
  on) is deliberately not copied. Msika's ledger is a different shape.

## What is new in Msika

- `Provenance` and `EntryKind`
- `LedgerEntry` (with `reversalOf`)
- `DailySeal` (sealing, verification and chain verification)
- their tests, `LedgerEntryTest` and `DailySealTest`
