// Small, dependency-free DOM fixture for the existing form and list interactions.
// Layout is checked through explicit grid coordinates; device notifications are not simulated here.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const decode = (s) => String(s).replace(/&(amp|lt|gt|quot|#39);/g,
  (_, key) => ({ amp: '&', lt: '<', gt: '>', quot: '"', '#39': "'" }[key]));
const voidTags = new Set(['input', 'img', 'br', 'hr', 'meta', 'link']);

class Element {
  constructor(tagName = 'div') {
    this.tagName = tagName.toUpperCase();
    this.children = [];
    this.dataset = {};
    this.attributes = {};
    this.style = { setProperty() {}, removeProperty() {} };
    this.listeners = {};
    this.className = '';
    this.value = '';
    this.textContent = '';
    this.classList = {
      contains: (name) => this.className.split(/\s+/).includes(name),
      add: (...names) => { this.className = [...new Set([...this.className.split(/\s+/), ...names])].filter(Boolean).join(' '); },
      remove: (...names) => { this.className = this.className.split(/\s+/).filter((name) => !names.includes(name)).join(' '); },
      toggle: (name, on) => {
        const enabled = on === undefined ? !this.classList.contains(name) : on;
        this.classList[enabled ? 'add' : 'remove'](name);
        return enabled;
      }
    };
  }
  setAttribute(name, value) {
    this.attributes[name] = value;
    if (name === 'class') this.className = value;
    else if (name.startsWith('data-')) this.dataset[name.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = value;
    else if (name === 'checked' || name === 'selected' || name === 'disabled') this[name] = true;
    else if (name !== 'style') this[name] = value;
  }
  getAttribute(name) { return this.attributes[name]; }
  removeAttribute(name) { delete this.attributes[name]; this[name] = false; }
  appendChild(el) { this.children.push(el); el.parentElement = this; return el; }
  remove() { if (this.parentElement) this.parentElement.children = this.parentElement.children.filter((el) => el !== this); }
  set innerHTML(html) {
    this._html = html;
    this.children = [];
    const stack = [this];
    for (const token of String(html).match(/<!--[\s\S]*?-->|<[^>]*>|[^<]+/g) || []) {
      if (token.startsWith('<!--') || token.startsWith('<!')) continue;
      if (token.startsWith('</')) {
        if (stack.length > 1) stack.pop();
      } else if (token.startsWith('<')) {
        const match = token.match(/^<([\w-]+)/);
        if (!match) continue;
        const el = new Element(match[1]);
        for (const attr of token.slice(match[0].length, -1).matchAll(/([\w-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g)) {
          el.setAttribute(attr[1], decode(attr[2] ?? attr[3] ?? attr[4] ?? ''));
        }
        stack.at(-1).appendChild(el);
        if (!voidTags.has(match[1].toLowerCase()) && !token.endsWith('/>')) stack.push(el);
      } else stack.at(-1).textContent += decode(token);
    }
    for (const select of this.querySelectorAll('select')) {
      select.value = (select.querySelectorAll('option').find((el) => el.selected) || select.querySelector('option') || {}).value || '';
    }
  }
  get innerHTML() { return this._html || ''; }
  matches(selector) {
    const compound = selector.match(/^([\w-]+)(\[.*\])$/);
    if (compound) return this.matches(compound[1]) && this.matches(compound[2]);
    if (selector.startsWith('#')) return this.id === selector.slice(1);
    if (selector.startsWith('.')) return this.classList.contains(selector.slice(1));
    if (selector.startsWith('[')) {
      const match = selector.match(/^\[([^=\]]+)(?:=["']?([^"'\]]+)["']?)?\]$/);
      return !!match && Object.hasOwn(this.attributes, match[1]) && (match[2] === undefined || this.attributes[match[1]] === match[2]);
    }
    return this.tagName.toLowerCase() === selector;
  }
  querySelectorAll(selector) {
    const parts = selector.split(/\s+/);
    const descendants = [];
    const walk = (el) => { for (const child of el.children) { descendants.push(child); walk(child); } };
    walk(this);
    return descendants.filter((el) => {
      if (!el.matches(parts.at(-1))) return false;
      let parent = el.parentElement;
      for (let i = parts.length - 2; i >= 0; i--) {
        while (parent && !parent.matches(parts[i])) parent = parent.parentElement;
        if (!parent) return false;
        parent = parent.parentElement;
      }
      return true;
    });
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  addEventListener(name, fn) { (this.listeners[name] ||= []).push(fn); }
  async dispatch(name, props = {}) {
    const event = { target: this, stopPropagation() {}, preventDefault() {}, ...props };
    for (const fn of this.listeners[name] || []) await fn(event);
    if (typeof this['on' + name] === 'function') await this['on' + name](event);
  }
  click() { return this.dispatch('click'); }
  focus() {}
}

function harness(files = ['timetable.js'], options = {}) {
  const root = path.join(__dirname, '..');
  const document = new Element('document');
  document.innerHTML = fs.readFileSync(path.join(root, 'www/index.html'), 'utf8');
  document.getElementById = (id) => document.querySelector('#' + id);
  document.createElement = (tag) => new Element(tag);
  document.body = document.querySelector('body');
  document.documentElement = document.querySelector('html');
  document.hidden = false;
  const storage = new Map(Object.entries(options.storage || {}));
  const saved = [], toasts = [], windowListeners = {};
  let now = options.now ?? new Date('2026-10-22T07:45:00+08:00').getTime();
  class Clock extends Date {
    constructor(...args) { super(...(args.length ? args : [now])); }
    static now() { return now; }
  }
  const context = {
    console, Date: Clock, document, navigator: {}, AbortController,
    setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {},
    S: options.state || { tasks: [], fired: [], settings: {} },
    confirm: () => true,
    toast: (...args) => toasts.push(args),
    fetch: options.fetch || (async () => ({ ok: false })),
    localStorage: {
      getItem: (key) => storage.get(key) ?? null,
      setItem: (key, value) => storage.set(key, String(value)),
      removeItem: (key) => storage.delete(key)
    },
    addEventListener: (name, fn) => (windowListeners[name] ||= []).push(fn),
    save: () => { saved.push(JSON.parse(JSON.stringify(context.S))); },
    ...options.globals
  };
  context.window = context;
  vm.createContext(context);
  for (const file of files) vm.runInContext(fs.readFileSync(path.join(root, 'www/js', file), 'utf8'), context, { filename: file });
  return {
    context, document, saved, toasts, storage,
    el: (id) => document.getElementById(id),
    now: (value) => { now = value; },
    run: (code) => vm.runInContext(code, context),
    bind: async () => { for (const fn of windowListeners.DOMContentLoaded || []) await fn(); }
  };
}

module.exports = { harness, Element };
