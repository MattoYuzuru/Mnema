#!/usr/bin/env python3
"""Bounded, disposable real Identity -> Learning HTTP security composition.

No dependencies beyond Python stdlib, Java 21, Docker and cached postgres:18.
Never prints credentials, bearer tokens, session cookies or private JWK material.
"""
import argparse
import base64
import concurrent.futures
import datetime
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.parse
from http.cookies import SimpleCookie

ROOT = Path(__file__).resolve().parents[2]
PASSWORD = "fixture-correct-horse-battery-42"
NEW_PASSWORD = "fixture-new-correct-horse-battery-43"
ISSUER = "https://identity.mnema.test"
REDIRECT = "https://mnema.app/auth/callback"


def command(args, **kwargs):
    kwargs.setdefault("timeout", 60)
    return subprocess.run(args, check=True, capture_output=True, **kwargs)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def require(condition, label):
    if not condition:
        raise AssertionError(label)


class Client:
    def __init__(self, port):
        self.port = port
        self.cookies = {}

    def request(self, method, path, payload=None, bearer=None, form=False):
        headers = {}
        if self.cookies:
            # Explicit local fixture transport; Secure cookie behavior is NOT a browser test.
            headers["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        if bearer is not None:
            headers["Authorization"] = "Bearer " + bearer
        if payload is not None:
            body = urllib.parse.urlencode(payload) if form else json.dumps(payload)
            headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        else:
            body = None
        if method not in ("GET", "HEAD", "OPTIONS") and not form and bearer is None:
            status, _, csrf = self.request("GET", "/api/accounts/csrf")
            require(status == 200, "csrf request failed")
            headers[csrf["headerName"]] = csrf["token"]
            headers["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=6)
        try:
            connection.request(method, path, body, headers)
            response = connection.getresponse()
            response_headers = response.getheaders()
            raw = response.read()
            for name, value in response_headers:
                if name.lower() == "set-cookie":
                    cookie = SimpleCookie(value)
                    for key, morsel in cookie.items():
                        if morsel["max-age"] == "0":
                            self.cookies.pop(key, None)
                        else:
                            self.cookies[key] = morsel.value
            try:
                result = json.loads(raw) if raw else None
            except (ValueError, UnicodeDecodeError):
                result = None  # Do not surface raw response bodies in failure evidence.
            return response.status, dict(response_headers), result
        finally:
            connection.close()


class Fixture:
    def __init__(self, args):
        self.args = args
        temp_parent = Path.home() if sys.platform == "darwin" else Path(os.environ.get("RUNNER_TEMP", tempfile.gettempdir()))
        self.tmp = Path(tempfile.mkdtemp(prefix="mnema-learning-security-", dir=temp_parent))
        os.chmod(self.tmp, 0o700)
        self.container = "mnema-r74-auth-" + secrets.token_hex(5)
        self.processes = []
        self.logs = []
        self.started_container = False
        self.identity_port, self.learning_port = free_port(), free_port()
        while self.identity_port == self.learning_port:
            self.learning_port = free_port()
        self.results = []
        self.started = time.monotonic()
        self.db_password = secrets.token_urlsafe(24)

    def record(self, name, **details):
        value = {"scenario": name, "state": "passed", **details}
        self.results.append(value)
        print(json.dumps(value, sort_keys=True), flush=True)

    def sql(self, sql, user="postgres", expected=0):
        result = subprocess.run(["docker", "exec", "-i", self.container, "psql", "-X", "-qAt",
                                 "-v", "ON_ERROR_STOP=1", "-U", user, "-d", "fixture"],
                                input=sql.encode(), capture_output=True, timeout=20)
        if result.returncode != expected:
            (self.tmp / "sql-error.log").write_bytes(result.stderr)
        require(result.returncode == expected, "synthetic SQL operation failed")
        return result.stdout.decode().strip()

    def boot(self, module, port, username):
        jar = ROOT / f"backend/services/{module}/build/libs/{module}-0.0.1-SNAPSHOT.jar"
        require(jar.is_file(), f"missing boot jar: {module}")
        environment = {k: v for k, v in os.environ.items()
                       if not k.startswith(("SPRING_", "MNEMA_", "LEARNING_", "IDENTITY_", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"))}
        environment.update({"PORT": str(port), "SPRING_DATASOURCE_URL": self.jdbc,
                            "SPRING_DATASOURCE_USERNAME": username,
                            "SPRING_DATASOURCE_PASSWORD": self.db_password,
                            "MNEMA_IDENTITY_ISSUER": ISSUER,
                            "MNEMA_IDENTITY_SIGNING_JWK_SET_FILE": str(self.tmp / "signing.json"),
                            "MNEMA_IDENTITY_SIGNING_ACTIVE_KID": "blackbox",
                            "MNEMA_IDENTITY_DELETION_ENABLED": "true",
                            "MNEMA_IDENTITY_DELETION_RECOVERY_PERIOD": "PT1H",
                            "MNEMA_IDENTITY_DELETION_SCAN_DELAY": "PT24H",
                            "APP_ENV": "local-blackbox"})
        arguments = ["java", "-Xms64m", "-Xmx384m", "-jar", str(jar), "--server.address=127.0.0.1"]
        if module == "learning":
            arguments += [f"--learning.identity.transport-base=http://127.0.0.1:{self.identity_port}",
                          "--learning.identity.allow-loopback-http=true"]
        log = (self.tmp / f"{module}.log").open("wb")
        self.logs.append(log)
        process = subprocess.Popen(arguments, env=environment, stdout=log, stderr=subprocess.STDOUT)
        self.processes.append(process)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            require(process.poll() is None, f"{module} exited before readiness; use --keep-on-failure for private diagnostics")
            try:
                status, _, _ = Client(port).request("GET", "/api/actuator/health/readiness")
                if status == 200:
                    return process, hashlib.sha256(jar.read_bytes()).hexdigest()
            except (OSError, http.client.HTTPException):
                pass
            time.sleep(0.25)
        raise AssertionError(f"{module} readiness deadline exceeded")

    def start(self):
        command(["docker", "image", "inspect", "postgres:18"])
        command(["java", str(Path(__file__).with_name("FixtureKey.java")), str(self.tmp / "signing.json")])
        os.chmod(self.tmp / "signing.json", 0o600)
        command(["docker", "run", "--detach", "--name", self.container, "--cpus", "2", "--memory", "512m",
                 "--publish", "127.0.0.1::5432", "--env", "POSTGRES_PASSWORD=" + self.db_password,
                 "--env", "POSTGRES_DB=fixture", "postgres:18"])
        self.started_container = True
        mapped = command(["docker", "port", self.container, "5432/tcp"]).stdout.decode().strip()
        self.jdbc = "jdbc:postgresql://127.0.0.1:" + mapped.rsplit(":", 1)[1] + "/fixture"
        deadline = time.monotonic() + 30
        while subprocess.run(["docker", "exec", self.container, "pg_isready", "-h", "127.0.0.1", "-U", "postgres"],
                             capture_output=True).returncode:
            require(time.monotonic() < deadline, "PostgreSQL readiness deadline exceeded")
            time.sleep(0.25)
        self.sql(f"""CREATE ROLE identity_fixture LOGIN PASSWORD '{self.db_password}';
CREATE ROLE learning_fixture LOGIN PASSWORD '{self.db_password}';
GRANT CONNECT,CREATE ON DATABASE fixture TO identity_fixture,learning_fixture;
CREATE SCHEMA app_identity AUTHORIZATION identity_fixture;
CREATE SCHEMA app_learning AUTHORIZATION learning_fixture;
REVOKE ALL ON SCHEMA app_identity FROM PUBLIC,learning_fixture;
REVOKE ALL ON SCHEMA app_learning FROM PUBLIC,identity_fixture;""")
        self.identity_process, identity_hash = self.boot("identity-account", self.identity_port, "identity_fixture")
        _, learning_hash = self.boot("learning", self.learning_port, "learning_fixture")
        self.record("both_real_apps_ready", identity_jar_sha256=identity_hash, learning_jar_sha256=learning_hash,
                    harness_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    fixture_key_source_sha256=hashlib.sha256(Path(__file__).with_name("FixtureKey.java").read_bytes()).hexdigest(),
                    postgres=self.sql("SELECT version()"), max_clients=self.args.clients,
                    started_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
        self.sql("SELECT account_id FROM app_identity.account LIMIT 1", user="learning_fixture", expected=3)
        self.record("learning_role_denied_identity_schema")

    def account(self, name):
        browser = Client(self.identity_port)
        status, _, profile = browser.request("POST", "/api/accounts/register", {
            "email": name + "@example.invalid", "loginName": name,
            "password": PASSWORD, "profileUsername": name})
        require(status == 201, f"registration status {status}")
        self.login(browser, name)
        status, _, profile = browser.request("GET", "/api/accounts/me")
        require(status == 200, "registered account profile")
        account_id = profile.get("accountId", profile.get("id"))
        require(account_id is not None, "account UUID missing from profile")
        return browser, account_id

    def login(self, browser, name, password=PASSWORD):
        browser.cookies.clear()  # A generation-only mutation intentionally leaves an invalid old session.
        status, _, _ = browser.request("POST", "/api/accounts/login", {"login": name, "password": password})
        require(status == 200, f"login status {status}")

    def authorization(self, browser, scope, denied=False):
        verifier = secrets.token_urlsafe(48)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
        state = secrets.token_urlsafe(12)
        query = urllib.parse.urlencode({"response_type": "code", "client_id": "mnema-web",
                                       "redirect_uri": REDIRECT, "scope": scope, "state": state,
                                       "code_challenge": challenge, "code_challenge_method": "S256"})
        status, headers, _ = browser.request("GET", "/oauth2/authorize?" + query)
        if denied:
            require(status == 403, f"recovery-only authorization status {status}, expected 403")
            return None
        require(status == 302, f"authorization status {status}")
        location = headers.get("Location", headers.get("location", ""))
        require(location.startswith(REDIRECT + "?"), "authorization redirect mismatch")
        params = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)
        require(params.get("state") == [state] and "code" in params, "authorization response invalid")
        return {"grant_type": "authorization_code", "client_id": "mnema-web", "redirect_uri": REDIRECT,
                "code": params["code"][0], "code_verifier": verifier}

    def token(self, browser, scope="openid learning.read learning.write"):
        form = self.authorization(browser, scope)
        status, _, tokens = Client(self.identity_port).request("POST", "/oauth2/token", form, form=True)
        require(status == 200 and tokens.get("access_token") and "refresh_token" not in tokens, "PKCE exchange failed")
        return tokens

    def learning(self, token=None, method="GET"):
        return Client(self.learning_port).request(method, "/api/_blackbox", bearer=token)[0]

    def unavailable(self, token):
        status, headers, body = Client(self.learning_port).request("GET", "/api/_blackbox", bearer=token)
        headers = {key.lower(): value for key, value in headers.items()}
        require(status == 503, f"Identity unavailable: Learning status {status}, expected 503")
        require(body["code"] == "IDENTITY_UNAVAILABLE" and headers.get("retry-after") == "1"
                and headers.get("cache-control") == "no-store", "safe Identity unavailable response")

    def valid(self, token):
        require(Client(self.identity_port).request("GET", "/userinfo", bearer=token)[0] == 200, "live userinfo")
        require(self.learning(token) == 404, "live bearer must reach unknown Learning route")

    def revoked(self, name, token, **details):
        start = time.monotonic()
        require(Client(self.identity_port).request("GET", "/userinfo", bearer=token)[0] == 401, name + " userinfo")
        require(self.learning(token) == 401, name + " Learning")
        with concurrent.futures.ThreadPoolExecutor(max_workers=self.args.clients) as executor:
            statuses = list(executor.map(lambda _: self.learning(token), range(16)))
        require(set(statuses) == {401}, name + " repeated revoked requests")
        self.record(name, identity_status=401, learning_status=401,
                    repeated_denials=len(statuses),
                    observation_ms=round((time.monotonic() - start) * 1000, 2), **details)

    def run(self):
        self.start()
        require(self.learning() == 401, "missing token")
        browser, account_id = self.account("fixture_main")
        tokens = self.token(browser)
        access = tokens["access_token"]
        self.valid(access)
        require(self.learning(access, "POST") == 404, "learning.write route")
        require(self.learning(tokens["id_token"]) == 401, "ID token must not authorize API")
        require(self.learning(access[:-8] + "AAAAAAAA") == 401, "tampered signature")
        require(Client(self.identity_port).request("GET", "/api/accounts/me", bearer=access)[0] == 403,
                "learning token must not grant account access")
        self.record("pkce_real_identity_to_learning", anonymous=401, authenticated_get=404,
                    authenticated_post=404, id_token=401, tampered=401, identity_account_scope=403)
        for scope, expected_get, expected_post in (("openid account.read", 403, 403),
                                                  ("openid learning.read", 404, 403),
                                                  ("openid learning.write", 403, 404)):
            scoped = self.token(browser, scope)["access_token"]
            require(self.learning(scoped) == expected_get, "GET scope guard " + scope)
            require(self.learning(scoped, "POST") == expected_post, "POST scope guard " + scope)
        self.record("least_privilege_scope_matrix")
        form = self.authorization(browser, "openid learning.read")
        status, _, _ = Client(self.identity_port).request("POST", "/oauth2/token", form, form=True)
        require(status == 200, "first code exchange")
        require(Client(self.identity_port).request("POST", "/oauth2/token", form, form=True)[0] == 400, "code replay")
        form = self.authorization(browser, "openid learning.read")
        form["code_verifier"] = secrets.token_urlsafe(48)
        require(Client(self.identity_port).request("POST", "/oauth2/token", form, form=True)[0] == 400, "wrong PKCE verifier")
        self.record("pkce_code_replay_and_wrong_verifier_rejected")
        start = time.monotonic()
        def paced_request(index):
            scheduled = start + index * self.args.duration_seconds / max(1, self.args.requests - 1)
            time.sleep(max(0, scheduled - time.monotonic()))
            before = time.monotonic()
            return self.learning(access), (time.monotonic() - before) * 1000
        with concurrent.futures.ThreadPoolExecutor(max_workers=self.args.clients) as executor:
            samples = list(executor.map(paced_request, range(self.args.requests)))
        require({status for status, _ in samples} == {404}, "bounded repeated real-service requests")
        latencies = sorted(latency for _, latency in samples)
        self.record("bounded_live_sequence", requests=len(samples), clients=self.args.clients,
                    p50_ms=round(latencies[len(latencies) // 2], 2),
                    p95_ms=round(latencies[min(len(latencies) - 1, int(len(latencies) * 0.95))], 2),
                    elapsed_ms=round((time.monotonic() - start) * 1000, 2))
        require(browser.request("POST", "/api/accounts/logout")[0] == 204, "logout")
        self.revoked("real_http_logout", access)
        self.login(browser, "fixture_main")
        access = self.token(browser)["access_token"]
        self.valid(access)
        require(browser.request("POST", "/api/accounts/me/password", {
            "currentPassword": PASSWORD, "newPassword": NEW_PASSWORD})[0] == 204, "password change")
        self.revoked("real_http_password_change", access)
        self.login(browser, "fixture_main", NEW_PASSWORD)
        access = self.token(browser)["access_token"]
        self.valid(access)
        reset = secrets.token_urlsafe(32)
        digest = hashlib.sha256(reset.encode()).hexdigest()
        self.sql(f"""UPDATE app_identity.account SET email_verified=true WHERE account_id='{account_id}';
INSERT INTO app_identity.ownership_challenge(secret_hash,account_id,purpose,generation,expires_at)
SELECT '{digest}',account_id,'RESET_PASSWORD',security_generation,now()+interval '10 minutes'
FROM app_identity.account WHERE account_id='{account_id}';""")
        reset_client = Client(self.identity_port)
        require(reset_client.request("POST", "/api/accounts/password-reset/confirm", {
            "token": reset, "newPassword": PASSWORD})[0] == 204, "password reset confirmation")
        self.revoked("real_http_reset_confirmation_synthetic_challenge", access, mail_delivery="not_tested")
        require(reset_client.request("POST", "/api/accounts/password-reset/confirm", {
            "token": reset, "newPassword": NEW_PASSWORD})[0] == 400, "reset replay")
        self.record("reset_proof_replay_rejected")
        self.login(browser, "fixture_main")
        access = self.token(browser)["access_token"]
        self.valid(access)
        admin, admin_id = self.account("fixture_admin")
        require(browser.request("POST", f"/api/accounts/admin/accounts/{admin_id}/ban", {"reason": "forbidden fixture action"})[0] == 403,
                "non-admin moderation must be denied")
        self.valid(access)
        self.record("real_http_non_admin_moderation_denied")
        self.sql(f"UPDATE app_identity.account SET is_admin=true WHERE account_id='{admin_id}'")
        require(admin.request("POST", f"/api/accounts/admin/accounts/{account_id}/ban", {"reason": "local fixture"})[0] == 204, "ban")
        self.revoked("real_http_admin_ban_synthetic_admin_bootstrap", access)
        require(admin.request("POST", f"/api/accounts/admin/accounts/{account_id}/unban")[0] == 204, "unban")
        self.revoked("real_http_unban_does_not_restore_old_token", access)
        self.login(browser, "fixture_main")
        access = self.token(browser)["access_token"]
        self.valid(access)
        before = self.sql(f"SELECT count(*) FROM app_identity.oauth2_authorization WHERE principal_name='{account_id}'")
        self.sql(f"UPDATE app_identity.account SET security_generation=security_generation+1 WHERE account_id='{account_id}'")
        require(self.sql(f"SELECT count(*) FROM app_identity.oauth2_authorization WHERE principal_name='{account_id}'") == before,
                "generation fixture must preserve grant")
        self.revoked("synthetic_generation_bump_grant_retained", access)
        self.login(browser, "fixture_main")
        access = self.token(browser)["access_token"]
        self.valid(access)
        generation = self.sql(f"SELECT security_generation FROM app_identity.account WHERE account_id='{account_id}'")
        self.sql(f"DELETE FROM app_identity.oauth2_authorization WHERE principal_name='{account_id}'")
        require(self.sql(f"SELECT security_generation FROM app_identity.account WHERE account_id='{account_id}'") == generation,
                "grant fixture must preserve generation")
        self.revoked("synthetic_grant_removal_generation_unchanged", access)
        access = self.token(browser)["access_token"]
        self.valid(access)
        status, _, proof = browser.request("POST", "/api/accounts/me/proofs", {"password": PASSWORD, "purpose": "DELETE_ACCOUNT"})
        require(status == 200, "deletion proof")
        status, _, deletion = browser.request("POST", "/api/accounts/me/deletion", {"proof": proof["token"]})
        require(status == 202, "deletion request")
        self.revoked("real_http_pending_deletion", access)
        self.login(browser, "fixture_main")
        require(browser.request("GET", "/api/accounts/me")[0] == 403, "recovery-only session cannot read account")
        # Identity deliberately invalidates a recovery session used outside its allowed boundary.
        self.login(browser, "fixture_main")
        self.authorization(browser, "openid learning.read", denied=True)
        self.record("real_http_recovery_login_is_restricted")
        self.login(browser, "fixture_main")
        status, _, recovered = browser.request("DELETE", "/api/accounts/deletion/recovery/" + deletion["operationId"])
        require(status == 200 and recovered["ordinaryAccessRestored"], "cancel pending deletion")
        self.revoked("real_http_deletion_cancellation_keeps_old_token_revoked", access)
        self.valid(self.token(browser)["access_token"])
        self.record("real_http_deletion_cancellation_allows_new_pkce_token")
        survivor, _ = self.account("fixture_survivor")
        survivor_token = self.token(survivor)["access_token"]
        self.valid(survivor_token)
        self.identity_process.send_signal(signal.SIGSTOP)
        start = time.monotonic()
        try:
            self.unavailable(survivor_token)
        finally:
            self.identity_process.send_signal(signal.SIGCONT)
        self.record("real_identity_process_pause_times_out_closed", learning_status=503,
                    observation_ms=round((time.monotonic() - start) * 1000, 2))
        self.valid(survivor_token)
        self.record("real_identity_resume_restores_still_valid_token")
        self.identity_process.terminate()
        self.identity_process.wait(timeout=15)
        self.unavailable(survivor_token)
        require(Client(self.learning_port).request("GET", "/api/actuator/health/liveness")[0] == 200, "Learning liveness during Identity outage")
        self.record("real_identity_process_outage_fails_closed", learning_status=503, learning_liveness=200)
        self.record("suite_complete", scenarios=len(self.results) + 1,
                    elapsed_seconds=round(time.monotonic() - self.started, 2))

    def close(self, failed):
        for process in reversed(self.processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        for log in self.logs:
            log.close()
        if self.started_container:
            command(["docker", "rm", "--force", "--volumes", self.container])
        if failed and self.args.keep_on_failure:
            print(json.dumps({"private_diagnostics": str(self.tmp), "warning": "contains disposable secrets; remove after debugging"}), flush=True)
        else:
            shutil.rmtree(self.tmp)
        print(json.dumps({"cleanup": "complete", "private_files_retained": bool(failed and self.args.keep_on_failure)}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clients", type=int, default=4, choices=range(1, 9))
    parser.add_argument("--requests", type=int, default=120)
    parser.add_argument("--duration-seconds", type=int, default=30, choices=range(0, 121))
    parser.add_argument("--keep-on-failure", action="store_true", help="keep mode-0700 private logs/keys for local debugging")
    args = parser.parse_args()
    if not 1 <= args.requests <= 1000:
        parser.error("requests must be between 1 and 1000")
    os.umask(0o077)
    def interrupted(_signal, _frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    fixture = Fixture(args)
    failed = True
    try:
        fixture.run()
        failed = False
    except KeyboardInterrupt:
        print(json.dumps({"suite": "interrupted"}), flush=True)
    except Exception as error:
        # Exceptions from HTTP/SQL processes may carry secrets: emit only controlled assertions.
        message = str(error) if isinstance(error, AssertionError) else type(error).__name__
        print(json.dumps({"suite": "failed", "reason": message}), flush=True)
    finally:
        fixture.close(failed)
    raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
