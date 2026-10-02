// Real-browser check of the native `code_block` node (#303): author it through the real editor UI with real keyboard input,
// publish, reload, read it in Browse, scroll long lines at 390 and 320 CSS px (DPR 2), reopen it in the editor and republish
// it unchanged. Runs with `--authoring`, after every other authoring scenario, on the signed-in account's tab, in a new
// material of the base deck. Node 24 built-ins only.
//
// A broken step is a finding for the product, never something to work around: it throws, a failure screenshot is written and
// the run fails with the step name.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

// A tab-indented line, trailing spaces, an inner blank line and markup that must stay text.
const SOURCE = [
  'SELECT id, name',
  '\tFROM users   ',
  '',
  ' WHERE active AND age < 30;',
  '-- <script>globalThis.__mnemaXss = true</script>',
  '-- <img src=x onerror="globalThis.__mnemaXss = true">'
].join('\n');
const LONG_LINE = 'abcdefghij'.repeat(40) + ';';
const LONG_SOURCE = `-- one line, no wrapping\n${LONG_LINE}`;

export async function runCodeBlock(ctx) {
  const { tab, config, record, SafeFailure, until, exists, sanitizedLocation, navigate, saveScreenshot, setStep,
    clickText, deckPath, bearer } = ctx;
  const need = (value, label) => { if (!value) throw new SafeFailure(label); };
  const page = (body, ...args) => tab.callFunction(`async function(...args) { ${body} }`, args);
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

  // ----- real keyboard input ---------------------------------------------------------------------------------------
  const KEYS = { Tab: ['Tab', 'Tab', 9], Enter: ['Enter', 'Enter', 13], Escape: ['Escape', 'Escape', 27],
    ArrowRight: ['ArrowRight', 'ArrowRight', 39] };
  const SHIFT = 8;
  const press = async (name, modifiers = 0) => {
    const [key, code, virtualKeyCode] = KEYS[name];
    const event = { key, code, windowsVirtualKeyCode: virtualKeyCode, nativeVirtualKeyCode: virtualKeyCode, modifiers };
    await tab.call('Page.bringToFront');
    await tab.call('Input.dispatchKeyEvent', name === 'Enter'
      ? { type: 'keyDown', text: '\r', unmodifiedText: '\r', ...event } : { type: 'rawKeyDown', ...event });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
  };
  /** Types character by character; a tab or a line feed in the text is a real Tab or Enter key. */
  const type = async text => {
    for (const character of text) {
      if (character === '\t') await press('Tab');
      else if (character === '\n') await press('Enter');
      else {
        await tab.call('Input.dispatchKeyEvent', { type: 'keyDown', key: character, text: character, unmodifiedText: character });
        await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', key: character });
      }
    }
  };
  const active = () => page(`const e = document.activeElement;
    return { tag: e?.tagName ?? null, field: e?.dataset?.field ?? null, isBody: e === document.body,
      inCode: Boolean(e?.closest?.('.mnema-code-node')), inEditor: Boolean(e?.closest?.('.ProseMirror')),
      isSurface: Boolean(e?.matches?.('.ProseMirror')) };`);
  const codeFields = () => page(`return [...document.querySelectorAll('.mnema-code-node')].map(node => ({
    lang: node.querySelector('input[data-field="lang"]').value, source: node.querySelector('textarea').value,
    invalid: node.querySelector('input[data-field="lang"]').getAttribute('aria-invalid'),
    selected: node.classList.contains('ProseMirror-selectednode') }));`);
  const focusEditorEnd = () => page(`const editor = document.querySelector('.ProseMirror[contenteditable="true"]');
    if (!(editor instanceof HTMLElement)) return false;
    editor.focus(); const selection = getSelection(); const range = document.createRange();
    range.selectNodeContents(editor); range.collapse(false); selection.removeAllRanges(); selection.addRange(range); return true;`);

  const failureShot = async name => {
    try { await saveScreenshot(`failure-code-block-${name}.png`, tab); } catch { /* the original failure is the verdict */ }
  };
  const stage = async (name, body) => {
    setStep(`code_block_${name}`);
    try { return await body(); } catch (error) {
      await failureShot(name);
      await writeFile(join(config.output, `failure-code-block-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + '\n').catch(() => {});
      throw error;
    }
  };
  const metrics = (width, height, scale, mobile) =>
    tab.call('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: scale, mobile });
  const reducedMotion = value => tab.call('Emulation.setEmulatedMedia',
    { features: value ? [{ name: 'prefers-reduced-motion', value: 'reduce' }] : [] });

  /** What a reader sees in Browse: asserted byte for byte against what was typed. */
  const browseState = () => page(`const blocks = [...document.querySelectorAll('figure.native-code')];
    const describe = figure => {
      const code = figure.querySelector('pre > code'); const region = figure.querySelector('.native-code-scroll');
      const style = getComputedStyle(region); const pre = getComputedStyle(figure.querySelector('pre'));
      return { text: code?.textContent ?? null, className: code?.className ?? null, childElements: code?.children.length ?? -1,
        dir: figure.getAttribute('dir'), direction: getComputedStyle(figure).direction,
        tabindex: region.getAttribute('tabindex'), role: region.getAttribute('role'), name: region.getAttribute('aria-label') ?? '',
        overflowX: style.overflowX, whiteSpace: pre.whiteSpace, label: figure.querySelector('figcaption')?.textContent ?? null,
        scrollWidth: region.scrollWidth, clientWidth: region.clientWidth };
    };
    return { blocks: blocks.map(describe), xss: Boolean(globalThis.__mnemaXss), injected: document.querySelectorAll('figure.native-code script, figure.native-code img').length,
      pageOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      innerWidth, dpr: devicePixelRatio, unsupported: document.querySelectorAll('.native-unsupported').length };`);
  const assertBrowse = async (label, state) => {
    state = state ?? await browseState();
    need(state.blocks.length === 2, `${label}: expected two code blocks, found ${state.blocks.length}`);
    const [sql, long] = state.blocks;
    need(sql.text === SOURCE, `${label}: the SQL source differs from what was typed (tabs, line breaks or trailing spaces lost)`);
    need(long.text === LONG_SOURCE, `${label}: the long source differs from what was typed`);
    need(sql.className.split(' ').includes('language-sql') && sql.label === 'sql', `${label}: language class or label missing`);
    need(!long.className.includes('language-') && long.label === null, `${label}: a block without a language shows one`);
    need(state.blocks.every(block => block.childElements === 0), `${label}: markup in the source became elements`);
    need(!state.xss && state.injected === 0 && state.unsupported === 0, `${label}: source executed or a block was unsupported`);
    need(state.blocks.every(block => block.dir === 'ltr' && block.direction === 'ltr'), `${label}: code is not left to right`);
    need(state.blocks.every(block => block.tabindex === '0' && block.role === 'region' && block.name.length > 3),
      `${label}: scroll region is not focusable with an accessible name`);
    need(state.blocks.every(block => block.overflowX === 'auto' && block.whiteSpace === 'pre'), `${label}: code wraps or does not scroll`);
    return state;
  };

  const evidence = { sourceBytes: new TextEncoder().encode(SOURCE).length, longLineLength: LONG_LINE.length };
  await reducedMotion(true);
  await metrics(1440, 900, 1, false);

  // ----- editor: real UI, real keyboard -----------------------------------------------------------------------------
  await stage('editor_keyboard', async () => {
    await navigate(`${deckPath}/materials/new`, tab);
    await until(() => exists('.ProseMirror[contenteditable="true"]', tab), 'native editor absent');
    need(await focusEditorEnd(), 'native editor could not be focused');
    await tab.call('Input.insertText', { text: 'Планировщик запросов' });
    need(await clickText('.editor-toolbar button', 'Блок кода', tab), 'toolbar action «Блок кода» absent or disabled');
    await until(async () => (await active()).field === 'source', 'focus did not move into the new code block textarea');
    need((await codeFields()).length === 1, 'the toolbar did not insert exactly one code block');

    // Shift+Tab leaves the textarea for the language field (never a trap), real typing, Tab on to the textarea.
    await press('Tab', SHIFT);
    need((await active()).field === 'lang', 'Shift+Tab did not move focus to the language field');
    await type('sql');
    await press('Tab');
    need((await active()).field === 'source', 'Tab in the language field did not reach the textarea');
    await type(SOURCE);
    const [first] = await codeFields();
    need(first.source === SOURCE, 'Tab inside the textarea did not insert a tab character (or typed text was altered)');
    need(first.lang === 'sql' && first.invalid === null, 'language field lost «sql»');
    need((await active()).field === 'source', 'Tab inside the textarea moved focus');
    evidence.tabInsertsTab = true;

    // Esc leaves the block: the block is selected and focus is back in the editor, not on <body>.
    await press('Escape');
    const afterEscape = await active();
    need(afterEscape.isSurface && !afterEscape.isBody, 'Esc did not return focus to the editor');
    need((await codeFields())[0].selected, 'Esc did not select the code block');
    // Tab keeps moving: focus leaves the editor within a few presses (no keyboard trap).
    let tabs = 0, state = afterEscape;
    while (state.inEditor && tabs < 12) { await press('Tab'); tabs++; state = await active(); }
    need(!state.inEditor && !state.isBody, 'Tab never left the editor after Esc (keyboard trap)');
    evidence.escapeLeavesBlock = true; evidence.tabsToLeaveEditor = tabs;
  });

  await stage('editor_second_block', async () => {
    need(await clickText('.editor-toolbar button', 'Блок кода', tab), 'toolbar action «Блок кода» absent for the second block');
    await until(async () => (await active()).field === 'source', 'focus did not move into the second code block');
    const fields = await codeFields();
    need(fields.length === 2 && fields[0].source === SOURCE && fields[1].source === '', 'second block was not inserted after the first');
    await type('-- one line, no wrapping');
    await press('Enter');
    await tab.call('Input.insertText', { text: LONG_LINE });
    need((await codeFields())[1].source === LONG_SOURCE, 'long source was not entered');
    await until(() => page(`return document.body.innerText.includes('Все изменения сохранены');`), 'draft acknowledgement absent');
    await page(`document.querySelector('.mnema-code-node').scrollIntoView({ block: 'center' }); return true;`);
    await saveScreenshot('code-block-editor-1440.png', tab);
  });

  // ----- publish, reload, Browse -------------------------------------------------------------------------------------
  let materialPath = null;
  await stage('publish_browse', async () => {
    need(await clickText('button', 'Опубликовать', tab), 'publication unavailable');
    await until(async () => /^\/decks\/[0-9a-f-]{36}\/materials\/[0-9a-f-]{36}$/.test(await sanitizedLocation(tab))
      && await exists('figure.native-code', tab), 'publication did not reach Browse with the code block');
    materialPath = await sanitizedLocation(tab);
    await navigate(materialPath, tab); // a full reload: nothing survives from the editor
    await until(() => exists('figure.native-code', tab), 'code block absent after reload');
    const state = await assertBrowse('browse');
    // A real keyboard focus on the region shows the focus ring.
    need(await page(`const region = document.querySelector('.native-code-scroll'); region.focus();
      return document.activeElement === region && getComputedStyle(region).outlineStyle !== 'none';`),
    'scroll region cannot take keyboard focus or shows no focus ring');
    await metrics(1440, 900, 1, false);
    await saveScreenshot('code-block-browse-1440.png', tab);
    evidence.browse = { blocks: state.blocks.length, byteForByte: true, markupInert: true, ltr: true, regionFocusable: true };
  });

  await stage('responsive_scroll', async () => {
    const widths = [];
    for (const [width, scale, mobile, name] of [[390, 1, true, 'code-block-browse-390.png'],
      [320, 2, false, 'code-block-browse-320-at-200-percent.png']]) {
      await metrics(width, 900, scale, mobile);
      await sleep(250);
      const state = await assertBrowse(`browse ${width}`);
      need(!state.pageOverflow && state.innerWidth === width && state.dpr === scale,
        `the page scrolls horizontally at ${width} CSS px (DPR ${scale})`);
      const long = state.blocks[1];
      need(long.scrollWidth > long.clientWidth, `the long line is not contained in a scroll region at ${width} px`);
      // The region scrolls (keyboard first: a focused region follows the arrow key), the page does not.
      const scrolled = await page(`const region = document.querySelectorAll('.native-code-scroll')[1]; region.scrollLeft = 0;
        region.focus(); return document.activeElement === region;`);
      need(scrolled, `long code region cannot be focused at ${width} px`);
      await press('ArrowRight');
      await sleep(150);
      need(await page(`const region = document.querySelectorAll('.native-code-scroll')[1];
        return region.scrollLeft > 0 && document.documentElement.scrollLeft === 0
          && document.documentElement.scrollWidth <= document.documentElement.clientWidth;`),
      `ArrowRight did not scroll the focused code region at ${width} px`);
      await page(`document.querySelectorAll('.native-code-scroll')[1].scrollLeft = 0; return true;`);
      await saveScreenshot(name, tab);
      widths.push(width);
    }
    evidence.responsive = { widths, deviceScaleFactorAt320: 2, pageOverflow: false, regionScrolls: true, keyboardScroll: true };
    await metrics(1440, 900, 1, false);
  });

  // ----- reopen in the editor and publish unchanged ----------------------------------------------------------------------
  await stage('reopen_roundtrip', async () => {
    // The editor only opens from the current list (it needs the confirmed position): list -> material -> «Редактировать».
    await navigate(`${deckPath}/materials`, tab);
    await until(() => page(`return document.querySelector('a[href="' + args[0] + '"]') !== null;`, materialPath),
      'the published material is not in the Browse list');
    need(await page(`document.querySelector('a[href="' + args[0] + '"]').click(); return true;`, materialPath), 'material link absent');
    await until(async () => (await sanitizedLocation(tab)) === materialPath && await clickText('a.button.primary', 'Редактировать', tab),
      'the material page offers no «Редактировать» action');
    await until(async () => (await page(`return document.querySelectorAll('.mnema-code-node').length;`)) === 2,
      'the published material did not reopen with two code blocks');
    const fields = await codeFields();
    need(fields[0].source === SOURCE && fields[0].lang === 'sql' && fields[1].source === LONG_SOURCE && fields[1].lang === '',
      'the editor shows a different code block than the one published');
    await until(() => page(`return document.body.innerText.includes('Все изменения сохранены')
      && [...document.querySelectorAll('button')].some(button => button.textContent.trim() === 'Опубликовать' && !button.disabled);`),
    'the reopened material cannot be republished');
    need(await clickText('button', 'Опубликовать', tab), 'republication unavailable');
    await until(async () => (await sanitizedLocation(tab)) === materialPath && await exists('figure.native-code', tab),
      'republication did not return to Browse');
    await navigate(materialPath, tab);
    await until(() => exists('figure.native-code', tab), 'code block absent after republication');
    await assertBrowse('browse after unchanged save');
    const memberKey = materialPath.split('/').at(-1);
    const stored = await page(`const response = await fetch(args[0] + '/api' + args[1] + '/items/' + args[2],
      { credentials: 'omit', headers: { Authorization: args[3] } });
      if (!response.ok) return null;
      const body = await response.json();
      return body.document.root.content.filter(node => node.type === 'code_block').map(node => ({ attrs: node.attrs, keys: Object.keys(node.attrs) }));`,
    config.frontend, deckPath, memberKey, 'Bearer ' + bearer);
    need(stored?.length === 2 && stored[0].attrs.source === SOURCE && stored[0].attrs.lang === 'sql'
      && stored[1].attrs.source === LONG_SOURCE && !('lang' in stored[1].attrs) && !JSON.stringify(stored).includes('\\r'),
    'the stored document differs from what was typed after a save without edits');
    evidence.roundTrip = { editorShowsSameSource: true, unchangedSaveKeepsSource: true, storedFieldsExact: true };
  });

  await reducedMotion(false);
  await metrics(1280, 900, 1, false);
  evidence.reducedMotion = true;
  evidence.screenshots = ['code-block-editor-1440.png', 'code-block-browse-1440.png', 'code-block-browse-390.png',
    'code-block-browse-320-at-200-percent.png'];
  record('code_block_real_editor_publish_browse_roundtrip', evidence);
  return evidence;
}
