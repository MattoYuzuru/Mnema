# Integrated authoring browser evidence

Captured 2026-09-19 from the issue #202 candidate. The fixture starts packaged
Identity and Learning services, disposable PostgreSQL 18.6, the production Angular
distribution and Chrome over real local HTTPS. Reproduce from the repository root:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
DOCKER_HOST=unix:///Users/yuzuru/.colima/default/docker.sock \
python3 scripts/browser-identity/run.py --authoring \
  --dist frontend/dist/mnema-frontend \
  --node /opt/homebrew/Cellar/node@22/22.23.2_2/bin/node --timeout 300
```

`browser.json` and `fixture.json` are the value-silent machine-readable summaries.
Screenshots show the restored editor at 390 CSS px and the published Browse view at
1440 CSS px. The harness deletes its mode-0700 private directory and retains no
passwords, tokens, cookies, keys or service logs here.

The local timing and request count describe this bounded fixture only. They are not
a production SLO or deployment claim. Human screen-reader, physical touch and real
OS IME gaps are recorded in the parent authoring UI evidence.
