const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { harness, Element } = require('./functional-dom.cjs');

function navigation() {
  const h = harness(['gestures.js'], { globals: { innerWidth: 360,
    getComputedStyle: node => ({ overflowX: node.attributes['data-scroll'] || 'visible' }) } });
  const tabs = h.document.querySelectorAll('.tab');
  const visits = [];
  tabs.forEach(tab => tab.addEventListener('click', () => {
    visits.push(tab.dataset.view);
    tabs.forEach(other => other.classList.toggle('active', other === tab));
  }));
  h.context.AppGestures.init();
  async function swipe(options = {}) {
    const target = options.target || h.el('view-home');
    const x = options.x ?? 240, y = options.y ?? 250;
    const dx = options.dx ?? -130, dy = options.dy ?? 8;
    let prevented = 0;
    const point = (a, b) => ({ identifier: 1, clientX: a, clientY: b });
    const props = { target, cancelable: true, preventDefault() { prevented++; } };
    await h.el('views').dispatch('touchstart', { ...props, touches: [point(x, y)] });
    await h.el('views').dispatch('touchmove', { ...props, touches: [point(x + dx, y + dy)] });
    if (options.wait) h.now(h.context.Date.now() + options.wait);
    await h.el('views').dispatch('touchend', { ...props, changedTouches: [point(x + dx, y + dy)] });
    return prevented;
  }
  return Object.assign(h, { visits, swipe, tabs });
}

test('phone horizontal swipes click the existing next and previous tabs without wrapping', async () => {
  const h = navigation();
  h.context.AppGestures.init();
  assert.equal(h.el('views').listeners.touchstart.length, 1, 'initialization is idempotent');
  await h.swipe();
  await h.swipe();
  await h.swipe({ x: 90, dx: 130 });
  assert.deepEqual(h.visits, ['matrix', 'calendar', 'matrix']);
  await h.tabs[0].click();
  h.visits.length = 0;
  await h.swipe({ x: 90, dx: 130 });
  assert.deepEqual(h.visits, []);
  assert.equal(h.storage.size, 0, 'navigation never rewrites saved data');
});

test('vertical scrolling, small drags, edge-back, long holds and cancelled touches do not switch views', async () => {
  const h = navigation();
  assert.equal(await h.swipe({ dx: 15, dy: 130 }), 0, 'vertical scroll is never cancelled');
  await h.swipe({ dx: -30, dy: 1 });
  await h.swipe({ x: 10, dx: 120 });
  await h.swipe({ wait: 1000 });
  const views = h.el('views'), target = h.el('view-home');
  await views.dispatch('touchstart', { target, touches: [{ identifier: 1, clientX: 240, clientY: 200 }] });
  await views.dispatch('touchcancel');
  await views.dispatch('touchend', { target, changedTouches: [{ identifier: 1, clientX: 60, clientY: 200 }] });
  assert.deepEqual(h.visits, []);
});

test('forms, images, timetable interactions, horizontal scrollers and open dialogs retain their gestures', async () => {
  const h = navigation();
  for (const id of ['setVibrationMode', 'bgDimRange', 'ttGrid', 'bgPick', 'fName']) await h.swipe({ target: h.el(id) });
  await h.swipe({ target: h.document.querySelector('img') });
  const scroller = new Element('div');
  scroller.scrollWidth = 500; scroller.clientWidth = 100; scroller.setAttribute('data-scroll', 'auto');
  h.el('view-home').appendChild(scroller);
  await h.swipe({ target: scroller });
  const custom = new Element('div'); custom.setAttribute('data-no-swipe', ''); h.el('view-home').appendChild(custom);
  await h.swipe({ target: custom });
  h.el('editorWrap').classList.remove('hidden');
  await h.swipe();
  h.el('editorWrap').classList.add('hidden');
  h.document.activeElement = h.el('fName');
  await h.swipe();
  h.document.activeElement = null;
  const views = h.el('views');
  await views.dispatch('touchstart', { target: h.el('view-home'), touches: [
    { identifier: 1, clientX: 240, clientY: 200 }, { identifier: 2, clientX: 250, clientY: 210 }
  ] });
  await views.dispatch('touchend', { changedTouches: [{ identifier: 1, clientX: 60, clientY: 200 }] });
  assert.deepEqual(h.visits, []);
});

