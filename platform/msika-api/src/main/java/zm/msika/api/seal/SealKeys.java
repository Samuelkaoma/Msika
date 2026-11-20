package zm.msika.api.seal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import zm.msika.ledger.EcdsaSignatureScheme;

/**
 * The key that signs the daily seals. The PRIVATE key never goes in the repository (AGENTS.md rule 9;
 * ARCHITECTURE.md section 9). Where it comes from:
 *
 * <ol>
 *   <li>the settings {@code msika.seal.private-key} and {@code msika.seal.public-key} (Base64 PKCS#8 and
 *       X.509), which a real server sets from its environment; or else
 *   <li>a key file, {@code msika.seal.key-file} (default {@code .msika-keys/seal-key.properties}, a
 *       folder git ignores). If it does not exist, a development key is generated and written there.
 * </ol>
 *
 * <p>Anyone holding the public key can check a seal, so it is shown at {@code GET /api/v1/seal-key}.
 */
@Component
public class SealKeys {

    private static final Logger LOG = LoggerFactory.getLogger(SealKeys.class);

    private final String keyId;
    private final KeyPair pair;

    SealKeys(
            @Value("${msika.seal.private-key:}") String privateKey,
            @Value("${msika.seal.public-key:}") String publicKey,
            @Value("${msika.seal.key-id:}") String configuredKeyId,
            @Value("${msika.seal.key-file:.msika-keys/seal-key.properties}") String keyFile) {
        try {
            if (!privateKey.isBlank() && !publicKey.isBlank()) {
                this.pair = new KeyPair(publicKeyOf(publicKey), privateKeyOf(privateKey));
            } else {
                this.pair = loadOrCreate(Path.of(keyFile));
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("could not load the seal signing key", e);
        }
        this.keyId = configuredKeyId.isBlank() ? fingerprint(pair.getPublic()) : configuredKeyId;
    }

    /** Names this key in every seal it signs, so a verifier knows which public key to use. */
    public String keyId() {
        return keyId;
    }

    public PrivateKey privateKey() {
        return pair.getPrivate();
    }

    public PublicKey publicKey() {
        return pair.getPublic();
    }

    /** The public key as Base64 X.509, which is how it is published and how the verifier reads it. */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    // ---------------------------------------------------------------- loading

    private static KeyPair loadOrCreate(Path file) throws IOException, GeneralSecurityException {
        if (Files.exists(file)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
            return new KeyPair(
                    publicKeyOf(properties.getProperty("publicKey")), privateKeyOf(properties.getProperty("privateKey")));
        }
        KeyPair created = new EcdsaSignatureScheme().generateKeyPair();
        Properties properties = new Properties();
        properties.setProperty("algorithm", "SHA256withECDSA");
        properties.setProperty("publicKey", Base64.getEncoder().encodeToString(created.getPublic().getEncoded()));
        properties.setProperty("privateKey", Base64.getEncoder().encodeToString(created.getPrivate().getEncoded()));
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "Msika development seal signing key. DO NOT COMMIT OR SHARE.");
        }
        restrict(file);
        LOG.warn("generated a DEVELOPMENT seal signing key at {} (git ignores it). A real server must set "
                + "msika.seal.private-key and msika.seal.public-key from its environment.", file.toAbsolutePath());
        return created;
    }

    /** Owner-only permissions where the file system supports them (not on Windows, which uses its own ACLs). */
    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX file system.
        }
    }

    /** Reads a Base64 X.509 public key. Also used by the verifier tool. */
    public static PublicKey publicKeyOf(String base64) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
    }

    private static PrivateKey privateKeyOf(String base64) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
    }

    private static String fingerprint(PublicKey key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return "k-" + HexFormat.of().formatHex(hash, 0, 6);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
