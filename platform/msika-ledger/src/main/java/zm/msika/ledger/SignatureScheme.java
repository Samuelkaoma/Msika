package zm.msika.ledger;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * A digital signature algorithm.
 *
 * <p><strong>Protected Variations, and this one is not hypothetical.</strong> A ledger
 * system built today will outlive the security of the elliptic curves it starts with. NIST has
 * already standardised ML-DSA for post-quantum signatures, and a system whose signature algorithm
 * is welded into a hundred call sites cannot migrate. Everything here goes through this interface
 * so that the migration is a new implementation and a configuration change.
 *
 * <p><strong>Signing is an effect, not ledger state.</strong> ECDSA signing is randomised, so
 * signing the same head twice yields different bytes. That does not compromise the replay rule: what
 * replay must reproduce is the <em>root hash</em>, which is a pure function of the log contents.
 * Signature bytes are a published artefact over that root, not an input to it.
 */
public interface SignatureScheme {

    /** The algorithm name, as published alongside heads so verifiers know what to check. */
    String algorithm();

    /** Signs a message. */
    byte[] sign(byte[] message, PrivateKey privateKey);

    /**
     * Verifies a signature.
     *
     * <p>Returns {@code false} rather than throwing on a malformed signature: a forged signature
     * is an expected input to a verifier, not an exceptional condition.
     */
    boolean verify(byte[] message, byte[] signature, PublicKey publicKey);

    /** Generates a key pair. For development and testing; production keys live outside the repository (ARCHITECTURE.md section 9). */
    KeyPair generateKeyPair();
}
