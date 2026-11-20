package zm.msika.ledger;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A council's sealed trading day (ARCHITECTURE.md section 4.4).
 *
 * <p>At day close the day's entries go into a Merkle tree, and the council, date, entry count, tree
 * root and the previous day's seal hash are signed. Because each seal contains the hash of the one
 * before, the seals form a chain: changing, removing or reordering any entry in a sealed day changes
 * the root and breaks that day's seal, and altering a whole past seal breaks every later one. Anyone
 * with the public key can check it.
 *
 * <p>The first seal for a council chains to {@link #GENESIS_HASH}, thirty-two zero bytes.
 *
 * <p>This class takes the entries and the timestamps as data. It never reads a clock or a database,
 * so sealing the same entries always gives the same root.
 *
 * @param councilId the council
 * @param businessDate the Africa/Lusaka trading date that was sealed
 * @param entryCount how many entries the day held
 * @param merkleRoot the root of the tree over the day's entries, in id order
 * @param previousSealHash the {@link #sealHash()} of the previous day's seal, or {@link #GENESIS_HASH}
 * @param algorithm the signature algorithm, so a verifier knows what to check
 * @param signature the signature over {@link #signedBytes()}
 */
public record DailySeal(
        UUID councilId,
        LocalDate businessDate,
        int entryCount,
        byte[] merkleRoot,
        byte[] previousSealHash,
        String algorithm,
        byte[] signature) {

    /** The "previous seal" of a council's very first seal. */
    public static final byte[] GENESIS_HASH = new byte[32];

    /** Validates and takes defensive copies. */
    public DailySeal {
        Objects.requireNonNull(councilId, "councilId must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(merkleRoot, "merkleRoot must not be null");
        Objects.requireNonNull(previousSealHash, "previousSealHash must not be null");
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        Objects.requireNonNull(signature, "signature must not be null");
        if (entryCount < 0) {
            throw new IllegalArgumentException("entryCount must not be negative, but was " + entryCount);
        }
        if (merkleRoot.length != 32) {
            throw new IllegalArgumentException("merkleRoot must be 32 bytes");
        }
        if (previousSealHash.length != 32) {
            throw new IllegalArgumentException("previousSealHash must be 32 bytes");
        }
        if (signature.length == 0) {
            throw new IllegalArgumentException("signature must not be empty");
        }
        merkleRoot = merkleRoot.clone();
        previousSealHash = previousSealHash.clone();
        signature = signature.clone();
    }

    /** The outcome of a check: either valid, or invalid with the first problem found. */
    public record Verification(boolean valid, String problem) {
        static Verification ok() {
            return new Verification(true, "");
        }

        static Verification failed(String problem) {
            return new Verification(false, problem);
        }
    }

    /**
     * Seals one council's trading day.
     *
     * @param entries the day's entries, in increasing id order; every one must belong to this
     *     council and this business date. A day with no entries can be sealed too, which keeps the
     *     chain unbroken.
     * @param previousSealHash {@link #sealHash()} of the previous seal, or {@link #GENESIS_HASH}
     * @throws IllegalArgumentException if an entry is for another council or date, or the entries
     *     are not in increasing id order
     */
    public static DailySeal seal(
            UUID councilId,
            LocalDate businessDate,
            List<LedgerEntry> entries,
            byte[] previousSealHash,
            SignatureScheme scheme,
            PrivateKey key) {
        Objects.requireNonNull(entries, "entries must not be null");
        Objects.requireNonNull(scheme, "scheme must not be null");
        Objects.requireNonNull(key, "key must not be null");
        String problem = problemWithEntries(councilId, businessDate, entries);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }

        byte[] root = rootOf(entries);
        byte[] payload = signedBytes(councilId, businessDate, entries.size(), root, previousSealHash);
        return new DailySeal(
                councilId,
                businessDate,
                entries.size(),
                root,
                previousSealHash,
                scheme.algorithm(),
                scheme.sign(payload, key));
    }

    /**
     * Checks this seal against the entries it claims to cover.
     *
     * <p>The entries must be given exactly as stored, in id order. It fails if any entry was
     * changed, removed, added or reordered, if the previous-seal link is not the expected one, or
     * if the signature is not the council key's.
     *
     * @param entries the day's entries as they are now
     * @param expectedPreviousSealHash the hash of the previous seal as the verifier knows it
     */
    public Verification verify(
            List<LedgerEntry> entries,
            byte[] expectedPreviousSealHash,
            PublicKey publicKey,
            SignatureScheme scheme) {
        Objects.requireNonNull(entries, "entries must not be null");
        Objects.requireNonNull(expectedPreviousSealHash, "expectedPreviousSealHash must not be null");
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        Objects.requireNonNull(scheme, "scheme must not be null");

        String problem = problemWithEntries(councilId, businessDate, entries);
        if (problem != null) {
            return Verification.failed(problem);
        }
        if (entries.size() != entryCount) {
            return Verification.failed(
                    "the seal covers " + entryCount + " entries but there are " + entries.size());
        }
        if (!MessageDigest.isEqual(rootOf(entries), merkleRoot)) {
            return Verification.failed("the entries do not match the sealed root: an entry was "
                    + "changed, removed, added or reordered");
        }
        if (!MessageDigest.isEqual(expectedPreviousSealHash, previousSealHash)) {
            return Verification.failed("the link to the previous day's seal is broken");
        }
        if (!scheme.verify(signedBytes(), signature, publicKey)) {
            return Verification.failed("the signature is not valid");
        }
        return Verification.ok();
    }

    /**
     * Checks a council's seals as a chain, oldest first: each links to the one before (the first to
     * {@link #GENESIS_HASH}), dates strictly increase, all are the same council's, and every
     * signature is valid. It does not look at entries; use {@link #verify} for each day for that.
     */
    public static Verification verifyChain(
            List<DailySeal> seals, PublicKey publicKey, SignatureScheme scheme) {
        Objects.requireNonNull(seals, "seals must not be null");
        byte[] expectedPrevious = GENESIS_HASH;
        DailySeal before = null;
        for (DailySeal seal : seals) {
            if (before != null && !seal.councilId.equals(before.councilId)) {
                return Verification.failed("the chain mixes councils at " + seal.businessDate);
            }
            if (before != null && !seal.businessDate.isAfter(before.businessDate)) {
                return Verification.failed(
                        "seal dates are not in increasing order at " + seal.businessDate);
            }
            if (!MessageDigest.isEqual(expectedPrevious, seal.previousSealHash)) {
                return Verification.failed(
                        "the chain is broken: " + seal.businessDate + " does not follow the seal before it");
            }
            if (!scheme.verify(seal.signedBytes(), seal.signature, publicKey)) {
                return Verification.failed("the signature on " + seal.businessDate + " is not valid");
            }
            expectedPrevious = seal.sealHash();
            before = seal;
        }
        return Verification.ok();
    }

    /**
     * The hash the next day's seal links to: SHA-256 over the signed bytes and the signature, so
     * changing any field of this seal, or its signature, changes the hash.
     */
    public byte[] sealHash() {
        MessageDigest digest = sha256();
        digest.update(signedBytes());
        digest.update(signature);
        return digest.digest();
    }

    /** The exact bytes that were signed. */
    public byte[] signedBytes() {
        return signedBytes(councilId, businessDate, entryCount, merkleRoot, previousSealHash);
    }

    private static byte[] signedBytes(
            UUID councilId,
            LocalDate businessDate,
            int entryCount,
            byte[] merkleRoot,
            byte[] previousSealHash) {
        return ByteBuffer.allocate(2 * Long.BYTES + Long.BYTES + Long.BYTES + 32 + 32)
                .putLong(councilId.getMostSignificantBits())
                .putLong(councilId.getLeastSignificantBits())
                .putLong(businessDate.toEpochDay())
                .putLong(entryCount)
                .put(merkleRoot)
                .put(previousSealHash)
                .array();
    }

    private static byte[] rootOf(List<LedgerEntry> entries) {
        MerkleAccumulator tree = new MerkleAccumulator();
        for (LedgerEntry entry : entries) {
            tree.append(entry.canonicalBytes());
        }
        return tree.rootHash();
    }

    /** Returns a description of the first problem, or {@code null} if the entries are acceptable. */
    private static String problemWithEntries(
            UUID councilId, LocalDate businessDate, List<LedgerEntry> entries) {
        long previousId = 0;
        for (LedgerEntry entry : entries) {
            if (!entry.councilId().equals(councilId)) {
                return "entry " + entry.id() + " belongs to another council";
            }
            if (!entry.businessDate().equals(businessDate)) {
                return "entry " + entry.id() + " belongs to " + entry.businessDate()
                        + ", not " + businessDate;
            }
            if (entry.id() <= previousId) {
                return "entries are not in increasing id order at entry " + entry.id();
            }
            previousId = entry.id();
        }
        return null;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /** Defensive copy on the way out. */
    @Override
    public byte[] merkleRoot() {
        return merkleRoot.clone();
    }

    /** Defensive copy on the way out. */
    @Override
    public byte[] previousSealHash() {
        return previousSealHash.clone();
    }

    /** Defensive copy on the way out. */
    @Override
    public byte[] signature() {
        return signature.clone();
    }

    /** Value equality over the byte arrays, since records compare arrays by reference. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DailySeal that)) {
            return false;
        }
        return entryCount == that.entryCount
                && councilId.equals(that.councilId)
                && businessDate.equals(that.businessDate)
                && algorithm.equals(that.algorithm)
                && Arrays.equals(merkleRoot, that.merkleRoot)
                && Arrays.equals(previousSealHash, that.previousSealHash)
                && Arrays.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(councilId, businessDate, entryCount, algorithm);
        result = 31 * result + Arrays.hashCode(merkleRoot);
        result = 31 * result + Arrays.hashCode(previousSealHash);
        return 31 * result + Arrays.hashCode(signature);
    }

    @Override
    public String toString() {
        return "DailySeal[council=" + councilId + ", date=" + businessDate + ", entries=" + entryCount
                + ", root=" + MerkleAccumulator.toHex(merkleRoot).substring(0, 16) + "...]";
    }
}
