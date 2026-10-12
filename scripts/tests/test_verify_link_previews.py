import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PRODUCTION = ROOT / "deploy/production/nginx.conf"
STANDALONE = ROOT / "frontend/nginx.conf"
LOCAL = ROOT / "deploy/local-full-stack/nginx.conf"
GENERATOR = ROOT / "frontend/docker/40-gen-app-config.sh"
MARKER = "  # --- Link previews (OG) without SSR"


def preview_block(path: Path) -> str:
    """The deck-preview locations of a configuration: from the marker to the end of its first server block."""
    text = path.read_text(encoding="utf-8")
    start = text.index(MARKER)
    end = text.index("\n}\n", start)
    return text[start:end]


def maps(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    return text[: text.index("\nserver {")]


class LinkPreviewConfigurationTest(unittest.TestCase):
    def test_the_three_web_server_files_carry_one_identical_preview_block_and_maps(self):
        reference = preview_block(PRODUCTION)
        self.assertEqual(reference, preview_block(STANDALONE))
        self.assertEqual(reference, preview_block(LOCAL))
        self.assertEqual(maps(PRODUCTION), maps(STANDALONE))
        self.assertEqual(maps(PRODUCTION), maps(LOCAL))

    def test_the_three_files_proxy_the_catalogue_sitemap_alike(self):
        def block(path: Path) -> str:
            text = path.read_text(encoding="utf-8")
            start = text.index("  location = /sitemap-decks.xml {")
            return text[start:text.index("\n  }\n", start)]
        reference = block(PRODUCTION)
        self.assertIn("proxy_pass http://$mnema_meta_upstream/api/public/sitemap.xml;", reference)
        self.assertIn('if ($mnema_meta_upstream = "") { return 404; }', reference)
        self.assertIn('proxy_set_header Authorization "";', reference)
        self.assertEqual(reference, block(STANDALONE))
        self.assertEqual(reference, block(LOCAL))

    def test_only_the_upstream_differs_between_the_files(self):
        self.assertIn("  set $mnema_meta_upstream 127.0.0.1:18082;", PRODUCTION.read_text(encoding="utf-8"))
        standalone = STANDALONE.read_text(encoding="utf-8")
        self.assertIn('  set $mnema_meta_upstream "";', standalone)
        self.assertIn("include /etc/nginx/conf.d/mnema-og-meta*.inc;", standalone)
        self.assertLess(standalone.index('set $mnema_meta_upstream ""'), standalone.index("mnema-og-meta*.inc"))
        local = LOCAL.read_text(encoding="utf-8")
        self.assertIn("  set $mnema_meta_upstream learning:8080;", local)
        self.assertIn("resolver 127.0.0.11", local)
        for path in (PRODUCTION, STANDALONE, LOCAL):
            self.assertIn("absolute_redirect off;", path.read_text(encoding="utf-8"), path)

    def test_production_asks_the_learning_port_of_the_production_compose_file(self):
        compose = (ROOT / "deploy/production/compose.yaml").read_text(encoding="utf-8")
        learning = compose.split("\n  learning:\n", 1)[1].split("\n  frontend:\n", 1)[0]
        self.assertIn('PORT: "18082"', learning)
        self.assertIn("SERVER_ADDRESS: 127.0.0.1", learning)
        self.assertIn("MNEMA_PUBLIC_BASE_URL: https://mnema.app", learning)
        caddy = (ROOT / "deploy/production/Caddyfile").read_text(encoding="utf-8")
        self.assertIn("reverse_proxy 127.0.0.1:18082", caddy)

    def test_the_calls_to_learning_are_bounded_cached_and_carry_nothing_of_the_visitor_but_the_address(self):
        block = preview_block(PRODUCTION)
        for name in ("/__mnema_og_gate", "/__mnema_og_head"):
            location = block.split(f"location = {name} {{", 1)[1].split("\n  }\n", 1)[0]
            self.assertIn("internal;", location)
            self.assertIn("proxy_pass_request_headers off;", location)
            self.assertIn("proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;", location)
            self.assertIn("proxy_next_upstream off;", location)
            self.assertNotIn("Cookie", location)
            self.assertNotIn("Authorization", location)
            self.assertNotIn("proxy_method", location)
            for directive in ("proxy_connect_timeout", "proxy_send_timeout", "proxy_read_timeout"):
                milliseconds = int(re.search(rf"{directive} (\d+)ms;", location).group(1))
                self.assertLessEqual(milliseconds, 300, directive)
            # the gate and the include share ONE cached answer per code: the key is the validated code and nothing the visitor chooses
            self.assertIn("proxy_cache mnema_og;", location)
            self.assertIn("proxy_cache_key $mnema_og_code;", location)
            self.assertIn("proxy_cache_valid 200 60s;", location)
            self.assertIn("proxy_cache_valid 404 10s;", location)
            self.assertIn("proxy_cache_valid 500 502 503 504 10s;", location)
            self.assertNotRegex(location, r"proxy_cache_valid[^;]*\b429\b")
            self.assertIn("proxy_cache_lock on;", location)
            self.assertIn("proxy_ignore_headers Cache-Control Expires X-Accel-Expires;", location)
            self.assertIn("proxy_intercept_errors on;", location)
        for path in (PRODUCTION, STANDALONE, LOCAL):
            self.assertRegex(maps(path), r"proxy_cache_path /var/cache/nginx/mnema-og levels=1:2 keys_zone=mnema_og:1m max_size=\d+m inactive=2m use_temp_path=off;")
        # a 401 of the gate becomes a plain failure, so no WWW-Authenticate reaches the page
        gate = block.split("location = /__mnema_og_gate {", 1)[1].split("\n  }\n", 1)[0]
        self.assertIn("error_page 401 = @mnema_og_gate_failed;", gate)
        self.assertIn("location @mnema_og_gate_failed {\n    return 500;\n  }", block)
        # the card goes into <head> as it is: an error body must never be printed there
        head = block.split("location = /__mnema_og_head {", 1)[1].split("\n  }\n", 1)[0]
        codes = set(map(int, re.search(r"error_page ([\d ]+) = @mnema_og_empty;", head).group(1).split()))
        self.assertTrue({301, 400, 404, 429, 500, 502, 503, 504} <= codes)
        self.assertIn("location @mnema_og_empty {\n    return 204;\n  }", block)

    def test_the_page_location_keeps_the_cache_policy_the_query_and_serves_the_plain_page_on_any_gate_failure(self):
        block = preview_block(PRODUCTION)
        entry = block.split("location ~ ^/d/ {", 1)[1].split("\n  }\n", 1)[0]
        self.assertIn("auth_request /__mnema_og_gate;", entry)
        self.assertIn("error_page 401 403 500 =200 @mnema_og_fallback;", entry)
        fallback = block.split("location @mnema_og_fallback {", 1)[1].split("\n  }\n", 1)[0]
        self.assertIn('add_header Cache-Control "public, max-age=0, must-revalidate";', fallback)
        self.assertIn("add_header X-Mnema-Preview fallback always;", fallback)
        self.assertNotIn("ssi on;", fallback)
        page = block.split("location @mnema_og_page {", 1)[1].split("\n  }\n", 1)[0]
        self.assertIn('add_header Cache-Control "public, max-age=0, must-revalidate";', page)
        self.assertIn("add_header X-Mnema-Preview $mnema_og_label always;", page)
        self.assertIn("ssi on;", page)
        self.assertIn("etag off;", page)
        self.assertIn("if_modified_since off;", page)
        # the redirect keeps the query string (a material link, utm tags); its target is validated to a deck path by the map before it is used
        self.assertIn("return 301 $mnema_og_redirect$is_args$args;", page)
        redirect = maps(PRODUCTION).split("map \"$mnema_og_canonical|$mnema_og_path\" $mnema_og_redirect {", 1)[1].split("\n}\n", 1)[0]
        self.assertIn(r"/d/[1-9A-HJ-NP-Za-km-z]{10}/[a-z0-9-]{1,80}", redirect)
        self.assertIn(r"(?!\k<canonical>$)", redirect)
        # the label is a whitelist of four values
        label = maps(PRODUCTION).split("$mnema_og_label {", 1)[1].split("\n}\n", 1)[0]
        self.assertEqual({"fallback", "redirect", "card", "generic"}, set(re.findall(r"\b(fallback|redirect|card|generic)\b", label)))
        # SSI stays off everywhere else: the only `ssi on` of the file is the preview page
        for path in (PRODUCTION, STANDALONE, LOCAL):
            self.assertEqual(1, len(re.findall(r"^\s*ssi on;", path.read_text(encoding="utf-8"), re.MULTILINE)), path)

    def test_the_preview_locations_come_after_the_asset_location_so_files_under_d_stay_404(self):
        for path in (PRODUCTION, STANDALONE, LOCAL):
            text = path.read_text(encoding="utf-8")
            self.assertLess(text.index("location ~* \\.(?:js|css|png"), text.index("location ~ ^/d/ {"), path)

    def test_index_html_carries_the_block_and_the_include_the_web_server_relies_on(self):
        source = (ROOT / "frontend/src/index.html").read_text(encoding="utf-8")
        self.assertEqual(1, source.count('<!--# block name="mnema_head" -->'))
        self.assertEqual(1, source.count("<!--# endblock -->"))
        self.assertEqual(1, source.count('<!--# include virtual="/__mnema_og_head" stub="mnema_head" -->'))
        block = source.split('<!--# block name="mnema_head" -->', 1)[1].split("<!--# endblock -->", 1)[0]
        self.assertIn("<title>Mnema</title>", block)
        self.assertIn('<meta name="robots" content="index,follow">', block)
        self.assertIn('<link rel="canonical" href="https://mnema.app/">', block)
        self.assertEqual(1, block.count("<title>"))
        # the block precedes its include, and the block holds every default tag a card replaces
        self.assertLess(source.index("<!--# endblock -->"), source.index("<!--# include"))
        outside = source.replace(block, "")
        for tag in ('name="description"', 'name="robots"', 'rel="canonical"', 'property="og:title"', 'name="twitter:title"'):
            self.assertNotIn(tag, outside)

    def test_the_contract_runs_in_both_quality_workflows(self):
        for workflow in ("pull-request.yaml", "deploy.yaml"):
            text = (ROOT / ".github/workflows" / workflow).read_text(encoding="utf-8")
            self.assertIn("run: ./scripts/test-link-previews.sh", text, workflow)
            self.assertLess(text.index("run: npm run build"), text.index("run: ./scripts/test-link-previews.sh"), workflow)


class GeneratorTest(unittest.TestCase):
    def generate(self, **environment):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            variables = {
                "PATH": os.environ["PATH"],
                "MNEMA_APP_CONFIG_OUT": str(base / "app-config.js"),
                "MNEMA_SECURITY_HEADERS_OUT": str(base / "security-headers.inc"),
                "MNEMA_OG_META_OUT": str(base / "mnema-og-meta.inc"),
                "MNEMA_APP_ENV": "development",
                **environment,
            }
            result = subprocess.run(["sh", str(GENERATOR)], env=variables, capture_output=True, text=True, timeout=20)
            include = (base / "mnema-og-meta.inc")
            return result, include.read_text(encoding="utf-8") if include.exists() else None

    def test_without_an_upstream_the_include_is_empty_and_the_feature_is_off(self):
        result, include = self.generate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", include)

    def test_a_host_and_port_and_an_ipv4_resolver_are_written_as_nginx_directives(self):
        result, include = self.generate(MNEMA_OG_META_UPSTREAM="learning:8080", MNEMA_OG_META_RESOLVER="127.0.0.11")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('set $mnema_meta_upstream "learning:8080";\nresolver 127.0.0.11 valid=10s ipv6=off;\n', include)
        for good in ("0.0.0.0", "255.255.255.255", "10.0.0.2"):
            result, include = self.generate(MNEMA_OG_META_RESOLVER=good)
            self.assertEqual(0, result.returncode, good)
            self.assertEqual(f"resolver {good} valid=10s ipv6=off;\n", include)
        result, include = self.generate(MNEMA_OG_META_UPSTREAM="10.0.0.5:65535")
        self.assertEqual('set $mnema_meta_upstream "10.0.0.5:65535";\n', include)

    def test_anything_that_could_inject_nginx_syntax_is_refused_and_writes_nothing(self):
        for value in ('a"; return 200; "b:80', "learning", "learning:", ":80", "a b:80", "learning:0", "learning:65536", "learning:123456789012",
                      "learning:08080", "a..b:80", "-x:80", "x;y:80", "x$y:80", "x:80\nset $a b"):
            result, include = self.generate(MNEMA_OG_META_UPSTREAM=value)
            self.assertNotEqual(0, result.returncode, value)
            self.assertIn("MNEMA_OG_META_UPSTREAM", result.stderr, value)
        for value in ("127.0.0.11; return 200", "dns.local", "1..2", "1.2.3.4 5.6.7.8", "256.1.1.1", "1.2.3.999", "1.2.3", "1.2.3.4.5", "01.2.3.4",
                      "1.2.3.", ".1.2.3", "1.2.3.4 ", "-1.2.3.4", "1.2.3.0004"):
            result, include = self.generate(MNEMA_OG_META_RESOLVER=value)
            self.assertNotEqual(0, result.returncode, value)


if __name__ == "__main__":
    unittest.main()
