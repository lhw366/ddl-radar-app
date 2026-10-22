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
    pending: {},
    retryAfter: {},
    key: (y) => 'ddlr_holiday_' + y,

    getLocal(y) {
      if (this.mem[y]) return this.mem[y];
      if (window.HOLIDAY_BUILTIN && window.HOLIDAY_BUILTIN[y]) {
        this.mem[y] = window.HOLIDAY_BUILTIN[y];
        return this.mem[y];
      }
      try {
        const raw = localStorage.getItem(this.key(y));
        if (raw) {
          const data = JSON.parse(raw);
          if (data && Array.isArray(data.days)) { this.mem[y] = data; return data; }
        }
      } catch (e) { /* ignore */ }
      return null;
    },

    // 异步确保某年数据：内置 → 缓存 → CDN。返回 Promise<data|null>
    async ensure(y) {
      const local = this.getLocal(y);
      if (local) return local;
      if (this.pending[y]) return this.pending[y];
      if (this.retryAfter[y] > Date.now()) return null;
      const request = this.fetchYear(y);
      this.pending[y] = request;
      try { return await request; }
      finally { delete this.pending[y]; }
    },

    async fetchYear(y) {
      const urls = [
        `https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/${y}.json`,
        `https://fastly.jsdelivr.net/gh/NateScarlet/holiday-cn@master/${y}.json`
      ];
      for (const u of urls) {
        const controller = typeof AbortController === 'function' ? new AbortController() : null;
        const timeout = controller ? setTimeout(() => controller.abort(), 8000) : null;
        try {
          const res = await fetch(u, { cache: 'no-cache', signal: controller ? controller.signal : undefined });
          if (!res.ok) continue;
          const data = await res.json();
          if (data && Array.isArray(data.days)) {
            this.mem[y] = data;
            delete this.retryAfter[y];
            try { localStorage.setItem(this.key(y), JSON.stringify(data)); } catch (e) { /* ignore */ }
            return data;
          }
        } catch (e) { /* 离线或被拦截，尝试下一个源 */ }
        finally { if (timeout !== null) clearTimeout(timeout); }
      }
      this.retryAfter[y] = Date.now() + 5 * 60000;
      return null;
    },

    // 某天的法定状态： {name, off} | null
    day(ymdStr) {
      const y = +ymdStr.slice(0, 4);
      const data = this.getLocal(y);
      if (!data || !Array.isArray(data.days)) return null;
      const hit = data.days.find((d) => d && d.date === ymdStr);
      return hit ? { name: hit.name, off: hit.isOffDay } : null;
    },
    yearStatus(y) { return this.getLocal(y) ? 'ok' : this.retryAfter[y] > Date.now() ? 'unavailable' : 'loading'; }
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
      let lead = first.getDay() - 1; if (lead < 0) lead = 6; // 周一开头
      const start = new Date(this.y, this.m, 1 - lead);
      const todayStr = ymd(new Date());
      const selStr = Cal._sel || null;

      const tasks = (window.S && window.S.tasks) || [];
      const taskDays = {};
      for (const t of tasks) {
        if (!t || t.done || !t.dueAt || !Number.isFinite(new Date(t.dueAt).getTime())) continue;
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

        const subText = String(sub.text || '').replace(/[&<>"']/g, (ch) =>
          ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
        cell.innerHTML =
          `<div class="d-num">${d.getDate()}</div>` +
          `<div class="d-sub ${sub.cls}">${subText}</div>` +
          (dots ? `<div class="cal-dots">${'<i></i>'.repeat(Math.min(dots, 3))}${dots > 3 ? '<i class="many"></i>' : ''}</div>` : '');

        cell.addEventListener('click', () => {
          Cal._sel = k;
          this.render();
          if (window.AppBridge && AppBridge.openDay) AppBridge.openDay(k);
        });
        grid.appendChild(cell);
      }

      // 年度数据状态注记；未内置的年份异步拉取后重绘
      const year = this.y, month = this.m;
      const st = Hol.yearStatus(year);
      $('calYearNote').textContent =
        st === 'ok'
          ? `${this.y} 年法定节假日与调休已加载 · 月历同时显示农历、节气与节日`
          : st === 'unavailable'
            ? `${year} 年节假日数据暂不可用，将稍后重试 · 农历和事项仍可查看`
            : `正在获取 ${year} 年法定节假日与调休安排…`;
      if (st === 'loading') {
        Hol.ensure(year).then(() => {
          if (this.y === year && this.m === month) this.render();
        });
      }
    }
  };
  window.Cal = Cal;
})();
