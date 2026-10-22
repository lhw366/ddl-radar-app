/* ============================================================
   DDL雷达 · 课表模块（v2.2）
   - 照片导入：调用原生 DdlOcr（ML Kit 中文识别）拿到带坐标的文本块
   - 网格还原：按坐标把文本块聚成「列=星期几 / 行=第几节」
   - 手动录入与修改：网格点击即可编辑
   - 与提醒联动：每门课在当天开始前生成一条提醒

   为什么要用坐标：课表是二维表，OCR 只给文字顺序无法判断
   「高等数学在星期二第 1-2 节」，必须靠包围盒落在哪一列哪一行。
   ============================================================ */
(function () {
  'use strict';

  // 默认节次时刻（北大标准，可由识别到的「课程时间」覆盖）
  var DEFAULT_SLOTS = [
    '08:00-08:50', '09:00-09:50', '10:10-11:00', '11:10-12:00',
    '13:00-13:50', '14:00-14:50', '15:10-16:00', '16:10-17:00',
    '17:10-18:00', '18:40-19:30', '19:40-20:30', '20:40-21:30'
  ];
  var DAY_CN = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'];

  function T() { return (window.S && window.S.timetable) || null; }

  function ensureState() {
    if (!window.S) return null;
    if (!window.S.timetable) {
      window.S.timetable = { courses: [], slots: DEFAULT_SLOTS.slice(), termStart: null };
    }
    var tt = window.S.timetable;
    if (!Array.isArray(tt.courses)) tt.courses = [];
    if (!Array.isArray(tt.slots) || tt.slots.length !== 12) tt.slots = DEFAULT_SLOTS.slice();
    return tt;
  }

  /* ---------------- 网格还原 ---------------- */

  /**
   * 从 OCR 块还原课程条目。
   * 步骤：
   *  1) 找出表头「星期一..星期日」的 x 坐标 → 得到 7 条列边界
   *  2) 找出「第一节..第十二节」的 y 坐标 → 得到 12 条行边界
   *  3) 其余文本块按中心点落格；同一格内多行合并成一门课
   *  4) 从合并文本里解析：课程名（首行）、教室（括号内）、单双周
   */
  function parseBlocks(blocks) {
    if (!Array.isArray(blocks) || !blocks.length) return { courses: [], slots: null };

    // ── 1) 找星期表头 ──
    var dayHeads = [];
    var dayRe = /^(星期|周)([一二三四五六日天])$/;
    var cnNum = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '日': 7, '天': 7 };
    blocks.forEach(function (b) {
      var t = norm(b.text);
      var m = t.match(dayRe);
      if (m) dayHeads.push({ day: cnNum[m[2]], cx: cxOf(b) });
    });
    // 去重（同一列可能有「星期一」和别的块）
    var dayMap = {};
    dayHeads.forEach(function (h) { if (!dayMap[h.day] || h.cx < dayMap[h.day]) dayMap[h.day] = h.cx; });
    var cols = Object.keys(dayMap).map(function (d) { return { day: +d, x: dayMap[d] }; })
      .sort(function (a, b) { return a.x - b.x; });

    // ── 2) 找节次行标签 ──
    var rowHeads = [];
    var jieRe = /^第?([一二三四五六七八九十]+)节?$/;
    blocks.forEach(function (b) {
      var t = norm(b.text);
      var m = t.match(jieRe);
      if (!m) return;
      var n = cnToNum(m[1]);
      if (n >= 1 && n <= 12) rowHeads.push({ n: n, y: cyOf(b) });
    });
    var rowMap = {};
    rowHeads.forEach(function (r) { if (rowMap[r.n] === undefined) rowMap[r.n] = r.y; });
    var rows = Object.keys(rowMap).map(function (n) { return { n: +n, y: rowMap[n] }; })
      .sort(function (a, b) { return a.y - b.y; });

    // ── 3) 解析「课程时间」表（若有），用于覆盖默认节次时刻 ──
    var slots = parseSlotTimes(blocks);

    // ── 4) 落格 ──
    // 若找不到表头/行标签，退化为「纯文本顺序解析」：只抽课程名+教室，
    // 星期与节次留空让用户手动补——总比什么都不给好。
    if (cols.length < 2 || rows.length < 2) {
      return { courses: fallbackParse(blocks), slots: slots, degraded: true };
    }

    var cells = {}; // key = day|rowIndex
    // ⚠️ 关键：按「行(line)」而不是按「块(block)」落格。
    // ML Kit 常把一个课程单元格整体识别成一个 block（内含课程名/教室/备注多行），
    // 而跨节课程（如第 5-8 节的实验课）的 block 包围盒会横跨多行。
    // 若按 block 中心点落格，整门课只会落进中间那一行 → 跨节信息全丢。
    // 按 line 落格能把每一行分配到它真正所在的行号，再靠「同名且行号连续」合并，
    // 才能正确还原 1-2 节、7-9 节这类跨节课程。
    blocks.forEach(function (b) {
      var lines = (b.lines && b.lines.length) ? b.lines : [{ text: b.text, cx: cxOf(b), cy: cyOf(b), top: b.top, bottom: b.bottom }];
      lines.forEach(function (ln) {
        var t = norm(ln.text);
        if (!t) return;
        if (dayRe.test(t) || jieRe.test(t)) return;      // 表头本身跳过
        if (/^(节次|课程时间|注意事项|备注$)/.test(t)) return;
        // 时间段本身不是课程（如「1018:40~19:30」这种被拆开的节次时间行）
        if (/^\d{1,2}\s*\d{1,2}[:：]\d{2}\s*[~～\-—至]\s*\d{1,2}[:：]\d{2}$/.test(t)) return;
        if (/^\d{1,2}[:：]\d{2}\s*[~～\-—至]/.test(t)) return;
        // 步行时间/课间说明等杂项
        if (/(min|分钟|课间|下课|上课时间[:：])/.test(t)) return;
        var x = cxOf(ln), y = cyOf(ln);
        var day = pickCol(cols, x, rows);
        if (!day) return;
        var rowIdx = pickRow(rows, y);
        if (rowIdx < 0) return;
        var key = day + '|' + rowIdx;
        if (!cells[key]) cells[key] = { day: day, row: rows[rowIdx].n, texts: [], top: 1e9, bottom: -1, spanBottom: -1 };
        // 同一格内避免重复推入完全相同的文本
        if (cells[key].texts.indexOf(t) < 0) cells[key].texts.push(t);
        var bb = ln.bottom || y;
        if (bb > cells[key].bottom) cells[key].bottom = bb;
        if ((ln.top || y) < cells[key].top) cells[key].top = ln.top || y;
        // 记录「所属 block 的纵向范围」：跨节课程的 block 包围盒覆盖整段，
        // 即便中间几行没有文字（合并单元格里文字常垂直居中），
        // 也能据此把课程延伸到真实的结束节次。
        if (b.bottom != null && b.bottom > cells[key].spanBottom) cells[key].spanBottom = b.bottom;
      });
    });

    // ── 5) 把同一列、相邻行的单元格合并成一门课（跨节课程） ──
    // 说明：按 line 落格后，一个跨节课程的不同行会分散到多个 row。
    // 合并判据见下方（同名 or 共享文本行 + 行号相邻）。
    var courses = [];
    var byDay = {};
    Object.keys(cells).forEach(function (k) {
      var c = cells[k];
      (byDay[c.day] = byDay[c.day] || []).push(c);
    });
    Object.keys(byDay).forEach(function (day) {
      var list = byDay[day].sort(function (a, b) { return a.row - b.row; });
      var merged = [];
      list.forEach(function (c) {
        var joined = c.texts.join(' ');
        var name = pickCourseName(c.texts);
        var prev = merged[merged.length - 1];
        // 「本格没有真正的课程名」= 只有教室/备注这类附属行。
        // 跨节课程按行切开后，第 2 行往往只有「（理教106）」（教室），
        // 若把它当成独立课程，一门课会被拆成好几条（实测 13 门变 27 条）。
        var nameIsMeaningful = !!name && !/^[（(]/.test(name)
          && !/^(备注|考试|每周|单周|双周)/.test(name);

        if (prev && !nameIsMeaningful && c.row === prev.end + 1) {
          // 附属行：并入上一门课
          prev.end = c.row;
          prev.raw += ' ' + joined;
          if (c.spanBottom > (prev.spanBottom || -1)) prev.spanBottom = c.spanBottom;
          c.texts.forEach(function (t) { if (prev.texts.indexOf(t) < 0) prev.texts.push(t); });
          return;
        }
        var sameName = prev && prev.name === name;
        var shared = prev && prev.texts && c.texts.some(function (t) {
          return prev.texts.indexOf(t) >= 0;
        });
        if (prev && c.row === prev.end + 1 && (sameName || shared)) {
          prev.end = c.row;
          prev.raw += ' ' + joined;
          if (c.spanBottom > (prev.spanBottom || -1)) prev.spanBottom = c.spanBottom;
          c.texts.forEach(function (t) { if (prev.texts.indexOf(t) < 0) prev.texts.push(t); });
        } else {
          merged.push({
            day: +day, start: c.row, end: c.row, name: name, raw: joined,
            texts: c.texts.slice(), spanBottom: c.spanBottom || -1
          });
        }
      });
      // 先记下「下一门课从第几节开始」，用于约束包围盒延伸不越界
      for (var mi = 0; mi < merged.length; mi++) {
        merged[mi].nextStart = (mi + 1 < merged.length) ? merged[mi + 1].start : 13;
      }
      merged.forEach(function (m) {
        // 按 block 包围盒的底边延伸结束节次：
        // 跨节课程的中间行往往没有文字（文字垂直居中在 block 里），
        // 只按「有文字的格」合并会少算节数（实测 5-8 节被算成 5-6 节）。
        // 这里看该课程最初那个 block 的底边落在哪一行，取更大者。
        if (m.spanBottom > 0) {
          for (var ri = 0; ri < rows.length; ri++) {
            // 行边界：当前行 y 与下一行 y 的中点
            var nextY = (ri + 1 < rows.length) ? (rows[ri].y + rows[ri + 1].y) / 2 : rows[ri].y + 1e6;
            if (m.spanBottom >= rows[ri].y && m.spanBottom < nextY) {
              // 约束：不能越过下一门课的起始节
              var cap = (m.nextStart || 13) - 1;
              var target = Math.min(rows[ri].n, cap);
              if (target > m.end) m.end = target;
              break;
            }
          }
        }
        var info = parseCourseInfo(m.raw, m.name);
        courses.push({
          id: 'tt' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
          name: info.name || m.name || '未命名课程',
          room: info.room,
          day: m.day,
          start: m.start,
          end: m.end,
          week: info.week,
          note: info.note,
          remindMin: 15
        });
      });
    });

    // 去重（同一门课可能被相邻格重复解析）
    var seen = {};
    courses = courses.filter(function (c) {
      var k = c.day + '|' + c.start + '|' + c.end + '|' + c.name;
      if (seen[k]) return false;
      seen[k] = 1;
      return true;
    });
    courses.sort(function (a, b) { return a.day - b.day || a.start - b.start; });
    return { courses: courses, slots: slots, degraded: false };
  }

  function cxOf(b) { return b.cx != null ? b.cx : ((b.left || 0) + (b.right || 0)) / 2; }
  function cyOf(b) { return b.cy != null ? b.cy : ((b.top || 0) + (b.bottom || 0)) / 2; }
  function norm(s) { return String(s == null ? '' : s).replace(/\s+/g, '').trim(); }

  function cnToNum(s) {
    if (!s) return 0;
    if (s === '十') return 10;
    if (s.length === 1) return ({ 一:1, 二:2, 三:3, 四:4, 五:5, 六:6, 七:7, 八:8, 九:9 })[s] || 0;
    var ten = s.indexOf('十');
    if (ten === 0) return 10 + (({ 一:1, 二:2, 三:3, 四:4, 五:5 })[s[1]] || 0);
    if (ten > 0) return (({ 一:1, 二:2, 三:3 })[s[0]] || 0) * 10 + (({ 一:1, 二:2, 三:3, 四:4 })[s[1]] || 0);
    return 0;
  }

  /**
   * 给定 x，找它落在哪一列。
   * 用「相邻列中点为边界」做区间判定，而不是「找最近列」——
   * 后者会把表外内容（如右下角的课程时间表、注意事事项）也拉到最近那一列，
   * 结果时间表数字被当成课程名录入（实测出现过「周5 第12节 | 1018:40~19:30」）。
   * @param rows 行坐标数组，用于额外限制 y 必须在表体内
   */
  function pickCol(cols, x, rows) {
    if (cols.length < 2) return cols.length ? cols[0].day : null;
    // 左边界：第一列中心减去半个列距；右边界：最后一列中心加半个列距
    var gap = Math.abs(cols[1].x - cols[0].x);
    var left = cols[0].x - gap / 2;
    var right = cols[cols.length - 1].x + gap / 2;
    if (x < left || x > right) return null;   // 表外
    for (var i = 0; i < cols.length - 1; i++) {
      var mid = (cols[i].x + cols[i + 1].x) / 2;
      if (x >= (i === 0 ? left : (cols[i - 1].x + cols[i].x) / 2) && x < mid) return cols[i].day;
    }
    return cols[cols.length - 1].day;
  }

  /** 给定 y，返回落在哪一行（返回索引） */
  function pickRow(rows, y) {
    if (!rows.length) return -1;
    if (y < rows[0].y) return 0;
    for (var i = 0; i < rows.length - 1; i++) {
      if (y >= rows[i].y && y < rows[i + 1].y) return i;
    }
    return rows.length - 1;
  }

  /**
   * 从多行文本里挑课程名。
   * 课表单元格的典型结构：
   *   第一行：课程名（可能含顿号但也可能含括号，如「高等数学（B）（一）」）
   *   第二行：（教室名）
   *   第三行：（备注：...）每周 / 单周 / 双周
   *   第四行：考试时间/考试方式
   * 不能简单取最长——「（备注：）每周」可能比课程名长。
   * 策略：优先取「不以括号开头、不含备注/考试/每周等关键词」的最长行。
   */
  function pickCourseName(texts) {
    var best = '';
    for (var i = 0; i < texts.length; i++) {
      var t = texts[i];
      if (!t) continue;
      if (/^[（(]/.test(t)) continue;                       // 以括号开头：教室或备注
      if (/^(备注|考试|上课|每周|单周|双周|考试方式|考试时间)/.test(t)) continue;
      if (/^\d+$/.test(t)) continue;
      if (/^[一二三四五六七八九十\d]+节/.test(t)) continue;
      if (t.length > best.length) best = t;
    }
    // 全被过滤掉时退回第一行里最长的（总比空好）
    if (!best) {
      for (var j = 0; j < texts.length; j++) {
        if (texts[j] && texts[j].length > best.length && !/^(备注|考试)/.test(texts[j])) best = texts[j];
      }
    }
    return best || (texts[0] || '');
  }

  /** 从合并文本里抽教室、单双周、备注 */
  function parseCourseInfo(raw, name) {
    var room = '';
    // 教室通常在中文括号或半角括号里，且含「教/楼/馆/区/机房」等
    var m = raw.match(/[（(]([^（()）]{1,24}?(?:教|楼|馆|室|机房|中心|厅|场|廊)[^（()）]{0,12})[）)]/);
    if (m) room = m[1].trim();
    if (!room) {
      var m2 = raw.match(/([一二三四五六七八九十\d]+教[一二三四五六七八九十\d]*|\S{0,6}楼\S{0,4})/);
      if (m2) room = m2[1];
    }
    if (/暂无上课教室/.test(room)) room = '';

    var week = 'all';
    if (/单周/.test(raw)) week = 'odd';
    else if (/双周/.test(raw)) week = 'even';

    // 备注：抓「备注：...」片段
    var note = '';
    var mn = raw.match(/备注[:：]?\s*([^）)]{0,60})/);
    if (mn) note = mn[1].trim();
    return { name: name, room: room, week: week, note: note };
  }

  /**
   * 解析「课程时间」表。
   * 实际格式多样：`1 8:00~8:50`、`1 8:00-8:50`、`第1节 08:00~08:50`
   * 早前的正则要求「节号:时间段」严格三段式，且把「1」当成小时解析，
   * 完全匹配不上真实数据 —— 导致课程时间表永远解析失败、退回默认时刻，
   * 对非北大作息（比如 8:00 变 8:30 开学）的学校就会整体错位。
   */
  function parseSlotTimes(blocks) {
    var slots = [];
    var found = 0;
    blocks.forEach(function (b) {
      var lines = b.lines || [];
      lines.forEach(function (l) {
        // ⚠️ 这里不能用 norm()：norm 会去掉空格，把「1 8:00~8:50」
        // 压成「18:00~8:50」，节号和时间粘连后就再也分不开（实测就是这个 bug）。
        // 用原文 + 保留分隔符的正则。
        var raw = String(l.text == null ? '' : l.text).trim();
        if (!raw) return;
        var m = raw.match(/^(?:第)?(\d{1,2})(?:节)?[\s：:]+(\d{1,2})[:：](\d{2})\s*[~～\-—至]\s*(\d{1,2})[:：](\d{2})/);
        if (!m) return;
        var idx = parseInt(m[1], 10) - 1;
        if (idx < 0 || idx > 11) return;
        slots[idx] = pad2(m[2]) + ':' + m[3] + '-' + pad2(m[4]) + ':' + m[5];
        found++;
      });
    });
    // 至少要认出 8 节才算有效（避免误把别的数字当节次）
    if (found < 8) return null;
    var out = [];
    for (var i = 0; i < 12; i++) out.push(slots[i] || DEFAULT_SLOTS[i]);
    return out;
  }
  function pad2(s) { s = String(s); return s.length < 2 ? '0' + s : s; }

  /** 找不到表头时的退化解析：只抽「看起来像课程」的行 */
  function fallbackParse(blocks) {
    var out = [];
    blocks.forEach(function (b) {
      var texts = (b.lines || []).map(function (l) { return norm(l.text); });
      var name = pickCourseName(texts);
      if (!name || name.length < 2) return;
      if (/^(节次|星期|课程时间|注意事项)/.test(name)) return;
      var info = parseCourseInfo(texts.join(' '), name);
      out.push({
        id: 'tt' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
        name: info.name, room: info.room, day: 1, start: 1, end: 2,
        week: info.week, note: info.note, remindMin: 15, needFix: true
      });
    });
    return out;
  }

  /* ---------------- 渲染 ---------------- */

  function render() {
    var tt = ensureState();
    var grid = document.getElementById('ttGrid');
    var empty = document.getElementById('ttEmpty');
    if (!grid) return;
    if (!tt || !tt.courses.length) {
      grid.innerHTML = '';
      if (empty) empty.classList.remove('hidden');
      return;
    }
    if (empty) empty.classList.add('hidden');

    var html = '<div class="tt-corner"></div>';
    for (var d = 1; d <= 7; d++) html += '<div class="tt-dayhead">' + DAY_CN[d] + '</div>';
    for (var r = 1; r <= 12; r++) {
      html += '<div class="tt-rowhead">' + r + '</div>';
      for (var dd = 1; dd <= 7; dd++) {
        var cs = tt.courses.filter(function (c) { return c.day === dd && r >= c.start && r <= c.end; });
        if (!cs.length) { html += '<div class="tt-cell"></div>'; continue; }
        var c = cs[0];
        if (r === c.start) {
          var span = Math.max(1, c.end - c.start + 1);
          var wk = c.week === 'odd' ? ' · 单周' : c.week === 'even' ? ' · 双周' : '';
          html += '<div class="tt-cell has-course" style="grid-row:span ' + span + '" data-cid="' + c.id + '">'
            + '<div class="tt-cname">' + esc(c.name) + '</div>'
            + (c.room ? '<div class="tt-croom">' + esc(c.room) + '</div>' : '')
            + (wk ? '<div class="tt-cweek">' + wk.replace(' · ', '') + '</div>' : '')
            + '</div>';
        }
      }
    }
    grid.innerHTML = html;
    grid.querySelectorAll('.tt-cell.has-course').forEach(function (el) {
      el.addEventListener('click', function () { openEditor(el.dataset.cid); });
    });
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
    });
  }

  /* ---------------- 编辑器 ---------------- */

  var editingId = null;
  function openEditor(id) {
    var tt = ensureState();
    var c = id ? tt.courses.find(function (x) { return x.id === id; }) : null;
    editingId = id || null;
    document.getElementById('ttEdTitle').textContent = c ? '编辑课程' : '添加课程';
    document.getElementById('ttEdName').value = c ? c.name : '';
    document.getElementById('ttEdRoom').value = c ? (c.room || '') : '';
    document.getElementById('ttEdDay').value = String(c ? c.day : 1);
    document.getElementById('ttEdWeek').value = c ? (c.week || 'all') : 'all';
    document.getElementById('ttEdStart').value = c ? c.start : 1;
    document.getElementById('ttEdEnd').value = c ? c.end : 2;
    document.getElementById('ttEdNote').value = c ? (c.note || '') : '';
    document.getElementById('ttEdDel').style.display = c ? '' : 'none';
    document.getElementById('ttEditor').classList.remove('hidden');
  }
  function closeEditor() {
    document.getElementById('ttEditor').classList.add('hidden');
    editingId = null;
  }
  function saveEditor() {
    var tt = ensureState();
    var name = document.getElementById('ttEdName').value.trim();
    if (!name) { if (window.toast) toast('⚠️ 课程名不能为空', ''); return; }
    var day = +document.getElementById('ttEdDay').value || 1;
    var start = Math.max(1, Math.min(12, +document.getElementById('ttEdStart').value || 1));
    var end = Math.max(start, Math.min(12, +document.getElementById('ttEdEnd').value || start));
    var data = {
      name: name,
      room: document.getElementById('ttEdRoom').value.trim(),
      day: day, start: start, end: end,
      week: document.getElementById('ttEdWeek').value,
      note: document.getElementById('ttEdNote').value.trim(),
      remindMin: 15
    };
    if (editingId) {
      var c = tt.courses.find(function (x) { return x.id === editingId; });
      if (c) Object.assign(c, data);
    } else {
      data.id = 'tt' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
      tt.courses.push(data);
    }
    if (window.save) save();
    render();
    closeEditor();
    if (window.NativeNotify && window.NativeNotify.syncTimetable) {
      try { window.NativeNotify.syncTimetable(window.S); } catch (e) {}
    }
    if (window.toast) toast('📚 已保存', name + ' · ' + DAY_CN[day] + ' 第' + start + '-' + end + '节');
  }
  function delCourse() {
    if (!editingId) return;
    var tt = ensureState();
    tt.courses = tt.courses.filter(function (x) { return x.id !== editingId; });
    if (window.save) save();
    render();
    closeEditor();
    if (window.toast) toast('🗑️ 已删除课程', '');
  }

  /* ---------------- OCR 导入流程 ---------------- */

  var pendingCourses = null;

  async function importFromImage(source) {
    var status = document.getElementById('ttOcrStatus');
    function say(t) { if (status) status.textContent = t; }
    var ocr = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.DdlOcr;
    if (!ocr) {
      say('当前环境不支持照片识别（需要安装 APK 版）');
      return;
    }
    say('🔍 正在识别文字…');
    try {
      var payload = {};
      if (typeof source === 'string' && source.indexOf('data:') === 0) payload.base64 = source;
      else if (typeof source === 'string') payload.path = source;
      else if (source && source.base64) payload.base64 = source.base64;
      var res = await ocr.recognize(payload);
      say('📐 正在还原课表网格…');
      var parsed = parseBlocks(res.blocks || []);
      if (!parsed.courses.length) {
        say('❌ 没识别出课程。可能是图片太模糊，或不是课表截图。可改用「手动添加」。');
        return;
      }
      // 若识别到了课程时间表，覆盖默认节次
      if (parsed.slots) {
        var tt0 = ensureState();
        tt0.slots = parsed.slots;
        if (window.save) save();
      }
      pendingCourses = parsed.courses;
      renderReview(parsed.courses, parsed.degraded);
      say('✅ 识别到 ' + parsed.courses.length + ' 门课，请核对后确认导入');
    } catch (e) {
      say('❌ 识别失败：' + (e && e.message ? e.message : e));
    }
  }

  function renderReview(courses, degraded) {
    var box = document.getElementById('ttReview');
    var list = document.getElementById('ttReviewList');
    if (!box || !list) return;
    var html = '';
    if (degraded) {
      html += '<div class="tt-warn">⚠️ 没能定位到课表的行列结构，已按文字顺序提取课程名。'
        + '下面的「星期/节次」需要你手动修正。</div>';
    }
    courses.forEach(function (c, i) {
      html += '<div class="tt-rv-row" data-i="' + i + '">'
        + '<input class="tt-rv-name" value="' + esc(c.name) + '" placeholder="课程名" />'
        + '<input class="tt-rv-room" value="' + esc(c.room || '') + '" placeholder="教室" />'
        + '<div class="tt-rv-line">'
        + '<select class="tt-rv-day">' + [1,2,3,4,5,6,7].map(function (d) {
            return '<option value="' + d + '"' + (c.day === d ? ' selected' : '') + '>' + DAY_CN[d] + '</option>';
          }).join('') + '</select>'
        + '<input class="tt-rv-start" type="number" min="1" max="12" value="' + c.start + '" />'
        + '<span>-</span>'
        + '<input class="tt-rv-end" type="number" min="1" max="12" value="' + c.end + '" />'
        + '<select class="tt-rv-week">'
        + '<option value="all"' + (c.week === 'all' ? ' selected' : '') + '>每周</option>'
        + '<option value="odd"' + (c.week === 'odd' ? ' selected' : '') + '>单周</option>'
        + '<option value="even"' + (c.week === 'even' ? ' selected' : '') + '>双周</option>'
        + '</select>'
        + '<button class="tt-rv-del btn-ghost-sm" data-del="' + i + '">删</button>'
        + '</div></div>';
    });
    list.innerHTML = html;
    box.classList.remove('hidden');
    list.querySelectorAll('[data-del]').forEach(function (b) {
      b.addEventListener('click', function () {
        var i = +b.dataset.del;
        pendingCourses.splice(i, 1);
        renderReview(pendingCourses, degraded);
      });
    });
  }

  function confirmImport() {
    if (!pendingCourses || !pendingCourses.length) return;
    var tt = ensureState();
    var list = document.getElementById('ttReviewList');
    var rows = list.querySelectorAll('.tt-rv-row');
    var added = 0;
    rows.forEach(function (row, i) {
      var c = pendingCourses[i];
      if (!c) return;
      var name = row.querySelector('.tt-rv-name').value.trim();
      if (!name) return;
      var start = Math.max(1, Math.min(12, +row.querySelector('.tt-rv-start').value || 1));
      var end = Math.max(start, Math.min(12, +row.querySelector('.tt-rv-end').value || start));
      tt.courses.push({
        id: 'tt' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
        name: name,
        room: row.querySelector('.tt-rv-room').value.trim(),
        day: +row.querySelector('.tt-rv-day').value || 1,
        start: start, end: end,
        week: row.querySelector('.tt-rv-week').value,
        note: c.note || '',
        remindMin: 15
      });
      added++;
    });
    if (window.save) save();
    pendingCourses = null;
    document.getElementById('ttReview').classList.add('hidden');
    document.getElementById('ttImportHint').classList.add('hidden');
    document.getElementById('ttOcrStatus').textContent = '';
    render();
    if (window.NativeNotify && window.NativeNotify.syncTimetable) {
      try { window.NativeNotify.syncTimetable(window.S); } catch (e) {}
    }
    if (window.toast) toast('📚 课表已导入', '共 ' + added + ' 门课，已按上课时间生成提醒');
  }

  /* ---------------- 绑定 ---------------- */

  function bind() {
    var importBtn = document.getElementById('ttImportBtn');
    if (importBtn) importBtn.addEventListener('click', function () {
      document.getElementById('ttImportHint').classList.toggle('hidden');
    });
    var pasteBtn = document.getElementById('ttPasteBtn');
    if (pasteBtn) pasteBtn.addEventListener('click', async function () {
      try {
        var items = await navigator.clipboard.read();
        for (var i = 0; i < items.length; i++) {
          var types = items[i].types || [];
          for (var j = 0; j < types.length; j++) {
            if (types[j].indexOf('image') === 0) {
              var blob = await items[i].getType(types[j]);
              var b64 = await blobToDataUrl(blob);
              await importFromImage(b64);
              return;
            }
          }
        }
        document.getElementById('ttOcrStatus').textContent = '剪贴板里没有图片，请先截图再点这里。';
      } catch (e) {
        document.getElementById('ttOcrStatus').textContent =
          '读剪贴板失败（可能需要授权）。可改用「选择图片」。';
      }
    });
    var fileBtn = document.getElementById('ttFileBtn');
    var fileInput = document.getElementById('ttFileInput');
    if (fileBtn && fileInput) {
      fileBtn.addEventListener('click', function () { fileInput.click(); });
      fileInput.addEventListener('change', async function () {
        var f = fileInput.files && fileInput.files[0];
        if (!f) return;
        var b64 = await blobToDataUrl(f);
        await importFromImage(b64);
        fileInput.value = '';
      });
    }
    var cancelBtn = document.getElementById('ttOcrCancel');
    if (cancelBtn) cancelBtn.addEventListener('click', function () {
      document.getElementById('ttImportHint').classList.add('hidden');
      document.getElementById('ttOcrStatus').textContent = '';
    });
    var addBtn = document.getElementById('ttAddBtn');
    if (addBtn) addBtn.addEventListener('click', function () { openEditor(null); });
    var clearBtn = document.getElementById('ttClearBtn');
    if (clearBtn) clearBtn.addEventListener('click', function () {
      if (!confirm('确定清空全部课程吗？')) return;
      var tt = ensureState();
      tt.courses = [];
      if (window.save) save();
      render();
    });
    var confirmBtn = document.getElementById('ttConfirmBtn');
    if (confirmBtn) confirmBtn.addEventListener('click', confirmImport);
    var reviewCancel = document.getElementById('ttReviewCancel');
    if (reviewCancel) reviewCancel.addEventListener('click', function () {
      pendingCourses = null;
      document.getElementById('ttReview').classList.add('hidden');
    });
    var edClose = document.getElementById('ttEdClose');
    if (edClose) edClose.addEventListener('click', closeEditor);
    var edSave = document.getElementById('ttEdSave');
    if (edSave) edSave.addEventListener('click', saveEditor);
    var edDel = document.getElementById('ttEdDel');
    if (edDel) edDel.addEventListener('click', delCourse);
  }

  function blobToDataUrl(blob) {
    return new Promise(function (resolve, reject) {
      var r = new FileReader();
      r.onload = function () { resolve(r.result); };
      r.onerror = reject;
      r.readAsDataURL(blob);
    });
  }

  /* ---------------- 对外接口 ---------------- */

  window.Timetable = {
    render: render,
    ensureState: ensureState,
    /** 生成某天的课程提醒点：[{key, at, title, body}] */
    planFor: function (dayTs) {
      var tt = ensureState();
      if (!tt || !tt.courses.length) return [];
      var d = new Date(dayTs);
      var jsDay = d.getDay();          // 0=周日
      var myDay = jsDay === 0 ? 7 : jsDay;
      var out = [];
      tt.courses.forEach(function (c) {
        if (c.day !== myDay) return;
        var slot = tt.slots[c.start - 1] || DEFAULT_SLOTS[c.start - 1];
        var m = String(slot).match(/(\d{1,2}):(\d{2})/);
        if (!m) return;
        var at = new Date(d.getFullYear(), d.getMonth(), d.getDate(), +m[1], +m[2], 0).getTime();
        var remind = at - (c.remindMin || 15) * 60000;
        if (remind <= Date.now()) return;
        out.push({
          key: 'tt:' + c.id + ':' + at,
          at: remind,
          title: '📚 ' + (c.remindMin || 15) + ' 分钟后上课',
          body: c.name + (c.room ? ' · ' + c.room : '') + ' · 第' + c.start + '-' + c.end + '节 ' + slot,
          kind: 'daily'
        });
      });
      return out;
    }
  };

  window.addEventListener('DOMContentLoaded', function () {
    try { bind(); render(); } catch (e) { console.warn('课表模块初始化失败', e); }
  });
})();
