package zm.msika.ledger;

import java.util.List;
import java.util.Objects;

/**
 * Evidence that a specific leaf sits at a specific position in a tree of a specific size.
 *
 * <p><strong></strong> A verifier given its own entries plus these proofs can recompute
 * the published root itself. It never has to take the operator's word that a entry was recorded
 * — it checks, with arithmetic, using only public information.
 *
 * <p>Verification is <strong>client-side by design</strong>. A proof that could only be checked by
 * asking the operator whether it was valid would prove nothing at all.
 *
 * @param leafIndex position of the leaf, zero-based
 * @param treeSize number of leaves in the tree this proof is against
 * @param path sibling hashes from the leaf upward
 */
public record InclusionProof(int leafIndex, int treeSize, List<byte[]> path) {

    /** Validates and takes a defensive copy. */
    public InclusionProof {
        Objects.requireNonNull(path, "path must not be null");
        if (treeSize <= 0) {
            throw new IllegalArgumentException("treeSize must be positive, but was " + treeSize);
        }
        if (leafIndex < 0 || leafIndex >= treeSize) {
            throw new IllegalArgumentException(
                    "leafIndex must be between 0 and " + (treeSize - 1) + ", but was " + leafIndex);
        }
        path = MerkleAccumulator.copyOf(path);
    }

    /**
     * Verifies this proof against a leaf hash and an expected root.
     *
     * <p>The RFC 6962 verification algorithm: walk up from the leaf, combining with each sibling
     * on the correct side, and check the result equals the published root.
     *
     * @return {@code true} only if the leaf provably sits at {@code leafIndex} in a tree of
     *     {@code treeSize} leaves whose head is {@code expectedRoot}
     */
    public boolean verify(byte[] leafHash, byte[] expectedRoot) {
        Objects.requireNonNull(leafHash, "leafHash must not be null");
        Objects.requireNonNull(expectedRoot, "expectedRoot must not be null");

        long nodeIndex = leafIndex;
        long lastIndex = treeSize - 1L;
        byte[] running = leafHash.clone();

        for (byte[] sibling : path) {
            if (lastIndex == 0L) {
                // More siblings than the tree could possibly have. The proof is malformed or
                // forged; either way it does not verify.
                return false;
            }
            if ((nodeIndex & 1L) == 1L || nodeIndex == lastIndex) {
                running = MerkleAccumulator.hashNode(sibling, running);
                while ((nodeIndex & 1L) == 0L && nodeIndex != 0L) {
                    nodeIndex >>= 1;
                    lastIndex >>= 1;
                }
            } else {
                running = MerkleAccumulator.hashNode(running, sibling);
            }
            nodeIndex >>= 1;
            lastIndex >>= 1;
        }

        return lastIndex == 0L && MerkleAccumulator.hashesEqual(running, expectedRoot);
    }

    /** Number of sibling hashes. Logarithmic in tree size. */
    public int length() {
        return path.size();
    }
}
