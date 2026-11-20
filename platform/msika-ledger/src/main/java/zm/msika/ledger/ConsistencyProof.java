package zm.msika.ledger;

import java.util.List;
import java.util.Objects;

/**
 * Evidence that a later tree is an append-only extension of an earlier one.
 *
 * <p><strong>This is the proof that matters most.</strong> An inclusion proof says a entry
 * is in the log. A consistency proof says the log <em>was not rewritten to put it there</em> — that
 * every leaf committed to by the older head is still present, unchanged, in the same order, and
 * that the newer head only adds to it.
 *
 * <p>Without this, an operator could republish a log with an inconvenient entry quietly removed
 * and every inclusion proof against the <em>new</em> head would still verify. Consistency is what
 * makes the record append-only in fact rather than merely by policy .
 *
 * <p><strong>What a failure means.</strong> If this returns {@code false} for two heads a
 * verifier genuinely received and both signatures check out, the verifier is holding
 * cryptographic, non-repudiable evidence that history was altered. It does not need the operator to
 * agree, and the operator cannot make the evidence go away. Escalation from there is a governance
 * matter, deliberately outside the software.
 *
 * @param oldSize number of leaves in the earlier tree
 * @param newSize number of leaves in the later tree
 * @param path the hashes needed to reconstruct both heads
 */
public record ConsistencyProof(int oldSize, int newSize, List<byte[]> path) {

    /** Validates and takes a defensive copy. */
    public ConsistencyProof {
        Objects.requireNonNull(path, "path must not be null");
        if (oldSize < 0) {
            throw new IllegalArgumentException("oldSize must not be negative, but was " + oldSize);
        }
        if (newSize < oldSize) {
            throw new IllegalArgumentException(
                    "newSize " + newSize + " is smaller than oldSize " + oldSize);
        }
        path = MerkleAccumulator.copyOf(path);
    }

    /**
     * Verifies that {@code newRoot} is an append-only extension of {@code oldRoot}.
     *
     * <p>The RFC 6962 algorithm: reconstruct both heads from the same path, and require each to
     * match what was published. A path that yields the new head but not the old one proves the old
     * leaves were changed.
     *
     * @return {@code true} only if nothing committed to by {@code oldRoot} was altered
     */
    public boolean verify(byte[] oldRoot, byte[] newRoot) {
        Objects.requireNonNull(oldRoot, "oldRoot must not be null");
        Objects.requireNonNull(newRoot, "newRoot must not be null");

        if (oldSize == newSize) {
            // Nothing was appended, so the heads must be identical and no path is needed.
            return path.isEmpty() && MerkleAccumulator.hashesEqual(oldRoot, newRoot);
        }
        if (oldSize == 0) {
            // Everything extends the empty tree; there is nothing prior to preserve.
            return path.isEmpty();
        }
        if (path.isEmpty()) {
            return false;
        }

        long node = oldSize - 1L;
        long lastNode = newSize - 1L;
        while ((node & 1L) == 1L) {
            node >>= 1;
            lastNode >>= 1;
        }

        int index = 0;
        byte[] oldHash;
        byte[] newHash;
        if (node != 0L) {
            oldHash = path.get(index).clone();
            newHash = path.get(index).clone();
            index++;
        } else {
            // The old tree was a complete subtree, so its own head seeds the reconstruction.
            oldHash = oldRoot.clone();
            newHash = oldRoot.clone();
        }

        for (; index < path.size(); index++) {
            byte[] sibling = path.get(index);
            if (lastNode == 0L) {
                return false;
            }
            if ((node & 1L) == 1L || node == lastNode) {
                oldHash = MerkleAccumulator.hashNode(sibling, oldHash);
                newHash = MerkleAccumulator.hashNode(sibling, newHash);
                while ((node & 1L) == 0L && node != 0L) {
                    node >>= 1;
                    lastNode >>= 1;
                }
            } else {
                newHash = MerkleAccumulator.hashNode(newHash, sibling);
            }
            node >>= 1;
            lastNode >>= 1;
        }

        return lastNode == 0L
                && MerkleAccumulator.hashesEqual(oldHash, oldRoot)
                && MerkleAccumulator.hashesEqual(newHash, newRoot);
    }

    /** Number of hashes in the path. Logarithmic in tree size. */
    public int length() {
        return path.size();
    }
}
