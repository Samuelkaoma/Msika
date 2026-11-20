package zm.msika.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** specification for {@link SignedTreeHead} and {@link SignatureScheme}. */
class SignedTreeHeadTest {

    private final SignatureScheme scheme = new EcdsaSignatureScheme();
    private final KeyPair operator = scheme.generateKeyPair();

    private static MerkleAccumulator treeOf(int size) {
        MerkleAccumulator tree = new MerkleAccumulator();
        for (int i = 0; i < size; i++) {
            tree.append(("entry-" + i).getBytes(StandardCharsets.UTF_8));
        }
        return tree;
    }

    private SignedTreeHead headOf(MerkleAccumulator tree, long timestamp) {
        return SignedTreeHead.sign(
                tree.size(), tree.rootHash(), timestamp, scheme, operator.getPrivate());
    }

    @Nested
    @DisplayName("a head is a commitment the operator cannot deny")
    class Commitment {

        @Test
        @DisplayName("a genuine head verifies against the operator public key")
        void genuineHeadVerifies() {
            assertThat(headOf(treeOf(10), 1_700_000_000_000L).verify(operator.getPublic(), scheme))
                    .isTrue();
        }

        @Test
        @DisplayName("a head does not verify against anyone else's key")
        void wrongKeyFails() {
            KeyPair impostor = scheme.generateKeyPair();
            assertThat(headOf(treeOf(10), 1L).verify(impostor.getPublic(), scheme)).isFalse();
        }

        @Test
        @DisplayName("altering the root hash invalidates the signature")
        void tamperedRootFails() {
            MerkleAccumulator tree = treeOf(10);
            SignedTreeHead genuine = headOf(tree, 1L);

            byte[] tamperedRoot = genuine.rootHash();
            tamperedRoot[0] ^= 0x01;

            SignedTreeHead forged =
                    new SignedTreeHead(
                            genuine.treeSize(),
                            tamperedRoot,
                            genuine.timestampMillis(),
                            genuine.algorithm(),
                            genuine.signature());

            assertThat(forged.verify(operator.getPublic(), scheme)).isFalse();
        }

        @Test
        @DisplayName("altering the tree size invalidates the signature")
        void tamperedSizeFails() {
            SignedTreeHead genuine = headOf(treeOf(10), 1L);
            SignedTreeHead forged =
                    new SignedTreeHead(
                            genuine.treeSize() + 1,
                            genuine.rootHash(),
                            genuine.timestampMillis(),
                            genuine.algorithm(),
                            genuine.signature());

            assertThat(forged.verify(operator.getPublic(), scheme)).isFalse();
        }

        @Test
        @DisplayName("altering the timestamp invalidates the signature")
        void tamperedTimestampFails() {
            SignedTreeHead genuine = headOf(treeOf(10), 1L);
            SignedTreeHead forged =
                    new SignedTreeHead(
                            genuine.treeSize(),
                            genuine.rootHash(),
                            genuine.timestampMillis() + 1,
                            genuine.algorithm(),
                            genuine.signature());

            assertThat(forged.verify(operator.getPublic(), scheme)).isFalse();
        }

        @Test
        @DisplayName("the signed payload is fixed-width, so two heads cannot collide")
        void canonicalEncodingIsUnambiguous() {
            // A variable-width encoding without delimiters could let two different heads
            // serialise identically, and a signature over an ambiguous payload authorises both
            // readings.
            SignedTreeHead head = headOf(treeOf(10), 1L);
            assertThat(head.signedBytes())
                    .hasSize(Long.BYTES + Long.BYTES + head.rootHash().length);
        }
    }

    @Nested
    @DisplayName("the full audit a verifier performs")
    class VerifierAudit {

        @Test
        @DisplayName("verify the head, then prove inclusion, then prove nothing was rewritten")
        void endToEndAudit() {
            // Exactly this audit, performed with nothing but published heads and arithmetic.
            MerkleAccumulator log = treeOf(8);
            SignedTreeHead earlier = headOf(log, 1_000L);

            for (int i = 8; i < 20; i++) {
                log.append(("entry-" + i).getBytes(StandardCharsets.UTF_8));
            }
            SignedTreeHead later = headOf(log, 2_000L);

            // 1. Both heads are genuinely the operator's.
            assertThat(earlier.verify(operator.getPublic(), scheme)).isTrue();
            assertThat(later.verify(operator.getPublic(), scheme)).isTrue();

            // 2. My entry is in the log.
            InclusionProof inclusion = log.inclusionProof(5);
            assertThat(inclusion.verify(
                            MerkleAccumulator.hashLeaf("entry-5".getBytes(StandardCharsets.UTF_8)),
                            later.rootHash()))
                    .isTrue();

            // 3. Nothing I saw before was rewritten to put it there.
            ConsistencyProof consistency = log.consistencyProof(earlier.treeSize());
            assertThat(consistency.verify(earlier.rootHash(), later.rootHash())).isTrue();
        }

        @Test
        @DisplayName("a rewritten log fails the audit even though every head is validly signed")
        void tamperedLogFailsAuditDespiteValidSignatures() {
            // The operator signs honestly - it just signs a log it altered. Signatures alone
            // cannot catch this; consistency can. That is why both exist.
            MerkleAccumulator original = treeOf(8);
            SignedTreeHead earlier = headOf(original, 1_000L);

            MerkleAccumulator rewritten = new MerkleAccumulator();
            for (int i = 0; i < 20; i++) {
                rewritten.append(
                        i == 3
                                ? "ALTERED".getBytes(StandardCharsets.UTF_8)
                                : ("entry-" + i).getBytes(StandardCharsets.UTF_8));
            }
            SignedTreeHead later = headOf(rewritten, 2_000L);

            assertThat(later.verify(operator.getPublic(), scheme))
                    .as("the head is validly signed - the operator is not forging, it is rewriting")
                    .isTrue();

            assertThat(rewritten.consistencyProof(earlier.treeSize())
                            .verify(earlier.rootHash(), later.rootHash()))
                    .as("but consistency fails, and the two signed heads are the evidence")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("empty root, empty signature and negative size are refused")
        void invalidHeadsRefused() {
            byte[] ok = new byte[] {1, 2, 3};
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SignedTreeHead(-1, ok, 1L, "x", ok));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SignedTreeHead(1, new byte[0], 1L, "x", ok));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SignedTreeHead(1, ok, 1L, "x", new byte[0]));
        }

        @Test
        @DisplayName("byte arrays are copied in and out, so a holder cannot mutate a stored head")
        void defensivelyCopies() {
            SignedTreeHead head = headOf(treeOf(4), 1L);
            byte[] borrowed = head.rootHash();
            borrowed[0] = (byte) (borrowed[0] ^ 0xFF);

            assertThat(head.verify(operator.getPublic(), scheme))
                    .as("mutating a returned copy must not affect the head")
                    .isTrue();
        }
    }
}
