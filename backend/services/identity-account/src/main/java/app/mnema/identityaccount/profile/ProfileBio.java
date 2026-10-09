package app.mnema.identityaccount.profile;

import app.mnema.identityaccount.contract.AccountFailure;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Plain text with bounded paragraphs; protocol fields retain their single-line rules. */
final class ProfileBio {
    private ProfileBio() {
    }

    static String normalize(String value) {
        if (value == null || value.length() > 200 || value.chars().anyMatch(c ->
                (c < 32 && c != '\t' && c != '\n' && c != '\r') || c == 127)) {
            throw new AccountFailure(400, "invalid_profile_bio");
        }
        String normalized = Arrays.stream(value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1))
                .map(line -> line.replaceAll("[\\t\\p{Zs}]+", " ").replaceAll("^ +| +$", ""))
                .collect(Collectors.joining("\n"))
                .replaceAll("^\\n+|\\n+$", "").replaceAll("\\n{3,}", "\n\n");
        if (normalized.split("\n", -1).length > 6) throw new AccountFailure(400, "invalid_profile_bio");
        return normalized;
    }
}
