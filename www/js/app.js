/* ============================================================
   DDL雷达 · 主程序
   状态 / 清单 / 四象限 / 提醒引擎 / 编辑器 / 设置 / 引导
   ============================================================ */
'use strict';

/* ---------------- 小工具 ---------------- */
const $ = (id) => document.getElementById(id);
const pad = (n) => String(n).padStart(2, '0');
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
const WD = ['日', '一', '二', '三', '四', '五', '六'];

function fmtDT(ts) {
  if (!ts) return '';
  const d = new Date(ts), now = new Date();
  const y = d.getFullYear() === now.getFullYear() ? '' : d.getFullYear() + '年';
  return `${y}${d.getMonth() + 1}月${d.getDate()}日(${WD[d.getDay()]}) ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function fmtLeft(ms) {
  if (ms <= 0) return '已到时间';
  const d = Math.floor(ms / 86400000), h = Math.floor(ms % 86400000 / 3600000),
        m = Math.floor(ms % 3600000 / 60000), s = Math.floor(ms % 60000 / 1000);
  if (d > 0) return `${d} 天 ${h} 小时`;
  if (h > 0) return `${h} 小时 ${m} 分`;
  if (m > 0) return `${m} 分 ${s} 秒`;
  return `${s} 秒`;
}
/* 英雄卡专用：永远精确到秒 */
function fmtLeftPrecise(ms) {
  if (ms <= 0) return '已到时间';
  const d = Math.floor(ms / 86400000), h = Math.floor(ms % 86400000 / 3600000),
        m = Math.floor(ms % 3600000 / 60000), s = Math.floor(ms % 60000 / 1000);
  if (d > 0) return `${d} 天 ${pad(h)}:${pad(m)}:${pad(s)}`;
  if (h > 0) return `${h}:${pad(m)}:${pad(s)}`;
  return `${m} 分 ${pad(s)} 秒`;
}
function dtLocalValue(ts) {
  const d = new Date(ts);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/* ---------------- 状态 ---------------- */
const LS_KEY = 'ddlr_v1';
// 壁纸单独存放：base64 图片体积大，混在主状态里会导致每次 save() 都序列化
// 整张图（拖动亮度滑块时每像素一次），是卡顿与耗电的主因之一。
const LS_BG = 'ddlr_bg_v1';
// 上一次已写入 localStorage 的壁纸引用：用于跳过重复写入
// （save() 高频触发，尤其是拖动亮度滑块时每像素一次）
let _lastBgWritten;
function defaultState() { return {
  onboarded: false,
  tasks: [],
  fired: [],
  settings: {
    theme: 'cat', bgImage: null, bgDim: 35,
    dailyTime: '12:00',
    sound: true,
    vibrate: true,
    banner: true,
    remindSilent: false,
    tiered: true, toastCompact: true
  }
}; }
function normalizeState(data) {
  if (!data || typeof data !== 'object' || !Array.isArray(data.tasks)) throw new Error('无效事项列表');
  const next = Object.assign(defaultState(), data);
  const rawSettings = data.settings || {};
  next.settings = Object.assign(defaultState().settings, rawSettings);
  // Migrate the old four fixed presets once. New settings store each output
  // independently, so sound/vibration/banner can be combined freely.
  if (rawSettings.sound === undefined && rawSettings.vibrate === undefined && rawSettings.banner === undefined) {
    const legacy = rawSettings.remindMode || 'full';
    next.settings.sound = legacy !== 'vib' && legacy !== 'silent';
    next.settings.vibrate = legacy === 'full' || legacy === 'vib';
    next.settings.banner = legacy !== 'silent';
    next.settings.remindSilent = legacy === 'silent';
  }
  next.settings.sound = next.settings.sound !== false;
  next.settings.vibrate = next.settings.vibrate !== false;
  next.settings.banner = next.settings.banner !== false;
  next.settings.remindSilent = next.settings.remindSilent === true;
  const ids = new Set();
  next.tasks = data.tasks.map(t => {
    if (!t || typeof t.id !== 'string' || !t.id || ids.has(t.id) ||
        typeof t.name !== 'string' || !Number.isFinite(t.dueAt) || t.dueAt <= 0 ||
        !Number.isFinite(new Date(t.dueAt).getTime())) throw new Error('无效事项');
    ids.add(t.id);
    const task = Object.assign({ detail: '', quadrant: 'q2', custom: [], tiers: {}, daily: false }, t);
    if (!['q1', 'q2', 'q3', 'q4'].includes(task.quadrant) ||
        !Array.isArray(task.custom) || task.custom.some(r => !r || !Number.isFinite(r.v) || r.v < 0 || !['m', 'h', 'd'].includes(r.u)) ||
        (task.isRange && (!Number.isFinite(task.startAt) || task.startAt > task.dueAt))) throw new Error('无效提醒设置');
    if (task.repeat && (!['daily', 'weekly', 'ndays'].includes(task.repeat.type) ||
        (task.repeat.type === 'ndays' && (!Number.isInteger(task.repeat.n) || task.repeat.n < 2)))) throw new Error('无效重复规则');
    return task;
  });
  next.fired = Array.isArray(data.fired) ? data.fired.filter(k => typeof k === 'string') : [];
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(next.settings.dailyTime)) next.settings.dailyTime = '12:00';
  if (data.timetable != null) {
    const tt = data.timetable;
    if (!Array.isArray(tt.courses) || tt.courses.some(c => !c || typeof c.id !== 'string' ||
        typeof c.name !== 'string' || !Number.isInteger(c.day) || c.day < 1 || c.day > 7 ||
        !Number.isInteger(c.start) || !Number.isInteger(c.end) || c.start < 1 || c.end > 12 || c.end < c.start)) throw new Error('无效课表');
  }
  return next;
}
let S = defaultState();
try {
  const raw = localStorage.getItem(LS_KEY);
  if (raw) S = normalizeState(JSON.parse(raw));
} catch (e) { console.warn('状态读取失败', e); }
// 壁纸从独立 key 恢复（save() 时不再混在主状态里）。
// 兼容旧数据：老版本把 bgImage 直接存在主状态里，读出来时若还在就迁移过来。
try {
  const bg = localStorage.getItem(LS_BG);
  if (bg) S.settings.bgImage = bg;
  else if (S.settings.bgImage) { localStorage.setItem(LS_BG, S.settings.bgImage); }
} catch (e) { /* ignore */ }
if (S.settings.toastCompact === undefined) S.settings.toastCompact = true;

// ⚠️ 必须显式暴露到 window：
// `let S` 声明在模块顶层，不会自动挂到 window 上（只有 var 会）。
// 而 nativenotify.js 是独立脚本，里面用 window.S 取状态——
// 早前这里没赋值，导致 init() 里 sync(window.S) 传进去的是 undefined，
// sync 第一行 `if (!this.available() || !S) return;` 直接返回，
// 于是**原生系统闹钟从未被排定过**，所有提醒只能靠 JS 轮询兜底：
//   · 后台被系统节流 → 延迟可达数十分钟到数小时（用户实测 1 小时）
//   · 没有系统级通知 → 不弹顶部横幅
//   · JS 高频轮询 → 耗电
// 这一行是整个提醒链路能工作的前提。
window.S = S;

function save() {
  // 壁纸（base64，可能 200KB~1MB）不参与主状态的 JSON 序列化：
  // 早前 S.settings.bgImage 直接躺在 S 里，每次 save() 都要 stringify 整张
  // base64 并同步写 localStorage。而 save() 被调用的频率很高——
  // 拖动壁纸亮度滑块时是「每个像素一次 input 事件」，tickReminders 里
  // 每条新提醒还会再调一次。这是卡顿与耗电的主要来源之一。
  // 现在壁纸单独存一个 key，主状态只留一个标记。
  try {
    const bg = S.settings.bgImage;
    const payload = JSON.stringify(S, (k, v) => (k === 'bgImage' ? undefined : v));
    localStorage.setItem(LS_KEY, payload);
    // 用「引用 + 脏标记」代替每次读回整个 base64 做字符串比较：
    // 拖动亮度滑块时 save() 每像素触发一次，读回 1MB 字符串再全量比对
    // 依然是可观的开销。只有壁纸对象本身变了才写。
    if (bg !== _lastBgWritten) {
      if (bg) localStorage.setItem(LS_BG, bg);
      else localStorage.removeItem(LS_BG);
      _lastBgWritten = bg;
    }
  } catch (e) { console.warn('存储失败', e); }
  // 保持 window.S 与内部 S 指向同一对象（防止某处整体替换了 S 的引用）
  window.S = S;
  // APK 内同步重排系统级精确提醒
  if (window.NativeNotify) { try { NativeNotify.sync(S).catch(function(){}); } catch (e) {} }
}

/* ---------------- 提示音 / 震动 ---------------- */
let audioCtx = null;
function reminderOutputsEnabled() {
  return !S.settings.remindSilent &&
    (S.settings.sound !== false || S.settings.vibrate !== false || S.settings.banner !== false);
}
function remindSoundEnabled() {
  return reminderOutputsEnabled() && S.settings.sound !== false;
}
function remindVibrateEnabled() {
  return reminderOutputsEnabled() && S.settings.vibrate !== false;
}
function remindBannerEnabled() {
  return reminderOutputsEnabled() && S.settings.banner !== false;
}
function beep() {
  if (!remindSoundEnabled()) return;
  try {
    audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
    if (audioCtx.state === 'suspended') audioCtx.resume();
    const t = audioCtx.currentTime;
    [[880, 0], [1174.7, 0.18]].forEach(([f, off]) => {
      const o = audioCtx.createOscillator(), g = audioCtx.createGain();
      o.type = 'sine'; o.frequency.value = f;
      g.gain.setValueAtTime(0.0001, t + off);
      g.gain.exponentialRampToValueAtTime(0.22, t + off + 0.02);
      g.gain.exponentialRampToValueAtTime(0.0001, t + off + 0.3);
      o.connect(g); g.connect(audioCtx.destination);
      o.start(t + off); o.stop(t + off + 0.32);
    });
  } catch (e) { /* 无声环境忽略 */ }
}
function vibe() {
  if (!remindVibrateEnabled()) return;
  if (navigator.vibrate) navigator.vibrate([180, 90, 180]);
}

/* ---------------- Toast 横幅 ---------------- */
function toast(title, body, kind) {
  const el = document.createElement('div');
  // kind 有两种调用风格：'urgent' 和 'tt-urgent'（nativenotify.js 里混用了）。
  // 早前无条件拼 'tt-' + kind，传入 'tt-urgent' 就变成 'tt-tt-urgent'，
  // CSS 里没有这个类 → 红边框静默丢失，告警样式看起来和普通提醒一样。
  // 这里统一归一化：无论带不带前缀都能正确映射。
  let kindCls = '';
  if (kind) {
    const k = String(kind).replace(/^tt-/, '');
    kindCls = ' tt-' + k;
  }
  el.className = 'toast' + kindCls + (S.settings.toastCompact ? ' compact' : '');
  // 用 textContent 赋值而非拼 innerHTML：任务名里若含 < > & 等字符，
  // 早前会把整段横幅结构破坏（用户反馈的「横幅显示有点小问题」即此类）。
  // 调用方传进来的 title/body 可能已做过 escapeHtml，这里统一按纯文本渲染，
  // 避免二次转义成 &amp;lt; 之类。
  const tEl = document.createElement('div');
  tEl.className = 'tt-title';
  tEl.textContent = stripTags(title);
  el.appendChild(tEl);
  if (body) {
    const bEl = document.createElement('div');
    bEl.className = 'tt-body';
    bEl.textContent = stripTags(body);
    el.appendChild(bEl);
  }
  $('toastWrap').appendChild(el);
  const kill = () => { el.classList.add('gone'); setTimeout(() => el.remove(), 320); };
  el.addEventListener('click', kill);
  setTimeout(kill, 9000);
  if (document.hidden) {
    document.title = '⏰ DDL雷达 · 有提醒！';
    if ('Notification' in window && Notification.permission === 'granted') {
      try {
        new Notification(stripTags(title), { body: stripTags(body), tag: 'ddlr' });
      } catch (e) { /* ignore */ }
    }
  }
}

/** 去掉 HTML 标签并把常见实体还原成字面字符（用于纯文本展示） */
function stripTags(s) {
  return String(s == null ? '' : s)
    .replace(/<[^>]*>/g, '')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&');
}
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) {
    document.title = 'DDL雷达 · 不再错过任何死线';
    // 切回前台立刻补查提醒（后台标签页的定时器会被浏览器节流）
    tickReminders();
    if (window.NativeNotify) { try { NativeNotify.sync(S).catch(function(){}); } catch (e) {} }
  }
});

/* ---------------- 秒级倒计时 ---------------- */
function tickCountdown() {
  const now = Date.now();
  // 英雄卡大数字每秒跳动（永远精确到秒）
  const hero = $('heroCard');
  if (hero && !hero.classList.contains('hidden') && hero.dataset.tid) {
    const t = S.tasks.find((x) => x.id === hero.dataset.tid);
    if (t && !t.done && t.dueAt) {
      const left = t.dueAt - now;
      const c = $('heroCount');
      c.textContent = left > 0 ? fmtLeftPrecise(left) : '已过期';
      c.classList.toggle('urgent', left > 0 && left < 3600000);
      const span = Math.max(t.dueAt - (t.createdAt || t.dueAt - 86400000), 1);
      $('heroBar').firstElementChild.style.width = Math.min(Math.max((1 - left / span) * 100, 2), 100) + '%';
    }
  }
  // 列表卡片上的剩余时间（同样秒级跳动）
  document.querySelectorAll('[data-due]').forEach((el) => {
    const dl = +el.dataset.due - now;
    el.textContent = dl <= 0 ? '已过期'
      : (dl < 86400000 ? `⏳ ${fmtLeft(dl)}后截止` : `⏳ 还剩 ${Math.ceil(dl / 86400000)} 天`);
  });
}

/* ---------------- 主题 & 壁纸 ---------------- */
const THEMES = [
  { id: 'cat',   name: '奶油喵喵', emoji: '🐾', sw: 'linear-gradient(135deg,#fdf6e9,#8fae8b,#f2a65a)' },
  { id: 'soda',  name: '汽水青春', emoji: '🥤', sw: 'linear-gradient(135deg,#ffe3ec,#ffd8c2,#ffdcf1)' },
  { id: 'tech',  name: '深空代码', emoji: '🛰️', sw: 'linear-gradient(135deg,#0b1020,#101736,#4de3c1)' },
  { id: 'cloud', name: '云朵白',   emoji: '☁️', sw: 'linear-gradient(135deg,#f7f8fd,#c9d4f5,#8fa5e8)' }
];
function applyTheme() {
  const t = S.settings.theme;
  document.body.removeAttribute('style'); // 清掉上一主题注入的照片配色变量
  document.body.className = 'theme-' + t + (t === 'tech' ? ' tech-deco' : '');
  if (t === 'custom' && S.settings.customPalette) applyCustomPalette();
  const th = THEMES.find((x) => x.id === t) || (t === 'custom' ? { emoji: '📷' } : THEMES[0]);
  document.querySelector('meta[name=theme-color]').content = t === 'tech' ? '#0b1020' : (t === 'cat' ? '#fdf6e9' : (t === 'cloud' ? '#f7f8fd' : (t === 'custom' ? (S.settings.customPalette ? S.settings.customPalette.bg1 : '#f4f4f8') : '#ffe3ec')));
  renderThemeGrids();
  applyBg();
}
function hsl2hex(h, s, l) {
  h /= 360; s /= 100; l /= 100;
  const f = (n) => { const k = (n + h * 12) % 12; const a = s * Math.min(l, 1 - l);
    return Math.round(255 * (l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1)))); };
  return '#' + [f(0), f(8), f(4)].map((x) => x.toString(16).padStart(2, '0')).join('');
}
function applyCustomPalette() {
  const p = S.settings.customPalette;
  if (!p) return;
  const bs = document.body.style;
  bs.setProperty('--bg-grad', `linear-gradient(165deg, ${p.bg1} 0%, ${p.bg2} 100%)`);
  bs.setProperty('--accent', p.accent);
  bs.setProperty('--accent-2', p.accent2);
  bs.setProperty('--accent-soft', p.accent + '22');
  bs.setProperty('--card', p.card);
  bs.setProperty('--card-strong', p.cardStrong);
  bs.setProperty('--card-border', p.cardBorder);
  bs.setProperty('--text', p.text);
  bs.setProperty('--text-dim', p.textDim);
  bs.setProperty('--tab-bg', p.tabBg);
  bs.setProperty('--shadow', p.shadow);
  bs.setProperty('--chip-on', `linear-gradient(135deg, ${p.accent}, ${p.accent2})`);
}
/* 从照片提取配色：主色相簇 → 强调色，明度 → 深浅模式，主中性色 → 背景渐变 */
function extractPalette(cv) {
  const w = 44, h = 44;
  const c2 = document.createElement('canvas');
  c2.width = w; c2.height = h;
  const cx = c2.getContext('2d');
  cx.drawImage(cv, 0, 0, w, h);
  const d = cx.getImageData(0, 0, w, h).data;
  const hueW = new Array(36).fill(0);
  const neutral = {};
  let lum = 0, satSum = 0, px = 0;
  for (let i = 0; i < d.length; i += 4) {
    const r = d[i] / 255, g = d[i + 1] / 255, b = d[i + 2] / 255;
    px++;
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
    const l = (mx + mn) / 2;
    lum += l;
    let s = 0;
    if (mx !== mn) s = (mx - mn) / (1 - Math.abs(2 * l - 1) || 1e-6);
    satSum += s;
    let hh = 0;
    if (mx !== mn) {
      const df = mx - mn;
      if (mx === r) hh = ((g - b) / df + 6) % 6;
      else if (mx === g) hh = (b - r) / df + 2;
      else hh = (r - g) / df + 4;
      hh *= 60;
    }
    if (s > 0.22 && l > 0.14 && l < 0.92) hueW[Math.floor(hh / 10) % 36] += s;
    else {
      const key = [Math.round(r * 4), Math.round(g * 4), Math.round(b * 4)].join(',');
      neutral[key] = neutral[key] || { n: 0, r: d[i], g: d[i + 1], b: d[i + 2] };
      neutral[key].n++;
    }
  }
  const avgLum = lum / px, avgSat = satSum / px;
  let h1 = -1, h2 = -1;
  {
    const order = hueW.map((v, i) => [v, i]).sort((a, b) => b[0] - a[0]);
    h1 = order[0][1] * 10 + 5;
    for (const [v, i] of order.slice(1)) { // 第二主色：与第一主色相距至少 40°
      const diff = Math.min(Math.abs(i * 10 + 5 - h1), 360 - Math.abs(i * 10 + 5 - h1));
      if (v > 0 && diff > 40) { h2 = i * 10 + 5; break; }
    }
    if (h2 < 0) h2 = (h1 + 145) % 360;
  }
  if (avgSat < 0.09) { h1 = 28; h2 = 150; } // 照片几乎无彩色时给默认点缀色
  const dark = avgLum < 0.42;
  // 主中性色 → 背景
  let bg1, bg2, card, cardStrong, tabBg, text, textDim;
  const neutralColors = Object.values(neutral).sort((a, b) => b.n - a.n);
  const nb = neutralColors[0] || { r: 245, g: 243, b: 238 };
  const mix = (c, t2, k) => Math.round(c + (t2 - c) * k);
  if (dark) {
    bg1 = `rgb(${mix(nb.r, 10, 0.55)},${mix(nb.g, 12, 0.55)},${mix(nb.b, 18, 0.55)})`;
    bg2 = `rgb(${Math.round(nb.r * 0.35)},${Math.round(nb.g * 0.35)},${Math.round(nb.b * 0.4)})`;
    card = 'rgba(28,30,40,.78)'; cardStrong = 'rgba(34,36,48,.96)';
    tabBg = 'rgba(22,24,34,.9)'; text = '#eef0f6'; textDim = '#9aa0b5';
  } else {
    bg1 = `rgb(${mix(nb.r, 255, 0.55)},${mix(nb.g, 252, 0.55)},${mix(nb.b, 246, 0.55)})`;
    bg2 = `rgb(${Math.round(nb.r * 0.82 + 40)},${Math.round(nb.g * 0.82 + 40)},${Math.round(nb.b * 0.82 + 42)})`;
    card = 'rgba(255,255,255,.86)'; cardStrong = 'rgba(255,255,255,.97)';
    tabBg = 'rgba(255,255,255,.9)'; text = '#33334a'; textDim = '#8b8ba3';
  }
  const accent = hsl2hex(h1, dark ? 58 : 62, dark ? 58 : 48);
  const accent2 = hsl2hex(h2, dark ? 60 : 64, dark ? 62 : 54);
  return {
    bg1, bg2, accent, accent2, dark,
    card, cardStrong, tabBg, text,
    textDim, cardBorder: dark ? 'rgba(255,255,255,.12)' : 'rgba(90,90,120,.16)',
    shadow: dark ? '0 8px 24px rgba(0,0,0,.5)' : '0 8px 24px rgba(60,60,90,.18)'
  };
}
function applyBg() {
  const bg = $('bgLayer'), dim = $('bgDim');
  if (S.settings.bgImage) {
    bg.style.backgroundImage = `url(${S.settings.bgImage})`;
  } else {
    bg.style.backgroundImage = '';
  }
  // 遮罩只用于自定义壁纸，纯渐变背景不需要压暗
  document.documentElement.style.setProperty('--dim', S.settings.bgImage ? S.settings.bgDim / 100 : 0);
  if (dim) dim.style.opacity = '';
}
function renderThemeGrids() {
  const list = S.settings.customPalette
    ? THEMES.concat([{ id: 'custom', name: '照片配色', emoji: '📷', sw: `linear-gradient(135deg, ${S.settings.customPalette.bg1}, ${S.settings.customPalette.accent}, ${S.settings.customPalette.accent2})` }])
    : THEMES;
  const html = list.map((t) =>
    `<button class="theme-card ${S.settings.theme === t.id ? 'on' : ''}" data-t="${t.id}">
      <div class="theme-swatch" style="background:${t.sw}">${t.emoji}</div>
      <div class="theme-name">${t.name}</div></button>`).join('');
  $('themeGrid').innerHTML = html;
  const ob = $('onbThemes');
  if (ob) ob.innerHTML = html;
  document.querySelectorAll('.theme-card').forEach((b) => b.addEventListener('click', () => {
    S.settings.theme = b.dataset.t; save(); applyTheme(); beep();
  }));
}

/* ---------------- 顶栏日期 ---------------- */
/**
 * 距离下一个节日还有几天。
 *
 * 结果一天才变一次，但早前 renderTop 每 30 秒就调用一次，每次都做
 * 90 天 × (Solar.fromDate + Lunar.fromDate) 的农历换算 —— 这是纯浪费的
 * CPU 与耗电。现在按「当天日期」缓存，一天只算一次。
 */
let _festivalCache = { day: '', text: '' };
function nextFestivalLine() {
  const todayKey = ymdOf(Date.now());
  if (_festivalCache.day === todayKey) return _festivalCache.text;
  let result = '';
  try {
    const now = new Date(); now.setHours(0, 0, 0, 0);
    for (let i = 0; i <= 90; i++) {
      const d = new Date(now.getTime() + i * 86400000);
      let name = '';
      try { const sf = Solar.fromDate(d).getFestivals(); if (sf.length) name = sf[0]; } catch (e) { /* */ }
      if (!name) { const l = Lunar.fromDate(d); const lf = l.getFestivals(); if (lf.length) name = lf[0]; }
      if (name) {
        const gap = i === 0 ? '就是今天' : `还有 ${i} 天`;
        result = `距离 ${name} ${gap}`;
        break;
      }
    }
  } catch (e) { /* ignore */ }
  _festivalCache = { day: todayKey, text: result };
  return result;
}
function renderTop() {
  const d = new Date();
  $('topDate').textContent = `${d.getMonth() + 1}月${d.getDate()}日 星期${WD[d.getDay()]}`;
  let sub = '';
  try {
    const l = Lunar.fromDate(d);
    sub = `农历${l.getMonthInChinese()}月${l.getDayInChinese()}`;
    const nf = nextFestivalLine();
    if (nf) sub += ' · ' + nf;
  } catch (e) { /* ignore */ }
  $('topLunar').textContent = sub;
}

/* ---------------- 视图切换 ---------------- */
let curView = 'home';
function switchView(v) {
  curView = v;
  document.querySelectorAll('.view').forEach((el) => el.classList.add('hidden'));
  $('view-' + v).classList.remove('hidden');
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.view === v));
  if (v === 'calendar' && window.Cal) Cal.render();
  if (v === 'home') renderHome();
  if (v === 'matrix') renderMatrix();
}

/* ---------------- 任务渲染 ---------------- */
const QUAD_NAME = { q1: '重要且紧急', q2: '重要不紧急', q3: '紧急不重要', q4: '不重要不紧急' };

/* 打卡类任务完成后，按重复规则自动生成下一次 */
function spawnNext(t) {
  if (!t.repeat || !t.dueAt) return null;
  if (t.nextRepeatId) return S.tasks.find(x => x.id === t.nextRepeatId) || null;
  const stepDays = t.repeat.type === 'daily' ? 1 : t.repeat.type === 'weekly' ? 7 : Math.max(2, t.repeat.n || 2);
  let due = t.dueAt + stepDays * 86400000;
  while (due <= Date.now()) due += stepDays * 86400000;
  const nt = Object.assign({}, t, {
    id: uid(), done: false, doneAt: null,
    createdAt: Date.now(), updatedAt: Date.now(),
    startAt: t.startAt ? t.startAt + (due - t.dueAt) : null,
    dueAt: due,
    repeat: { type: t.repeat.type, n: t.repeat.n }
  });
  delete nt.nextRepeatId;
  t.nextRepeatId = nt.id;
  S.tasks.push(nt);
  return nt;
}
const REPEAT_TXT = { daily: '每天', weekly: '每周', ndays: '' };

function taskCard(t) {
  const el = document.createElement('div');
  el.className = 'card task' + (t.done ? ' done' : '');
  const left = t.dueAt - Date.now();
  if (!t.done && left < 0) el.classList.add('overdue');

  const leftTxt = t.done ? '' :
    (left < 0 ? '已过期' :
      (left < 86400000 ? `⏳ ${fmtLeft(left)}后截止` : `⏳ 还剩 ${Math.ceil(left / 86400000)} 天`));
  const timeTxt = t.isRange && t.startAt ? `${fmtDT(t.startAt)} → ${fmtDT(t.dueAt)}` : fmtDT(t.dueAt);
  const hasDetail = !!(t.detail && t.detail.trim());

  el.innerHTML = `
    <div class="t-top">
      <span class="t-check ${t.done ? 'on' : ''}">✓</span>
      <span class="t-name">${escapeHtml(t.name)}</span>
      <span class="t-badge b-${t.quadrant}">${QUAD_NAME[t.quadrant]}</span>
      ${t.repeat ? `<span class="t-badge b-rep">🔁 ${t.repeat.type === 'ndays' ? '每隔' + t.repeat.n + '天' : REPEAT_TXT[t.repeat.type]}</span>` : ''}
    </div>
    <div class="t-meta">
      <span>🕐 ${timeTxt}</span>
      ${leftTxt ? `<span data-due="${t.dueAt}">${leftTxt}</span>` : ''}
      ${hasDetail ? `<span class="t-toggle">细节 <span class="t-arrow">▶</span></span>` : ''}
    </div>
    ${hasDetail ? `<div class="t-detail">${escapeHtml(t.detail)}</div>` : ''}`;

  el.querySelector('.t-check').addEventListener('click', (e) => {
    e.stopPropagation();
    t.done = !t.done; t.doneAt = t.done ? Date.now() : null;
    t.updatedAt = Date.now();
    if (t.done) {
      const nt = spawnNext(t);
      toast('🎉 干得漂亮！', nt ? `🔁 下一次已自动排到 ${fmtDT(nt.dueAt)}` : `『${escapeHtml(t.name)}』完成`);
      beep();
    }
    save();
    renderAll();
  });
  const toggle = el.querySelector('.t-toggle');
  if (toggle) toggle.addEventListener('click', (e) => {
    e.stopPropagation();
    el.querySelector('.t-detail').classList.toggle('open');
    el.querySelector('.t-arrow').classList.toggle('open');
  });
  el.addEventListener('click', () => openEditor(t.id));
  return el;
}
function escapeHtml(s) {
  return String(s || '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function renderHome() {
  const now = Date.now();
  const todayEnd = new Date(); todayEnd.setHours(23, 59, 59, 999);
  const week = now + 7 * 86400000;

  const open = S.tasks.filter((t) => !t.done && t.dueAt);
  const done = S.tasks.filter((t) => t.done).sort((a, b) => (b.doneAt || 0) - (a.doneAt || 0));

  // 英雄卡：优先显示最近的「未来」DDL；全部过期时才显示最近的过期项
  const future = open.filter((t) => t.dueAt > now).sort((a, b) => a.dueAt - b.dueAt);
  const next = future[0] || open.slice().sort((a, b) => a.dueAt - b.dueAt)[0];
  if (next) {
    $('heroCard').classList.remove('hidden'); $('heroEmpty').classList.add('hidden');
    $('heroCard').dataset.tid = next.id;
    $('heroName').textContent = next.name;
    const left = next.dueAt - now;
    $('heroCount').textContent = left > 0 ? fmtLeft(left) : '已过期';
    $('heroCount').classList.toggle('urgent', left > 0 && left < 3600000);
    $('heroMeta').textContent =
      `${next.isRange && next.startAt ? fmtDT(next.startAt) + ' 开始 · ' : ''}截止 ${fmtDT(next.dueAt)} · ${QUAD_NAME[next.quadrant]}`;
    const span = Math.max(next.dueAt - (next.createdAt || next.dueAt - 86400000), 1);
    $('heroBar').firstElementChild.style.width = Math.min(Math.max((1 - left / span) * 100, 2), 100) + '%';
  } else {
    $('heroCard').classList.add('hidden'); $('heroEmpty').classList.remove('hidden');
  }

  // 分组列表
  const today = [], soon = [], later = [];
  for (const t of open) {
    if (t.dueAt <= todayEnd.getTime()) today.push(t);
    else if (t.dueAt <= week) soon.push(t);
    else later.push(t);
  }
  today.sort((a, b) => a.dueAt - b.dueAt);
  soon.sort((a, b) => a.dueAt - b.dueAt);
  later.sort((a, b) => a.dueAt - b.dueAt);

  fillList('todayList', today, '今天没有到期的事，心安 😌');
  fillList('soonList', soon, '未来一周没有安排');
  fillList('laterList', later, '更远处一片祥和');
  fillList('doneList', done.slice(0, 8), '');
  $('todaySection').classList.toggle('hidden', today.length === 0);
  $('soonSection').classList.toggle('hidden', soon.length === 0);
  $('laterSection').classList.toggle('hidden', later.length === 0);
  $('doneSection').classList.toggle('hidden', done.length === 0);
  $('emptyHome').classList.toggle('hidden', S.tasks.length > 0);

  renderBored();
}

function fillList(id, arr, emptyText) {
  const box = $(id);
  box.innerHTML = '';
  if (!arr.length) {
    if (emptyText) box.innerHTML = `<div class="empty-tip">${emptyText}</div>`;
    return;
  }
  arr.forEach((t) => box.appendChild(taskCard(t)));
}

/* ---------------- 闲得发慌 ---------------- */
function renderBored() {
  const now = Date.now();
  const urgentSoon = S.tasks.some((t) => !t.done && t.dueAt && t.dueAt - now < 86400000);
  const q2 = S.tasks.filter((t) => !t.done && t.quadrant === 'q2');
  const card = $('boredCard');
  card.classList.remove('hidden');
  const sug = $('boredSuggest');
  sug.classList.add('hidden');
  $('boredText').textContent = urgentSoon
    ? '火烧眉毛的事处理完，也可以歇口气'
    : '当下没有火烧眉毛的事，正是推进「重要不紧急」的好时机';
}
function boredPick() {
  const q2 = S.tasks.filter((t) => !t.done && t.quadrant === 'q2');
  const sug = $('boredSuggest');
  if (!q2.length) {
    sug.classList.remove('hidden');
    sug.innerHTML = `<div class="bs-name">四象限里还没有「重要不紧急」的事</div>
      <div class="bs-meta">想想看：健身、读书、学个新技能、陪家人……把它们丢进 🟡 象限吧</div>`;
    return;
  }
  const pick = q2[Math.floor(Math.random() * q2.length)];
  sug.classList.remove('hidden');
  sug.innerHTML = `<div class="bs-name">🎯 ${escapeHtml(pick.name)}</div>
    <div class="bs-meta">重要不紧急 · 点下面的按钮去推进它</div>
    <button class="btn-ghost-sm" id="boredGo" style="margin-top:8px">这就去做 →</button>`;
  $('boredGo').addEventListener('click', () => openEditor(pick.id));
}

/* ---------------- 四象限 ---------------- */
function renderMatrix() {
  for (const q of ['q1', 'q2', 'q3', 'q4']) {
    const box = $('qlist-' + q);
    box.innerHTML = '';
    const arr = S.tasks.filter((t) => t.quadrant === q && !t.done)
      .sort((a, b) => (a.dueAt || 9e15) - (b.dueAt || 9e15));
    if (!arr.length) {
      box.innerHTML = `<div class="mx-empty">空空如也<br>Nothing here ✨</div>`;
      continue;
    }
    arr.forEach((t) => {
      const d = document.createElement('div');
      d.className = 'mx-item' + (t.done ? ' done' : '');
      d.innerHTML = `${escapeHtml(t.name)}
        <small>${t.dueAt ? fmtDT(t.dueAt).replace(/\(\S+?\)/, '') : '无截止时间'}</small>`;
      d.addEventListener('click', () => openEditor(t.id));
      box.appendChild(d);
    });
  }
}

/* ---------------- 编辑器 ---------------- */
let editingId = null;
let curQuad = 'q1', curType = 'point', curTiers = { 120: true, 60: true, 30: true, 5: true };
let curCustom = [];
let curRepeat = 'none';

function openEditor(id) {
  editingId = id || null;
  const t = id ? S.tasks.find((x) => x.id === id) : null;
  $('editorTitle').textContent = t ? '✏️ 编辑事项' : '✨ 新建事项';
  $('fName').value = t ? t.name : '';
  $('fDetail').value = t ? (t.detail || '') : '';
  const defaultDue = new Date(Date.now() + 2 * 3600000);
  $('fDue').value = t && t.dueAt ? dtLocalValue(t.dueAt) : dtLocalValue(defaultDue);
  $('fStart').value = t && t.startAt ? dtLocalValue(t.startAt) : dtLocalValue(Date.now());
  curQuad = t ? t.quadrant : 'q1';
  curType = t && t.isRange ? 'range' : 'point';
  curTiers = t && t.tiers ? Object.assign({ 120: true, 60: true, 30: true, 5: true }, t.tiers) : { 120: true, 60: true, 30: true, 5: true };
  curCustom = t && t.custom ? JSON.parse(JSON.stringify(t.custom)) : [];
  curRepeat = t && t.repeat ? t.repeat.type : 'none';
  $('fRepN').value = t && t.repeat && t.repeat.type === 'ndays' && t.repeat.n ? t.repeat.n : 3;
  $('fDaily').checked = t ? t.daily !== false : true;
  $('fDailyDef').textContent = S.settings.dailyTime;
  $('fDelete').classList.toggle('hidden', !t);
  syncEditorUI();
  $('editorWrap').classList.remove('hidden');
  if (!t) setTimeout(() => $('fName').focus(), 350);
}
function syncEditorUI() {
  document.querySelectorAll('#fQuadrant button').forEach((b) => b.classList.toggle('on', b.dataset.q === curQuad));
  document.querySelectorAll('#fTimeType button').forEach((b) => b.classList.toggle('on', b.dataset.t === curType));
  $('fStartRow').classList.toggle('hidden', curType !== 'range');
  $('fDueLabel').innerHTML = curType === 'range' ? '结束时间 <small>(提醒按此时间倒推)</small>' : '截止时间';
  document.querySelectorAll('#fRepeat button').forEach((b) => b.classList.toggle('on', b.dataset.r === curRepeat));
  $('fRepNRow').classList.toggle('hidden', curRepeat !== 'ndays');
  document.querySelectorAll('#fTier button').forEach((b) => b.classList.toggle('on', !!curTiers[b.dataset.min]));
  const rows = curCustom.map((r, i) => `
    <div class="crem-row">
      <input type="number" min="1" max="999" value="${r.v}" data-i="${i}" class="f-input crv">
      <select data-i="${i}" class="cru">
        <option value="m" ${r.u === 'm' ? 'selected' : ''}>分钟前</option>
        <option value="h" ${r.u === 'h' ? 'selected' : ''}>小时前</option>
        <option value="d" ${r.u === 'd' ? 'selected' : ''}>天前</option>
      </select>
      <button data-del="${i}">✕</button>
    </div>`).join('');
  $('fCustomRem').innerHTML = rows;
  $('fCustomRem').querySelectorAll('.crv').forEach((inp) => inp.addEventListener('change', () => {
    curCustom[+inp.dataset.i].v = Math.max(1, +inp.value || 1);
  }));
  $('fCustomRem').querySelectorAll('.cru').forEach((sel) => sel.addEventListener('change', () => {
    curCustom[+sel.dataset.i].u = sel.value;
  }));
  $('fCustomRem').querySelectorAll('[data-del]').forEach((b) => b.addEventListener('click', () => {
    curCustom.splice(+b.dataset.del, 1); syncEditorUI();
  }));
}

function saveEditor() {
  const name = $('fName').value.trim();
  const dueV = $('fDue').value;
  if (!name) { toast('⚠️ 还没写名字', '给这件事起个名字吧'); $('fName').focus(); return; }
  if (!dueV) { toast('⚠️ 还没定截止时间', '什么时候是死线？'); return; }
  const dueAt = new Date(dueV).getTime();
  const isRange = curType === 'range';
  const startAt = isRange ? new Date($('fStart').value || dueV).getTime() : null;
  if (!Number.isFinite(dueAt) || (isRange && (!Number.isFinite(startAt) || startAt > dueAt))) {
    toast('⚠️ 时间无效', '请检查日期，并确保开始时间不晚于截止时间'); return;
  }

  const data = {
    name, detail: $('fDetail').value.trim(), quadrant: curQuad,
    isRange, startAt, dueAt,
    repeat: curRepeat === 'none' ? null : { type: curRepeat, n: curRepeat === 'ndays' ? Math.max(2, +$('fRepN').value || 3) : 0 },
    tiers: curTiers, custom: curCustom, daily: $('fDaily').checked,
    updatedAt: Date.now()
  };
  if (editingId) {
    const target = S.tasks.find((x) => x.id === editingId);
    if (!target) { toast('⚠️ 事项已不存在', '请关闭编辑器后重新选择'); return; }
    // 只有「截止时间真的变了」才清掉该任务的 fired 记录（提醒需要重排）。
    // 早前是无条件 filter：只改标题/细节也会清空，于是 15 分钟补弹窗口内
    // 已经弹过的提醒会被再弹一遍 —— 用户感知就是「重复提醒 / 乱弹」。
    const dueChanged = !target || target.dueAt !== dueAt;
    Object.assign(target, data);
    if (dueChanged) {
      S.fired = S.fired.filter((k) => !k.startsWith(editingId + '|'));
      invalidateFiredCache(); // 过滤后长度可能不变，必须显式失效 Set 缓存
    }
  } else {
    S.tasks.push(Object.assign({ id: uid(), done: false, createdAt: Date.now() }, data));
    askNotify();
  }
  save();
  $('editorWrap').classList.add('hidden');
  toast('📌 已上雷达', `『${escapeHtml(name)}』${editingId ? '已更新' : '开始盯梢'}`);
  renderAll();
}

/* ---------------- 日历某日 ---------------- */
function openDay(ymdStr) {
  const [y, m, d] = ymdStr.split('-').map(Number);
  const date = new Date(y, m - 1, d);
  let lunarTxt = '', tag = '';
  try {
    const l = Lunar.fromDate(date);
    lunarTxt = `农历${l.getMonthInChinese()}月${l.getDayInChinese()}`;
    const lf = l.getFestivals(); if (lf.length) lunarTxt += ' · ' + lf.join('、');
    const jq = l.getJieQi(); if (jq) lunarTxt += ' · ' + jq;
    const sf = Solar.fromDate(date).getFestivals(); if (sf.length) lunarTxt += ' · ' + sf.join('、');
  } catch (e) { /* ignore */ }
  const legal = window.Hol ? Hol.day(ymdStr) : null;
  if (legal) tag = legal.off
    ? `<span class="di-tag di-rest">法定节假日 · ${legal.name} · 放假</span>`
    : `<span class="di-tag di-work">调休上班 · ${legal.name}</span>`;

  $('dayInfo').innerHTML = `
    <div class="di-big">${m}月${d}日</div>
    <div class="di-lunar">${lunarTxt}</div>${tag}`;

  const list = $('dayTaskList');
  list.innerHTML = '';
  const arr = S.tasks.filter((t) => t.dueAt && !t.done &&
    new Date(t.dueAt).toDateString() === date.toDateString());
  if (!arr.length) list.innerHTML = '<div class="empty-tip">这天没有安排 DDL</div>';
  else arr.forEach((t) => list.appendChild(taskCard(t)));

  $('dayAddBtn').onclick = () => {
    $('dayWrap').classList.add('hidden');
    openEditor(null);
    $('fDue').value = `${ymdStr}T12:00`;
  };
  $('dayWrap').classList.remove('hidden');
}
window.AppBridge = { openDay };

/* ---------------- 提醒引擎 ---------------- */
const TIER_MIN = [120, 60, 30, 5];

// 计算某任务所有「应触发时刻」: [{key, at, title, body, kind}]
function planReminders(t) {
  const out = [];
  if (!t || t.done || !Number.isFinite(t.dueAt)) return out;
  const due = t.dueAt;
  const cutoff = Date.now() - 15 * 60000;

  // 当天分段提醒：设置页总开关 × 单事项细分开关
  if (t.tiers && S.settings.tiered) {
    const nowTs = Date.now();
    for (const min of TIER_MIN) {
      if (t.tiers[min] === false) continue;
      const at = due - min * 60000;
      // 已过期的分段提醒不再生成：早前这里没有这个判断，
      // 截止时间一过，due-120min 等全是过去时刻，会生成一堆过期条目，
      // 白白占用系统提醒队列、挤掉真正有效的提醒。
      if (at < cutoff) continue;
      out.push({
        key: `${t.id}|t${min}`, at,
        title: min > 60 ? `⏰ 还有 ${min / 60} 小时` : `⏰ 还有 ${min} 分钟`,
        body: `『${escapeHtml(t.name)}』${fmtDT(due)} 截止${min <= 5 ? '，冲！' : ''}`,
        kind: 'urgent'
      });
    }
  }
  // 自定义提醒
  for (const r of (t.custom || [])) {
    const mult = r.u === 'd' ? 1440 : r.u === 'h' ? 60 : 1;
    const at = due - r.v * mult * 60000;
    if (at < cutoff) continue;
    const unit = r.u === 'd' ? '天' : r.u === 'h' ? '小时' : '分钟';
    out.push({
      key: `${t.id}|c${r.v}${r.u}`, at,
      title: `⏰ 提前 ${r.v} ${unit}`,
      body: `『${escapeHtml(t.name)}』${fmtDT(due)} 截止`,
      kind: 'daily'
    });
  }
  // 远期每日提醒（截止日在未来且不是今天）
  // 注意：这里最多往后排 30 天（原为 400 天）。
  // planReminders 在 tickReminders(2s) 和 sync 里都会被调用，400 天意味着
  // 每个任务每次调用要生成 400 个 Date 对象；几个任务就是上千个，
  // 每秒多次 → 明显的 CPU 占用与耗电。30 天足够覆盖「远期提醒」的实际用途，
  // 且更远的日期在临近时会重新计算，不会漏。
  if (t.daily !== false) {
    const dueDay = new Date(due); dueDay.setHours(0, 0, 0, 0);
    const todayDay = new Date(); todayDay.setHours(0, 0, 0, 0);
    if (dueDay > todayDay) {
      const [hh, mm] = (t.dailyTime || S.settings.dailyTime || '12:00').split(':').map(Number);
      const MAX_DAYS = 30;
      for (let i = 0; i < MAX_DAYS; i++) {
        const day = new Date(todayDay.getTime() + i * 86400000);
        if (day > dueDay) break;
        if (day.getTime() === dueDay.getTime()) break; // 当天由分段提醒负责
        const at = new Date(day.getFullYear(), day.getMonth(), day.getDate(), hh, mm, 0).getTime();
        if (at < cutoff) continue;
        const days = Math.ceil((dueDay - day) / 86400000);
        out.push({
          key: `${t.id}|d${ymdOf(day.getTime())}`, at,
          title: `📅 每日提醒 · 还有 ${days} 天`,
          body: `『${escapeHtml(t.name)}』将于 ${fmtDT(due)} 截止`,
          kind: 'daily'
        });
      }
    }
  }
  // 到点提醒
  // 保留「刚过期 15 分钟内」的条目：tickReminders 靠它做前台补弹
  // （原生没送达时兜底）。更早的不再生成，避免过期条目长期占用配额。
  if (due > Date.now() - 15 * 60000) {
    out.push({
      key: `${t.id}|due`, at: due,
      title: '🚨 时间到！',
      body: `『${escapeHtml(t.name)}』就是现在 · ${QUAD_NAME[t.quadrant]}`,
      kind: 'urgent'
    });
  }
  return out;
}

function ymdOf(ts) {
  const d = new Date(ts);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/**
 * 让 tickReminders 的 Set 缓存失效。
 * 凡是「整体替换 S.fired」的地方都必须调用——因为替换后数组长度可能恰好
 * 与替换前相同，而 tickReminders 的缓存判据包含长度，会误判为「没变」。
 */
function invalidateFiredCache() {
  tickReminders._set = null;
  tickReminders._setSize = -1;
  tickReminders._arr = null;
}

function tickReminders() {
  // "静默（关闭提醒）" or all three outputs switched off means reminders are
  // intentionally disabled; do not consume them or fall back to a toast.
  if (!reminderOutputsEnabled()) return;
  const now = Date.now();
  // ⚠️ native 必须是「原生闹钟确实排上了」，而不只是「原生插件可用」。
  // 早前只判断 available()，于是 apply() 处于失败重试窗口（_sig 被置 null，
  // 等着下一次 sync）时，这里仍认为「原生会负责投递」，把到点提醒标记为
  // 已处理却既不弹系统通知也不弹应用内横幅 —— 用户什么都收不到。
  // 而重试只在 App 前台每 60s 跑一次，一退后台就退回 JS 轮询被系统节流，
  // 延迟重新变成小时级。所以必须用「最近一次 apply 是否成功」作为判据。
  const pluginReady = !!(window.NativeNotify && window.NativeNotify.available &&
    window.NativeNotify.available());
  const native = pluginReady && window.NativeNotify.isSynced();
  // S.fired 用 Set 索引：早前是数组 + includes()，O(n) 线性查找，
  // 且每轮都要扫全部任务 × 全部提醒点，任务一多就明显卡顿耗电。
  // ⚠️ 缓存失效判据必须用「引用 + 长度」而不是只看长度：
  // 编辑任务时会执行 S.fired = S.fired.filter(...)（L658），过滤后长度
  // 有可能与之前相同（比如删掉 1 个又补上 1 个），只看长度会漏掉内容变化，
  // 导致已移除的 key 仍留在缓存里 → 该提醒永远不再弹。
  // 因此每次外部整体替换 S.fired 时都会重置 tickReminders._set（见 invalidateFiredCache）。
  if (!tickReminders._set || tickReminders._setSize !== S.fired.length
      || tickReminders._arr !== S.fired) {
    tickReminders._set = new Set(S.fired);
    tickReminders._setSize = S.fired.length;
    tickReminders._arr = S.fired;
  }
  const fired = tickReminders._set;
  let dirty = false;
  for (const t of S.tasks) {
    // 逐任务 try/catch：planReminders 只要有一条任务的数据异常（比如
    // 时间戳为 null、字段缺失），异常会向上冒泡打断整个 tickReminders，
    // 进而打断 init()，导致后面所有 setInterval 一个都注册不上——
    // 这是「提醒引擎整体静默」的放大器。隔离到单任务，坏一条不影响其他。
    let list;
    try { list = planReminders(t); } catch (e) { console.warn('planReminders 失败', t && t.id, e); continue; }
    for (const r of list) {
      if (fired.has(r.key)) continue;
      if (r.at > now) continue;
      // 超过 15 分钟：只标记不再打扰（避免堆积）
      if (now - r.at > 15 * 60000) { fired.add(r.key); S.fired.push(r.key); dirty = true; continue; }
      fired.add(r.key);
      S.fired.push(r.key);
      dirty = true;
      // 原生负责准点投递：留 5s 宽限，超过才前台补弹。
      const late = now - r.at > 5000;
      if (native && !late) continue;
      if (remindBannerEnabled()) toast(r.title + (late ? ' · 补弹' : ''), r.body, r.kind);
      beep(); vibe();
    }
  }
  // ── 课表提醒的前台补弹 ──
  // 课表提醒走的是 sync() → 原生闹钟，但 tickReminders 早前完全不看课表。
  // 一旦原生没送达（Doze、权限被撤、闹钟被 ROM 拦截），课表提醒就静默丢失，
  // 而任务提醒至少还有这里兜底 —— 这是「课程有时提醒有时不提醒」的成因。
  // 现在把课表也纳入同一套 fired 去重与补弹逻辑。
  try {
    if (window.Timetable && window.Timetable.planFor) {
      const todays = window.Timetable.planFor(now);
      for (const r of todays) {
        if (fired.has(r.key)) continue;
        if (r.at > now) continue;
        if (now - r.at > 15 * 60000) { fired.add(r.key); S.fired.push(r.key); dirty = true; continue; }
        fired.add(r.key);
        S.fired.push(r.key);
        dirty = true;
        const late = now - r.at > 5000;
        if (native && !late) continue;
        if (remindBannerEnabled()) toast(r.title + (late ? ' · 补弹' : ''), r.body, r.kind);
        beep(); vibe();
      }
    }
  } catch (e) { console.warn('课表补弹失败', e); }

  // 只在真正有变化时写盘：早前无论有没有新提醒每 2s 都 save() 一次，
  // 这是 localStorage 频繁写入、进而拖慢主线程的元凶之一。
  if (dirty) {
    if (S.fired.length > 600) {
      S.fired = S.fired.slice(-400);
      tickReminders._set = new Set(S.fired);
      tickReminders._setSize = S.fired.length;
    }
    tickReminders._setSize = S.fired.length;
    save();
  }
}

/* 每日闲逛提醒（自动·一天一次） */
let lastInteract = Date.now();
['click', 'touchstart', 'keydown'].forEach((ev) =>
  document.addEventListener(ev, () => { lastInteract = Date.now(); }, { passive: true }));
function idleNudge() {
  const today = ymdOf(Date.now());
  if (S.lastNudge === today) return;
  const now = Date.now();
  const busy = S.tasks.some((t) => !t.done && t.dueAt && t.dueAt - now < 86400000);
  if (busy) return;
  if (Date.now() - lastInteract < 5 * 60000) return;
  const q2 = S.tasks.filter((t) => !t.done && t.quadrant === 'q2');
  if (!q2.length) return;
  const pick = q2[Math.floor(Math.random() * q2.length)];
  toast('🥱 闲得发慌？', `也许可以推进一下『${escapeHtml(pick.name)}』——它重要，但一直不紧急`, 'daily');
  S.lastNudge = today; save();
}

/* ---------------- 通知权限 ---------------- */
function askNotify() {
  if (!('Notification' in window)) return;
  if (Notification.permission === 'default') Notification.requestPermission();
}
function refreshNotifyBtn() {
  const b = $('notifyBtn');
  if (!('Notification' in window)) { b.textContent = '此浏览器不支持系统通知'; b.disabled = true; return; }
  const p = Notification.permission;
  b.textContent = p === 'granted' ? '✅ 系统通知已开启' :
    p === 'denied' ? '🚫 通知被拒绝（请到系统设置里放开）' : '🔔 申请系统通知权限（状态：未开启）';
}

/* ---------------- 设置页 ---------------- */
/* ---------------- 版本更新 ---------------- */
const APP_VERSION = '2.2.10';
const REPO = 'lhw366/ddl-radar-app';
let pendingUpdateMirrors = []; // 下载线路：jsdelivr 各边缘 + 国内反代 + 代理，github 直连兜底
function fetchTimeout(ms) {
  try { return AbortSignal.timeout ? AbortSignal.timeout(ms) : undefined; } catch (e) { return undefined; }
}
function openExternal(url) {
  try {
    if (window.Capacitor && Capacitor.Plugins.App) { Capacitor.Plugins.App.openUrl({ url }); return; }
  } catch (e) { /* 网页端走 window.open */ }
  window.open(url, '_blank');
}
async function fetchRemoteVersion() {
  /* 5 路并发查版本、取最大 code：jsdelivr 各边缘的 @main 缓存可能滞后几小时
     （实测 fastly 曾落后 4 个构建导致误判「已是最新」），单一路源不可信 */
  const bases = ['https://gcore.jsdelivr.net/gh/', 'https://testingcf.jsdelivr.net/gh/',
    'https://cdn.jsdelivr.net/gh/', 'https://fastly.jsdelivr.net/gh/', 'https://cdn.jsdmirror.com/gh/'];
  const jobs = bases.map(async (base) => {
    const r = await fetch(`${base}${REPO}@main/version.json`, { cache: 'no-store', signal: fetchTimeout(8000) });
    if (!r.ok) throw new Error('http ' + r.status);
    const j = await r.json();
    return Number.isSafeInteger(Number(j.code)) && Number(j.code) > 0 ? { ...j, code: Number(j.code) } : null;
  });
  jobs.push((async () => {
    const r = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, { cache: 'no-store', signal: fetchTimeout(8000) });
    if (!r.ok) throw new Error('http ' + r.status);
    const j = await r.json();
    const code = parseInt((j.tag_name || '').replace(/^v/, ''), 10) || 0;
    const asset = (j.assets || []).find((a) => /\.apk(?:\.bin)?$/i.test(a.name || ''));
    return (code > 0 && asset) ? { code, version: j.tag_name, apk: asset.browser_download_url } : null;
  })());
  const results = await Promise.allSettled(jobs);
  let best = null;
  for (const res of results) {
    if (res.status !== 'fulfilled' || !res.value) continue;
    if (!best || (res.value.code || 0) > (best.code || 0)) best = res.value;
  }
  return best ? withMirrors(best) : null;
}
/* APK 下载线路（设备端逐条尝试直到成功）。文件名带构建号：每个构建都是新文件，
   CDN 不可能命中过期缓存（曾实测各边缘对同名文件缓存滞后 1~4 个构建、互不相同，
   导致装到旧包/误判已是最新）。gcore/testingcf/jsdmirror 不拦 .apk。
   注意：不要再用 .bin —— CI 只在 Release 附件里产出 .bin，仓库的 apk-latest/
   目录下只有 .apk；旧的第 4 条 .bin 线路必然 404（且 cdn 边缘还会 301 拦截），
   纯属浪费一次超时。 */
function withMirrors(j) {
  const code = j.code || 0;
  const m = [];
  if (code > 0) {
    m.push(`https://gcore.jsdelivr.net/gh/${REPO}@main/apk-latest/ddl-radar-${code}.apk`);
    m.push(`https://testingcf.jsdelivr.net/gh/${REPO}@main/apk-latest/ddl-radar-${code}.apk`);
    m.push(`https://cdn.jsdmirror.com/gh/${REPO}@main/apk-latest/ddl-radar-${code}.apk`);
  }
  if (j.apk) {
    if (j.apk.indexOf('https://github.com/') === 0) {
      m.push(`https://ghfast.top/${j.apk}`);
      m.push(`https://gh-proxy.com/${j.apk}`);
      m.push(`https://ghproxy.net/${j.apk}`);
    }
    m.push(j.apk);
  }
  j.mirrors = m;
  return j;
}

