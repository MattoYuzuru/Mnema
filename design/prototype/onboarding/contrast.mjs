// Contrast of the proposed OB-102 pairs (WCAG 2.2 relative luminance). Usage: node contrast.mjs
const hex = h => h.match(/\w\w/g).map(x => parseInt(x, 16) / 255);
const lin = c => (c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4);
const lum = h => { const [r, g, b] = hex(h).map(lin); return .2126 * r + .7152 * g + .0722 * b; };
const over = (fg, alpha, bg) => '#' + hex(fg).map((c, i) => Math.round((c * alpha + hex(bg)[i] * (1 - alpha)) * 255).toString(16).padStart(2, '0')).join('');
const ratio = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + .05) / (y + .05); };
const dimmedPaper = over('#160c45', .74, '#f4f0e5');
const pairs = [
  ['text', 'on-night / night', '#fbf8ef', '#160c45', 4.5],
  ['text', 'on-night-muted / night', '#cfc6ea', '#160c45', 4.5],
  ['text', 'star-lavender eyebrow / night', '#b9a6ec', '#160c45', 4.5],
  ['text', 'ink / on-night (primary button on night)', '#281378', '#fbf8ef', 4.5],
  ['text', 'ink / path-hover (chevron, hover row)', '#281378', '#e6dcf6', 4.5],
  ['text', 'body / path-hover', '#342e44', '#e6dcf6', 4.5],
  ['text', 'muted / sheet (tour card hint)', '#625c70', '#fbf8ef', 4.5],
  ['text', 'ink / sheet (tour card key words)', '#281378', '#fbf8ef', 4.5],
  ['non-text', 'star-lavender ring / dimmed paper (cutout)', '#b9a6ec', dimmedPaper, 3],
  ['non-text', 'ink progress / soft track', '#281378', '#e8e1ed', 3],
  ['non-text', 'muted todo glyph / paper', '#625c70', '#f4f0e5', 3],
  ['non-text', 'star-lavender glow edge / night (decorative)', '#b9a6ec', '#160c45', 3],
];
const rows = pairs.map(([kind, name, fg, bg, min]) => ({ kind, name, fg, bg, ratio: +ratio(fg, bg).toFixed(2), min, pass: ratio(fg, bg) >= min }));
console.log(JSON.stringify({ dimmedPaper, rows }, null, 1));
process.exitCode = rows.every(r => r.pass) ? 0 : 1;
