function id(value) {
  return `00000000-0000-4000-8000-${value.toString(16).padStart(12, "0")}`;
}

const text = (value, content, marks = []) => ({
  id: id(value), type: "text", version: 1, attrs: { text: content, marks }, content: [],
});

export function createFixture({ long = false } = {}) {
  const content = [
    {
      id: id(2), type: "heading", version: 1,
      attrs: { level: 2, lang: "ru", dir: "ltr" },
      content: [text(3, "Память, письмо и проверяемые знания", ["strong"])],
    },
    {
      id: id(4), type: "paragraph", version: 1,
      attrs: { lang: "ru", dir: "auto" },
      content: [
        text(5, "Длинный русский фрагмент сохраняет идентичность при правке: "),
        text(6, "смысл важнее формы", ["em"]),
        { id: id(7), type: "ruby", version: 1, attrs: { base: "漢字", reading: "かんじ" }, content: [] },
        text(8, "."),
      ],
    },
    {
      id: id(9), type: "paragraph", version: 1,
      attrs: { lang: "ar", dir: "rtl" },
      content: [
        text(10, "العِلْمُ نورٌ — "),
        {
          id: id(11), type: "link", version: 1, attrs: { href: "https://example.test/source" },
          content: [text(12, "مصدر آمن"), text(32, " ومتابعة", ["em"])],
        },
      ],
    },
    {
      id: id(13), type: "paragraph", version: 1,
      attrs: { lang: "en", dir: "ltr" },
      content: [
        text(14, "Mixed direction keeps "),
        {
          id: id(15), type: "future_formula", version: 3,
          attrs: { source: "<img src=x onerror=globalThis.pwned=1>", engine: "future" }, content: [],
        },
        text(16, " surrounding text."),
      ],
    },
    {
      id: id(17), type: "heading", version: 2,
      attrs: { level: 2, futureFlag: { unsafeLooking: "<script>globalThis.pwned=2</script>" } },
      content: [text(18, "Future heading remains opaque")],
    },
    {
      id: id(19), type: "table", version: 7,
      attrs: { layout: "future-grid", payload: { nested: true } },
      content: [
        { id: id(20), type: "future_row", version: 1, attrs: { position: 1 }, content: [] },
      ],
    },
    {
      id: id(21), type: "blockquote", version: 1,
      attrs: { lang: "ru", dir: "auto" },
      content: [
        { id: id(22), type: "paragraph", version: 1, attrs: { lang: "ru", dir: "auto" }, content: [text(23, "Цитата проверяет вложенный блок.")] },
      ],
    },
    {
      id: id(24), type: "bullet_list", version: 1, attrs: {},
      content: [
        {
          id: id(25), type: "list_item", version: 1, attrs: {}, content: [
            { id: id(26), type: "paragraph", version: 1, attrs: { lang: "ru", dir: "auto" }, content: [text(27, "Первый пункт")] },
          ],
        },
        {
          id: id(28), type: "list_item", version: 1, attrs: {}, content: [
            { id: id(29), type: "paragraph", version: 1, attrs: { lang: "ru", dir: "auto" }, content: [text(30, "Второй пункт", ["code"])] },
          ],
        },
      ],
    },
    { id: id(31), type: "divider", version: 1, attrs: {}, content: [] },
    {
      id: id(33), type: "code_block", version: 1,
      attrs: { language: "kotlin", source: "println(\"данные, не выполнение\")", wrap: true }, content: [],
    },
    {
      id: id(34), type: "math_block", version: 1,
      attrs: { source: "E = mc^2", notation: "tex" }, content: [],
    },
    {
      id: id(35), type: "media_reference", version: 1,
      attrs: { assetId: id(9_000), kind: "image", alt: "Безопасная ссылка на закрытый ресурс" }, content: [],
    },
  ];

  if (long) {
    const sentence = "Мнема хранит материал как проверяемое дерево: длинный русский текст не должен терять направление, выделение или устойчивые ссылки. ";
    for (let index = 0; index < 180; index += 1) {
      const base = 100 + index * 2;
      content.push({
        id: id(base), type: "paragraph", version: 1,
        attrs: { lang: "ru", dir: "auto" },
        content: [text(base + 1, `${index + 1}. ${sentence.repeat(3)}`)],
      });
    }
  }

  return { formatVersion: 1, root: { id: id(1), type: "doc", version: 1, attrs: {}, content } };
}

export function createFutureRootFixture() {
  return {
    formatVersion: 1,
    root: {
      id: id(900), type: "doc", version: 4,
      attrs: { engine: "future", unsafeLooking: "<svg onload=globalThis.pwned=3>" },
      content: [
        { id: id(901), type: "future_canvas", version: 2, attrs: { commands: ["do-not-run"] }, content: [] },
      ],
    },
  };
}

export function createScaleFixture(paragraphs = 3_500) {
  const content = [];
  for (let index = 0; index < paragraphs; index += 1) {
    const base = 1_000 + index * 2;
    content.push({
      id: id(base), type: "paragraph", version: 1, attrs: { lang: "ru", dir: "auto" },
      content: [text(base + 1, `${index + 1}. Проверяемая строка.`)],
    });
  }
  return { formatVersion: 1, root: { id: id(999), type: "doc", version: 1, attrs: {}, content } };
}

