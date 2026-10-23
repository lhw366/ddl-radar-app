/* Derive readable UI colors from a skin without changing the saved palette. */
(function () {
  'use strict';
  function color(value) {
    const text = String(value || '').trim();
    if (/^#[\da-f]{3}$/i.test(text)) return text.slice(1).split('').map(x => parseInt(x + x, 16)).concat(1);
    if (/^#[\da-f]{6}$/i.test(text)) return [1, 3, 5].map(i => parseInt(text.slice(i, i + 2), 16)).concat(1);
    const parts = text.match(/^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:\s*[,/]\s*([\d.]+))?\s*\)$/i);
    return parts ? [Number(parts[1]), Number(parts[2]), Number(parts[3]), parts[4] === undefined ? 1 : Number(parts[4])] : null;
  }
  function luminance(rgb) {
    const channels = rgb.slice(0, 3).map(c => { const v = c / 255; return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; });
    return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
  }
  function contrast(a, b) {
    const x = luminance(a), y = luminance(b);
    return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
  }
  const rgb = c => 'rgb(' + c.slice(0, 3).map(Math.round).join(',') + ')';
  const composite = (a, b) => a.slice(0, 3).map((v, i) => v * a[3] + b[i] * (1 - a[3]));
  const minimum = (foreground, backgrounds) => Math.min(...backgrounds.map(bg => contrast(foreground, bg)));
  function blackOrWhite(backgrounds) {
    const black = [0, 0, 0], white = [255, 255, 255];
    return minimum(black, backgrounds) >= minimum(white, backgrounds) ? black : white;
  }
  function readable(foreground, backgrounds) {
    if (minimum(foreground, backgrounds) >= 4.5) return foreground;
    const target = blackOrWhite(backgrounds);
    for (let step = 1; step <= 20; step++) {
      const next = foreground.slice(0, 3).map((v, i) => v + (target[i] - v) * step / 20);
      if (minimum(next, backgrounds) >= 4.5) return next;
    }
    return target;
  }
  function derive(tokens, wallpaper) {
    const card = color(tokens.card) || [255, 255, 255, 1];
    const strong = color(tokens.strong) || card;
    const tab = color(tokens.tab) || strong;
    const surfaces = [card, strong, tab].flatMap(c => wallpaper || c[3] === 1
      ? [c.slice(0, 3)] : [composite(c, [0, 0, 0]), composite(c, [255, 255, 255])]);
    const accent = color(tokens.accent) || [50, 80, 150, 1];
    const accent2 = color(tokens.accent2) || accent;
    const actionColors = [accent, accent2];
    let onAccent = blackOrWhite(actionColors);
    let action = tokens.chip;
    if (minimum(onAccent, actionColors) < 4.5) {
      action = rgb(accent);
      onAccent = blackOrWhite([accent]);
    }
    return {
      '--surface-card': wallpaper ? rgb(card) : tokens.card,
      '--surface-strong': wallpaper ? rgb(strong) : tokens.strong,
      '--surface-tab': wallpaper ? rgb(tab) : tokens.tab,
      '--ui-color-scheme': luminance(card) < 0.2 ? 'dark' : 'light',
      '--readable-text': rgb(readable(color(tokens.text) || [40, 40, 50], surfaces)),
      '--readable-dim': rgb(readable(color(tokens.dim) || [100, 100, 110], surfaces)),
      '--readable-accent': rgb(readable(accent, surfaces)),
      '--readable-accent-2': rgb(readable(accent2, surfaces)),
      '--on-accent': rgb(onAccent),
      '--on-accent-2': rgb(blackOrWhite([accent2])),
      '--action-bg': action || rgb(accent)
    };
  }
  let previous = '';
  function refresh() {
    if (!window.getComputedStyle || !document.body) return;
    const body = document.body, style = getComputedStyle(body);
    const read = key => style.getPropertyValue(key).trim();
    const settings = window.S && window.S.settings;
    const wallpaper = !!(settings && settings.bgImage);
    const tokens = { card: read('--card'), strong: read('--card-strong'), tab: read('--tab-bg'),
      text: read('--text'), dim: read('--text-dim'), accent: read('--accent'), accent2: read('--accent-2'), chip: read('--chip-on') };
    const signature = JSON.stringify([body.className, wallpaper, tokens]);
    // applyTheme clears inline styles, so check a derived property as well.
    if (signature === previous && body.style.getPropertyValue('--readable-text')) return;
    previous = signature;
    const derived = derive(tokens, wallpaper);
    Object.keys(derived).forEach(key => body.style.setProperty(key, derived[key]));
    body.setAttribute('data-wallpaper', wallpaper ? 'true' : 'false');
  }
  window.AppReadability = { refresh, derive, color, contrast };
  document.addEventListener('DOMContentLoaded', refresh);
})();
