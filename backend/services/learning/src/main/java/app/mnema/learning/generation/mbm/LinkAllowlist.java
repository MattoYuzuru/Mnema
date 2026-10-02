package app.mnema.learning.generation.mbm;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The session link allowlist after the native-v1 {@code href} profile. A source URL matches an entry by string
 * equality after lowercasing scheme and authority; the entry itself is what a {@code link} node stores.
 */
final class LinkAllowlist {

    private final Map<String, String> entries = new HashMap<>();

    /** Filters {@code urls} through the profile; every rejected entry is a line-0 warning. */
    static LinkAllowlist of(List<String> urls, Findings findings) {
        var allowlist = new LinkAllowlist();
        for (String url : urls) {
            if (NativeProfile.acceptsHref(url)) {
                allowlist.entries.putIfAbsent(normalize(url), url);
            } else {
                findings.warning(0, null, MbmCode.MBM_LINK_REJECTED_BY_PROFILE);
            }
        }
        return allowlist;
    }

    /** The allowlist entry that {@code url} equals, if any. */
    Optional<String> lookup(String url) {
        return Optional.ofNullable(entries.get(normalize(url)));
    }

    static String normalize(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int authority = scheme + 3;
        int end = authority;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) {
            end++;
        }
        return url.substring(0, scheme).toLowerCase(Locale.ROOT) + "://"
                + url.substring(authority, end).toLowerCase(Locale.ROOT) + url.substring(end);
    }
}