export const sharedLexicalVectors = Object.freeze({
  lang: {
    accept: ["ru", "RU-ru", "ja", "ar", "zh-Hant-TW", "en-001", "sl-rozaj", "de-CH-1901", "sh"],
    reject: ["abcd", "x-private", "i-klingon", "en-GB-oed", "en-u-ca-gregory", "en_US", "ru-", "sl-rozaj-ROZAJ", "рус", "en-1234-1234"],
  },
  href: {
    accept: ["https://example.test/path?q=%3Cscript%3E#anchor", "HTTPS://example.test", "https://[::1]:443/", "https://127.0.0.1/", "https://xn--e1afmkfd.xn--p1ai/path", "https://example.test/%D1%8F"],
    reject: ["http://example.test", "javascript:alert(1)", "//example.test", "https://", "https://u:p@example.test", "https://@example.test", "https://example.test:65536", "https://example.test:0", "https://example.test/я", "https://пример.рф", "https://example.test/\n", "https://[fe80::1%25en0]/", "https://0177.0.0.1/", "https://2130706433/", "https://127.1/", "https://example.test:", "https://example.test.", "https://example..test", "https://a_b.test/", "https://example.test/%zz", "https://0x7f.0.0.1/", "https://1.2.3.256/", "https://[x]/", "https://example.test\\@evil.test", " https://example.test", "https://exa mple.test", "https://xn--a/", "https://xn--abc/", "https://xn--0/"],
  },
});

export function createOptionalMetadataFixture() {
  return {
    formatVersion: 1,
    root: {
      id: id(800), type: "doc", version: 1, attrs: { lang: "RU-ru", dir: "auto" },
      content: [
        {
          id: id(801), type: "heading", version: 1, attrs: { level: 3, lang: "de-CH-1901", dir: "ltr" },
          content: [{ id: id(802), type: "text", version: 1, attrs: { text: "Mark order", marks: ["code", "strong"], lang: "sh", dir: "ltr" }, content: [] }],
        },
        {
          id: id(803), type: "paragraph", version: 1, attrs: {},
          content: [
            { id: id(804), type: "text", version: 1, attrs: { text: "marks absent" }, content: [] },
            {
              id: id(805), type: "link", version: 1,
              attrs: { href: "HTTPS://example.test/Case", lang: "en-001", dir: "ltr" },
              content: [
                { id: id(806), type: "ruby", version: 1, attrs: { base: "学", reading: "がく", lang: "ja", dir: "ltr" }, content: [] },
              ],
            },
          ],
        },
        {
          id: id(807), type: "blockquote", version: 1, attrs: { lang: "ru", dir: "rtl" },
          content: [{ id: id(808), type: "paragraph", version: 1, attrs: {}, content: [{ id: id(809), type: "text", version: 1, attrs: { text: "цитата", marks: [] }, content: [] }] }],
        },
        {
          id: id(810), type: "ordered_list", version: 1, attrs: { lang: "en", dir: "ltr" },
          content: [{
            id: id(811), type: "list_item", version: 1, attrs: { lang: "en", dir: "auto" },
            content: [{ id: id(812), type: "paragraph", version: 1, attrs: {}, content: [{ id: id(813), type: "text", version: 1, attrs: { text: "one" }, content: [] }] }],
          }],
        },
        {
          id: id(814), type: "bullet_list", version: 1, attrs: { lang: "ar", dir: "rtl" },
          content: [{
            id: id(815), type: "list_item", version: 1, attrs: {},
            content: [{ id: id(816), type: "paragraph", version: 1, attrs: {}, content: [{ id: id(817), type: "text", version: 1, attrs: { text: "عنصر" }, content: [] }] }],
          }],
        },
        { id: id(818), type: "divider", version: 1, attrs: { lang: "zh-Hant-TW", dir: "auto" }, content: [] },
      ],
    },
  };
}

export function createOpaqueListFixture() {
  return {
    formatVersion: 1,
    root: {
      id: id(830), type: "doc", version: 1, attrs: {},
      content: [
        {
          id: id(831), type: "bullet_list", version: 1, attrs: { lang: "ru" },
          content: [{
            id: id(832), type: "future_list_child", version: 4,
            attrs: { inert: true, extra: { command: "never-run" } },
            content: [{ id: id(833), type: "future_leaf", version: 1, attrs: { value: 7 }, content: [] }],
          }],
        },
        {
          id: id(834), type: "ordered_list", version: 1, attrs: {},
          content: [{
            id: id(835), type: "list_item", version: 1, attrs: {},
            content: [{
              id: id(836), type: "paragraph", version: 2,
              attrs: { futureLayout: "inert" },
              content: [{ id: id(837), type: "text", version: 1, attrs: { futureTextShape: true }, content: [] }],
              extension: { preserved: true },
            }],
          }],
        },
      ],
    },
  };
}

export function fixtureId(value) {
  return id(value);
}
