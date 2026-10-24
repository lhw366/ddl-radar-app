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
      '--surface-card': wallpaper ? 'rgba(14,20,30,.28)' : tokens.card,
      '--surface-strong': wallpaper ? 'rgba(14,20,30,.58)' : tokens.strong,
      '--surface-tab': wallpaper ? 'rgba(14,20,30,.70)' : tokens.tab,
      '--ui-color-scheme': wallpaper || luminance(card) < 0.2 ? 'dark' : 'light',
      '--readable-text': wallpaper ? 'rgb(250,252,255)' : rgb(readable(color(tokens.text) || [40, 40, 50], surfaces)),
      '--readable-dim': wallpaper ? 'rgb(226,233,244)' : rgb(readable(color(tokens.dim) || [100, 100, 110], surfaces)),
      '--readable-accent': wallpaper ? 'rgb(232,242,255)' : rgb(readable(accent, surfaces)),
      '--readable-accent-2': wallpaper ? 'rgb(196,222,255)' : rgb(readable(accent2, surfaces)),
      '--on-accent': rgb(onAccent),
      '--on-accent-2': rgb(blackOrWhite([accent2])),
      '--action-bg': action || rgb(accent)
    };
  }
  // Sample the cover-cropped image once. Later region reads use this tiny grid,
  // never a full-size image and never a background polling timer.
  function sampleRegion(grid, rectangle) {
    if (!grid || !grid.values || !grid.width || !grid.height) return { mean: 0.25, p90: 0.45, spread: 0 };
    const x0 = Math.max(0, Math.min(grid.width - 1, Math.floor(rectangle.left * grid.width)));
    const y0 = Math.max(0, Math.min(grid.height - 1, Math.floor(rectangle.top * grid.height)));
    const x1 = Math.max(x0 + 1, Math.min(grid.width, Math.ceil(rectangle.right * grid.width)));
    const y1 = Math.max(y0 + 1, Math.min(grid.height, Math.ceil(rectangle.bottom * grid.height)));
    const histogram = new Array(20).fill(0);
    let total = 0, count = 0, low = 1, high = 0;
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
      const value = grid.values[y * grid.width + x];
      total += value; count++; low = Math.min(low, value); high = Math.max(high, value);
      histogram[Math.min(19, Math.floor(value * 20))]++;
    }
    let seen = 0, percentile = 0;
    for (let bin = 0; bin < histogram.length; bin++) {
      seen += histogram[bin];
      if (seen >= count * 0.9) { percentile = (bin + 1) / 20; break; }
    }
    return { mean: total / count, p90: percentile, spread: high - low };
  }
  function lightingFor(stats) {
    if (stats.mean > 0.82 && stats.p90 > 0.84 && stats.spread < 0.10) {
      return { shade: 0, shadow: 'none', tone: 'flat-light', ink: 'rgb(66,76,91)', muted: 'rgb(85,97,112)' };
    }
    const glare = Math.max(stats.mean, stats.p90 * 0.85);
    const shade = Math.max(0, Math.min(0.16, (glare - 0.12) * 0.22));
    const edge = 0.32 + glare * 0.26;
    return { shade: Number(shade.toFixed(3)),
      shadow: '0 1px 3px rgba(2,7,15,' + edge.toFixed(2) + '),0 0 9px rgba(2,7,15,' + (0.10 + glare * 0.14).toFixed(2) + ')',
      tone: glare > 0.62 ? 'bright' : glare < 0.18 ? 'dark' : 'mixed',
      ink: 'rgb(250,252,255)', muted: 'rgb(226,233,244)' };
  }
  let previous = '', imageSource = '', loadedImage = null, pixels = null, brightness = null, brightnessKey = '';
  let imageGeneration = 0, scheduled = false, bound = false;
  function buildPixels() {
    if (!loadedImage || !document.createElement) return;
    try {
      const width = 48, height = Math.max(48, Math.min(128, Math.round(width * window.innerHeight / window.innerWidth)));
      const canvas = document.createElement('canvas'); canvas.width = width; canvas.height = height;
      const context = canvas.getContext('2d', { willReadFrequently: true });
      const scale = Math.max(width / loadedImage.naturalWidth, height / loadedImage.naturalHeight);
      context.drawImage(loadedImage, (width - loadedImage.naturalWidth * scale) / 2,
        (height - loadedImage.naturalHeight * scale) / 2, loadedImage.naturalWidth * scale, loadedImage.naturalHeight * scale);
      pixels = { width, height, data: context.getImageData(0, 0, width, height).data };
      brightnessKey = '';
    } catch (error) { pixels = null; brightness = null; }
  }
  function currentGrid(settings) {
    if (!pixels) return null;
    const dim = Math.max(0, Math.min(1, Number(settings.bgDim || 0) / 100));
    const rawOverlay = getComputedStyle(document.body).getPropertyValue('--overlay');
    const overlay = String(rawOverlay || '0,0,0').split(',').map(Number);
    const key = dim + ':' + overlay.join(',');
    if (key === brightnessKey && brightness) return brightness;
    const values = new Float32Array(pixels.width * pixels.height);
    for (let i = 0; i < values.length; i++) {
      const rgbValue = [0, 1, 2].map(channel => pixels.data[i * 4 + channel] * (1 - dim) + (overlay[channel] || 0) * dim);
      values[i] = luminance(rgbValue);
    }
    brightness = { width: pixels.width, height: pixels.height, values };
    brightnessKey = key;
    return brightness;
  }
  function refreshRegions() {
    const settings = window.S && window.S.settings;
    if (!settings || !settings.bgImage) return;
    const grid = currentGrid(settings), width = window.innerWidth, height = window.innerHeight;
    if (!width || !height) return;
    const regions = document.querySelectorAll('#topbar,.sec-title,.cal-toolbar,.cal-week,.cal-cell,.cal-legend,.cal-note,.mx-tip,.empty-tip,.tt-empty,' +
      '.card .f-label,.card .set-line > div:first-child,.card .set-line > label:not(.switch),.card .tip,' +
      '.card .hero-label,#heroName,#heroCount,#heroMeta,.card .he-title,.card .he-sub,.card .bored-head,#boredText,' +
      '.card .t-name,.card .t-meta,.card .t-detail');
    regions.forEach(el => {
      if (!el.getBoundingClientRect) return;
      const rect = el.getBoundingClientRect();
      if (!rect.width || !rect.height || rect.bottom < 0 || rect.top > height) return;
      const stats = sampleRegion(grid, { left: rect.left / width, right: rect.right / width,
        top: rect.top / height, bottom: rect.bottom / height });
      const local = lightingFor(stats);
      el.style.setProperty('--photo-shade', local.shade);
      el.style.setProperty('--photo-shadow', local.shadow);
      el.style.setProperty('--photo-ink', local.ink);
      el.style.setProperty('--photo-muted', local.muted);
      el.setAttribute('data-photo-tone', local.tone);
    });
    document.body.setAttribute('data-photo-sampled', grid ? 'true' : 'false');
  }
  function scheduleRegions() {
    if (scheduled || document.hidden) return;
    scheduled = true;
    const frame = window.requestAnimationFrame || (callback => setTimeout(callback, 0));
    frame(() => { scheduled = false; refreshRegions(); });
  }
  function loadPhoto(source) {
    if (source === imageSource) return;
    imageSource = source; loadedImage = null; pixels = null; brightness = null; brightnessKey = '';
    const generation = ++imageGeneration;
    if (!source || !window.Image) return;
    const image = new Image();
    image.onload = () => {
      if (generation !== imageGeneration) return;
      loadedImage = image; buildPixels(); scheduleRegions();
    };
    image.onerror = () => { if (generation === imageGeneration) scheduleRegions(); };
    image.src = source;
  }
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
    if (signature !== previous || !body.style.getPropertyValue('--readable-text')) {
      previous = signature;
      const derived = derive(tokens, wallpaper);
      Object.keys(derived).forEach(key => body.style.setProperty(key, derived[key]));
      body.setAttribute('data-wallpaper', wallpaper ? 'true' : 'false');
    }
    loadPhoto(wallpaper ? settings.bgImage : '');
    scheduleRegions();
  }
  function init() {
    refresh();
    if (bound) return;
    bound = true;
    document.querySelectorAll('.tab,.cal-nav,.cal-today').forEach(el => el.addEventListener('click', scheduleRegions));
    const views = document.getElementById('views');
    if (views) {
      views.addEventListener('scroll', scheduleRegions, { capture: true, passive: true });
      views.addEventListener('click', scheduleRegions, { passive: true });
    }
    const calendar = document.getElementById('calGrid');
    if (calendar && window.MutationObserver) {
      // Holiday data and date selection can replace the calendar asynchronously.
      // Observe that one container's children, never the ticking app subtree.
      const observer = new MutationObserver(scheduleRegions);
      observer.observe(calendar, { childList: true });
      document.querySelectorAll('.task-list').forEach(list => observer.observe(list, { childList: true }));
    }
    window.addEventListener('resize', () => { buildPixels(); scheduleRegions(); }, { passive: true });
    document.addEventListener('visibilitychange', () => { if (!document.hidden) scheduleRegions(); });
  }
  window.AppReadability = { refresh, derive, color, contrast, sampleRegion, lightingFor };
  document.addEventListener('DOMContentLoaded', init);
})();
