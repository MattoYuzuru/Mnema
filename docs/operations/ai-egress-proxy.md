# AI egress proxy

Status: current, owner decision 2026-10-04 (#340, INFRA-02). Scope: Learning `worker` calls to AI providers that are
unreachable from Russia (Google Gemini TTS and transcription first; later possibly Brave or Perplexity). Config and files:
[`deploy/ai-egress-proxy/`](../../deploy/ai-egress-proxy/README.md); code: `app.mnema.learning.ai.EgressClients`
([guide](../../backend/services/learning/guide.md)).

## Shape

```
Learning worker (RU) --HTTP CONNECT, Basic proxy auth--> Squid on the Finland VPS --TLS end to end--> provider
```

- The proxy is stateless: no cache, no credentials of providers, only an access log of `host:port` lines (7 rotated files).
- Provider keys stay in the Learning `worker` environment. TLS to the provider is established through the CONNECT tunnel, so
  the VPS sees host names, ports, timing and byte counts, never a prompt, a response or a key.
- Local runs and CI use no proxy (the Stub, or `direct` providers).
- A provider opts in with `learning.ai.providers.<id>.egress=proxy` (default `direct`). A `proxy` provider without an
  active proxy has no adapter, so its capability is `PROVIDER_NOT_CONFIGURED` and the route falls back (startup INFO
  `ai_egress provider=<id> mode=proxy state=not_configured`).

## Install the proxy (Debian or Ubuntu VPS)

```bash
sudo apt-get update && sudo apt-get install -y squid apache2-utils ufw
sudo htpasswd -c /etc/squid/mnema.htpasswd mnema-egress      # prompts for the password; use a long random one
sudo chown proxy:proxy /etc/squid/mnema.htpasswd && sudo chmod 640 /etc/squid/mnema.htpasswd
sudo cp /etc/squid/squid.conf /etc/squid/squid.conf.dist
sudo cp deploy/ai-egress-proxy/squid.conf /etc/squid/squid.conf
sudoedit /etc/squid/squid.conf                                 # replace <RU_SERVER_IP> (and <OWNER_IP> while testing)
sudo squid -k parse && sudo systemctl enable --now squid && sudo systemctl reload squid
```

Check that `/usr/lib/squid/basic_ncsa_auth` exists on the distribution; the path is in `squid.conf`. An unreplaced
`<RU_SERVER_IP>` placeholder fails `squid -k parse`, so a half-edited config never starts.

### Firewall (mandatory)

Basic credentials cross the internet in clear text, so only known sources may reach the port:

```bash
sudo ufw default deny incoming && sudo ufw allow OpenSSH
sudo ufw allow from <RU_SERVER_IP> to any port 3128 proto tcp
sudo ufw allow from <OWNER_IP> to any port 3128 proto tcp     # remove after testing
sudo ufw enable
```

Optional hardening: run the hop over WireGuard and point `MNEMA_AI_EGRESS_PROXY_URL` at the tunnel address.

### Verify

From an allowed address:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -x http://user:pass@<VPS_IP>:3128 https://generativelanguage.googleapis.com/ -I   # 404 or 200
curl -sS -o /dev/null -w '%{http_code}\n' -x http://user:pass@<VPS_IP>:3128 https://example.com/ -I                          # curl fails: 403 on CONNECT
curl -sS -o /dev/null -w '%{http_code}\n' -x http://<VPS_IP>:3128 https://generativelanguage.googleapis.com/ -I              # 407 without credentials
curl -sS -o /dev/null -w '%{http_code}\n' -x http://user:pass@<VPS_IP>:3128 https://142.250.74.10/ -I -k                    # curl fails: 403, numeric targets are denied
```

From a non-allowed address the port must time out or be refused. Do not put the real password on a shared shell history;
use a private environment.

## Mnema side

Set in the private environment of the Learning `worker` (names only; values never in the repository or a log):

| Name | Meaning |
|---|---|
| `MNEMA_AI_EGRESS_PROXY_URL` | `http://host:port` of the VPS; no user info, path or query |
| `MNEMA_AI_EGRESS_PROXY_USER` / `MNEMA_AI_EGRESS_PROXY_PASSWORD` | Basic credentials; both or neither |

The JVM must start with `-Djdk.http.auth.tunneling.disabledSchemes=`: the JDK ignores Basic credentials for a CONNECT
tunnel otherwise (Java 25 `java.net.http` module documentation, "System properties"; default in `conf/net.properties`).
The Learning runtime images set it in their entrypoint; a non-container run (`bootRun`, `java -jar`) must pass it. A
startup WARN `ai_egress state=basic_tunneling_may_be_disabled` means it is missing. Each `ai_call` log line and
`mnema_ai_calls_total` carry `egress=direct|proxy`.

## Kill switch, fallback, rollback

- Fast off, no release (a restart of the Learning worker, which reads the setting at startup): `learning.ai.egress.enabled=false`
  (`LEARNING_AI_EGRESS_ENABLED=false`) or an empty `MNEMA_AI_EGRESS_PROXY_URL`. Every proxied provider then reports `PROVIDER_NOT_CONFIGURED`; each capability must have
  its documented fallback (for example the browser or Stub path) and the UI shows the capability as unavailable.
- Per provider: `learning.ai.providers.<id>.enabled=false` or `egress=direct` for one that became reachable.
- Rollback of the proxy itself: `sudo cp /etc/squid/squid.conf.dist /etc/squid/squid.conf && sudo systemctl reload squid`, or
  `sudo systemctl stop squid`. Rotate the password by re-running `htpasswd` (without `-c`) and updating the Mnema variable.
- A failing or timing-out proxy trips the per-provider circuit breaker like any transport failure; wrong credentials end in
  `TRANSIENT io_error` (no secret in the message).

## What the proxy may log

`time client result bytes method host:port` per CONNECT (`logformat mnema`), rotated 7 times. It must not log request lines
with paths or queries (a CONNECT has none), user agents, the authenticated user name, or bodies. Squid cannot see TLS content.

## Risk acceptance (Google)

The Gemini API terms restrict serving users outside the supported regions, so calling it from a Russian service through a
Finnish exit risks blocking of the Google account or key. This is an account risk, not a 152-FZ issue (the proxy stores
nothing). The owner accepted it on 2026-10-04; the kill switch and a working fallback per capability are mandatory, and a
provider block must be handled as `PROVIDER_NOT_CONFIGURED` or an open breaker, not as an outage.
