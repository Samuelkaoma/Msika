package zm.msika.api.seal;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import zm.msika.ledger.EcdsaSignatureScheme;

/**
 * The command behind {@code scripts/verify-seal}: checks every council's daily seals against the
 * database and says whether anything was changed.
 *
 * <pre>
 *   --url jdbc:postgresql://host:5432/msika      (default: MSIKA_VERIFY_DB_URL, else localhost)
 *   --user msika_owner                           (default: MSIKA_VERIFY_DB_USER, else msika_owner)
 *   password: environment MSIKA_VERIFY_DB_PASSWORD, else MSIKA_OWNER_PASSWORD (never a command-line option)
 *   --public-key BASE64                          (default: MSIKA_SEAL_PUBLIC_KEY)
 *   --key-file path/to/seal-key.properties       (default: .msika-keys/seal-key.properties)
 *   --council UUID                               (default: every council)
 * </pre>
 *
 * <p>Exit code 0: every seal verifies. 1: a problem was found (the ledger or a seal was altered). 2: it
 * could not run (bad options, no key, cannot connect). The password is read from the environment so it
 * never appears in a process list or shell history.
 */
public final class VerifySealsCli {

    private VerifySealsCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    /** Runs the check and returns the exit code. Separate from {@link #main} so tests can call it. */
    public static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        try {
            Map<String, String> options = parse(args);
            String url = pick(options.get("url"), env.get("MSIKA_VERIFY_DB_URL"), "jdbc:postgresql://localhost:5432/msika");
            String user = pick(options.get("user"), env.get("MSIKA_VERIFY_DB_USER"), "msika_owner");
            String password = pick(env.get("MSIKA_VERIFY_DB_PASSWORD"), env.get("MSIKA_OWNER_PASSWORD"), null);
            if (password == null) {
                err.println("verify-seal: set MSIKA_VERIFY_DB_PASSWORD (or MSIKA_OWNER_PASSWORD) to the owner role's password");
                return 2;
            }
            PublicKey key = publicKey(options, env);
            UUID council = options.containsKey("council") ? UUID.fromString(options.get("council")) : null;

            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(url, user, password, true);
            try {
                SealVerifier.Report report =
                        new SealVerifier(JdbcClient.create(dataSource), new EcdsaSignatureScheme()).verify(key, council);
                out.printf("Checked %d council(s), %d seal(s), %d entr%s.%n", report.councils(), report.seals(),
                        report.entries(), report.entries() == 1 ? "y" : "ies");
                if (report.ok()) {
                    out.println("OK: every seal verifies. Nothing was changed.");
                    return 0;
                }
                out.printf("FAILED: %d problem(s) found. The ledger or a seal has been altered:%n", report.problems().size());
                report.problems().forEach(problem -> out.println("  - " + problem));
                return 1;
            } finally {
                dataSource.destroy();
            }
        } catch (IllegalArgumentException | IOException | GeneralSecurityException e) {
            err.println("verify-seal: " + e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            err.println("verify-seal: could not check the database: " + e.getMessage());
            return 2;
        }
    }

    private static Map<String, String> parse(String[] args) {
        java.util.HashMap<String, String> options = new java.util.HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("unexpected argument: " + args[i]
                        + " (options: --url, --user, --public-key, --key-file, --council)");
            }
            options.put(args[i].substring(2), args[++i]);
        }
        return options;
    }

    private static PublicKey publicKey(Map<String, String> options, Map<String, String> env)
            throws IOException, GeneralSecurityException {
        String base64 = pick(options.get("public-key"), env.get("MSIKA_SEAL_PUBLIC_KEY"), null);
        if (base64 == null) {
            Path file = Path.of(pick(options.get("key-file"), null, ".msika-keys/seal-key.properties"));
            if (!Files.exists(file)) {
                throw new IllegalArgumentException("no public key: pass --public-key, set MSIKA_SEAL_PUBLIC_KEY, or "
                        + "give --key-file (looked for " + file + ")");
            }
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
            base64 = properties.getProperty("publicKey");
            if (base64 == null) {
                throw new IllegalArgumentException(file + " has no publicKey");
            }
        }
        return SealKeys.publicKeyOf(base64);
    }

    private static String pick(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }
}
