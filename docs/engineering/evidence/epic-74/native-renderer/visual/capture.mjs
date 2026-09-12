import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const chromePath = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const debuggingPort = 43184;
const outputDirectory = '/tmp/mnema-epic74-resume-nQqPGB/renderer-visual';
const profileDirectory = await mkdtemp(join(tmpdir(), 'mnema-renderer-cdp-'));

await mkdir(outputDirectory, { recursive: true });

const chrome = spawn(chromePath, [
    '--headless=new',
    '--disable-background-networking',
    '--disable-component-update',
    '--disable-default-apps',
    '--disable-extensions',
    '--disable-sync',
    '--hide-scrollbars',
    '--metrics-recording-only',
    '--no-first-run',
    `--remote-debugging-port=${debuggingPort}`,
    `--user-data-dir=${profileDirectory}`,
    'about:blank'
], { stdio: ['ignore', 'ignore', 'pipe'] });

let chromeStderr = '';
chrome.stderr.setEncoding('utf8');
chrome.stderr.on('data', chunk => chromeStderr += chunk);

async function waitForDebugger() {
    for (let attempt = 0; attempt < 100; attempt += 1) {
        try {
            const response = await fetch(`http://127.0.0.1:${debuggingPort}/json/version`);
            if (response.ok) return;
        } catch {
            // Chrome is still starting.
        }
        await new Promise(resolve => setTimeout(resolve, 50));
    }
    throw new Error(`Chrome DevTools did not start. ${chromeStderr}`);
}

class CdpClient {
    #nextId = 1;
    #pending = new Map();
    #eventWaiters = new Map();

    constructor(url) {
        this.socket = new WebSocket(url);
        this.socket.addEventListener('message', event => {
            const message = JSON.parse(event.data);
            if (message.id) {
                const pending = this.#pending.get(message.id);
                this.#pending.delete(message.id);
                if (message.error) pending?.reject(new Error(message.error.message));
                else pending?.resolve(message.result);
                return;
            }
            const waiters = this.#eventWaiters.get(message.method) ?? [];
            this.#eventWaiters.delete(message.method);
            for (const resolve of waiters) resolve(message.params);
        });
    }

    async open() {
        if (this.socket.readyState === WebSocket.OPEN) return;
        await new Promise((resolve, reject) => {
            this.socket.addEventListener('open', resolve, { once: true });
            this.socket.addEventListener('error', reject, { once: true });
        });
    }

    send(method, params = {}) {
        const id = this.#nextId++;
        return new Promise((resolve, reject) => {
            this.#pending.set(id, { resolve, reject });
            this.socket.send(JSON.stringify({ id, method, params }));
        });
    }

    waitFor(method) {
        return new Promise(resolve => {
            const waiters = this.#eventWaiters.get(method) ?? [];
            waiters.push(resolve);
            this.#eventWaiters.set(method, waiters);
        });
    }

    close() {
        this.socket.close();
    }
}

