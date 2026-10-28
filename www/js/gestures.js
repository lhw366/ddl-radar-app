/* One-finger horizontal navigation uses the existing tabs; vertical scroll stays native. */
(function () {
  'use strict';
  let initialized = false;
  const controls = new Set(['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON', 'A', 'LABEL', 'IMG', 'CANVAS', 'VIDEO']);
  function blockedNode(node, boundary) {
    for (let el = node; el && el !== boundary; el = el.parentElement) {
      if (controls.has(el.tagName) || el.isContentEditable || el.getAttribute('contenteditable') === 'true' ||
          el.getAttribute('data-no-swipe') !== undefined && el.getAttribute('data-no-swipe') !== null ||
          el.id === 'ttGrid' || el.classList.contains('sheet-wrap')) return true;
      if (el.scrollWidth > el.clientWidth + 2 && window.getComputedStyle &&
          /auto|scroll/.test(getComputedStyle(el).overflowX)) return true;
    }
    return false;
  }
  function modalOpen() {
    return [...document.querySelectorAll('.sheet-wrap')].some(el => !el.classList.contains('hidden')) ||
      !!(document.getElementById('onboard') && !document.getElementById('onboard').classList.contains('hidden'));
  }
  function dismissSheet(wrap) {
    if (!wrap) return;
    if (wrap.id === 'ttEditor') {
      const close = document.getElementById('ttEdClose');
      if (close) close.click();
      return;
    }
    const mask = wrap.querySelector('.mask');
    if (mask && mask.dataset.close) mask.click();
  }
  function bindSheetGestures(wrap) {
    const sheet = wrap.querySelector('.sheet');
    if (!sheet || sheet.dataset.gestureBound === '1') return;
    sheet.dataset.gestureBound = '1';
    let start = null;
    sheet.addEventListener('touchstart', event => {
      if (event.touches.length !== 1) { start = null; return; }
      const t = event.touches[0];
      start = { x: t.clientX, y: t.clientY, at: Date.now(), scrollTop: sheet.scrollTop, mode: '' };
    }, { passive: true });
    sheet.addEventListener('touchmove', event => {
      if (!start || event.touches.length !== 1) return;
      const t = event.touches[0];
      const dx = t.clientX - start.x, dy = t.clientY - start.y;
      if (!start.mode) {
        if (start.scrollTop <= 0 && dy > 12 && dy > Math.abs(dx) * 1.25) start.mode = 'down';
        else if (start.x <= 28 && dx > 12 && dx > Math.abs(dy) * 1.25) start.mode = 'back';
      }
      if (start.mode && event.cancelable) event.preventDefault();
    }, { passive: false });
    sheet.addEventListener('touchend', event => {
      const gesture = start; start = null;
      if (!gesture || Date.now() - gesture.at > 1100) return;
      const t = [...event.changedTouches][0];
      if (!t) return;
      const dx = t.clientX - gesture.x, dy = t.clientY - gesture.y;
      const closes = (gesture.mode === 'down' && dy >= 88 && dy > Math.abs(dx) * 1.15) ||
        (gesture.mode === 'back' && dx >= 72 && dx > Math.abs(dy) * 1.25);
      if (closes) dismissSheet(wrap);
    }, { passive: true });
    sheet.addEventListener('touchcancel', () => { start = null; }, { passive: true });
  }
  function init() {
    if (initialized) return;
    const views = document.getElementById('views');
    if (!views) return;
    initialized = true;
    document.querySelectorAll('.sheet-wrap').forEach(bindSheetGestures);
    let gesture = null, suppressClickUntil = 0;
    views.addEventListener('touchstart', event => {
      gesture = null;
      if (event.touches.length !== 1 || modalOpen() || blockedNode(event.target, views)) return;
      const focused = document.activeElement;
      if (focused && ['INPUT', 'SELECT', 'TEXTAREA'].includes(focused.tagName)) return;
      if (window.getSelection && !getSelection().isCollapsed) return;
      const t = event.touches[0], width = window.innerWidth || document.documentElement.clientWidth;
      // Leave the phone's edge-back gesture to the operating system.
      if (t.clientX < 20 || t.clientX > width - 20) return;
      gesture = { id: t.identifier, x: t.clientX, y: t.clientY, at: Date.now(), horizontal: false };
    }, { passive: true });
    views.addEventListener('touchmove', event => {
      if (!gesture) return;
      if (event.touches.length !== 1 || modalOpen()) { gesture = null; return; }
      const t = event.touches[0];
      if (t.identifier !== gesture.id) { gesture = null; return; }
      const dx = Math.abs(t.clientX - gesture.x), dy = Math.abs(t.clientY - gesture.y);
      if (!gesture.horizontal) {
        if (dy >= 10 && dy >= dx * 0.8) { gesture = null; return; }
        if (dx < 16 || dx < dy * 1.5) return;
        gesture.horizontal = true;
      }
      if (event.cancelable) event.preventDefault();
    }, { passive: false });
    views.addEventListener('touchend', event => {
      const start = gesture; gesture = null;
      if (!start || !start.horizontal || modalOpen() || Date.now() - start.at > 900) return;
      const t = [...event.changedTouches].find(item => item.identifier === start.id);
      if (!t) return;
      const dx = t.clientX - start.x, dy = t.clientY - start.y;
      const threshold = Math.max(56, (window.innerWidth || 360) * 0.14);
      if (Math.abs(dx) < threshold || Math.abs(dx) < Math.abs(dy) * 1.5) return;
      const tabs = [...document.querySelectorAll('.tab')];
      const current = tabs.findIndex(tab => tab.classList.contains('active'));
      const target = tabs[current + (dx < 0 ? 1 : -1)];
      if (current < 0 || !target) return;
      suppressClickUntil = Date.now() + 300;
      if (event.cancelable) event.preventDefault();
      target.click();
    }, { passive: false });
    views.addEventListener('touchcancel', () => { gesture = null; }, { passive: true });
    views.addEventListener('click', event => {
      if (Date.now() >= suppressClickUntil) return;
      event.preventDefault(); event.stopPropagation();
    }, true);
  }
  window.AppGestures = { init };
  document.addEventListener('DOMContentLoaded', init);
})();
