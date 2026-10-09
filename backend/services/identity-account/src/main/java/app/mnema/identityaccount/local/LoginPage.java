package app.mnema.identityaccount.local;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.net.URI;

@Controller
public class LoginPage {
    private final String frontendOrigin;

    LoginPage(@Value("${identity.frontend-origin}") URI frontendOrigin) {
        var origin = frontendOrigin.toString();
        this.frontendOrigin = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }

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
                <button>Sign in</button><p role="status" id="status"></p><p role="alert" id="error"></p></form>
                <p>Sign-in protection uses Cloudflare Turnstile. <a href="/login/privacy">Data processing</a>.</p>
                <p>By continuing you accept the <a href="%1$s/terms">Terms of Service</a> and the
                <a href="%1$s/privacy">Personal Data Policy</a> (in Russian).</p></main>
                <script src="/login/script.js" defer></script></html>
                """.formatted(frontendOrigin);
    }

    @GetMapping(value = "/login/privacy", produces = "text/html")
    @ResponseBody
    String privacy() {
        return """
                <!doctype html><html lang="en"><meta charset="utf-8"><title>Sign-in protection</title>
                <main><h1>Cloudflare Turnstile</h1><p>Your browser sends connection and device information,
                including IP address, browser information and site hostname, to Cloudflare for abuse protection.
                Mnema sends only the verification token to Siteverify, without your password, email or learning content.</p>
                <p>Cloudflare processing is described in the
                <a href="https://www.cloudflare.com/turnstile-privacy-policy/">Turnstile Privacy Addendum</a>.</p>
                <p>If protection is unavailable, sign-in stays closed until a successful new verification.</p>
                <p>The full <a href="%1$s/privacy">Personal Data Policy</a> (in Russian) lists the operator, purposes,
                recipients and your rights.</p>
                <a href="/login">Return to sign in</a></main></html>
                """.formatted(frontendOrigin);
    }

    @GetMapping(value = "/login/script.js", produces = "application/javascript")
    @ResponseBody
    String script() {
        return """
                async function protection() {
                  const config = await fetch('/api/accounts/abuse-protection', {signal: AbortSignal.timeout(8000)}).then(r => r.json());
                  if (config.mode === 'disabled') return null;
                  if (config.mode !== 'required') throw new Error('Sign-in protection is unavailable. Try again later.');
                  if (!window.turnstile) await new Promise((resolve,reject) => {
                    const script=document.createElement('script');
                    const timer=setTimeout(()=>{script.remove();reject(new Error('Protection did not load. Check script blocking and retry.'));},8000);
                    script.src='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
                    script.onload=()=>{clearTimeout(timer);window.turnstile?resolve():reject(new Error('Protection did not load.'));};
                    script.onerror=()=>{clearTimeout(timer);script.remove();reject(new Error('Protection did not load. Check your connection and retry.'));};
                    document.head.append(script);
                  });
                  return new Promise((resolve,reject)=>{
                    const container=document.createElement('div');container.setAttribute('data-mnema-turnstile','');document.body.append(container);
                    let widget,finished=false;
                    const complete=token=>{if(finished)return;finished=true;clearTimeout(timer);try{if(widget!==undefined)turnstile.remove(widget);}catch{}container.remove();
                      token?resolve(token):reject(new Error('Protection expired or failed. Retry for a new verification.'));};
                    const timer=setTimeout(()=>complete(),20000);
                    try {widget=turnstile.render(container,{sitekey:config.siteKey,action:'login',execution:'execute',tabindex:-1,'response-field':false,retry:'never','refresh-expired':'never',
                      callback:complete,'error-callback':()=>{complete();return true;},'expired-callback':()=>complete(),'timeout-callback':()=>complete()});turnstile.execute(widget);}
                    catch {complete();}
                  });
                }
                document.querySelector('form').onsubmit=async e=>{
                  e.preventDefault();const form=e.target,button=form.querySelector('button'),error=document.querySelector('#error'),status=document.querySelector('#status');
                  if(button.disabled)return;button.disabled=true;error.textContent='';status.textContent='Verifying sign-in…';
                  try {
                    const turnstileToken=await protection();
                    const csrf=await fetch('/api/accounts/csrf',{signal:AbortSignal.timeout(8000)}).then(r=>r.json());
                    const r=await fetch('/api/accounts/login',{method:'POST',signal:AbortSignal.timeout(8000),headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token},
                      body:JSON.stringify({...Object.fromEntries(new FormData(form)),turnstileToken})});
                    if(r.ok)location.href='/login/continue';else throw new Error(r.status===503?'Sign-in protection is unavailable. Try later.':'Unable to sign in. Check your details and retry.');
                  } catch(failure){error.textContent=failure.message||'Unable to sign in. Retry.';}
                  finally {form.elements.password.value='';button.disabled=false;status.textContent='';}
                };
                """;
    }
}
