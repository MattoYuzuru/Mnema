import { Fragment, Schema, Slice } from "prosemirror-model";
import { Plugin } from "prosemirror-state";

export const LIMITS = Object.freeze({
  bytes: 1024 * 1024,
  nodes: 10_000,
  depth: 32,
  scalarBytes: 32 * 1024,
  jsonDepth: 128,
  jsonTokens: 250_000,
});

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MAX_INT32 = 2_147_483_647;
const TYPE = /^[a-z][a-z0-9_]{0,63}$/;
const LANGUAGE = /^[a-z]{2,3}(?:-[a-z]{4})?(?:-(?:[a-z]{2}|[0-9]{3}))?(?:-(?:[a-z0-9]{5,8}|[0-9][a-z0-9]{3}))*$/i;
const DNS_LABEL = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i;
const DIRECTIONS = new Set(["auto", "ltr", "rtl"]);
const NATIVE_MARKS = new Set(["strong", "em", "code"]);
const MARK_ORDER = ["strong", "em", "code"];
const KNOWN_TYPES = new Set([
  "doc", "paragraph", "heading", "blockquote", "bullet_list", "ordered_list",
  "list_item", "divider", "text", "ruby", "link",
]);

function uuid() {
  return globalThis.crypto.randomUUID();
}

function clone(value) {
  return structuredClone(value);
}

