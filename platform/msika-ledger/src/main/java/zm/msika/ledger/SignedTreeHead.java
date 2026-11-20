package zm.msika.ledger;

import java.nio.ByteBuffer;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Objects;

/**
 * A signed commitment to the state of the log at a moment in time.
 *
 * <p><strong></strong> This is what gets published, and it is the only thing a verifier
 * needs in order to verify. Given two heads it has kept, a verifier can check that the later one
 * extends the earlier — without asking the operator anything, and without the operator being able
 * to withdraw what it already published.
 *
 * <p><strong>Why the signature matters.</strong> The root hash alone proves the log is internally
 * consistent, but not that the operator committed to it. A signed head is a statement the operator
 * cannot later deny making. If it ever publishes two inconsistent heads, both signed, the
 * contradiction is permanent and provable by anyone holding both.
 *
 * <p><strong>Canonical bytes.</strong> The signed payload is a fixed-width encoding of tree size,
 * timestamp and root hash. Fixed-width matters: a delimiter-free variable encoding could let two
 * different heads serialise identically, and a signature over an ambiguous payload authorises both
 * readings.
 *
 * <p>The timestamp is supplied by the caller, never read from a clock here — the domain must stay
 * replayable .
 *
 * @param treeSize number of leaves committed to
 * @param rootHash the Merkle tree head
 * @param timestampMillis when this head was issued, supplied as data
 * @param algorithm the signature algorithm, so verifiers know what to check
 * @param signature the operator's signature over {@link #signedBytes()}
 */
public record SignedTreeHead(
        int treeSize, byte[] rootHash, long timestampMillis, String algorithm, byte[] signature) {

    /** Validates and takes defensive copies. */
    public SignedTreeHead {
        Objects.requireNonNull(rootHash, "rootHash must not be null");
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        Objects.requireNonNull(signature, "signature must not be null");
        if (treeSize < 0) {
            throw new IllegalArgumentException("treeSize must not be negative, but was " + treeSize);
        }
        if (rootHash.length == 0) {
            throw new IllegalArgumentException("rootHash must not be empty");
        }
        if (signature.length == 0) {
            throw new IllegalArgumentException("signature must not be empty");
        }
        rootHash = rootHash.clone();
        signature = signature.clone();
    }

    /** Signs the current state of a log. */
    public static SignedTreeHead sign(
            int treeSize,
            byte[] rootHash,
            long timestampMillis,
            SignatureScheme scheme,
            PrivateKey key) {

        byte[] payload = canonicalBytes(treeSize, rootHash, timestampMillis);
        return new SignedTreeHead(
                treeSize,
                rootHash,
                timestampMillis,
                scheme.algorithm(),
                scheme.sign(payload, key));
    }

    /** The exact bytes that were signed. */
    public byte[] signedBytes() {
        return canonicalBytes(treeSize, rootHash, timestampMillis);
    }

    private static byte[] canonicalBytes(int treeSize, byte[] rootHash, long timestampMillis) {
        return ByteBuffer.allocate(Long.BYTES + Long.BYTES + rootHash.length)
                .putLong(treeSize)
                .putLong(timestampMillis)
                .put(rootHash)
                .array();
    }

    /** Verifies this head against the operator's published public key. */
    public boolean verify(PublicKey operatorKey, SignatureScheme scheme) {
        Objects.requireNonNull(operatorKey, "operatorKey must not be null");
        Objects.requireNonNull(scheme, "scheme must not be null");
        return scheme.verify(signedBytes(), signature, operatorKey);
    }

    /** Defensive copy on the way out. */
    @Override
    public byte[] rootHash() {
        return rootHash.clone();
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
        if (!(other instanceof SignedTreeHead that)) {
            return false;
        }
        return treeSize == that.treeSize
                && timestampMillis == that.timestampMillis
                && algorithm.equals(that.algorithm)
                && Arrays.equals(rootHash, that.rootHash)
                && Arrays.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(treeSize);
        result = 31 * result + Long.hashCode(timestampMillis);
        result = 31 * result + algorithm.hashCode();
        result = 31 * result + Arrays.hashCode(rootHash);
        return 31 * result + Arrays.hashCode(signature);
    }

    @Override
    public String toString() {
        return "SignedTreeHead[size="
                + treeSize
                + ", root="
                + MerkleAccumulator.toHex(rootHash).substring(0, 16)
                + "..., at="
                + timestampMillis
                + "]";
    }
}
