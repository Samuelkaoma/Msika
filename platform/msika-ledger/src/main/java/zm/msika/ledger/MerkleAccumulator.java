package zm.msika.ledger;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An append-only Merkle tree over ledger entries.
 *
 * <p><strong>Why this exists.</strong> A conventional ledger asks people to trust that the operator
 * has not altered the record. This one does not: every verifier can check, using only public tree
 * heads and arithmetic, that its own history is present and that nothing before it was rewritten.
 *
 * <p><strong>RFC 6962 hashing</strong>, the Certificate Transparency construction:
 *
 * <ul>
 *   <li>leaf hash = {@code SHA-256(0x00 || data)}
 *   <li>node hash = {@code SHA-256(0x01 || left || right)}
 * </ul>
 *
 * <p>The domain separation prefixes are not decoration. Without them, a leaf whose data happened to
 * equal a concatenation of two hashes could be presented as an internal node, letting an attacker
 * claim a leaf exists that never did — the second-preimage attack that Merkle trees are otherwise
 * vulnerable to. One byte prevents it.
 *
 * <p><strong>Deterministic.</strong> SHA-256 is a pure function and the tree shape is a pure
 * function of the leaf count, so replaying the same entries reproduces the same root, byte for
 * byte, on any machine .
 *
 * <p><strong>Append-only by construction.</strong> There is no method to update or remove a leaf —
 * the same discipline as {@code Ledger}, and for the same reason.
 */
public final class MerkleAccumulator {

    /** Prefix distinguishing a leaf hash from an internal node hash. */
    public static final byte LEAF_PREFIX = 0x00;

    /** Prefix distinguishing an internal node hash from a leaf hash. */
    public static final byte NODE_PREFIX = 0x01;

    private final List<byte[]> leafHashes = new ArrayList<>();

    /**
     * Appends data and returns its leaf index.
     *
     * <p>The data is hashed immediately and the original is not retained: the tree commits to
     * content, and holding the content as well would only invite someone to change it.
     */
    public int append(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        leafHashes.add(hashLeaf(data));
        return leafHashes.size() - 1;
    }

    /** Number of leaves. */
    public int size() {
        return leafHashes.size();
    }

    /** The leaf hash at the given index. */
    public byte[] leafHash(int index) {
        requireIndex(index);
        return leafHashes.get(index).clone();
    }

    /**
     * The Merkle tree head over all leaves.
     *
     * <p>An empty tree hashes to {@code SHA-256("")}, per RFC 6962. That is deliberate: an empty
     * log still has a well-defined head, so a verifier can verify a log that has not yet
     * recorded anything rather than having to special-case it.
     */
    public byte[] rootHash() {
        return rootOf(leafHashes, 0, leafHashes.size());
    }

    /**
     * An inclusion proof for the leaf at {@code index}: the sibling hashes needed to recompute the
     * root from that leaf alone.
     *
     * <p>Logarithmic in tree size, so verification stays cheap as the log grows to millions of
     * entries.
     */
    public InclusionProof inclusionProof(int index) {
        requireIndex(index);
        List<byte[]> path = new ArrayList<>();
        buildInclusionPath(index, leafHashes, 0, leafHashes.size(), path);
        return new InclusionProof(index, leafHashes.size(), path);
    }

    /**
     * A consistency proof showing the current tree is an append-only extension of the tree it was
     * at {@code oldSize}.
     *
     * <p>This is the proof that matters most. An inclusion proof says a entry is in the log; a
     * consistency proof says the log <em>was not rewritten</em> to put it there.
     *
     * @throws IllegalArgumentException if {@code oldSize} is negative or exceeds the current size
     */
    public ConsistencyProof consistencyProof(int oldSize) {
        if (oldSize < 0 || oldSize > leafHashes.size()) {
            throw new IllegalArgumentException(
                    "oldSize must be between 0 and " + leafHashes.size() + ", but was " + oldSize);
        }
        List<byte[]> path = new ArrayList<>();
        if (oldSize > 0 && oldSize < leafHashes.size()) {
            buildConsistencyPath(oldSize, leafHashes, 0, leafHashes.size(), true, path);
        }
        return new ConsistencyProof(oldSize, leafHashes.size(), path);
    }

