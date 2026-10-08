"""Crawler metadata and hydration data must never broaden executable inline content."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_browser_security_headers import ContractError, verify_index


PUBLIC = ('<meta name="robots" content="index,follow"><app-root ngh="0"></app-root>'
          '<script id="mnema-structured-data" type="application/ld+json">'
          '{"@type":"WebSite","name":"Мнема"}</script>'
          '<script id="ng-state" type="application/json">{"__nghData__":[]}</script>'
          '<script src="/app-config.js"></script><script src="main-12345678.js" type="module"></script>')
CSR = '<meta name="robots" content="noindex,follow"><app-root></app-root><script src="main-12345678.js" type="module"></script>'


class BrowserSecurityHtml(unittest.TestCase):
    def test_public_prerender_and_private_csr_have_distinct_valid_data_blocks(self):
        verify_index(PUBLIC, 'public fixture')
        verify_index(CSR, 'private fixture')

    def test_public_document_cannot_silently_drop_structured_data(self):
        with self.assertRaises(ContractError):
            verify_index('<meta name="robots" content="index,follow"><app-root></app-root>', 'empty fixture')

    def test_inline_js_import_maps_and_unknown_data_blocks_are_rejected(self):
        for added in ('<script>alert(1)</script>', '<script type="module">alert(1)</script>',
                      '<script type="importmap">{}</script>', '<script type="speculationrules">{}</script>',
                      '<script type="application/json" id="other">{}</script>',
                      '<script type="text/javascript" type="application/json" id="ng-state">{}</script>',
                      '<script type="application/json" id="ng-state">{}</script>',
                      '<script src="/safe.js" src="/other.js"></script>',
                      '<img src="x" onerror="alert(1)">'):
            with self.subTest(added=added), self.assertRaises(ContractError):
                verify_index(PUBLIC + added, 'adversarial fixture')

    def test_malformed_json_and_script_breakout_are_rejected(self):
        for body in ('not-json', '[]', '{"text":"</script><script>alert(1)</script>"}'):
            html = CSR + '<script id="ng-state" type="application/json">' + body + '</script>'
            with self.subTest(body=body), self.assertRaises(ContractError):
                verify_index(html, 'invalid state fixture')

    def test_data_does_not_create_an_event_handler_and_external_scripts_have_no_inline_body(self):
        verify_index(CSR + '<script id="ng-state" type="application/json">{"text":"onclick=value"}</script>', 'data fixture')
        for script in ('<script src=""></script>', '<script src="/main.js">alert(1)</script>', '<script>'):
            with self.subTest(script=script), self.assertRaises(ContractError):
                verify_index(CSR + script, 'invalid external fixture')


if __name__ == '__main__':
    unittest.main()
