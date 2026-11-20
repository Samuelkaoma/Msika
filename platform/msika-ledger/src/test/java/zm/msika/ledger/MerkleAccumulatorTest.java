package zm.msika.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * specification for the Merkle accumulator and its proofs.
 *
 * <p>The exhaustive tests carry the weight. Merkle implementations fail on specific tree shapes —
 * odd sizes, the boundary where a subtree completes — so rather than testing a few hand-picked
 * cases, these walk <em>every</em> leaf of <em>every</em> tree size up to 33, and every pair of
 * sizes for consistency. That is roughly 1,600 proofs, and an off-by-one anywhere fails it.
 */
class MerkleAccumulatorTest {

    private static final int EXHAUSTIVE_LIMIT = 33;

    private static byte[] leaf(int i) {
        return ("entry-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static MerkleAccumulator treeOf(int size) {
        MerkleAccumulator tree = new MerkleAccumulator();
        for (int i = 0; i < size; i++) {
            tree.append(leaf(i));
        }
        return tree;
    }

    @Nested
    @DisplayName("hashing — RFC 6962")
    class Hashing {

        @Test
        @DisplayName("an empty tree hashes to SHA-256 of nothing")
        void emptyTreeHasAWellDefinedHead() {
            // An empty log still has a head, so a verifier can verify a log that has recorded
            // nothing yet rather than having to special-case it.
            assertThat(new MerkleAccumulator().rootHash())
                    .isEqualTo(MerkleAccumulator.emptyRoot());
        }

        @Test
        @DisplayName("a single-leaf tree hashes to the leaf hash")
        void singleLeafRoot() {
            MerkleAccumulator tree = treeOf(1);
            assertThat(tree.rootHash()).isEqualTo(MerkleAccumulator.hashLeaf(leaf(0)));
        }

        @Test
        @DisplayName("leaf and node hashing use different prefixes")
        void domainSeparation() {
            // Without the prefixes, a leaf whose content happened to equal two concatenated
            // hashes could be passed off as an internal node - the second-preimage attack. One
            // byte prevents it.
            byte[] left = MerkleAccumulator.hashLeaf(leaf(0));
            byte[] right = MerkleAccumulator.hashLeaf(leaf(1));

            byte[] concatenated = new byte[left.length + right.length];
            System.arraycopy(left, 0, concatenated, 0, left.length);
            System.arraycopy(right, 0, concatenated, left.length, right.length);

            assertThat(MerkleAccumulator.hashNode(left, right))
                    .isNotEqualTo(MerkleAccumulator.hashLeaf(concatenated));
        }

        @Test
        @DisplayName("the same leaves always produce the same root")
        void deterministic() {
            assertThat(treeOf(17).rootHash()).isEqualTo(treeOf(17).rootHash());
        }

        @Test
        @DisplayName("appending changes the root")
        void appendingChangesTheRoot() {
            assertThat(treeOf(8).rootHash()).isNotEqualTo(treeOf(9).rootHash());
        }

        @Test
        @DisplayName("the split point is the largest power of two below the leaf count")
        void splitPoint() {
            assertThat(MerkleAccumulator.largestPowerOfTwoLessThan(2)).isEqualTo(1);
            assertThat(MerkleAccumulator.largestPowerOfTwoLessThan(3)).isEqualTo(2);
            assertThat(MerkleAccumulator.largestPowerOfTwoLessThan(4)).isEqualTo(2);
            assertThat(MerkleAccumulator.largestPowerOfTwoLessThan(5)).isEqualTo(4);
            assertThat(MerkleAccumulator.largestPowerOfTwoLessThan(8)).isEqualTo(4);
            assertThat(MerkleAccumulator.largestPowerOfTwoLessThan(9)).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("inclusion proofs")
    class Inclusion {

        @Test
        @DisplayName("every leaf of every tree size up to 33 proves inclusion")
        void exhaustiveInclusion() {
            for (int size = 1; size <= EXHAUSTIVE_LIMIT; size++) {
                MerkleAccumulator tree = treeOf(size);
                byte[] root = tree.rootHash();
                for (int index = 0; index < size; index++) {
                    InclusionProof proof = tree.inclusionProof(index);
                    assertThat(proof.verify(MerkleAccumulator.hashLeaf(leaf(index)), root))
                            .as("leaf %d of %d", index, size)
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("a proof does not verify against the wrong leaf")
        void wrongLeafFails() {
            MerkleAccumulator tree = treeOf(16);
            InclusionProof proof = tree.inclusionProof(5);
            assertThat(proof.verify(MerkleAccumulator.hashLeaf(leaf(6)), tree.rootHash())).isFalse();
        }

        @Test
        @DisplayName("a proof does not verify against the wrong root")
        void wrongRootFails() {
            MerkleAccumulator tree = treeOf(16);
            InclusionProof proof = tree.inclusionProof(5);
            assertThat(proof.verify(MerkleAccumulator.hashLeaf(leaf(5)), treeOf(17).rootHash()))
                    .isFalse();
        }

        @Test
        @DisplayName("a tampered path does not verify")
        void tamperedPathFails() {
            MerkleAccumulator tree = treeOf(16);
            InclusionProof proof = tree.inclusionProof(5);

            byte[] corrupted = proof.path().get(0).clone();
            corrupted[0] ^= 0x01;
            InclusionProof forged =
                    new InclusionProof(
                            proof.leafIndex(),
                            proof.treeSize(),
                            java.util.List.of(
                                    corrupted,
                                    proof.path().get(1),
                                    proof.path().get(2),
                                    proof.path().get(3)));

            assertThat(forged.verify(MerkleAccumulator.hashLeaf(leaf(5)), tree.rootHash()))
                    .isFalse();
        }

        @Test
        @DisplayName("proof size is logarithmic, so verification stays cheap as the log grows")
        void proofsAreLogarithmic() {
            assertThat(treeOf(1024).inclusionProof(500).length()).isLessThanOrEqualTo(10);
            assertThat(treeOf(1_048_576).inclusionProof(1000).length()).isLessThanOrEqualTo(20);
        }

        @Test
        @DisplayName("an out-of-range index is refused")
        void indexOutOfRange() {
            MerkleAccumulator tree = treeOf(4);
            assertThatIllegalArgumentException().isThrownBy(() -> tree.inclusionProof(4));
            assertThatIllegalArgumentException().isThrownBy(() -> tree.inclusionProof(-1));
        }
    }

    @Nested
    @DisplayName("consistency proofs")
    class Consistency {

        @Test
        @DisplayName("every pair of tree sizes up to 33 proves consistency")
        void exhaustiveConsistency() {
            for (int newSize = 1; newSize <= EXHAUSTIVE_LIMIT; newSize++) {
                MerkleAccumulator tree = treeOf(newSize);
                byte[] newRoot = tree.rootHash();
                for (int oldSize = 0; oldSize <= newSize; oldSize++) {
                    byte[] oldRoot = treeOf(oldSize).rootHash();
                    ConsistencyProof proof = tree.consistencyProof(oldSize);
                    assertThat(proof.verify(oldRoot, newRoot))
                            .as("consistency %d -> %d", oldSize, newSize)
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("identical sizes need no path and require identical roots")
        void identicalSizes() {
            MerkleAccumulator tree = treeOf(10);
            ConsistencyProof proof = tree.consistencyProof(10);
            assertThat(proof.length()).isZero();
            assertThat(proof.verify(tree.rootHash(), tree.rootHash())).isTrue();
            assertThat(proof.verify(treeOf(9).rootHash(), tree.rootHash())).isFalse();
        }

        @Test
        @DisplayName("a proof does not verify against a root the log never had")
        void wrongOldRootFails() {
            MerkleAccumulator tree = treeOf(20);
            ConsistencyProof proof = tree.consistencyProof(12);
            byte[] fabricated = MerkleAccumulator.hashLeaf("not a real head".getBytes(
                    StandardCharsets.UTF_8));
            assertThat(proof.verify(fabricated, tree.rootHash())).isFalse();
        }

        @Test
        @DisplayName("an oldSize beyond the tree is refused")
        void oldSizeOutOfRange() {
            MerkleAccumulator tree = treeOf(4);
            assertThatIllegalArgumentException().isThrownBy(() -> tree.consistencyProof(5));
            assertThatIllegalArgumentException().isThrownBy(() -> tree.consistencyProof(-1));
        }
    }

    @Nested
    @DisplayName("the adversarial case")
    class Adversarial {

        @Test
        @DisplayName("rewriting a historical entry breaks consistency at every tree size")
        void rewritingHistoryIsDetected() {
            // The scenario the whole layer exists for: an operator quietly alters a recorded
            // entry and republishes. Inclusion proofs against the NEW head would all still
            // verify - which is exactly why inclusion alone is not enough.
            for (int size = 4; size <= 24; size++) {
                int checkpoint = size / 2;

                MerkleAccumulator honest = treeOf(size);
                byte[] honestOldRoot = treeOf(checkpoint).rootHash();

                MerkleAccumulator tampered = new MerkleAccumulator();
                for (int i = 0; i < size; i++) {
                    tampered.append(i == 1 ? "ALTERED".getBytes(StandardCharsets.UTF_8) : leaf(i));
                }

                ConsistencyProof proof = tampered.consistencyProof(checkpoint);
                assertThat(proof.verify(honestOldRoot, tampered.rootHash()))
                        .as("tampered log of size %d must not prove consistency", size)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("deleting an entry breaks consistency")
        void deletingAnEntryIsDetected() {
            // Deletion is the attack a naive append-only log misses entirely: the remaining
            // entries are all genuine, so nothing looks wrong locally.
            MerkleAccumulator honest = treeOf(16);
            byte[] honestOldRoot = treeOf(8).rootHash();

            MerkleAccumulator shortened = new MerkleAccumulator();
            for (int i = 0; i < 16; i++) {
                if (i != 3) {
                    shortened.append(leaf(i));
                }
            }

            ConsistencyProof proof = shortened.consistencyProof(8);
            assertThat(proof.verify(honestOldRoot, shortened.rootHash())).isFalse();
        }

        @Test
        @DisplayName("reordering entries breaks consistency")
        void reorderingIsDetected() {
            // Order is part of what the log commits to. Two entries that recorded in one order
            // cannot be presented as having recorded in the other.
            MerkleAccumulator honestOld = treeOf(8);

            MerkleAccumulator reordered = new MerkleAccumulator();
            for (int i = 0; i < 16; i++) {
                if (i == 2) {
                    reordered.append(leaf(3));
                } else if (i == 3) {
                    reordered.append(leaf(2));
                } else {
                    reordered.append(leaf(i));
                }
            }

            ConsistencyProof proof = reordered.consistencyProof(8);
            assertThat(proof.verify(honestOld.rootHash(), reordered.rootHash())).isFalse();
        }

        @Test
        @DisplayName("appending honestly still proves consistent — the check is not vacuous")
        void honestAppendStillVerifies() {
            // Guards against a verifier that simply returns false. If the adversarial tests above
            // pass but this one fails, the proofs prove nothing.
            MerkleAccumulator tree = treeOf(16);
            assertThat(tree.consistencyProof(8).verify(treeOf(8).rootHash(), tree.rootHash()))
                    .isTrue();
        }
    }
}
