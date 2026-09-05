package app.mnema.identityaccount.transfer;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class AccountTransferCli {
    private AccountTransferCli() {
    }

    public static void main(String[] arguments) {
        try {
            execute(arguments, System.getenv());
        } catch (AccountTransferFailure failure) {
            System.err.println("account_transfer_error=" + failure.code());
            System.exit(2);
        } catch (RuntimeException failure) {
            System.err.println("account_transfer_error=internal_failure");
            System.exit(2);
        }
    }

    static void execute(String[] arguments, Map<String, String> environment) {
        AccountTransferBundle.require("true".equals(environment.get("MNEMA_ACCOUNT_TRANSFER_DISPOSABLE_TARGET")),
                "disposable_target_confirmation_required");
        String appEnvironment = environment.getOrDefault("APP_ENV", "").trim().toLowerCase(java.util.Locale.ROOT);
        AccountTransferBundle.require(!Set.of("prod", "production").contains(appEnvironment),
                "production_execution_forbidden");
        AccountTransferBundle.require(arguments.length >= 2, "invalid_arguments");
        String operation = arguments[0];
        Map<String, Path> paths = parsePaths(Arrays.copyOfRange(arguments, 1, arguments.length));
        AccountTransferCodec codec = new AccountTransferCodec(encryptionKey(environment));
        switch (operation) {
            case "export" -> {
                requireKeys(paths, Set.of("artifact"));
                AccountTransferArtifact artifact = new LegacyAccountExporter(dataSource(environment, "SOURCE"),
                        new DirectoryAvatarBlobStore(requiredPath(environment, "MNEMA_ACCOUNT_TRANSFER_SOURCE_AVATAR_ROOT")))
                        .export();
                codec.write(paths.get("artifact"), artifact);
                System.out.printf("account_transfer_status=exported accounts=%d credentials=%d identities=%d avatars=%d%n",
                        artifact.bundle().accounts().size(),
                        artifact.bundle().accounts().stream().filter(account -> account.credential() != null).count(),
                        artifact.bundle().accounts().stream().mapToLong(account -> account.externalIdentities().size()).sum(),
                        artifact.bundle().accounts().stream().filter(account -> account.avatar() != null).count());
            }
            case "import" -> {
                requireKeys(paths, Set.of("artifact", "evidence"));
                AccountTransferArtifact artifact = codec.read(paths.get("artifact"));
                AccountTransferEvidence evidence = importer(environment, codec).importAndReconcile(artifact);
                writeEvidence(paths.get("evidence"), codec.evidence(evidence));
                System.out.printf("account_transfer_status=reconciled accounts=%d credentials=%d identities=%d avatars=%d%n",
                        evidence.accountCount(), evidence.credentialCount(), evidence.externalIdentityCount(),
                        evidence.avatarCount());
            }
            case "reconcile" -> {
                requireKeys(paths, Set.of("artifact", "evidence"));
                AccountTransferArtifact artifact = codec.read(paths.get("artifact"));
                AccountTransferEvidence evidence = importer(environment, codec).reconcile(artifact);
                writeEvidence(paths.get("evidence"), codec.evidence(evidence));
                System.out.printf("account_transfer_status=reconciled accounts=%d credentials=%d identities=%d avatars=%d%n",
                        evidence.accountCount(), evidence.credentialCount(), evidence.externalIdentityCount(),
                        evidence.avatarCount());
            }
            default -> throw new AccountTransferFailure("invalid_operation");
        }
    }

    private static AccountTransferImporter importer(Map<String, String> environment, AccountTransferCodec codec) {
        return new AccountTransferImporter(dataSource(environment, "TARGET"),
                new DirectoryAvatarBlobStore(requiredPath(environment, "MNEMA_ACCOUNT_TRANSFER_TARGET_AVATAR_ROOT")), codec);
    }

    private static DataSource dataSource(Map<String, String> environment, String side) {
        String prefix = "MNEMA_ACCOUNT_TRANSFER_" + side + "_";
        String url = required(environment, prefix + "URL");
        AccountTransferBundle.require(url.startsWith("jdbc:postgresql://"), "invalid_database_url");
        return new DriverManagerDataSource(url, required(environment, prefix + "USERNAME"),
                required(environment, prefix + "PASSWORD"));
    }

    private static Map<String, Path> parsePaths(String[] arguments) {
        Map<String, Path> result = new HashMap<>();
        for (String argument : arguments) {
            int separator = argument.indexOf('=');
            AccountTransferBundle.require(argument.startsWith("--") && separator > 2, "invalid_arguments");
            String name = argument.substring(2, separator);
            String value = argument.substring(separator + 1);
            AccountTransferBundle.require(!value.isBlank() && result.put(name, Path.of(value)) == null,
                    "invalid_arguments");
        }
        return result;
    }

    private static void requireKeys(Map<String, Path> paths, Set<String> keys) {
        AccountTransferBundle.require(paths.keySet().equals(keys), "invalid_arguments");
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        AccountTransferBundle.require(value != null && !value.isBlank(), "missing_configuration");
        return value;
    }

    private static Path requiredPath(Map<String, String> environment, String name) {
        return Path.of(required(environment, name));
    }

    private static byte[] encryptionKey(Map<String, String> environment) {
        try {
            return Base64.getDecoder().decode(required(environment, "MNEMA_ACCOUNT_TRANSFER_ENCRYPTION_KEY_B64"));
        } catch (IllegalArgumentException exception) {
            throw new AccountTransferFailure("invalid_encryption_key", exception);
        }
    }

    private static void writeEvidence(Path output, byte[] bytes) {
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        AccountTransferBundle.require(parent != null && Files.isDirectory(parent), "invalid_evidence_parent");
        AccountTransferBundle.require(!Files.exists(absolute), "evidence_exists");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".mnema-account-evidence-", ".json");
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Parent ACL is authoritative on non-POSIX platforms.
            }
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute);
            }
        } catch (IOException exception) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The original evidence write failure remains authoritative.
                }
            }
            throw new AccountTransferFailure("evidence_write_failed", exception);
        }
    }
}