const tokens = (overrides = {}) => ({ card: 'rgba(255,255,255,.85)', strong: 'rgba(255,255,255,.97)',
  tab: 'rgba(255,255,255,.9)', text: '#33334a', dim: '#8b8ba3', accent: '#8fae8b', accent2: '#a8c29e',
  chip: 'linear-gradient(135deg,#8fae8b,#a8c29e)', ...overrides });

test('light accent buttons use legible foregrounds and dark-to-light gradients receive a safe action surface', () => {
  const h = harness(['readability.js']), api = h.context.AppReadability;
  for (const palette of [tokens(), tokens({ accent: '#ff5e7e', accent2: '#ff9f43' }),
    tokens({ accent: '#4de3c1', accent2: '#7c5cff' }), tokens({ accent: '#5b7cfa', accent2: '#8f6cf6' })]) {
    const derived = api.derive(palette, false), foreground = api.color(derived['--on-accent']);
    assert.ok(api.contrast(foreground, api.color(palette.accent)) >= 4.5);
    assert.ok(api.contrast(foreground, api.color(palette.accent2)) >= 4.5);
  }
  const mixed = api.derive(tokens({ accent: '#101020', accent2: '#ffffb0' }), true);
  assert.equal(mixed['--action-bg'], 'rgb(16,16,32)');
  assert.ok(api.contrast(api.color(mixed['--on-accent']), api.color(mixed['--action-bg'])) >= 4.5);
});

test('light and dark photo skins keep body, muted text and accents legible without altering the saved palette', () => {
  const h = harness(['readability.js']), api = h.context.AppReadability;
  for (const palette of [tokens(), tokens({ card: 'rgba(28,30,40,.78)', strong: 'rgba(34,36,48,.96)',
    tab: 'rgba(22,24,34,.9)', text: '#eef0f6', dim: '#9aa0b5', accent: '#c5df50', accent2: '#814cf0' })]) {
    const before = JSON.stringify(palette), derived = api.derive(palette, true);
    for (const fg of ['--readable-text', '--readable-dim', '--readable-accent', '--readable-accent-2']) {
      for (const bg of ['--surface-card', '--surface-strong', '--surface-tab']) {
        assert.ok(api.contrast(api.color(derived[fg]), api.color(derived[bg])) >= 4.5, fg + ' on ' + bg);
      }
    }
    assert.doesNotMatch(derived['--surface-card'], /rgba/);
    assert.equal(JSON.stringify(palette), before);
  }
});

test('readability refresh follows theme changes, is idempotent and never modifies stored settings', () => {
  let palette = tokens(), writes = 0;
  const h = harness(['readability.js'], { globals: {
    getComputedStyle: () => ({ getPropertyValue: name => ({ '--card': palette.card, '--card-strong': palette.strong,
      '--tab-bg': palette.tab, '--text': palette.text, '--text-dim': palette.dim, '--accent': palette.accent,
      '--accent-2': palette.accent2, '--chip-on': palette.chip })[name] || '' })
  } });
  const properties = new Map();
  h.document.body.style = { setProperty: (key, value) => { properties.set(key, value); writes++; }, getPropertyValue: key => properties.get(key) || '' };
  h.context.S.settings = { theme: 'custom', bgImage: 'local-photo', customPalette: palette };
  const before = JSON.stringify(h.context.S);
  h.context.AppReadability.refresh();
  const firstWrites = writes;
  h.context.AppReadability.refresh();
  assert.equal(writes, firstWrites);
  assert.equal(h.document.body.getAttribute('data-wallpaper'), 'true');
  assert.equal(JSON.stringify(h.context.S), before);
  palette = tokens({ accent: '#101020', accent2: '#ffffb0' });
  h.context.AppReadability.refresh();
  assert.ok(writes > firstWrites);
  assert.equal(h.storage.size, 0);
});

test('mobile layout constrains overflowing settings descendants and photo/gesture modules are loaded', () => {
  const css = fs.readFileSync(path.join(__dirname, '../www/css/style.css'), 'utf8');
  const html = fs.readFileSync(path.join(__dirname, '../www/index.html'), 'utf8');
  assert.match(css, /\.view\{[^}]*overflow-x:hidden/);
  assert.match(css, /\.diag-row > span\{[^}]*overflow-wrap:anywhere/);
  assert.match(css, /\.slider-row input\[type=range\]\{[^}]*min-width:0/);
  assert.match(css, /\.set-line > select\{[^}]*flex:1 1 9rem/);
  assert.match(html, /js\/app\.js[\s\S]*js\/gestures\.js[\s\S]*js\/readability\.js/);
});