// 更新按钮的锁覆盖检查、原生下载和恢复；安装器返回后保留目标构建。
function bindUpdater() {
  const b = $('updateBtn');
  if (!b || b.dataset.bound === '1') return;
  b.dataset.bound = '1';
  const plugin = window.Capacitor && Capacitor.Plugins.ApkInstaller;
  let requestPending = false, nativeBusy = false, pollTimer = null;
  let stateRevision = 0;
  const setBusy = () => {
    b.disabled = requestPending || nativeBusy;
    if (b.disabled) b.dataset.busy = '1'; else delete b.dataset.busy;
  };
  const clearTarget = () => {
    delete b.dataset.url;
    delete b.dataset.code;
    pendingUpdateMirrors = [];
    b.classList.remove('primary');
  };
  const applyNativeState = (state) => {
    if (!state || !state.status || state.status === 'idle') return;
    // 一个旧构建的恢复结果不能覆盖刚检查到的新构建。
    if (!state.busy && Number(b.dataset.code || 0) > Number(state.code || 0)) return;
    stateRevision++;
    nativeBusy = !!state.busy;
    if (state.code > 0 && state.status !== 'installed') {
      b.dataset.code = String(state.code);
      if (Array.isArray(state.urls) && state.urls.length) {
        pendingUpdateMirrors = state.urls.filter((u) => typeof u === 'string' && u.startsWith('https://'));
        b.dataset.url = pendingUpdateMirrors[0] || '';
      }
      b.classList.add('primary');
    }
    if (state.status === 'installed') {
      clearTarget();
      b.textContent = '✅ 此版本已安装，点击检查更新';
    } else if (state.status === 'ready' || state.status === 'installerOpened') {
      b.textContent = '📦 继续安装已下载的更新';
    } else if (state.status === 'awaitingPermission') {
      b.textContent = state.canInstall ? '⬇️ 权限已开启，继续更新' : '⚙️ 允许安装后继续更新';
    } else if (state.status === 'downloading' || state.status === 'preparing') {
      b.textContent = state.total > 0 ? '⬇️ 下载中 ' + Math.min(99, Math.round(state.bytes / state.total * 100)) + '%' : '⬇️ 正在准备更新包…';
    } else {
      b.textContent = state.status === 'error' ? '⬇️ 更新失败，点击重试' : '⬇️ 继续更新 (b' + state.code + ')';
    }
    setBusy();
    // 监听器失败或 WebView 重建也能从原生状态恢复，避免永久停在“下载中”。
    if (pollTimer) clearTimeout(pollTimer);
    pollTimer = nativeBusy ? setTimeout(recoverState, 1500) : null;
  };
  async function recoverState() {
    if (!plugin || !plugin.getUpdateState) return;
    const before = stateRevision;
    try {
      const state = await plugin.getUpdateState();
      if (before === stateRevision) applyNativeState(state);
    } catch (e) { /* 旧插件没有状态接口时保留当前按钮 */ }
  }
  const initialization = (async () => {
    if (!plugin) return;
    await Promise.allSettled([
      (async () => {
        await plugin.addListener('stateChanged', applyNativeState);
      })(),
      (async () => {
        await plugin.addListener('progress', (d) => {
          if (!d || (d.code && Number(b.dataset.code) !== Number(d.code))) return;
          if (!nativeBusy && !requestPending) return;
          b.textContent = d.total > 0 ? '⬇️ 下载中 ' + Math.min(99, Math.round(d.bytes / d.total * 100)) + '%' : '⬇️ 正在下载更新包…';
        });
      })()
    ]);
    await recoverState();
  })();
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) recoverState();
  });
  if (window.Capacitor && Capacitor.Plugins.App && Capacitor.Plugins.App.addListener) {
    try {
      Promise.resolve(Capacitor.Plugins.App.addListener('appStateChange', (s) => {
        if (s.isActive) recoverState();
      })).catch(() => {});
    } catch (e) { /* 网页端没有 App 生命周期 */ }
  }
  b.addEventListener('click', async () => {
    if (requestPending || nativeBusy) return;
    requestPending = true;
    setBusy();
    try {
      await initialization;
      if (nativeBusy) return;
      if (b.dataset.url && Number(b.dataset.code) > 0) {
        const urls = pendingUpdateMirrors.length ? pendingUpdateMirrors.slice() : [b.dataset.url];
        if (!plugin) { openExternal(b.dataset.url); return; }
        b.textContent = '⬇️ 正在准备更新包…';
        const result = await plugin.installApk({ urls, versionCode: Number(b.dataset.code) });
        if (result && result.status) {
          applyNativeState(result);
          if (result.status === 'awaitingPermission') {
            toast('⚙️ 需要安装权限', '开启「允许安装未知应用」后返回，再点继续更新');
          } else if (result.status === 'installerOpened') {
            toast('📦 更新已就绪', '请在系统界面确认安装；返回后可继续安装，无需重新下载');
          } else if (result.status === 'installed') {
            toast('✅ 此版本已安装', '可继续检查是否有更新版本');
          }
        } else {
          // 兼容旧原生插件；新版结果由持久状态提供。
          b.textContent = '📦 继续安装已下载的更新';
        }
        return;
      }
      b.textContent = '🔄 检查中…';
      let local = 0;
      try {
        if (window.Capacitor && Capacitor.Plugins.App && Capacitor.Plugins.App.getInfo) {
          const info = await Capacitor.Plugins.App.getInfo();
          local = parseInt(info && info.build, 10) || 0;
        }
      } catch (e) { /* 回退到构建时写入的版本 */ }
      if (!local) {
        try { const r = await fetch('version.json', { cache: 'no-store' }); if (r.ok) local = Number((await r.json()).code) || 0; } catch (e) {}
      }
      const rem = await fetchRemoteVersion();
      if (!rem) throw new Error('暂时无法取得版本信息，请稍后重试');
      if (Number(rem.code) > local) {
        pendingUpdateMirrors = rem.mirrors || [];
        b.dataset.url = rem.apk || rem.url || pendingUpdateMirrors[0] || '';
        if (!b.dataset.url) throw new Error('新版本没有可用的下载地址');
        b.dataset.code = String(rem.code);
        b.classList.add('primary');
        b.textContent = '⬇️ 更新到 ' + (rem.version || '') + ' (b' + rem.code + ')';
        toast('🚀 发现新版本 ' + (rem.version || '') + ' (b' + rem.code + ')',
          plugin ? '再点一次获取更新，完成后打开系统安装确认页' : '再点一次按钮，浏览器会下载新 APK');
      } else {
        clearTarget();
        b.textContent = '✅ 已是最新 v' + APP_VERSION + (local > 0 ? ' (b' + local + ')' : '');
      }
    } catch (e) {
      nativeBusy = false;
      b.textContent = b.dataset.url ? '⬇️ 更新失败，点击重试' : '🔄 检查更新';
      toast('⚠️ 更新未完成', (e && e.message ? String(e.message).slice(0, 180) : '请稍后重试'));
      await recoverState();
    } finally {
      requestPending = false;
      setBusy();
    }
  });
}