async function capture({ name, width, state = 'ready', focus = false }) {
    const target = await fetch(`http://127.0.0.1:${debuggingPort}/json/new?about:blank`, { method: 'PUT' }).then(r => r.json());
    const cdp = new CdpClient(target.webSocketDebuggerUrl);
    await cdp.open();
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    await cdp.send('Emulation.setDeviceMetricsOverride', {
        width,
        height: 900,
        deviceScaleFactor: 1,
        mobile: false
    });

    const loaded = cdp.waitFor('Page.loadEventFired');
    const query = new URLSearchParams();
    if (state === 'invalid') query.set('state', 'invalid');
    await cdp.send('Page.navigate', { url: `http://127.0.0.1:43183/?${query}` });
    await loaded;
    await cdp.send('Runtime.evaluate', {
        expression: 'new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))',
        awaitPromise: true
    });
    if (focus) {
        await cdp.send('Input.dispatchKeyEvent', {
            type: 'rawKeyDown', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9, nativeVirtualKeyCode: 9
        });
        await cdp.send('Input.dispatchKeyEvent', {
            type: 'keyUp', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9, nativeVirtualKeyCode: 9
        });
    }

    const evaluated = await cdp.send('Runtime.evaluate', {
        expression: `JSON.stringify({
          url: location.href,
          title: document.title,
          viewport: { width: innerWidth, height: innerHeight, devicePixelRatio },
          layout: {
            documentScrollWidth: document.documentElement.scrollWidth,
            documentScrollHeight: document.documentElement.scrollHeight,
            bodyScrollWidth: document.body.scrollWidth,
            bodyScrollHeight: document.body.scrollHeight
          },
          renderState: document.querySelector('[data-native-render-state]')?.getAttribute('data-native-render-state'),
          article: Boolean(document.querySelector('article.native-document')),
          rubyText: document.querySelector('ruby')?.textContent ?? null,
          rtlDirection: document.querySelector('[lang="ar"]')?.getAttribute('dir') ?? null,
          href: document.querySelector('.native-link')?.getAttribute('href') ?? null,
          unsupportedBlocks: document.querySelectorAll('.native-unsupported:not(.native-unsupported--inline)').length,
          unsupportedInline: document.querySelectorAll('.native-unsupported--inline').length,
          rendererExecutableElements: document.querySelectorAll('app-native-document-renderer script, app-native-document-renderer iframe, app-native-document-renderer img, app-native-document-renderer form').length,
          activeElement: {
            tagName: document.activeElement?.tagName ?? null,
            className: document.activeElement?.className ?? null,
            href: document.activeElement?.getAttribute?.('href') ?? null
          },
          horizontalOverflow: document.documentElement.scrollWidth > innerWidth
        })`,
        returnByValue: true
    });
    const metrics = JSON.parse(evaluated.result.value);
    if (metrics.viewport.width !== width || metrics.horizontalOverflow) {
        throw new Error(`${name}: viewport/overflow assertion failed: ${JSON.stringify(metrics)}`);
    }
    if (state === 'ready') {
        if (metrics.renderState !== 'ready' || !metrics.article
            || metrics.rubyText !== '漢字(かんじ)' || metrics.rtlDirection !== 'rtl'
            || metrics.href !== 'https://example.test/source'
            || metrics.unsupportedBlocks !== 2 || metrics.unsupportedInline !== 1
            || metrics.rendererExecutableElements !== 0) {
            throw new Error(`${name}: ready-state assertion failed: ${JSON.stringify(metrics)}`);
        }
    } else if (metrics.renderState !== 'invalid' || metrics.article || metrics.href !== null
        || metrics.rendererExecutableElements !== 0) {
        throw new Error(`${name}: invalid-state assertion failed: ${JSON.stringify(metrics)}`);
    }
    if (focus && (metrics.activeElement.tagName !== 'A'
        || metrics.activeElement.className !== 'native-link'
        || metrics.activeElement.href !== 'https://example.test/source')) {
        throw new Error(`${name}: actual Tab did not focus the expected link: ${JSON.stringify(metrics.activeElement)}`);
    }
    const layout = await cdp.send('Page.getLayoutMetrics');
    const content = layout.cssContentSize;
    const screenshot = await cdp.send('Page.captureScreenshot', {
        format: 'png',
        fromSurface: true,
        captureBeyondViewport: true,
        clip: { x: 0, y: 0, width: content.width, height: content.height, scale: 1 }
    });
    await writeFile(join(outputDirectory, `${name}.png`), Buffer.from(screenshot.data, 'base64'));
    cdp.close();
    await fetch(`http://127.0.0.1:${debuggingPort}/json/close/${target.id}`);
    return { name, requestedWidth: width, state, focus, contentSize: content, ...metrics };
}

try {
    await waitForDebugger();
    const results = [];
    results.push(await capture({ name: 'renderer-ready-320', width: 320 }));
    results.push(await capture({ name: 'renderer-ready-390', width: 390 }));
    results.push(await capture({ name: 'renderer-ready-1440', width: 1440 }));
    results.push(await capture({ name: 'renderer-link-focus-390', width: 390, focus: true }));
    results.push(await capture({ name: 'renderer-invalid-390', width: 390, state: 'invalid' }));
    await writeFile(join(outputDirectory, 'metrics.json'), `${JSON.stringify(results, null, 2)}\n`);
} finally {
    chrome.kill('SIGTERM');
    await new Promise(resolve => chrome.once('exit', resolve));
    await rm(profileDirectory, { recursive: true, force: true });
}
