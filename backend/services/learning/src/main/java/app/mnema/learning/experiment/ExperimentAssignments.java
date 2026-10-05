package app.mnema.learning.experiment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Server-owned A/B assignment. The variant of an account is a function of the account and the experiment key only:
 * {@code bucket = HMAC-SHA256(secret, accountId ":" key) mod 100}, then the first variant whose cumulative weight exceeds the bucket. The same
 * account always gets the same variant, on every device and instance, and the assignment is stored nowhere. Without
 * {@code MNEMA_EXPERIMENT_SECRET} nobody can be bucketed, so everybody is {@code control} (fail closed: no accidental exposure).
 */
@Service
public class ExperimentAssignments {
    private final ExperimentSettings settings;
    private final byte[] secret;

    ExperimentAssignments(ExperimentSettings settings, @Value("${learning.experiment-secret:}") String secret) {
        this.settings = settings;
        this.secret = secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
    }

    /** The variant of every enabled experiment for {@code account}, ordered by key. */
    public Map<String, String> variants(UUID account) {
        Map<String, String> variants = new TreeMap<>();
        settings.enabled().keySet().forEach(key -> variants.put(key, variant(account, key)));
        return variants;
    }

    /** @return the variant of {@code account} in {@code key}, {@code control} for an unknown or disabled experiment or a missing secret */
    public String variant(UUID account, String key) {
        ExperimentSettings.Experiment experiment = settings.enabled().get(key);
        if (experiment == null || secret == null) return ExperimentSettings.CONTROL;
        int bucket = bucket(account, key);
        int upper = 0;
        for (ExperimentSettings.Variant variant : experiment.variants()) {
            upper += variant.weight();
            if (bucket < upper) return variant.name();
        }
        return ExperimentSettings.CONTROL;
    }

    boolean known(String key) {
        return settings.isEnabled(key);
    }

    private int bucket(UUID account, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal((account + ":" + key).getBytes(StandardCharsets.UTF_8));
            return (int) (Integer.toUnsignedLong(ByteBuffer.wrap(digest).getInt()) % 100);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", failure);
        }
    }
}
