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
function dtLocalValue(ts) {
  const d = new Date(ts);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/* ---------------- 状态 ---------------- */
const LS_KEY = 'ddlr_v1';
let S = {
  onboarded: false,
  tasks: [],
  fired: [],
  settings: {
    theme: 'cat', bgImage: null, bgDim: 35,
    dailyTime: '12:00',
    remindMode: 'full', // full=响铃+震动 / vib=仅震动 / ring=仅响铃 / silent=仅应用内横幅
    tiered: true, sound: true, vibrate: true
  }
};
try {
  const raw = localStorage.getItem(LS_KEY);
  if (raw) S = Object.assign(S, JSON.parse(raw));
} catch (e) { console.warn('状态读取失败', e); }

function save() {
  try { localStorage.setItem(LS_KEY, JSON.stringify(S)); } catch (e) { console.warn('存储失败', e); }
  // APK 内同步重排系统级精确提醒
  if (window.NativeNotify) NativeNotify.sync(S);
}

/* ---------------- 提示音 / 震动 ---------------- */
let audioCtx = null;
function remindMode() { return S.settings.remindMode || 'full'; }
function beep() {
  if (!S.settings.sound) return;
  if (remindMode() === 'vib' || remindMode() === 'silent') return;
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
  if (!S.settings.vibrate) return;
  if (remindMode() === 'ring' || remindMode() === 'silent') return;
  if (navigator.vibrate) navigator.vibrate([180, 90, 180]);
}

/* ---------------- Toast 横幅 ---------------- */
function toast(title, body, kind) {
  const el = document.createElement('div');
  el.className = 'toast' + (kind ? ' tt-' + kind : '');
  el.innerHTML = `<div class="tt-title">${title}</div>${body ? `<div class="tt-body">${body}</div>` : ''}`;
  $('toastWrap').appendChild(el);
  const kill = () => { el.classList.add('gone'); setTimeout(() => el.remove(), 320); };
  el.addEventListener('click', kill);
  setTimeout(kill, 9000);
  if (document.hidden) {
    document.title = '⏰ DDL雷达 · 有提醒！';
    if ('Notification' in window && Notification.permission === 'granted') {
      try {
        new Notification(title.replace(/<[^>]+>/g, ''), { body: (body || '').replace(/<[^>]+>/g, ''), tag: 'ddlr' });
      } catch (e) { /* ignore */ }
    }
  }
}
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) document.title = 'DDL雷达 · 不再错过任何死线';
});