    // ---------------------------------------------------------------- hashing

    /** Hashes a leaf: {@code SHA-256(0x00 || data)}. */
    public static byte[] hashLeaf(byte[] data) {
        MessageDigest digest = sha256();
        digest.update(LEAF_PREFIX);
        digest.update(data);
        return digest.digest();
    }

    /** Hashes an internal node: {@code SHA-256(0x01 || left || right)}. */
    public static byte[] hashNode(byte[] left, byte[] right) {
        MessageDigest digest = sha256();
        digest.update(NODE_PREFIX);
        digest.update(left);
        digest.update(right);
        return digest.digest();
    }

    /** The head of an empty tree: {@code SHA-256("")}. */
    public static byte[] emptyRoot() {
        return sha256().digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform specification. If it is genuinely absent,
            // the JVM is not one we can record payments on.
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    // ------------------------------------------------------------ tree shape

    /**
     * The largest power of two strictly less than {@code n}.
     *
     * <p>RFC 6962 splits a tree of {@code n} leaves at this point rather than in the middle, which
     * is what makes the left subtree always a complete tree and the whole structure stable as
     * leaves are appended.
     */
    static int largestPowerOfTwoLessThan(int n) {
        if (n < 2) {
            throw new IllegalArgumentException("n must be at least 2, but was " + n);
        }
        return Integer.highestOneBit(n - 1);
    }

    private static byte[] rootOf(List<byte[]> hashes, int from, int to) {
        int count = to - from;
        if (count == 0) {
            return emptyRoot();
        }
        if (count == 1) {
            return hashes.get(from).clone();
        }
        int split = from + largestPowerOfTwoLessThan(count);
        return hashNode(rootOf(hashes, from, split), rootOf(hashes, split, to));
    }

    private static void buildInclusionPath(
            int index, List<byte[]> hashes, int from, int to, List<byte[]> path) {
        int count = to - from;
        if (count <= 1) {
            return;
        }
        int k = largestPowerOfTwoLessThan(count);
        int split = from + k;
        if (index - from < k) {
            buildInclusionPath(index, hashes, from, split, path);
            path.add(rootOf(hashes, split, to));
        } else {
            buildInclusionPath(index, hashes, split, to, path);
            path.add(rootOf(hashes, from, split));
        }
    }

    /** RFC 6962 SUBPROOF. {@code startsAtLeftEdge} is the specification's boolean {@code b}. */
    private static void buildConsistencyPath(
            int m,
            List<byte[]> hashes,
            int from,
            int to,
            boolean startsAtLeftEdge,
            List<byte[]> path) {

        int count = to - from;
        if (m == count) {
            if (!startsAtLeftEdge) {
                path.add(rootOf(hashes, from, to));
            }
            return;
        }
        int k = largestPowerOfTwoLessThan(count);
        int split = from + k;
        if (m <= k) {
            buildConsistencyPath(m, hashes, from, split, startsAtLeftEdge, path);
            path.add(rootOf(hashes, split, to));
        } else {
            buildConsistencyPath(m - k, hashes, split, to, false, path);
            path.add(rootOf(hashes, from, split));
        }
    }

    private void requireIndex(int index) {
        if (index < 0 || index >= leafHashes.size()) {
            throw new IllegalArgumentException(
                    "index must be between 0 and "
                            + (leafHashes.size() - 1)
                            + ", but was "
                            + index);
        }
    }

    /** Constant-time-ish equality for hashes. Length is public, so a simple compare is adequate. */
    static boolean hashesEqual(byte[] left, byte[] right) {
        return MessageDigest.isEqual(left, right);
    }

    /** Renders a hash as lowercase hex, for logs and published heads. */
    public static String toHex(byte[] hash) {
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    /** Defensive copy of a list of hashes. */
    static List<byte[]> copyOf(List<byte[]> hashes) {
        List<byte[]> copy = new ArrayList<>(hashes.size());
        for (byte[] hash : hashes) {
            copy.add(Arrays.copyOf(hash, hash.length));
        }
        return List.copyOf(copy);
    }
}
