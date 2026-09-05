package app.mnema.identityaccount.local;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.net.URI;

@Controller
public class LoginPage {
    @GetMapping("/login/continue")
    void resume(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var cache = new HttpSessionRequestCache();
        var saved = cache.getRequest(request, response);
        if (saved == null) {
            response.sendRedirect("/login");
            return;
        }
        var uri = URI.create(saved.getRedirectUrl());
        if (!"/oauth2/authorize".equals(uri.getRawPath())) {
            response.sendError(400);
            return;
        }
        cache.removeRequest(request, response);
        response.sendRedirect(uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
    }

    /**
     * Same JSON login endpoint/session authority as the SPA; no password OAuth grant.
     */
    @GetMapping(value = "/login", produces = "text/html")
    @ResponseBody
    String login(CsrfToken token) {
        return """
                <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Sign in to Mnema</title><main><h1>Sign in</h1><form id="login">
                <label>Login or email <input name="login" autocomplete="username" required maxlength="320"></label>
                <label>Password <input name="password" type="password" autocomplete="current-password" required maxlength="128"></label>
                <button>Sign in</button><p role="alert" id="error"></p></form></main>
                <script>document.querySelector('form').onsubmit=async e=>{e.preventDefault();let t=await fetch('/api/accounts/csrf').then(r=>r.json());let r=await fetch('/api/accounts/login',{method:'POST',headers:{'Content-Type':'application/json',[t.headerName]:t.token},body:JSON.stringify(Object.fromEntries(new FormData(e.target)))});if(r.ok){location.href='/login/continue';}else{document.querySelector('#error').textContent='Unable to sign in.';}};</script></html>
                """;
    }
}