/* ---------------- 提醒诊断面板 ---------------- */
function diagRow(ok, text, fix) {
  const icon = ok === null || ok === undefined ? '➖' : ok ? '✅' : '❌';
  const btn = fix ? `<button class="btn-ghost-sm" data-fix="${fix}" style="margin-left:auto">去修复</button>` : '';
  return `<div class="diag-row">${icon} <span style="flex:1">${text}</span>${btn}</div>`;
}
async function renderDiag() {
  const box = $('diagList');
  if (!box) return;
  if (!(window.NativeNotify && NativeNotify.available())) {
    box.innerHTML = diagRow(null, '网页版环境：无系统级提醒，完整体验请安装 APK');
    return;
  }
  box.innerHTML = '<div>🔍 诊断中…</div>';
  const d = await NativeNotify.diag();
  let html = '';
  html += diagRow(d.notify === true, '应用通知权限', d.notify ? '' : 'notify');
  html += diagRow((d.channels || 0) >= 4, '提醒通道已创建（' + (d.channels || 0) + '/4）', d.channels >= 4 ? '' : 'reinit');
  const queued = d.queued || 0;
  html += diagRow((d.pending || 0) > 0 || queued > 0,
    '系统闹钟已排定 ' + d.pending + ' 条' + (queued ? '，候补 ' + queued + ' 条' : '') +
      (d.nextAt ? '，下一条 ' + fmtDT(d.nextAt) : ''),
    (d.pending || 0) > 0 || queued > 0 ? '' : 'notask');
  if (((d.pending || 0) > 0 || queued > 0) && !(d.sysNextAt > 0)) {
    html += diagRow(false, '有待提醒内容，但系统当前没有接受任何闹钟（可能被 ROM 拦截）');
  } else if (d.sysNextAt > 0) {
    html += diagRow(true, '系统已接受的最近闹钟 ' + fmtDT(d.sysNextAt));
  } else {
    html += diagRow(null, '系统当前无已接受的闹钟（未排定属正常）');
  }
  html += diagRow(d.lastFireAt > 0, d.lastFireAt > 0
    ? '上次闹钟触发 ' + fmtDT(d.lastFireAt) +
      (d.lastFireVia === 'exact' ? '（经精确闹钟备份）' :
       d.lastFireVia === 'service' ? '（经守护服务兜底）' : '（经系统闹钟）')
    : '还没有触发记录（闹钟触发一次后出现）');
  if (d.lastDelayMs >= 0) {
    html += diagRow(d.lastDelayMs < 1000, '上次提醒到点后延迟 ' + (d.lastDelayMs / 1000).toFixed(2) + ' 秒');
    if (d.lastNotificationAllowed === false) html += diagRow(false, '上次提醒的系统通知被权限或通道开关阻止', 'notify');
  }
  if (d.lastReceiverAt > 0) {
    html += diagRow(true, '系统已把闹钟送到 App：' + fmtDT(d.lastReceiverAt) +
      (d.lastFireAt > 0 ? '' : '，但提醒内容未完成展示'));
  }

  // ── 新增：直接回答「为什么没横幅 / 为什么不准时」 ──
  if (!reminderOutputsEnabled()) {
    html += diagRow(null, '提醒已关闭：响铃、震动和横幅均未启用');
  } else if (!remindBannerEnabled()) {
    html += diagRow(null, '横幅已关闭；响铃和震动仍可由系统闹钟独立执行');
  } else if (d.headsUp === false) {
    html += diagRow(false, '当前提醒通道重要性为 ' + d.channelImportance + '/4；请调回「重要」并开启横幅', 'channel');
  } else if (d.headsUp === true) {
    html += diagRow(true, '当前提醒通道重要性 4/4；若仍无横幅，请检查 ColorOS 的横幅显示开关', 'channel');
  }
  if (d.canFullScreen === false) {
    html += diagRow(false, '「全屏通知」权限被关闭：锁屏时不会亮屏弹提醒页', 'appinfo');
  }
  if (d.exactOk === false) {
    html += diagRow(false, '「闹钟和提醒」权限被关闭：提醒只能降级为普通闹钟，Doze 下可能延迟较久', 'appinfo');
  }
  if (d.missed > 0) {
    html += diagRow(false, '关机/重启期间错过 ' + d.missed + ' 条提醒（已补记，不会再弹）');
  }

  html += diagRow(d.guard === true, d.guard === true ? '守护服务运行中' : '守护服务未运行：请重新打开 App，并到系统设置开启自启动', d.guard ? '' : 'appinfo');
  html += diagRow(null, 'ColorOS 后台保活（若切后台不响务必做）：允许自启动 + 耗电管理 → 允许后台运行；后台任务卡片下拉「锁定」', 'appinfo');
  html += diagRow(null, '系统闹钟负责唤醒提醒；重启和更新后自动恢复，实际延迟见上方记录');
  if (d.sdk) html += '<div style="font-size:11px;color:var(--text-dim);margin-top:6px">设备系统版本 Android ' + d.sdk + '（诊断结果可截图发给开发者）</div>';
  box.innerHTML = html;
  box.querySelectorAll('[data-fix]').forEach((b) => b.addEventListener('click', async () => {
    const f = b.dataset.fix;
    if (f === 'notify') { askNotify(); setTimeout(refreshNotifyBtn, 500); }
    if (f === 'appinfo') {
      try { await Capacitor.Plugins.DdlNotify.openAppDetails(); } catch (e) { /* 忽略 */ }
    }
    if (f === 'reinit') {
      await NativeNotify.ensureChannels(true);
      await Capacitor.Plugins.DdlNotify.openChannelSettings({ id: NativeNotify.channelIdFor(S.settings) });
      toast('🔧 已检查提醒通道', '请确认允许通知，并开启横幅；系统保留的开关需在此修改');
    }
    if (f === 'channel') {
      // 通道重要性 App 改不了，只能直达系统设置让用户手动调回「重要」
      try {
        const channelId = NativeNotify.channelIdFor(S.settings);
        const modeName = S.settings.banner !== false ? '横幅通知' : '无横幅通知';
        await Capacitor.Plugins.DdlNotify.openChannelSettings({ id: channelId });
        toast('⚙️ 已打开通道设置', '确认「' + modeName + '」的重要性为「重要/紧急」，并允许横幅显示');
      } catch (e) { toast('⚠️ 打不开设置', '请手动到 系统设置 → 应用 → DDL雷达 → 通知 里调整'); }
    }
    if (f === 'notask') { toast('📌 暂无可提醒事项', '添加带截止时间的事项后，系统提醒会自动排定'); }
    setTimeout(renderDiag, 900);
  }));
}