/* ---------------- 主题 & 壁纸 ---------------- */
const THEMES = [
  { id: 'cat',   name: '奶油喵喵', emoji: '🐾', sw: 'linear-gradient(135deg,#fdf6e9,#8fae8b,#f2a65a)' },
  { id: 'soda',  name: '汽水青春', emoji: '🥤', sw: 'linear-gradient(135deg,#ffe3ec,#ffd8c2,#ffdcf1)' },
  { id: 'tech',  name: '深空代码', emoji: '🛰️', sw: 'linear-gradient(135deg,#0b1020,#101736,#4de3c1)' },
  { id: 'cloud', name: '云朵白',   emoji: '☁️', sw: 'linear-gradient(135deg,#f7f8fd,#c9d4f5,#8fa5e8)' }
];
function applyTheme() {
  const t = S.settings.theme;
  document.body.className = 'theme-' + t + (t === 'tech' ? ' tech-deco' : '');
  const th = THEMES.find((x) => x.id === t) || THEMES[0];
  document.querySelector('meta[name=theme-color]').content = t === 'tech' ? '#0b1020' : (t === 'cat' ? '#fdf6e9' : (t === 'cloud' ? '#f7f8fd' : '#ffe3ec'));
  renderThemeGrids();
  applyBg();
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
  const html = THEMES.map((t) =>
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
function nextFestivalLine() {
  try {
    const now = new Date(); now.setHours(0, 0, 0, 0);
    for (let i = 0; i <= 90; i++) {
      const d = new Date(now.getTime() + i * 86400000);
      let name = '';
      try { const sf = Solar.fromDate(d).getFestivals(); if (sf.length) name = sf[0]; } catch (e) { /* */ }
      if (!name) { const l = Lunar.fromDate(d); const lf = l.getFestivals(); if (lf.length) name = lf[0]; }
      if (name) {
        const gap = i === 0 ? '就是今天' : `还有 ${i} 天`;
        return `距离 ${name} ${gap}`;
      }
    }
  } catch (e) { /* ignore */ }
  return '';
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
    </div>
    <div class="t-meta">
      <span>🕐 ${timeTxt}</span>
      ${leftTxt ? `<span>${leftTxt}</span>` : ''}
      ${hasDetail ? `<span class="t-toggle">细节 <span class="t-arrow">▶</span></span>` : ''}
    </div>
    ${hasDetail ? `<div class="t-detail">${escapeHtml(t.detail)}</div>` : ''}`;

  el.querySelector('.t-check').addEventListener('click', (e) => {
    e.stopPropagation();
    t.done = !t.done; t.doneAt = t.done ? Date.now() : null; save();
    if (t.done) { toast('🎉 干得漂亮！', `『${escapeHtml(t.name)}』完成`); beep(); }
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

  const data = {
    name, detail: $('fDetail').value.trim(), quadrant: curQuad,
    isRange, startAt, dueAt,
    tiers: curTiers, custom: curCustom, daily: $('fDaily').checked,
    updatedAt: Date.now()
  };
  if (editingId) {
    Object.assign(S.tasks.find((x) => x.id === editingId), data);
    S.fired = S.fired.filter((k) => !k.startsWith(editingId + '|')); // 时间变了，提醒重新排
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
  if (t.done || !t.dueAt) return out;
  const due = t.dueAt;

  // 当天分段提醒：设置页总开关 × 单事项细分开关
  if (t.tiers) {
    for (const min of TIER_MIN) {
      if (!S.settings.tiered) break;
      if (t.tiers[min] === false) continue;
      const at = due - min * 60000;
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
    if (at <= Date.now()) continue;
    const unit = r.u === 'd' ? '天' : r.u === 'h' ? '小时' : '分钟';
    out.push({
      key: `${t.id}|c${r.v}${r.u}`, at,
      title: `⏰ 提前 ${r.v} ${unit}`,
      body: `『${escapeHtml(t.name)}』${fmtDT(due)} 截止`,
      kind: 'daily'
    });
  }
  // 远期每日提醒（截止日在未来且不是今天）
  if (t.daily !== false) {
    const dueDay = new Date(due); dueDay.setHours(0, 0, 0, 0);
    const todayDay = new Date(); todayDay.setHours(0, 0, 0, 0);
    if (dueDay > todayDay) {
      const [hh, mm] = (t.dailyTime || S.settings.dailyTime || '12:00').split(':').map(Number);
      for (let i = 0; i < 400; i++) { // 最多往后排 400 天
        const day = new Date(todayDay.getTime() + i * 86400000);
        if (day > dueDay) break;
        if (day.getTime() === dueDay.getTime()) break; // 当天由分段提醒负责
        const at = new Date(day.getFullYear(), day.getMonth(), day.getDate(), hh, mm, 0).getTime();
        if (at <= Date.now()) continue;
        const days = Math.ceil((dueDay - day) / 86400000);
        out.push({
          key: `${t.id}|d${ymd(day.getTime())}`, at,
          title: `📅 每日提醒 · 还有 ${days} 天`,
          body: `『${escapeHtml(t.name)}』将于 ${fmtDT(due)} 截止`,
          kind: 'daily'
        });
      }
    }
  }
  // 到点提醒
  out.push({
    key: `${t.id}|due`, at: due,
    title: '🚨 时间到！',
    body: `『${escapeHtml(t.name)}』就是现在 · ${QUAD_NAME[t.quadrant]}`,
    kind: 'urgent'
  });
  return out;
}

function ymdOf(ts) {
  const d = new Date(ts);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function tickReminders() {
  const now = Date.now();
  for (const t of S.tasks) {
    for (const r of planReminders(t)) {
      if (S.fired.includes(r.key)) continue;
      if (r.at > now) continue;
      if (now - r.at > 10 * 60000) { S.fired.push(r.key); continue; } // 错过太久，静默标记
      S.fired.push(r.key);
      toast(r.title, r.body, r.kind);
      beep(); vibe();
      if (S.fired.length > 600) S.fired = S.fired.slice(-400);
      save();
    }
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

  const eb = $('exactAlarmBtn');
  if (window.NativeNotify && NativeNotify.available()) {
    NativeNotify.refreshExact().then((on) => {
      eb.classList.toggle('hidden', on !== false);
      if (on === false) eb.textContent = '⏰ 开启系统精确闹钟权限（锁屏必响，强烈建议）';
    });
  } else {
    eb.classList.add('hidden');
  }
}

/* ---------------- 设置页 ---------------- */
function bindSettings() {
  document.querySelectorAll('#setRemindMode button').forEach((b) =>
    b.addEventListener('click', () => {
      S.settings.remindMode = b.dataset.m;
      save(); syncRemindModeUI();
      toast('🔕 提醒方式已切换', '🔊 响铃+震动 / 📳 仅震动 / 🔔 仅响铃 / 🔇 仅横幅'.split(' / ')[['full','vib','ring','silent'].indexOf(b.dataset.m)]);
    }));
  syncRemindModeUI();

  $('setDailyTime').value = S.settings.dailyTime;
  $('setDailyTime').addEventListener('change', () => {
    S.settings.dailyTime = $('setDailyTime').value || '12:00'; save(); renderAll();
  });
  $('setTiered').checked = S.settings.tiered;
  $('setTiered').addEventListener('change', () => { S.settings.tiered = $('setTiered').checked; save(); });
  $('setSound').checked = S.settings.sound;
  $('setSound').addEventListener('change', () => { S.settings.sound = $('setSound').checked; save(); });
  $('setVib').checked = S.settings.vibrate;
  $('setVib').addEventListener('change', () => { S.settings.vibrate = $('setVib').checked; save(); });
  $('notifyBtn').addEventListener('click', () => { askNotify(); setTimeout(refreshNotifyBtn, 600); });
  $('exactAlarmBtn').addEventListener('click', async () => {
    if (!window.NativeNotify) return;
    const opened = await NativeNotify.openExactAlarm();
    if (!opened) toast('ℹ️ 请手动开启', '系统设置 → 应用 → DDL雷达 → 闹钟和提醒');
    setTimeout(refreshNotifyBtn, 1200);
  });

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
      S.tasks = []; S.fired = []; save(); renderAll(); toast('🧹 已清空', '一切从头开始');
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
    save(); applyBg();
    toast('🖼️ 壁纸已换', '可以在下方调整遮罩浓度');
  };
  img.src = URL.createObjectURL(f);
}
function handleImport(f) {
  if (!f) return;
  const rd = new FileReader();
  rd.onload = () => {
    try {
      const data = JSON.parse(rd.result);
      if (!data || !Array.isArray(data.tasks)) throw new Error('bad');
      S = Object.assign(S, data); save(); applyTheme(); bindSettingsValues(); renderAll();
      toast('📥 导入成功', `共 ${S.tasks.length} 条事项`);
    } catch (e) { toast('⚠️ 导入失败', '文件格式不对，需要本应用导出的 JSON'); }
  };
  rd.readAsText(f);
}
function syncRemindModeUI() {
  const m = S.settings.remindMode || 'full';
  document.querySelectorAll('#setRemindMode button').forEach((b) =>
    b.classList.toggle('on', b.dataset.m === m));
}

function bindSettingsValues() {
  syncRemindModeUI();
  $('setDailyTime').value = S.settings.dailyTime;
  $('setTiered').checked = S.settings.tiered;
  $('setSound').checked = S.settings.sound;
  $('setVib').checked = S.settings.vibrate;
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
  S.fired = []; save(); renderAll();
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
  if (window.NativeNotify && NativeNotify.available()) NativeNotify.init(S);

  // 隐藏文件选择器
  const bgFile = document.createElement('input');
  bgFile.type = 'file'; bgFile.accept = 'image/*'; bgFile.style.display = 'none';
  bgFile.addEventListener('change', () => { handleBgFile(bgFile.files[0]); bgFile.value = ''; });
  document.body.appendChild(bgFile);
  $('bgPick').addEventListener('click', () => bgFile.click());

  const impFile = document.createElement('input');
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
  tickReminders();

  // 心跳：提醒检查 20s / 顶栏与倒计时 30s / 闲逛提醒 60s
  setInterval(tickReminders, 20000);
  setInterval(() => { renderTop(); if (curView === 'home') renderHome(); }, 30000);
  setInterval(idleNudge, 60000);
}
document.addEventListener('DOMContentLoaded', init);
