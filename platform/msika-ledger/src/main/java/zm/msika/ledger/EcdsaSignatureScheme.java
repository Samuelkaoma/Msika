package zm.msika.ledger;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/**
 * ECDSA over NIST P-256 with SHA-256.
 *
 * <p><strong></strong> The default scheme. Widely implemented, available in every JDK, and
 * supported by every HSM we might eventually use - which matters, because the production
 * signing key must never exist outside one.
 *
 * <p>This implementation holds keys in memory and is therefore for development and testing.
 * Production signing goes through the same {@link SignatureScheme} interface backed by PKCS11,
 * which is exactly why the interface exists.
 */
public final class EcdsaSignatureScheme implements SignatureScheme {

    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final String KEY_ALGORITHM = "EC";
    private static final String CURVE = "secp256r1";

    @Override
    public String algorithm() {
        return SIGNATURE_ALGORITHM;
    }

    @Override
    public byte[] sign(byte[] message, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    @Override
    public boolean verify(byte[] message, byte[] signatureBytes, PublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(message);
            return signature.verify(signatureBytes);
        } catch (GeneralSecurityException e) {
            // A forged or malformed signature is an expected input to a verifier, not an
            // exceptional condition. Fail closed and say no.
            return false;
        }
    }

    @Override
    public KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            generator.initialize(new ECGenParameterSpec(CURVE));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("key generation failed", e);
        }
    }
}