function bindSettings() {
  const reminderToggle = (id, key) => $(id).addEventListener('change', () => {
    if (S.settings.remindSilent) return;
    S.settings[key] = $(id).checked;
    save();
    syncRemindModeUI();
    resyncReminderSettings();
  });
  reminderToggle('setSound', 'sound');
  reminderToggle('setVibrate', 'vibrate');
  reminderToggle('setBanner', 'banner');
  $('setSilent').addEventListener('change', () => {
    S.settings.remindSilent = $('setSilent').checked;
    save();
    syncRemindModeUI();
    resyncReminderSettings();
  });
  syncRemindModeUI();

  $('setDailyTime').value = S.settings.dailyTime;
  $('setDailyTime').addEventListener('change', () => {
    S.settings.dailyTime = $('setDailyTime').value || '12:00'; save(); renderAll();
  });
  $('setTiered').checked = S.settings.tiered;
  $('setTiered').addEventListener('change', () => { S.settings.tiered = $('setTiered').checked; save(); });
  $('setCompact').checked = S.settings.toastCompact !== false;
  $('setCompact').addEventListener('change', () => {
    S.settings.toastCompact = $('setCompact').checked; save();
    toast(S.settings.toastCompact ? '📴 顶部一行模式已开' : '📇 卡片模式已开', '之后弹出的提醒横幅将使用新样式');
  });
  $('testRemBtn').addEventListener('click', async () => {
    if (window.NativeNotify && NativeNotify.available()) {
      // 提示统一由 NativeNotify.test() 负责（含排定结果自验证与权限预检）
      await NativeNotify.test();
    } else {
      toast('🧪 已排定测试', '5 秒后应用内提醒（此环境无系统级通知）');
      setTimeout(() => {
        if (remindBannerEnabled()) toast('🧪 测试提醒', '看到这条说明应用内链路正常', 'urgent');
        beep(); vibe();
      }, 5000);
    }
  });
  $('notifyBtn').addEventListener('click', () => { askNotify(); setTimeout(refreshNotifyBtn, 600); });

  $('verText').textContent = 'v' + APP_VERSION;
  // 版本显示以 CI 写入的 version.json 为准（version+构建号），杜绝手改遗漏：
  fetch('version.json', { cache: 'no-store' }).then((r) => r.ok ? r.json() : null).then((j) => {
    if (j && j.code) $('verText').textContent = 'v' + (j.version || APP_VERSION) + ' (b' + j.code + ')';
  }).catch(() => {});
  $('diagBtn').addEventListener('click', renderDiag);
  bindUpdater();
  $('bgDimRange').value = S.settings.bgDim;
  $('bgDimVal').textContent = S.settings.bgDim + '%';
  $('bgDimRange').addEventListener('input', () => {
    S.settings.bgDim = +$('bgDimRange').value;
    $('bgDimVal').textContent = S.settings.bgDim + '%';
    applyBg(); save();
  });
  $('bgClear').addEventListener('click', () => {
    S.settings.bgImage = null; S.settings.bgDim = 35; save(); applyBg();
    $('bgDimRange').value = 35; $('bgDimVal').textContent = '35%';
  });

  $('exportBtn').addEventListener('click', () => {
    const blob = new Blob([JSON.stringify(S, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `ddlradar-backup-${ymdOf(Date.now())}.json`;
    a.click(); URL.revokeObjectURL(a.href);
  });
  $('importBtn').addEventListener('click', () => $('importFile').click());
  $('wipeBtn').addEventListener('click', () => {
    if (confirm('确定清空所有事项和数据吗？此操作不可恢复。')) {
      S = defaultState(); S.onboarded = true; window.S = S;
      localStorage.removeItem('ddlr_testAt');
      invalidateFiredCache(); save(); applyTheme(); bindSettingsValues(); renderAll();
      if (window.Timetable && Timetable.render) Timetable.render();
      toast('🧹 已清空', '事项、课表和设置已清空');
    }
  });
  $('demoBtn').addEventListener('click', loadDemo);
}
function handleBgFile(f) {
  if (!f || !f.type.startsWith('image/')) return;
  const img = new Image();
  img.onload = () => {
    const max = 1600, r = Math.min(max / img.width, max / img.height, 1);
    const cv = document.createElement('canvas');
    cv.width = Math.round(img.width * r); cv.height = Math.round(img.height * r);
    cv.getContext('2d').drawImage(img, 0, 0, cv.width, cv.height);
    S.settings.bgImage = cv.toDataURL('image/jpeg', 0.86);
    // 照片取色 → 自动生成整套 UI 配色（背景/强调色/卡片/文字深浅）
    try {
      S.settings.customPalette = extractPalette(cv);
      S.settings.theme = 'custom';
    } catch (e) { console.warn('取色失败', e); }
    save(); applyTheme();
    toast('🖼️ 壁纸已换 + 🎨 已生成照片配色', '整套 UI（强调色/卡片/文字深浅）已按照片自动调整，可去「皮肤」里切换或微调遮罩');
  };
  img.src = URL.createObjectURL(f);
}
function handleImport(f) {
  if (!f) return;
  const rd = new FileReader();
  rd.onload = () => {
    try {
      const data = JSON.parse(rd.result);
      const imported = normalizeState(data);
      S = imported; window.S = S; invalidateFiredCache();
      save(); applyTheme(); bindSettingsValues(); renderAll();
      if (window.Timetable && Timetable.render) Timetable.render();
      toast('📥 导入成功', `共 ${S.tasks.length} 条事项`);
    } catch (e) { toast('⚠️ 导入失败', '文件格式不对，需要本应用导出的 JSON'); }
  };
  rd.readAsText(f);
}
function syncRemindModeUI() {
  const silent = S.settings.remindSilent === true;
  $('setSilent').checked = silent;
  $('setSound').checked = !silent && S.settings.sound !== false;
  $('setVibrate').checked = !silent && S.settings.vibrate !== false;
  $('setBanner').checked = !silent && S.settings.banner !== false;
  ['setSound', 'setVibrate', 'setBanner'].forEach((id) => { $(id).disabled = silent; });
}

function resyncReminderSettings() {
  if (!(window.NativeNotify && NativeNotify.available())) return;
  NativeNotify._sig = null;
  try { NativeNotify.sync(S).catch(function () {}); } catch (e) {}
}

function bindSettingsValues() {
  syncRemindModeUI();
  $('setDailyTime').value = S.settings.dailyTime;
  $('setTiered').checked = S.settings.tiered;
  $('setCompact').checked = S.settings.toastCompact !== false;
  $('bgDimRange').value = S.settings.bgDim;
  $('bgDimVal').textContent = S.settings.bgDim + '%';
}

/* ---------------- 示例数据 ---------------- */
function loadDemo() {
  const now = Date.now(), H = 3600000, D = 86400000;
  S.tasks = [
    { id: uid(), name: '提交数据结构实验报告', detail: '① 报告 PDF + 源码打包 zip\n② 命名：学号_姓名_lab3\n③ 传到课程平台，逾期扣分\n④ 源码里 quickSort 有个边界没测，交前再跑一遍', quadrant: 'q1', isRange: false, startAt: null, dueAt: now + 3 * H, tiers: { 120: true, 60: true, 30: true, 5: true }, custom: [], daily: false, done: false, createdAt: now - D },
    { id: uid(), name: '小组作业 · 市场调研问卷', detail: '问卷星链接要先发到小组群让大家提意见，再发放到朋友圈。目标回收 80 份以上。', quadrant: 'q1', isRange: true, startAt: now + 20 * H, dueAt: now + 2 * D + 5 * H, tiers: { 120: true, 60: true, 30: true, 5: true }, custom: [{ v: 1, u: 'd' }], daily: true, done: false, createdAt: now - 2 * D },
    { id: uid(), name: '每天背 30 个六级单词', detail: '用不背单词 App，考研前把高频词过完两轮。今天份还没打卡。', quadrant: 'q2', isRange: false, startAt: null, dueAt: now + 30 * D, tiers: { 120: false, 60: false, 30: false, 5: false }, custom: [], daily: true, done: false, createdAt: now - 3 * D },
    { id: uid(), name: '预约体检（拖了两个月了）', detail: '校医院只有周三周五上午能约，带上校园卡。最好约周五。', quadrant: 'q2', isRange: false, startAt: null, dueAt: now + 12 * D, tiers: { 120: false, 60: false, 30: false, 5: false }, custom: [], daily: true, done: false, createdAt: now - 60 * D },
    { id: uid(), name: '取快递（驿站今晚八点关门）', detail: '两个件：一个书，一个数据线。', quadrant: 'q3', isRange: false, startAt: null, dueAt: now + 6 * H, tiers: { 120: true, 60: true, 30: true, 5: false }, custom: [], daily: false, done: false, createdAt: now - H },
    { id: uid(), name: '刷会儿短视频放松下', detail: '就刷半小时，设了闹钟的。', quadrant: 'q4', isRange: false, startAt: null, dueAt: now + 30 * 60000, tiers: { 120: false, 60: false, 30: false, 5: false }, custom: [], daily: false, done: false, createdAt: now - H },
    { id: uid(), name: '写周报', detail: '上周的周报模板在邮箱里。', quadrant: 'q3', isRange: false, startAt: null, dueAt: now - 6 * H, tiers: { 120: true, 60: true, 30: true, 5: true }, custom: [], daily: false, done: false, createdAt: now - 2 * D },
    { id: uid(), name: '给妈妈打电话', detail: '聊了半小时，她说家里桂花开了。', quadrant: 'q2', isRange: false, startAt: null, dueAt: now - D, tiers: { 120: false, 60: false, 30: false, 5: false }, custom: [], daily: false, done: true, doneAt: now - 20 * H, createdAt: now - 2 * D }
  ];
  S.fired = []; invalidateFiredCache(); save(); renderAll();
  toast('🐟 示例数据已填入', '到处点点看看：展开细节、切象限、换皮肤、翻日历');
}

/* ---------------- 渲染总入口 ---------------- */
function renderAll() {
  renderTop(); renderHome();
  if (curView === 'matrix') renderMatrix();
  if (curView === 'calendar' && window.Cal) Cal.render();
}

/* ---------------- 初始化 ---------------- */
function init() {
  applyTheme();
  bindSettings();
  refreshNotifyBtn();
  if (window.NativeNotify && NativeNotify.available()) {
    NativeNotify.init(S).then(() => { if (curView === 'settings') renderDiag(); }).catch(function (e) { console.warn('NativeNotify.init 失败', e); });
  }

  // 隐藏文件选择器
  const bgFile = document.createElement('input');
  bgFile.type = 'file'; bgFile.accept = 'image/*'; bgFile.style.display = 'none';
  bgFile.addEventListener('change', () => { handleBgFile(bgFile.files[0]); bgFile.value = ''; });
  document.body.appendChild(bgFile);
  $('bgPick').addEventListener('click', () => bgFile.click());

  const impFile = document.createElement('input');
  impFile.id = 'importFile';
  impFile.type = 'file'; impFile.accept = '.json,application/json'; impFile.style.display = 'none';
  impFile.addEventListener('change', () => { handleImport(impFile.files[0]); impFile.value = ''; });
  document.body.appendChild(impFile);

  // Esc 关闭弹层（桌面端顺手）
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      $('editorWrap').classList.add('hidden');
      $('dayWrap').classList.add('hidden');
    }
  });

  // 标签栏
  document.querySelectorAll('.tab').forEach((t) =>
    t.addEventListener('click', () => switchView(t.dataset.view)));
  $('fab').addEventListener('click', () => openEditor(null));

  // 编辑器
  $('fSave').addEventListener('click', saveEditor);
  document.querySelectorAll('[data-close]').forEach((el) =>
    el.addEventListener('click', () => {
      $(el.dataset.close === 'editor' ? 'editorWrap' : 'dayWrap').classList.add('hidden');
    }));
  $('fDelete').addEventListener('click', () => {
    if (!editingId) return;
    if (confirm('删除这条事项？')) {
      S.tasks = S.tasks.filter((x) => x.id !== editingId);
      save(); $('editorWrap').classList.add('hidden'); renderAll();
      toast('🗑️ 已删除');
    }
  });
  document.querySelectorAll('#fQuadrant button').forEach((b) =>
    b.addEventListener('click', () => { curQuad = b.dataset.q; syncEditorUI(); }));
  document.querySelectorAll('#fTimeType button').forEach((b) =>
    b.addEventListener('click', () => { curType = b.dataset.t; syncEditorUI(); }));
  document.querySelectorAll('#fRepeat button').forEach((b) =>
    b.addEventListener('click', () => { curRepeat = b.dataset.r; syncEditorUI(); }));
  document.querySelectorAll('#fTier button').forEach((b) =>
    b.addEventListener('click', () => {
      const m = b.dataset.min; curTiers[m] = !curTiers[m]; syncEditorUI();
    }));
  $('fAddRem').addEventListener('click', () => {
    curCustom.push({ v: 1, u: 'd' }); syncEditorUI();
  });

  // 闲得发慌
  $('boredBtn').addEventListener('click', boredPick);

  // 日历导航
  $('calPrev').addEventListener('click', () => {
    Cal.m--; if (Cal.m < 0) { Cal.m = 11; Cal.y--; } Cal.render();
  });
  $('calNext').addEventListener('click', () => {
    Cal.m++; if (Cal.m > 11) { Cal.m = 0; Cal.y++; } Cal.render();
  });
  $('calTodayBtn').addEventListener('click', () => {
    const d = new Date(); Cal.y = d.getFullYear(); Cal.m = d.getMonth(); Cal._sel = null; Cal.render();
  });

  // 引导
  if (!S.onboarded) {
    $('onboard').classList.remove('hidden');
    $('onbGo').addEventListener('click', () => {
      S.onboarded = true; save();
      $('onboard').classList.add('hidden');
      toast('📡 雷达已开机', '点右下角 + 新建你的第一条 DDL；想先看看效果，去「我的」里点「填入示例数据」');
      askNotify();
    });
  }

  renderAll();
  // ⚠️ 这三处必须各自 try/catch：
  // 早前是裸调用，tickReminders() 内部一旦抛异常（历史上曾因 planReminders
  // 引用未定义的 ymd() 而必抛），异常会直接打断 init()，导致下面五个
  // setInterval 一个都注册不上 —— 提醒引擎、倒计时、界面刷新全部静默，
  // 但界面本身已经渲染完了，用户看不出异常，只觉得「提醒不响」。
  try { tickReminders(); } catch (e) { console.warn('tickReminders 初始化失败', e); }
  try { tickCountdown(); } catch (e) { console.warn('tickCountdown 初始化失败', e); }

  // ── 心跳：全部按「前台/后台」分档，避免后台空转耗电 ──
  // 背景：WebView 在后台本就会被系统节流（通常 60s 一次），但即便被节流，
  // 每次唤醒仍要跑完 tickCountdown（遍历所有 DOM）和 tickReminders
  // （遍历所有任务 × 所有提醒点，含 400 天 daily）。这是待机耗电的主要来源。
  // 现在：隐藏时直接跳过重活，只保留最小限度的提醒补弹。
  let tickCount = 0;
  setInterval(() => {
    if (document.hidden) return;   // 后台不跑倒计时（看不见，纯浪费）
    tickCountdown();
  }, 1000);
  setInterval(() => {
    // 提醒补弹：前台 2s 一次；后台降到 ~10s（靠计数跳过，不新增定时器）
    if (document.hidden && (++tickCount % 5) !== 0) return;
    tickReminders();
  }, 2000);
  setInterval(() => {
    if (document.hidden) return;   // 后台不重绘
    renderTop();
    if (curView === 'home') renderHome();
  }, 30000);
  setInterval(idleNudge, 60000);
  if (window.NativeNotify && NativeNotify.available()) {
    // 兜底重排：只在前台做。后台靠原生闹钟，不需要 JS 参与。
    setInterval(() => { if (!document.hidden && window.NativeNotify) { try { NativeNotify.sync(S).catch(function(){}); } catch (e) {} } }, 60000);
  }
}
document.addEventListener('DOMContentLoaded', init);