function isRecord(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function scalarBytes(value) {
  return new TextEncoder().encode(value).byteLength;
}

function validateJsonValue(value, path) {
  const pending = [{ value, path, depth: 0 }];
  const seen = new WeakSet();
  let tokens = 0;
  while (pending.length) {
    const current = pending.pop();
    const entry = current.value;
    if (typeof entry === "string") {
      tokens += 1;
      if (scalarBytes(entry) > LIMITS.scalarBytes) throw new Error(`${current.path}: scalar exceeds ${LIMITS.scalarBytes} UTF-8 bytes`);
    } else if (typeof entry === "number") {
      tokens += 1;
      if (!Number.isFinite(entry)) throw new Error(`${current.path}: number must be finite`);
    } else if (entry === null || typeof entry === "boolean") {
      tokens += 1;
    } else if (Array.isArray(entry) || isRecord(entry)) {
      if (seen.has(entry)) throw new Error(`${current.path}: cyclic value is not JSON-compatible`);
      seen.add(entry);
      const depth = current.depth + 1;
      if (depth > LIMITS.jsonDepth) throw new Error(`${current.path}: exceeds JSON depth ${LIMITS.jsonDepth}`);
      tokens += 2;
      if (Array.isArray(entry)) {
        entry.forEach((child, index) => pending.push({ value: child, path: `${current.path}[${index}]`, depth }));
      } else {
        const properties = Object.entries(entry);
        tokens += properties.length;
        for (const [key, child] of properties) {
          if (scalarBytes(key) > LIMITS.scalarBytes) throw new Error(`${current.path}: property name exceeds ${LIMITS.scalarBytes} UTF-8 bytes`);
          pending.push({ value: child, path: `${current.path}.${key}`, depth });
        }
      }
    } else {
      throw new Error(`${current.path}: value is not JSON-compatible`);
    }
    if (tokens > LIMITS.jsonTokens) throw new Error(`document: exceeds ${LIMITS.jsonTokens} JSON tokens`);
  }
}

function validateLang(value, path) {
  if (value === undefined) return;
  if (typeof value !== "string" || value.length > 64 || !LANGUAGE.test(value)) {
    throw new Error(`${path}: invalid shared language tag`);
  }
  const variants = new Set();
  for (const subtag of value.split("-")) {
    const variant = subtag.length >= 5 || (subtag.length === 4 && /^[0-9]/.test(subtag));
    if (variant && variants.has(subtag.toLowerCase())) throw new Error(`${path}: duplicate language variant`);
    if (variant) variants.add(subtag.toLowerCase());
  }
}

function validateDirection(value, path) {
  if (value !== undefined && !DIRECTIONS.has(value)) throw new Error(`${path}: direction must be auto, ltr, or rtl`);
}

function validateKnownNode(node, path) {
  if (node.version !== 1) return;
  const allowedAttrs = {
    doc: [], paragraph: [], heading: ["level"], blockquote: [], bullet_list: [],
    ordered_list: ["order"], list_item: [], divider: [], text: ["text", "marks"],
    ruby: ["base", "reading"], link: ["href"],
  };
  const extraAttrs = Object.keys(node.attrs).filter((key) => !allowedAttrs[node.type].includes(key) && key !== "lang" && key !== "dir");
  if (extraAttrs.length) throw new Error(`${path}.attrs: unsupported attributes ${extraAttrs.join(", ")}`);
  validateLang(node.attrs.lang, `${path}.attrs.lang`);
  validateDirection(node.attrs.dir, `${path}.attrs.dir`);
  switch (node.type) {
    case "doc":
      if (node.content.length === 0) throw new Error(`${path}: doc requires content`);
      break;
    case "heading":
      if (!Number.isInteger(node.attrs.level) || node.attrs.level < 1 || node.attrs.level > 6) {
        throw new Error(`${path}.attrs.level: heading level must be 1..6`);
      }
      break;
    case "ordered_list":
      if (node.attrs.order !== undefined
          && (!Number.isInteger(node.attrs.order) || node.attrs.order < 1 || node.attrs.order > MAX_INT32)) {
        throw new Error(`${path}.attrs.order: list order must be a positive 32-bit integer`);
      }
      break;
    case "text": {
      if (typeof node.attrs.text !== "string" || node.attrs.text.length === 0) throw new Error(`${path}.attrs.text: text must be non-empty`);
      const hasMarks = Object.hasOwn(node.attrs, "marks");
      const marks = hasMarks ? node.attrs.marks : [];
      if (!Array.isArray(marks) || marks.some((mark) => !NATIVE_MARKS.has(mark)) || new Set(marks).size !== marks.length) {
        throw new Error(`${path}.attrs.marks: unsupported or duplicate mark`);
      }
      if (node.content.length !== 0) throw new Error(`${path}: text cannot contain children`);
      break;
    }
    case "ruby":
      if (typeof node.attrs.base !== "string" || !node.attrs.base || typeof node.attrs.reading !== "string" || !node.attrs.reading) {
        throw new Error(`${path}.attrs: ruby requires non-empty base and reading`);
      }
      if (node.content.length !== 0) throw new Error(`${path}: ruby cannot contain children`);
      break;
    case "link":
      if (!isSafeHttpsUrl(node.attrs.href)) throw new Error(`${path}.attrs.href: only https links are allowed`);
      if (node.content.length === 0) throw new Error(`${path}: link requires content`);
      break;
    case "divider":
      if (node.content.length !== 0) throw new Error(`${path}: divider cannot contain children`);
      break;
    default:
      break;
  }
}

function childSlot(node) {
  switch (node.type) {
    case "doc":
    case "blockquote":
    case "list_item":
      return "block";
    case "paragraph":
    case "heading":
    case "link":
      return "inline";
    case "bullet_list":
    case "ordered_list":
      return "list_item";
    default:
      return "none";
  }
}

function validateShapeForSlot(node, slot, path) {
  if (!KNOWN_TYPES.has(node.type) || node.version !== 1) return;
  const inline = node.type === "text" || node.type === "ruby" || node.type === "link";
  const listItem = node.type === "list_item";
  if (node.type === "doc" && slot !== "root") throw new Error(`${path}: doc is only allowed at root`);
  if (node.type === "list_item" && slot !== "list_item") throw new Error(`${path}: list_item is only allowed in a list`);
  if (slot === "inline" && !inline) throw new Error(`${path}: block node in inline-only parent`);
  if (slot === "block" && inline) throw new Error(`${path}: inline node in block-only parent`);
  if (slot === "list_item" && !listItem) throw new Error(`${path}: list requires list_item children`);
  if (slot === "none") throw new Error(`${path}: parent cannot contain children`);
}

export function validateNativeDocument(document) {
  if (!isRecord(document) || Object.keys(document).length !== 2
      || !Object.hasOwn(document, "formatVersion") || !Object.hasOwn(document, "root")
      || document.formatVersion !== 1 || !isRecord(document.root)) {
    throw new Error("document: expected formatVersion 1 and a root object");
  }
  validateJsonValue(document, "document");
  const bytes = new TextEncoder().encode(JSON.stringify(document)).byteLength;
  if (bytes > LIMITS.bytes) throw new Error(`document: exceeds ${LIMITS.bytes} serialized UTF-8 bytes`);

  const ids = new Set();
  let count = 0;
  function visit(node, path, depth, slot, opaque = false) {
    if (!isRecord(node)) throw new Error(`${path}: node must be an object`);
    if (depth > LIMITS.depth) throw new Error(`${path}: exceeds depth ${LIMITS.depth}`);
    if (++count > LIMITS.nodes) throw new Error(`document: exceeds ${LIMITS.nodes} nodes`);
    if (!UUID_V4.test(node.id)) throw new Error(`${path}.id: expected UUIDv4`);
    const semanticId = node.id.toLowerCase();
    if (ids.has(semanticId)) throw new Error(`${path}.id: duplicate UUID`);
    ids.add(semanticId);
    if (typeof node.type !== "string" || !TYPE.test(node.type)) throw new Error(`${path}.type: invalid type`);
    if (!Number.isInteger(node.version) || node.version < 1 || node.version > MAX_INT32) throw new Error(`${path}.version: expected a positive 32-bit integer`);
    if (!isRecord(node.attrs)) throw new Error(`${path}.attrs: expected object`);
    if (!Array.isArray(node.content)) throw new Error(`${path}.content: expected array`);
    const supported = KNOWN_TYPES.has(node.type) && node.version === 1;
    if (!opaque && supported) {
      const fields = Object.keys(node);
      if (fields.length !== 5 || fields.some((field) => !["id", "type", "version", "attrs", "content"].includes(field))) {
        throw new Error(`${path}: supported node must contain exactly the native core fields`);
      }
    }
    if (!opaque) validateShapeForSlot(node, slot, path);
    const nodeIsOpaque = opaque || !supported;
    if (!nodeIsOpaque) validateKnownNode(node, path);
    if (!nodeIsOpaque && ["blockquote", "bullet_list", "ordered_list", "list_item"].includes(node.type) && node.content.length === 0) {
      throw new Error(`${path}: ${node.type} requires content`);
    }
    if (!nodeIsOpaque && node.type === "list_item" && node.content[0]?.type !== "paragraph") {
      throw new Error(`${path}: list_item first child must have type paragraph`);
    }
    const nextSlot = nodeIsOpaque ? "opaque" : childSlot(node);
    const insideLink = !nodeIsOpaque && node.type === "link";
    node.content.forEach((child, index) => {
      if (insideLink && !nodeIsOpaque && child.type === "link" && child.version === 1) {
        throw new Error(`${path}.content[${index}]: nested links are not allowed`);
      }
      visit(child, `${path}.content[${index}]`, depth + 1, nextSlot, nodeIsOpaque);
    });
  }
  visit(document.root, "document.root", 1, "root");
  if (document.root.type !== "doc") throw new Error("document.root: root type must be doc");
  return clone(document);
}

export function isSafeHttpsUrl(value) {
  if (typeof value !== "string" || value.length > 2048 || /[^\x21-\x7e]/.test(value)
      || value.includes("\\") || /%(?![0-9a-f]{2})/i.test(value)) return false;
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password) return false;
    const authority = value.slice(value.indexOf("//") + 2).split(/[/?#]/, 1)[0];
    if (!value.slice(0, value.indexOf("//")).match(/^https:$/i) || authority.includes("@") || authority.endsWith(":")) return false;
    let host;
    let port = "";
    if (authority.startsWith("[")) {
      const closing = authority.indexOf("]");
      if (closing < 0) return false;
      host = authority.slice(0, closing + 1);
      if (authority.length > closing + 1) {
        if (authority[closing + 1] !== ":") return false;
        port = authority.slice(closing + 2);
      }
      if (host.includes("%")) return false;
    } else {
      const colon = authority.lastIndexOf(":");
      host = colon < 0 ? authority : authority.slice(0, colon);
      port = colon < 0 ? "" : authority.slice(colon + 1);
      if (host.includes(":")) return false;
      if (host.length > 253) return false;
      const labels = host.split(".");
      const last = labels.at(-1);
      if (/^(?:[0-9]+|0x[0-9a-f]*)$/i.test(last)) {
        if (labels.length !== 4 || labels.some((label) => !/^(?:0|[1-9][0-9]{0,2})$/.test(label) || Number(label) > 255)) return false;
      } else if (labels.some((label) => !DNS_LABEL.test(label))) return false;
      // URL uses the browser/Node IDNA implementation. Requiring its ASCII result to
      // equal the supplied A-label catches the frozen invalid vectors, but Java 21 is
      // still the publication authority for arbitrary IDNA labels.
      if (labels.some((label) => label.toLowerCase().startsWith("xn--"))
          && parsed.hostname.toLowerCase() !== host.toLowerCase()) return false;
    }
    if (port && (!/^[0-9]+$/.test(port) || Number(port) < 1 || Number(port) > 65_535)) return false;
    return true;
  } catch {
    return false;
  }
}

function blockAttrs(dom) {
  const nativeAttrs = {};
  if (dom.hasAttribute("lang")) nativeAttrs.lang = dom.getAttribute("lang");
  if (dom.hasAttribute("dir") && DIRECTIONS.has(dom.getAttribute("dir"))) nativeAttrs.dir = dom.getAttribute("dir");
  return {
    nativeId: null,
    nativeVersion: 1,
    nativeAttrs,
    lang: nativeAttrs.lang,
    dir: nativeAttrs.dir,
  };
}

function languageDomAttrs(attrs) {
  return Object.fromEntries(Object.entries({ lang: attrs.lang, dir: attrs.dir }).filter(([, value]) => value !== undefined));
}

const identityAttrs = {
  nativeId: { default: null }, nativeVersion: { default: 1 }, nativeAttrs: { default: null },
  lang: { default: undefined }, dir: { default: undefined },
};

const blockNode = (tag) => ({
  group: "block",
  content: "inline*",
  attrs: identityAttrs,
  parseDOM: [{ tag, getAttrs: blockAttrs }],
  toDOM(node) { return [tag, languageDomAttrs(node.attrs), 0]; },
});

export const mnemaSchema = new Schema({
  nodes: {
    doc: {
      content: "block+",
      attrs: identityAttrs,
    },
    paragraph: blockNode("p"),
    heading: {
      ...blockNode("h2"),
      attrs: { ...blockNode("h2").attrs, level: { default: 2 } },
      parseDOM: [1, 2, 3, 4, 5, 6].map((level) => ({ tag: `h${level}`, getAttrs: (dom) => ({ ...blockAttrs(dom), level }) })),
      toDOM(node) { return [`h${node.attrs.level}`, languageDomAttrs(node.attrs), 0]; },
    },
    blockquote: {
      group: "block",
      content: "block+",
      attrs: identityAttrs,
      parseDOM: [{ tag: "blockquote", getAttrs: blockAttrs }],
      toDOM(node) { return ["blockquote", languageDomAttrs(node.attrs), 0]; },
    },
    bullet_list: {
      group: "block", content: "list_item+",
      attrs: identityAttrs,
      parseDOM: [{ tag: "ul", getAttrs: blockAttrs }], toDOM(node) { return ["ul", languageDomAttrs(node.attrs), 0]; },
    },
    ordered_list: {
      group: "block", content: "list_item+",
      attrs: { ...identityAttrs, order: { default: 1 } },
      parseDOM: [{ tag: "ol", getAttrs: (dom) => {
        const attrs = blockAttrs(dom);
        if (dom.hasAttribute("start") && Number.isInteger(Number(dom.getAttribute("start")))
            && Number(dom.getAttribute("start")) > 0 && Number(dom.getAttribute("start")) <= MAX_INT32) {
          attrs.order = Number(dom.getAttribute("start"));
          attrs.nativeAttrs.order = attrs.order;
        }
        return attrs;
      } }],
      toDOM(node) { return ["ol", { ...languageDomAttrs(node.attrs), ...(node.attrs.order === 1 ? {} : { start: node.attrs.order }) }, 0]; },
    },
    list_item: {
      content: "paragraph block*",
      attrs: identityAttrs,
      parseDOM: [{ tag: "li", getAttrs: blockAttrs }], toDOM(node) { return ["li", languageDomAttrs(node.attrs), 0]; },
    },
    divider: {
      group: "block", atom: true, selectable: true,
      attrs: identityAttrs,
      parseDOM: [{ tag: "hr", getAttrs: blockAttrs }], toDOM(node) { return ["hr", languageDomAttrs(node.attrs)]; },
    },
    ruby: {
      inline: true, group: "inline", atom: true, selectable: true,
      attrs: {
        ...identityAttrs, base: { default: "" }, reading: { default: "" },
      },
      toDOM(node) { return ["ruby", languageDomAttrs(node.attrs), node.attrs.base, ["rp", {}, "("], ["rt", {}, node.attrs.reading], ["rp", {}, ")"]]; },
    },
    unsupported_block: {
      group: "block", atom: true, selectable: true, draggable: true,
      attrs: { nativeId: {}, nativeVersion: {}, nativeType: {}, payload: {} },
      toDOM() { return ["div", { class: "unsupported" }, "Неподдерживаемый блок"]; },
    },
    unsupported_inline: {
      inline: true, group: "inline", atom: true, selectable: true, draggable: true,
      attrs: { nativeId: {}, nativeVersion: {}, nativeType: {}, payload: {} },
      toDOM() { return ["span", { class: "unsupported" }, "Неподдерживаемый фрагмент"]; },
    },
    text: { group: "inline" },
  },
  marks: {
    mnema_text_identity: {
      attrs: { id: {}, version: { default: 1 }, nativeAttrs: { default: null } },
      inclusive: true,
      toDOM(mark) { return ["span", languageDomAttrs(mark.attrs.nativeAttrs ?? {}), 0]; },
    },
    strong: {
      parseDOM: [{ tag: "strong" }, { tag: "b" }],
      toDOM() { return ["strong", 0]; },
    },
    em: {
      parseDOM: [{ tag: "em" }, { tag: "i" }],
      toDOM() { return ["em", 0]; },
    },
    code: {
      parseDOM: [{ tag: "code" }],
      toDOM() { return ["code", 0]; },
    },
    link: {
      attrs: { ...identityAttrs, href: {} },
      inclusive: false,
      parseDOM: [{ tag: "a[href]", getAttrs: (dom) => {
        const href = dom.getAttribute("href");
        if (!isSafeHttpsUrl(href)) return false;
        const attrs = blockAttrs(dom);
        attrs.href = href;
        attrs.nativeAttrs.href = href;
        return attrs;
      } }],
      toDOM(mark) { return ["a", { ...languageDomAttrs(mark.attrs), href: mark.attrs.href, rel: "noreferrer noopener" }, 0]; },
    },
  },
});

function identityMark(id, version = 1, nativeAttrs = null) {
  return mnemaSchema.marks.mnema_text_identity.create({ id, version, nativeAttrs });
}

function semanticMarks(names) {
  return [...names].sort((a, b) => MARK_ORDER.indexOf(a) - MARK_ORDER.indexOf(b)).map((name) => mnemaSchema.marks[name].create());
}

function unsupportedNode(node, slot) {
  const name = slot === "inline" ? "unsupported_inline" : "unsupported_block";
  return mnemaSchema.nodes[name].create({
    nativeId: node.id,
    nativeVersion: node.version,
    nativeType: node.type,
    payload: clone(node),
  });
}

function nativeCommon(node) {
  return {
    nativeId: node.id,
    nativeVersion: node.version,
    nativeAttrs: clone(node.attrs),
    lang: node.attrs.lang,
    dir: node.attrs.dir,
  };
}

// PM requires list_item/paragraph in these slots. When the native document uses
// an opaque direct list child or a future paragraph in the first slot, keep the
// whole list inert instead of manufacturing a shape that cannot round-trip.
function representableList(node) {
  return node.content.every((item) => item.type === "list_item" && item.version === 1
    && item.content[0]?.type === "paragraph" && item.content[0]?.version === 1);
}

function importNode(node, slot) {
  if (!KNOWN_TYPES.has(node.type) || node.version !== 1) return unsupportedNode(node, slot);
  const common = nativeCommon(node);
  switch (node.type) {
    case "text":
      return mnemaSchema.text(node.attrs.text, [identityMark(node.id, node.version, clone(node.attrs)), ...semanticMarks(node.attrs.marks ?? [])]);
    case "ruby":
      return mnemaSchema.nodes.ruby.create({ ...common, base: node.attrs.base, reading: node.attrs.reading });
    case "link": {
      const link = mnemaSchema.marks.link.create({ ...common, href: node.attrs.href });
      return node.content.map((child) => {
        const imported = importNode(child, "inline");
        return imported.mark([...imported.marks.filter((mark) => mark.type !== mnemaSchema.marks.link), link]);
      });
    }
    case "paragraph":
    case "heading": {
      const children = node.content.flatMap((child) => importNode(child, "inline"));
      return mnemaSchema.nodes[node.type].create({ ...common, ...node.attrs }, children);
    }
    case "blockquote": {
      const children = node.content.flatMap((child) => importNode(child, "block"));
      return mnemaSchema.nodes.blockquote.create({ ...common, ...node.attrs }, children);
    }
    case "bullet_list":
    case "ordered_list": {
      if (!representableList(node)) return unsupportedNode(node, slot);
      const children = node.content.flatMap((child) => importNode(child, "list_item"));
      return mnemaSchema.nodes[node.type].create({ ...common, ...node.attrs }, children);
    }
    case "list_item": {
      const nextSlot = node.type === "list_item" ? "block" : "list_item";
      const children = node.content.flatMap((child) => importNode(child, nextSlot));
      return mnemaSchema.nodes[node.type].create({ ...common, ...node.attrs }, children);
    }
    case "divider":
      return mnemaSchema.nodes.divider.create(common);
    default:
      return unsupportedNode(node, slot);
  }
}

export function importNativeDocument(document) {
  const validated = validateNativeDocument(document);
  if (validated.root.version !== 1) {
    return {
      readOnly: true,
      original: validated,
      doc: mnemaSchema.nodes.doc.create(
        { nativeId: uuid(), nativeVersion: 1, nativeAttrs: {} },
        unsupportedNode(validated.root, "block"),
      ),
    };
  }
  const content = validated.root.content.flatMap((node) => importNode(node, "block"));
  return {
    readOnly: false,
    original: validated,
    doc: mnemaSchema.nodes.doc.create({ ...nativeCommon(validated.root) }, content),
  };
}

function textIdentity(node) {
  const mark = node.marks.find((candidate) => candidate.type === mnemaSchema.marks.mnema_text_identity);
  if (!mark) throw new Error("editor text is missing Mnema identity");
  return mark.attrs;
}

function editableAttrs(attrs, required = {}, optionalDefaults = {}) {
  const output = clone(attrs.nativeAttrs ?? {});
  for (const [key, value] of Object.entries(required)) output[key] = value;
  for (const [key, defaultValue] of Object.entries(optionalDefaults)) {
    const value = attrs[key];
    if (Object.hasOwn(output, key) || (value !== undefined && value !== defaultValue)) output[key] = value;
    else delete output[key];
  }
  return output;
}

function exportText(node) {
  const identity = textIdentity(node);
  const currentMarks = node.marks
    .filter((mark) => NATIVE_MARKS.has(mark.type.name))
    .map((mark) => mark.type.name);
  const originalMarks = Array.isArray(identity.nativeAttrs?.marks) ? identity.nativeAttrs.marks : [];
  const marks = [
    ...originalMarks.filter((mark) => currentMarks.includes(mark)),
    ...currentMarks.filter((mark) => !originalMarks.includes(mark)).sort((a, b) => MARK_ORDER.indexOf(a) - MARK_ORDER.indexOf(b)),
  ];
  const attrs = clone(identity.nativeAttrs ?? {});
  attrs.text = node.text;
  if (Object.hasOwn(attrs, "marks") || marks.length) attrs.marks = marks;
  else delete attrs.marks;
  return { id: identity.id, type: "text", version: identity.version, attrs, content: [] };
}

function linkMark(node) {
  return node.marks.find((mark) => mark.type === mnemaSchema.marks.link) ?? null;
}

function exportInlineNode(node) {
  if (node.isText) return exportText(node);
  if (node.type === mnemaSchema.nodes.ruby) {
    return {
      id: node.attrs.nativeId, type: "ruby", version: node.attrs.nativeVersion,
      attrs: editableAttrs(node.attrs, { base: node.attrs.base, reading: node.attrs.reading }, { lang: undefined, dir: undefined }), content: [],
    };
  }
  if (node.type === mnemaSchema.nodes.unsupported_inline) return clone(node.attrs.payload);
  throw new Error(`cannot export inline editor node ${node.type.name}`);
}

function exportInline(parent) {
  const output = [];
  let activeLinkMark = null;
  let activeLinkOutput = null;
  parent.forEach((node) => {
    const link = linkMark(node);
    if (link) {
      if (!activeLinkMark?.eq(link)) {
        activeLinkMark = link;
        activeLinkOutput = {
          id: link.attrs.nativeId,
          type: "link",
          version: link.attrs.nativeVersion,
          attrs: editableAttrs(link.attrs, { href: link.attrs.href }, { lang: undefined, dir: undefined }),
          content: [],
        };
        output.push(activeLinkOutput);
      }
      activeLinkOutput.content.push(exportInlineNode(node));
      return;
    }
    activeLinkMark = null;
    activeLinkOutput = null;
    output.push(exportInlineNode(node));
  });
  return output;
}

function exportBlock(node) {
  if (node.type === mnemaSchema.nodes.unsupported_block) return clone(node.attrs.payload);
  const result = {
    id: node.attrs.nativeId,
    type: node.type.name,
    version: node.attrs.nativeVersion,
    attrs: {},
    content: [],
  };
  switch (node.type.name) {
    case "paragraph":
    case "heading":
      result.attrs = editableAttrs(
        node.attrs,
        node.type.name === "heading" ? { level: node.attrs.level } : {},
        { lang: undefined, dir: undefined },
      );
      result.content = exportInline(node);
      break;
    case "blockquote":
      result.attrs = editableAttrs(node.attrs, {}, { lang: undefined, dir: undefined });
      node.forEach((child) => result.content.push(exportBlock(child)));
      break;
    case "ordered_list":
      result.attrs = editableAttrs(node.attrs, {}, { order: 1, lang: undefined, dir: undefined });
      node.forEach((child) => result.content.push(exportBlock(child)));
      break;
    case "bullet_list":
    case "list_item":
      result.attrs = editableAttrs(node.attrs, {}, { lang: undefined, dir: undefined });
      node.forEach((child) => result.content.push(exportBlock(child)));
      break;
    case "divider":
      result.attrs = editableAttrs(node.attrs, {}, { lang: undefined, dir: undefined });
      break;
    default:
      throw new Error(`cannot export editor node ${node.type.name}`);
  }
  return result;
}

export function exportNativeDocument(sessionOrDoc) {
  if (sessionOrDoc?.readOnly) return clone(sessionOrDoc.original);
  const doc = sessionOrDoc?.doc ?? sessionOrDoc;
  const result = {
    formatVersion: 1,
    root: {
      id: doc.attrs.nativeId,
      type: "doc",
      version: doc.attrs.nativeVersion,
      attrs: editableAttrs(doc.attrs, {}, { lang: undefined, dir: undefined }),
      content: [],
    },
  };
  doc.forEach((node) => result.root.content.push(exportBlock(node)));
  return validateNativeDocument(result);
}

function reidentifyNative(node) {
  const copied = clone(node);
  copied.id = uuid();
  copied.content = copied.content.map(reidentifyNative);
  return copied;
}

function marksWithLink(node, replacementLink) {
  const marks = node.marks.filter((mark) => mark.type !== mnemaSchema.marks.link);
  if (replacementLink) marks.push(replacementLink);
  return marks;
}

function freshenChildren(parent) {
  const children = [];
  let sourceLink = null;
  let copiedLink = null;
  parent.forEach((child) => {
    const nextSourceLink = child.isInline ? linkMark(child) : null;
    if (!nextSourceLink) {
      sourceLink = null;
      copiedLink = null;
    } else if (!sourceLink?.eq(nextSourceLink)) {
      sourceLink = nextSourceLink;
      copiedLink = mnemaSchema.marks.link.create({ ...nextSourceLink.attrs, nativeId: uuid() });
    }
    children.push(freshenNode(child, copiedLink));
  });
  return children;
}

function freshenNode(node, copiedLink = null) {
  if (node.isText) {
    const sourceIdentity = node.marks.find((mark) => mark.type === mnemaSchema.marks.mnema_text_identity);
    const marks = node.marks
      .filter((mark) => mark.type !== mnemaSchema.marks.mnema_text_identity && mark.type !== mnemaSchema.marks.link)
      .concat(identityMark(uuid(), sourceIdentity?.attrs.version ?? 1, clone(sourceIdentity?.attrs.nativeAttrs ?? { text: node.text })));
    if (copiedLink) marks.push(copiedLink);
    return mnemaSchema.text(node.text, marks);
  }
  let attrs = { ...node.attrs };
  if (Object.hasOwn(attrs, "nativeId")) attrs.nativeId = uuid();
  if (node.type === mnemaSchema.nodes.unsupported_block || node.type === mnemaSchema.nodes.unsupported_inline) {
    attrs.payload = reidentifyNative(attrs.payload);
    attrs.nativeId = attrs.payload.id;
  }
  const children = freshenChildren(node);
  return node.type.create(attrs, children.length ? children : undefined, marksWithLink(node, copiedLink));
}

export function freshenPastedSlice(slice) {
  const children = freshenChildren(slice.content);
  return new Slice(Fragment.fromArray(children), slice.openStart, slice.openEnd);
}

function normalizedAttrs(node, replacementId) {
  const attrs = { ...node.attrs, nativeId: replacementId };
  if (node.type === mnemaSchema.nodes.unsupported_block || node.type === mnemaSchema.nodes.unsupported_inline) {
    attrs.payload = reidentifyNative(attrs.payload);
    attrs.nativeId = attrs.payload.id;
  }
  return attrs;
}

export function buildIdentityNormalization(transaction) {
  const seen = new Set();
  const nodeUpdates = [];
  const textUpdates = [];
  const linkGroups = [];
  let activeLinkGroup = null;

  const rootId = transaction.doc.attrs.nativeId;
  if (UUID_V4.test(rootId)) seen.add(rootId.toLowerCase());

  function reserveOpaqueDescendants(payload) {
    const pending = [...payload.content];
    while (pending.length) {
      const current = pending.pop();
      const key = typeof current.id === "string" ? current.id.toLowerCase() : "";
      if (!UUID_V4.test(current.id) || seen.has(key)) return false;
      seen.add(key);
      pending.push(...current.content);
    }
    return true;
  }

  transaction.doc.descendants((node, pos, parent, index) => {
    if (node.isText) {
      const identity = node.marks.find((mark) => mark.type === mnemaSchema.marks.mnema_text_identity);
      const key = typeof identity?.attrs.id === "string" ? identity.attrs.id.toLowerCase() : "";
      if (!identity || seen.has(key) || !UUID_V4.test(identity.attrs.id)) {
        textUpdates.push({ from: pos, to: pos + node.nodeSize, old: identity });
      } else seen.add(key);
    }
    if (!node.isText && Object.hasOwn(node.attrs, "nativeId")) {
      const id = node.attrs.nativeId;
      const key = typeof id === "string" ? id.toLowerCase() : "";
      const opaque = node.type === mnemaSchema.nodes.unsupported_block || node.type === mnemaSchema.nodes.unsupported_inline;
      if (!UUID_V4.test(id) || seen.has(key)) nodeUpdates.push({ pos, node });
      else {
        seen.add(key);
        if (opaque && !reserveOpaqueDescendants(node.attrs.payload)) nodeUpdates.push({ pos, node });
      }
    }
    if (!node.isInline) return;
    const link = linkMark(node);
    if (!link) {
      activeLinkGroup = null;
      return;
    }
    if (!activeLinkGroup || activeLinkGroup.parent !== parent
        || activeLinkGroup.lastIndex + 1 !== index || !activeLinkGroup.mark.eq(link)) {
      activeLinkGroup = { parent, lastIndex: index, mark: link, from: pos, to: pos + node.nodeSize };
      linkGroups.push(activeLinkGroup);
    } else {
      activeLinkGroup.lastIndex = index;
      activeLinkGroup.to = pos + node.nodeSize;
    }
  });

  for (const { pos, node } of nodeUpdates) transaction.setNodeMarkup(pos, undefined, normalizedAttrs(node, uuid()), node.marks);
  for (const { from, to, old } of textUpdates) {
    if (old) transaction.removeMark(from, to, old);
    transaction.addMark(from, to, identityMark(uuid(), old?.attrs.version ?? 1, clone(old?.attrs.nativeAttrs ?? null)));
  }
  let linkUpdates = 0;
  for (const group of linkGroups) {
    const id = group.mark.attrs.nativeId;
    const key = typeof id === "string" ? id.toLowerCase() : "";
    if (UUID_V4.test(id) && !seen.has(key)) {
      seen.add(key);
      continue;
    }
    const replacementId = uuid();
    seen.add(replacementId.toLowerCase());
    transaction.removeMark(group.from, group.to, group.mark);
    transaction.addMark(group.from, group.to, mnemaSchema.marks.link.create({ ...group.mark.attrs, nativeId: replacementId }));
    linkUpdates += 1;
  }
  return nodeUpdates.length + textUpdates.length + linkUpdates;
}

export const identityNormalizationPlugin = new Plugin({
  appendTransaction(transactions, _oldState, newState) {
    if (!transactions.some((transaction) => transaction.docChanged)) return null;
    const transaction = newState.tr;
    const changes = buildIdentityNormalization(transaction);
    return changes ? transaction.setMeta("mnemaIdentityNormalization", true) : null;
  },
});

export function moveTopLevelBlock(state, fromIndex, toIndex) {
  const children = [];
  state.doc.forEach((child) => children.push(child));
  if (fromIndex < 0 || fromIndex >= children.length || toIndex < 0 || toIndex >= children.length) throw new Error("move index out of range");
  const [moved] = children.splice(fromIndex, 1);
  children.splice(toIndex, 0, moved);
  return state.tr.replaceWith(0, state.doc.content.size, Fragment.fromArray(children));
}

function opaqueNodeView(node, label) {
  const dom = document.createElement(node.isInline ? "span" : "div");
  dom.className = "unsupported";
  dom.contentEditable = "false";
  dom.tabIndex = 0;
  dom.setAttribute("role", "note");
  dom.setAttribute("aria-label", `${label}: ${node.attrs.nativeType}, версия ${node.attrs.nativeVersion}`);
  const strong = document.createElement("strong");
  strong.textContent = label;
  const detail = document.createElement("span");
  detail.textContent = ` ${node.attrs.nativeType} · v${node.attrs.nativeVersion}`;
  dom.append(strong, detail);
  return { dom, ignoreMutation: () => true, stopEvent: () => false };
}

export const nodeViews = {
  unsupported_block: (node) => opaqueNodeView(node, "Неподдерживаемый блок"),
  unsupported_inline: (node) => opaqueNodeView(node, "Неподдерживаемый фрагмент"),
};

export function sanitizePastedHtml(html) {
  const parsed = new DOMParser().parseFromString(html, "text/html");
  const output = document.implementation.createHTMLDocument("");
  const allowed = new Set(["P", "BR", "H1", "H2", "H3", "BLOCKQUOTE", "UL", "OL", "LI", "HR", "STRONG", "B", "EM", "I", "CODE", "A"]);
  const droppedWithContents = new Set(["SCRIPT", "STYLE", "SVG", "IFRAME", "FORM", "OBJECT", "EMBED", "IMG", "VIDEO", "AUDIO"]);

  function copySafe(source, target) {
    for (const child of source.childNodes) {
      if (child.nodeType === Node.TEXT_NODE) {
        target.append(output.createTextNode(child.data));
        continue;
      }
      if (child.nodeType !== Node.ELEMENT_NODE) continue;
      const tagName = child.tagName.toUpperCase();
      if (droppedWithContents.has(tagName)) continue;
      if (!allowed.has(tagName)) {
        copySafe(child, target);
        continue;
      }
      const next = output.createElement(tagName.toLowerCase());
      if (tagName === "A") {
        const href = child.getAttribute("href");
        if (!isSafeHttpsUrl(href)) {
          copySafe(child, target);
          continue;
        }
        next.setAttribute("href", href);
      }
      if (tagName === "OL") {
        const start = Number(child.getAttribute("start"));
        if (Number.isInteger(start) && start > 1 && start <= MAX_INT32) next.setAttribute("start", String(start));
      }
      copySafe(child, next);
      target.append(next);
    }
  }
  copySafe(parsed.body, output.body);
  return output.body.innerHTML;
}

export function createPreview(document, container) {
  container.replaceChildren();
  const root = document.root;
  function applyLanguage(element, attrs) {
    if (attrs.lang) element.lang = attrs.lang;
    if (attrs.dir) element.dir = attrs.dir;
  }
  function appendNative(node, parent) {
    if (!KNOWN_TYPES.has(node.type) || node.version !== 1) {
      const placeholder = documentApi.createElement("div");
      placeholder.className = "unsupported";
      placeholder.setAttribute("role", "note");
      placeholder.textContent = `Неподдерживаемый материал: ${node.type}, версия ${node.version}`;
      parent.append(placeholder);
      return;
    }
    if (node.type === "text") {
      let element = documentApi.createTextNode(node.attrs.text);
      for (const mark of node.attrs.marks ?? []) {
        const wrapper = documentApi.createElement(mark === "strong" ? "strong" : mark === "em" ? "em" : "code");
        wrapper.append(element);
        element = wrapper;
      }
      if (node.attrs.lang || node.attrs.dir) {
        const wrapper = documentApi.createElement("span");
        applyLanguage(wrapper, node.attrs);
        wrapper.append(element);
        element = wrapper;
      }
      parent.append(element);
      return;
    }
    if (node.type === "ruby") {
      const ruby = documentApi.createElement("ruby");
      applyLanguage(ruby, node.attrs);
      ruby.append(documentApi.createTextNode(node.attrs.base));
      const rt = documentApi.createElement("rt");
      rt.textContent = node.attrs.reading;
      ruby.append(rt);
      parent.append(ruby);
      return;
    }
    if (node.type === "link") {
      const link = documentApi.createElement("a");
      link.href = node.attrs.href;
      link.rel = "noreferrer noopener";
      applyLanguage(link, node.attrs);
      node.content.forEach((child) => appendNative(child, link));
      parent.append(link);
      return;
    }
    const tags = { doc: "div", paragraph: "p", heading: `h${node.attrs.level}`, blockquote: "blockquote", bullet_list: "ul", ordered_list: "ol", list_item: "li", divider: "hr" };
    const element = documentApi.createElement(tags[node.type]);
    applyLanguage(element, node.attrs);
    if (node.type === "ordered_list" && node.attrs.order > 1) element.start = node.attrs.order;
    node.content.forEach((child) => appendNative(child, element));
    parent.append(element);
  }
  const documentApi = container.ownerDocument;
  appendNative(root, container);
}
