/* ============================================================
   DDL雷达 · 日历模块
   农历(lunar.js) + 法定节假日/调休(holiday-cn)
   年份逐年更新：内置 2025/2026 → 本地缓存 → CDN 在线拉取
   ============================================================ */

(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const pad = (n) => String(n).padStart(2, '0');
  const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

  /* ---------- 节假日数据仓库 ---------- */
  const Hol = {
    mem: {},
    key: (y) => 'ddlr_holiday_' + y,

    getLocal(y) {
      if (this.mem[y]) return this.mem[y];
      if (window.HOLIDAY_BUILTIN && window.HOLIDAY_BUILTIN[y]) {
        this.mem[y] = window.HOLIDAY_BUILTIN[y];
        return this.mem[y];
      }
      try {
        const raw = localStorage.getItem(this.key(y));
        if (raw) { this.mem[y] = JSON.parse(raw); return this.mem[y]; }
      } catch (e) { /* ignore */ }
      return null;
    },

    // 异步确保某年数据：内置 → 缓存 → CDN。返回 Promise<data|null>
    async ensure(y) {
      const local = this.getLocal(y);
      if (local) return local;
      const urls = [
        `https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/${y}.json`,
        `https://fastly.jsdelivr.net/gh/NateScarlet/holiday-cn@master/${y}.json`
      ];
      for (const u of urls) {
        try {
          const res = await fetch(u, { cache: 'no-cache' });
          if (!res.ok) continue;
          const data = await res.json();
          if (data && Array.isArray(data.days)) {
            this.mem[y] = data;
            try { localStorage.setItem(this.key(y), JSON.stringify(data)); } catch (e) { /* ignore */ }
            return data;
          }
        } catch (e) { /* 离线或被拦截，尝试下一个源 */ }
      }
      return null;
    },

    // 某天的法定状态： {name, off} | null
    day(ymdStr) {
      const y = +ymdStr.slice(0, 4);
      const data = this.getLocal(y);
      if (!data || !data.days) return null;
      const hit = data.days.find((d) => d.date === ymdStr);
      return hit ? { name: hit.name, off: hit.isOffDay } : null;
    },
    yearStatus(y) { return this.getLocal(y) ? 'ok' : 'loading'; }
  };
  window.Hol = Hol;

  /* ---------- 农历/节日描述 ---------- */
  function lunarSafe(date) {
    try { return Lunar.fromDate(date); } catch (e) { return null; }
  }

  // 单元格副标题：法定节日(红) > 公历/农历节日(橘) > 节气 > 农历日
  function cellSub(date, legal) {
    if (legal) return { text: legal.name, cls: 'fest' };
    const solar = (() => { try { return Solar.fromDate(date); } catch (e) { return null; } })();
    const lunar = lunarSafe(date);
    if (solar) {
      try {
        const f = solar.getFestivals();
        if (f && f.length) return { text: f[0], cls: 'lfest' };
      } catch (e) { /* ignore */ }
    }
    if (lunar) {
      try {
        const f = lunar.getFestivals();
        if (f && f.length) return { text: f[0], cls: 'lfest' };
      } catch (e) { /* ignore */ }
      try {
        const jq = lunar.getJieQi();
        if (jq) return { text: jq, cls: 'jq' };
      } catch (e) { /* ignore */ }
      const day = lunar.getDayInChinese();
      if (day === '初一') return { text: lunar.getMonthInChinese() + '月', cls: '' };
      return { text: day, cls: '' };
    }
    return { text: '', cls: '' };
  }

  /* ---------- 月历渲染 ---------- */
  const Cal = {
    y: new Date().getFullYear(),
    m: new Date().getMonth(), // 0-based

    async render() {
      const grid = $('calGrid');
      if (!grid) return;
      $('calTitle').textContent = `${this.y} 年 ${this.m + 1} 月`;
      grid.innerHTML = '';

      const first = new Date(this.y, this.m, 1);
      const daysInMonth = new Date(this.y, this.m + 1, 0).getDate();
      let lead = first.getDay() - 1; if (lead < 0) lead = 6; // 周一开头
      const start = new Date(this.y, this.m, 1 - lead);
      const todayStr = ymd(new Date());
      const selStr = Cal._sel && Cal._sel.startsWith(`${this.y}-${pad(this.m + 1)}`) ? Cal._sel : null;

      const tasks = (window.S && window.S.tasks) || [];
      const taskDays = {};
      for (const t of tasks) {
        if (t.done || !t.dueAt) continue;
        const k = ymd(new Date(t.dueAt));
        (taskDays[k] = taskDays[k] || []).push(t);
      }

      for (let i = 0; i < 42; i++) {
        const d = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i);
        const k = ymd(d);
        const cell = document.createElement('div');
        cell.className = 'cal-cell';
        if (d.getMonth() !== this.m) cell.classList.add('out');
        if (k === todayStr) cell.classList.add('today');
        if (k === selStr) cell.classList.add('sel');

        const legal = Hol.day(k);
        if (legal && legal.off) cell.classList.add('rest');
        if (legal && !legal.off) cell.classList.add('work');

        const sub = cellSub(d, legal);
        const dots = (taskDays[k] || []).length;

        cell.innerHTML =
          `<div class="d-num">${d.getDate()}</div>` +
          `<div class="d-sub ${sub.cls}">${sub.text}</div>` +
          (dots ? `<div class="cal-dots">${'<i></i>'.repeat(Math.min(dots, 3))}${dots > 3 ? '<i class="many"></i>' : ''}</div>` : '');

        cell.addEventListener('click', () => {
          Cal._sel = k;
          this.render();
          if (window.AppBridge && AppBridge.openDay) AppBridge.openDay(k);
        });
        grid.appendChild(cell);
      }

      // 年度数据状态注记；未内置的年份异步拉取后重绘
      const st = Hol.yearStatus(this.y);
      $('calYearNote').textContent =
        st === 'ok'
          ? `${this.y} 年法定节假日与调休已加载 · 月历同时显示农历、节气与节日`
          : `${this.y} 年节假日安排尚未发布（通常在上一年的 11 月前后公布），发布后将自动在线获取`;
      if (st !== 'ok') {
        Hol.ensure(this.y).then((data) => { if (data) this.render(); });
      }
    }
  };
  window.Cal = Cal;
})();
