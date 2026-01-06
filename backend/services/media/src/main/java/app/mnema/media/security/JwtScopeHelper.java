package app.mnema.media.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class JwtScopeHelper {

    public boolean hasAnyScope(Jwt jwt, Set<String> allowed) {
        if (jwt == null || allowed == null || allowed.isEmpty()) {
            return false;
        }
        Set<String> scopes = new HashSet<>();

        List<String> scp = jwt.getClaimAsStringList("scp");
        if (scp != null) {
            scopes.addAll(scp);
        }

        String scope = jwt.getClaimAsString("scope");
        if (scope != null && !scope.isBlank()) {
            scopes.addAll(Arrays.asList(scope.split(" ")));
        }

        for (String value : allowed) {
            if (scopes.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
