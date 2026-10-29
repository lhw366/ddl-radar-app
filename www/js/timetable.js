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

  // 全新用户先使用通用作息；北大作息是可选模板，不会自动导入课程。
  var GENERIC_SLOTS = [
    '08:00-08:45', '08:55-09:40', '10:00-10:45', '10:55-11:40',
    '14:00-14:45', '14:55-15:40', '16:00-16:45', '16:55-17:40',
    '19:00-19:45', '19:55-20:40', '20:50-21:35', '21:40-22:25'
  ];
  var PKU_SLOTS = [
    '08:00-08:50', '09:00-09:50', '10:10-11:00', '11:10-12:00',
    '13:00-13:50', '14:00-14:50', '15:10-16:00', '16:10-17:00',
    '17:10-18:00', '18:40-19:30', '19:40-20:30', '20:40-21:30'
  ];
  var DEFAULT_SLOTS = GENERIC_SLOTS;
  var SCHOOL_PRESETS = { generic: GENERIC_SLOTS, pku: PKU_SLOTS };
  var DAY_CN = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'];

  function T() { return (window.S && window.S.timetable) || null; }

  function ensureState() {
    if (!window.S) return null;
    if (!window.S.timetable) {
      window.S.timetable = { courses: [], slots: DEFAULT_SLOTS.slice(), termStart: null, schoolPreset: 'generic' };
    }
    var tt = window.S.timetable;
    if (!Array.isArray(tt.courses)) tt.courses = [];
    if (!Array.isArray(tt.slots) || tt.slots.length < 1) tt.slots = DEFAULT_SLOTS.slice();
    tt.slots = tt.slots.slice(0, 20).map(function (slot, index) {
      if (slot == null) return null; // Unknown screenshot times must never become a template time.
      var clean = String(slot || '').trim();
      return /^([01]\d|2[0-3]):[0-5]\d-([01]\d|2[0-3]):[0-5]\d$/.test(clean)
        ? clean : (DEFAULT_SLOTS[index] || '');
    });
    if (!tt.schoolPreset) tt.schoolPreset = sameSlots(tt.slots, PKU_SLOTS) ? 'pku' :
      sameSlots(tt.slots, GENERIC_SLOTS) ? 'generic' : 'custom';
    return tt;
  }
  function sameSlots(a, b) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length
      && a.every(function (slot, i) { return slot === b[i]; });
  }
  function coursesOverlap(a, b) {
    if (!a || !b || +a.day !== +b.day) return false;
    var wa = a.week || 'all', wb = b.week || 'all';
    var sharesWeek = wa === 'all' || wb === 'all' || wa === wb;
    return sharesWeek && Math.max(a.start, b.start) <= Math.min(a.end, b.end);
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
  function parseBlocks(blocks, imageMeta) {
    if (!Array.isArray(blocks) || !blocks.length) return { courses: [], slots: null };

    // ── 1) 找星期表头 ──
    var dayHeads = [];
    var dayRe = /^(星期|周|呈期)([一二三四五六日天-])$/;
    var cnNum = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '日': 7, '天': 7 };
    var allLines = [];
    blocks.forEach(function (b) {
      allLines = allLines.concat(b.lines && b.lines.length ? b.lines : [b]);
    });
    allLines.forEach(function (b) {
      var t = norm(b.text);
      var m = t.match(dayRe);
      if (m) dayHeads.push({ day: m[2] === '-' ? 1 : cnNum[m[2]], cx: cxOf(b), top: b.top, bottom: b.bottom });
    });
    // Mobile timetables use a bare 一 / 二 / … / 日 header. Only accept an
    // aligned weekday sequence, so a lone Chinese numeral in a title is not a column.
    var compactLayout = false;
    if (dayHeads.length < 2) {
      var shortHeads = allLines.filter(function (line) { return /^[一二三四五六日天]$/.test(norm(line.text)); });
      var sequences = shortHeads.map(function (anchor) {
        return shortHeads.filter(function (line) {
          return Math.abs(cyOf(line) - cyOf(anchor)) <= Math.max(18, (anchor.bottom - anchor.top) || 0);
        }).sort(function (a, b) { return cxOf(a) - cxOf(b); });
      }).filter(function (sequence) {
        return sequence.length >= 5 && sequence.every(function (line, index) {
          return !index || cnNum[norm(line.text)] > cnNum[norm(sequence[index - 1].text)];
        });
      }).sort(function (a, b) { return cyOf(a[0]) - cyOf(b[0]); });
      if (sequences.length) {
        compactLayout = true;
        dayHeads = sequences[0].map(function (line) {
          return { day: cnNum[norm(line.text)], cx: cxOf(line), top: line.top, bottom: line.bottom };
        });
      }
    }
    // 去重（同一列可能有「星期一」和别的块）
    var dayMap = {};
    dayHeads.forEach(function (h) { if (!dayMap[h.day] || h.cx < dayMap[h.day]) dayMap[h.day] = h.cx; });
    var cols = Object.keys(dayMap).map(function (d) { return { day: +d, x: dayMap[d] }; })
      .sort(function (a, b) { return a.x - b.x; });
    if (compactLayout && cols.length >= 5) {
      var slope = (cols[cols.length - 1].x - cols[0].x) / (cols[cols.length - 1].day - cols[0].day);
      var origin = cols[0].x - cols[0].day * slope;
      cols = Array.from({ length: 7 }, function (_, i) { return { day: i + 1, x: origin + (i + 1) * slope }; });
    }

    // ── 2) 找节次行标签 ──
    var numericRows = numericRowHeads(allLines, cols, dayHeads);
    var rowHeads = numericRows.slice();
    // Chinese period labels are often split into separate blocks: "第一" / "节".
    // Read their geometry in the label gutter before falling back to text matching.
    if (cols.length >= 2) {
      var gutterRight = cols[0].x - (cols[1].x - cols[0].x) * 0.42;
      var pieces = allLines.filter(function (l) { return cxOf(l) < gutterRight; })
        .sort(function (a,b) { return cyOf(a)-cyOf(b); });
      var labels = pieces.filter(function (l) { return /^第[一二三四五六七八九十]/.test(norm(l.text).replace(/^[|丨]+/,'')); });
      if (labels.length >= 4 && labels.length <= 20) {
        labels.forEach(function (label,index) {
          var below = pieces.find(function (p) {
            var delta = cyOf(p)-cyOf(label);
            return delta > 0 && delta < Math.max(35, (label.bottom-label.top)*2)
              && /^[一二三四五六七八九十]*[节书共]$/.test(norm(p.text));
          });
          var text = norm(label.text).replace(/^[|丨]+/,'') + (below ? norm(below.text) : '');
          var match = text.match(/^第([一二三四五六七八九十]+)/);
          var n = match ? cnToNum(match[1]) : index+1;
          // With all twelve ordered labels present, a missed 一/二 in the
          // final wrapped labels must not collapse lessons 11/12 into 10.
          if (labels.length >= 4) n = index+1;
          rowHeads.push({n:n,y:below ? (label.top+below.bottom)/2 : cyOf(label)});
        });
      }
    }
    var jieRe = /^第?([一二三四五六七八九十]{1,3})(?:大)?节?$/;
    blocks.forEach(function (b) {
      var candidates = [b.text];
      if (b.lines && b.lines.length > 1) {
        candidates.push(b.lines.map(function (line) { return line.text || ''; }).join(''));
      }
      candidates.forEach(function (value) {
        var t = norm(value);
        var m = t.match(jieRe);
        if (!m) return;
        // 单独一个「四」不能算行标签；必须带「第」前缀或「节」后缀。
        if (!/^第/.test(t) && !/节$/.test(t)) return;
        var n = cnToNum(m[1]);
        if (n >= 1 && n <= 20) rowHeads.push({ n: n, y: cyOf(b) });
      });
    });
    var rowMap = {};
    rowHeads.forEach(function (r) { if (rowMap[r.n] === undefined) rowMap[r.n] = r.y; });
    var rows = Object.keys(rowMap).map(function (n) { return { n: +n, y: rowMap[n] }; })
      .sort(function (a, b) { return a.y - b.y; });
    if (rows.length >= 4) {
      var complete = [];
      for (var number=rows[0].n;number<=rows[rows.length-1].n;number++) {
        if (rowMap[number] !== undefined) complete.push({n:number,y:rowMap[number]});
        else {
          var before=rows.filter(function(r){return r.n<number;}).pop();
          var after=rows.find(function(r){return r.n>number;});
          if(before && after) complete.push({n:number,y:before.y+(after.y-before.y)*(number-before.n)/(after.n-before.n)});
        }
      }
      rows=complete;
    }
    var rowsInferred = false;
    if (rows.length < 8 && numericRows.length < 4 && cols.length >= 2) {
      var inferred = inferRows(blocks, dayHeads, imageMeta);
      if (inferred.length >= 2) {
        rows = inferred;
        rowsInferred = true;
      }
    }

    // ── 3) 解析「课程时间」表（若有），用于覆盖默认节次时刻 ──
    var slots = parseSlotTimes(blocks, numericRows);

    var raster = parseRasterCards(blocks, cols, rows, compactLayout);
    if (raster.length && numericRows.length >= 4) return { courses: raster, slots: slots, degraded: false, rowsInferred: rowsInferred, rasterBounds: true };

    if (compactLayout) blocks = compactCourseBlocks(blocks, cols, rows);

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
    // blockId 用于后续判断「这两行是否来自同一个 OCR 块」——同一块内的行
    // 必然属于同一门课，这比「行号连续」更可靠（中间行可能没有文字）。
    blocks.forEach(function (b, bi) {
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
        var x = cxOf(ln);
        var rowCentreGap = rows.length > 1 ? Math.abs(rows[1].y - rows[0].y) : 0;
        // Text usually starts inside its cell but a long wrapped line can cross
        // the lower border. Assign by its top with a small tolerance so room
        // and note lines stay with the course they were printed under.
        var y = rowsInferred && ln.top != null ? ln.top - rowCentreGap * 0.05 : cyOf(ln);
        if (y < rows[0].y - rowCentreGap * 0.5) return;
        if (y > rows[rows.length-1].y + (rows[rows.length-1].y-rows[rows.length-2].y)/2) return;
        var day = pickCol(cols, x, rows);
        if (!day) return;
        var rowIdx = pickRow(rows, y);
        if (rowIdx < 0) return;
        var key = day + '|' + rowIdx;
        if (!cells[key]) cells[key] = {
          day: day, row: rows[rowIdx].n, texts: [], top: 1e9, bottom: -1,
          spanBottom: -1, blockId: bi, blockIds: {}, byBlock: {}, byBlockBounds: {}
        };
        cells[key].blockIds[bi] = 1;
        // 按 block 分别记文本：同一格里「单周课」和「双周课」是两个 block，
        // 各自有独立的课名/教室，混在一起会让 pickCourseName 只挑出一个。
        (cells[key].byBlock[bi] = cells[key].byBlock[bi] || []).push(t);
        var blockBounds = cells[key].byBlockBounds[bi] || { top: 1e9, bottom: -1 };
        blockBounds.top = Math.min(blockBounds.top, ln.top != null ? ln.top : y);
        blockBounds.bottom = Math.max(blockBounds.bottom, ln.bottom != null ? ln.bottom : y);
        cells[key].byBlockBounds[bi] = blockBounds;
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
    // 合并判据见下方（同 block / 同名 / 共享文本行 + 行号相邻）。
    // ⚠️ 但「同一格里来自多个不同 block」的情况必须拆开：
    // 典型是「单周课」和「双周课」共用同一时段，OCR 会给出两个独立 block。
    // 若按格合并，第二门课会被吞掉（实测只识别出单周课）。
    var courses = [];
    var byDay = {};
    Object.keys(cells).forEach(function (k) {
      var c = cells[k];
      // 若这一格里混了多个 block 的文本（单周课+双周课同格），
      // 按 block 拆成多条，避免后续按格合并把第二门课吞掉。
      var bIds = Object.keys(c.blockIds || {});
      if (bIds.length > 1) {
        var parts = bIds.map(function (bid) {
          var texts = ((c.byBlock && c.byBlock[bid]) || []).slice();
          var bounds = (c.byBlockBounds && c.byBlockBounds[bid]) || { top: c.top, bottom: c.bottom };
          var name = pickCourseName(texts);
          var meaningful = isMeaningfulCourseName(name);
          return {
            blockId: +bid,
            texts: texts,
            name: name,
            meaningful: meaningful,
            top: bounds.top,
            bottom: bounds.bottom
          };
        });
        var anchors = parts.filter(function (part) { return part.meaningful; });
        if (anchors.length > 1) {
          var groups = anchors.map(function (anchor) {
            return {
              day: c.day, row: c.row, top: anchor.top, bottom: anchor.bottom,
              spanBottom: anchor.bottom, blockId: anchor.blockId, texts: []
            };
          });
          parts.forEach(function (part) {
            var bestIndex = 0, bestDistance = Number.MAX_VALUE;
            anchors.forEach(function (anchor, index) {
              var distance = Math.abs((part.top + part.bottom) / 2 - (anchor.top + anchor.bottom) / 2);
              if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
              }
            });
            var group = groups[bestIndex];
            group.texts = group.texts.concat(part.texts);
            group.top = Math.min(group.top, part.top);
            group.bottom = Math.max(group.bottom, part.bottom);
          });
          groups.forEach(function (group) {
            (byDay[c.day] = byDay[c.day] || []).push(group);
          });
        } else {
          // All fields belong to one course. Splitting by block made room and
          // note lines become separate bogus courses on real ML Kit output.
          var allTexts = [];
          parts.forEach(function (part) {
            part.texts.forEach(function (text) {
              if (allTexts.indexOf(text) < 0) allTexts.push(text);
            });
          });
          (byDay[c.day] = byDay[c.day] || []).push({
            day: c.day, row: c.row, top: c.top, bottom: c.bottom,
            // Only the text block that owns the course title is trusted for
            // merged-cell height. Long wrapped notes may cross a row border.
            spanBottom: anchors.length === 1 ? anchors[0].bottom : -1,
            blockId: anchors.length === 1 ? anchors[0].blockId : null,
            texts: allTexts
          });
        }
      } else {
        (byDay[c.day] = byDay[c.day] || []).push(c);
      }
    });
    Object.keys(byDay).forEach(function (day) {
      var list = byDay[day].sort(function (a, b) { return a.row - b.row; });
      var merged = [];
      list.forEach(function (c) {
        var joined = c.texts.join(' ');
        var name = pickCourseName(c.texts);
        var prev = merged[merged.length - 1];
        // 「本格没有真正的课程名」= 只有教室/备注这类附属行。
        var nameIsMeaningful = isMeaningfulCourseName(name);
        // 行号是否「够近」：中间行可能没有文字（合并单元格里文字垂直居中），
        // 所以不能要求严格 +1。同一 block 切出来的行天然属于同一门课。
        var sameBlock = prev && c.blockId != null && prev.blockId === c.blockId;
        var adjacent = prev && c.row > prev.end && c.row <= prev.end + 3;

        if (prev && !nameIsMeaningful && (sameBlock || adjacent)) {
          // 附属行：并入上一门课
          // A wrapped note can cross into the next visual row; auxiliary text
          // must not turn a 1-period course into a multi-period course.
          if (sameBlock) prev.end = Math.max(prev.end, c.row);
          prev.raw += ' ' + joined;
          if (sameBlock && c.spanBottom > (prev.spanBottom || -1)) prev.spanBottom = c.spanBottom;
          c.texts.forEach(function (t) { if (prev.texts.indexOf(t) < 0) prev.texts.push(t); });
          return;
        }
        var sameName = prev && similarCourseName(prev.name, name)
          && extractWeek(prev.texts, prev.raw) === extractWeek(c.texts, joined);
        if (sameName && name.length > prev.name.length) prev.name = name;
        if (prev && (sameBlock || ((adjacent || c.row === prev.end) && sameName))) {
          prev.end = Math.max(prev.end, c.row);
          prev.raw += ' ' + joined;
          if (c.spanBottom > (prev.spanBottom || -1)) prev.spanBottom = c.spanBottom;
          c.texts.forEach(function (t) { if (prev.texts.indexOf(t) < 0) prev.texts.push(t); });
        } else {
          merged.push({
            day: +day, start: c.row, end: c.row, name: name, raw: joined,
            texts: c.texts.slice(), spanBottom: c.spanBottom || -1,
            blockId: c.blockId
          });
        }
      });
      // 先记下「下一门课从第几节开始」，用于约束包围盒延伸不越界
      for (var mi = 0; mi < merged.length; mi++) {
        merged[mi].nextStart = (mi + 1 < merged.length) ? merged[mi + 1].start : rows[rows.length - 1].n + 1;
      }
      merged.forEach(function (m) {
        // 按 block 包围盒的底边延伸结束节次：
        // 跨节课程的中间行往往没有文字（文字垂直居中在 block 里），
        // 只按「有文字的格」合并会少算节数（实测 5-8 节被算成 5-6 节）。
        // 这里看该课程最初那个 block 的底边落在哪一行，取更大者。
        if (m.spanBottom > 0) {
          var spanIndex = pickRow(rows, m.spanBottom);
          // 约束：不能越过下一门课的起始节
          var cap = (m.nextStart || rows[rows.length - 1].n + 1) - 1;
          var target = Math.min(rows[spanIndex].n, cap);
          if (target > m.end) m.end = target;
        }
        var info = parseCourseInfo(m.raw, consensusName(m.texts, m.name), m.texts);
        if (!info.name) return;
        var variants = m.texts.filter(function(t){return isMeaningfulCourseName(t) && similarCourseName(t,m.name);});
        var needsReview = new Set(variants).size > 1 || /层[-－]/.test(info.room);
        courses.push({
          id: 'tt' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
          name: info.name || m.name || '未命名课程',
          room: info.room,
          day: m.day,
          start: m.start,
          end: m.end,
          week: info.week,
          note: info.note,
          needsReview: needsReview,
          remindMin: 15
        });
      });
    });

    // 去重（同一门课可能被相邻格重复解析）
    var seen = {};
    courses = courses.filter(function (c) {
      if (!isMeaningfulCourseName(c.name)) return false;
      var k = c.day + '|' + c.start + '|' + c.end + '|' + c.name + '|' + c.room + '|' + c.week;
      if (seen[k]) return false;
      seen[k] = 1;
      return true;
    });
    courses.sort(function (a, b) { return a.day - b.day || a.start - b.start; });
    return { courses: courses, slots: slots, degraded: false, rowsInferred: rowsInferred };
  }

  function numericRowHeads(lines, cols, dayHeads) {
    if (cols.length < 2) return [];
    var gutterRight = cols[0].x - (cols[1].x - cols[0].x) * 0.42;
    var headerBottom = Math.max.apply(null, dayHeads.map(function (head) { return head.bottom || 0; }));
    var gutter = lines.map(function (line) {
      var prefix = String(line.text).match(/^(\d{1,2}[:：]\d{2})\s+.+/);
      return prefix && line.left < gutterRight ? Object.assign({}, line, { text: prefix[1], cx: line.left + 24 }) : line;
    }).filter(function (line) { return cxOf(line) < gutterRight && cyOf(line) > headerBottom; })
      .sort(function (a, b) { return cyOf(a) - cyOf(b); });
    var allTimes = gutter.filter(function (line) { return /^(?:[01]?\d|2[0-3])[:：][0-5]\d$/.test(norm(line.text)); });
    var labels = gutter.filter(function (line) {
      return /^(?:第)?(?:[1-9]|1\d|20)(?:节)?$/.test(norm(line.text))
        && (!allTimes.length || cyOf(line) > cyOf(allTimes[0]) - 100);
    });
    var result = labels.map(function (label, index) {
      var following = gutter.filter(function (line) {
        return cyOf(line) > cyOf(label) && (!labels[index + 1] || cyOf(line) < cyOf(labels[index + 1]));
      });
      var times = following.filter(function (line) { return /^(?:[01]?\d|2[0-3])[:：][0-5]\d$/.test(norm(line.text)); });
      var slot = times.length === 2 ? times.map(function (line) {
        var parts = norm(line.text).replace('：', ':').split(':');
        return pad2(parts[0]) + ':' + parts[1];
      }).join('-') : null;
      return {
        n: +norm(label.text).replace(/第|节/g, ''),
        y: slot ? ((label.top == null ? cyOf(label) : label.top) + times[1].bottom) / 2 : cyOf(label),
        slot: slot
      };
    });
    var fits = [];
    result.forEach(function (a, i) { result.slice(i + 1).forEach(function (b) {
      var step = (b.y - a.y) / (b.n - a.n);
      if (b.n <= a.n || step < 15) return;
      var origin = a.y - step * a.n;
      var votes = result.filter(function (r) { return Math.abs(r.y - origin - step * r.n) < step * .22; }).length;
      fits.push({ origin: origin, step: step, votes: votes });
    }); });
    fits.sort(function (a, b) { return b.votes - a.votes; });
    if (fits.length && fits[0].votes >= 3) {
      var fit = fits[0];
      result.forEach(function (r) {
        var inferred = Math.round((r.y - fit.origin) / fit.step);
        if (inferred >= 1 && inferred <= 20 && Math.abs(r.y - fit.origin - inferred * fit.step) < fit.step * .22) r.n = inferred;
      });
      var last = Math.max.apply(null, result.map(function (r) { return r.n; }));
      for (var n = 1; n <= last; n++) {
        var row = result.find(function (r) { return r.n === n; });
        if (!row) { row = { n: n, y: fit.origin + fit.step * n }; result.push(row); }
        var near = allTimes.filter(function (line) { return cyOf(line) > row.y - fit.step * .15 && cyOf(line) < row.y + fit.step * .4; });
        if (near.length === 2) row.slot = near.map(function (line) {
          var parts = norm(line.text).replace('：', ':').split(':'); return pad2(parts[0]) + ':' + parts[1];
        }).join('-');
      }
    }
    return result;
  }

  // On narrow mobile cards, a course title is wrapped into several short
  // lines, and @ marks the start of the separately wrapped classroom field.
  function compactCourseBlocks(blocks, cols, rows) {
    return blocks.map(function (block) {
      var lines = (block.lines && block.lines.length ? block.lines : [block]).slice()
        .sort(function (a, b) { return cyOf(a) - cyOf(b); });
      if (!lines.length || !rows.length || !pickCol(cols, cxOf(block), rows)
          || cyOf(lines[0]) < rows[0].y - (rows[1].y - rows[0].y) / 2) return block;
      var roomIndex = lines.findIndex(function (line) { return /^[@＠]/.test(norm(line.text)); });
      var titleLines = (roomIndex < 0 ? lines : lines.slice(0, roomIndex)).filter(function (line) {
        return !/^(?:非本周|单周|双周|每周)$/.test(norm(line.text));
      });
      if (!titleLines.length) return block;
      var name = titleLines.map(function (line) { return norm(line.text); }).join('');
      if (!isMeaningfulCourseName(name)) return block;
      var title = Object.assign({}, titleLines[0], { text: name });
      var normalized = [title];
      if (roomIndex >= 0) {
        var roomLines = lines.slice(roomIndex);
        normalized.push(Object.assign({}, roomLines[0], {
          text: '（' + roomLines.map(function (line) { return norm(line.text); }).join('').replace(/^[@＠]/, '') + '）'
        }));
      }
      return Object.assign({}, block, { lines: normalized });
    });
  }

  function parseRasterCards(blocks, cols, rows, compact) {
    if (cols.length < 2 || rows.length < 2) return [];
    var cards = [], unplaced = [], gap = Math.abs(cols[1].x - cols[0].x), rowGap = Math.abs(rows[1].y - rows[0].y);
    blocks.forEach(function (block) {
      (block.lines && block.lines.length ? block.lines : [block]).forEach(function (line) {
        (line.elements && line.elements.length ? line.elements : [line]).forEach(function (piece) {
          var inherited = line.cell || block.cell;
          var bounds = piece.cell || (inherited && cxOf(piece) >= inherited.left && cxOf(piece) <= inherited.right
            && cyOf(piece) >= inherited.top && cyOf(piece) <= inherited.bottom ? inherited : null);
          if (!bounds || bounds.right - bounds.left < gap * .55 || bounds.right - bounds.left > gap * 1.4
              || bounds.top < rows[0].y - rowGap * .8 || bounds.bottom > rows[rows.length - 1].y + rowGap * .8) {
            unplaced.push(piece); return;
          }
          var day = pickCol(cols, (bounds.left + bounds.right) / 2, rows);
          if (!day) return;
          var card = cards.find(function (c) {
            return c.day === day && Math.abs(c.bounds.top - bounds.top) < rowGap * .12
              && Math.abs(c.bounds.bottom - bounds.bottom) < rowGap * .12;
          });
          if (!card) { card = { day: day, bounds: bounds, pieces: [] }; cards.push(card); }
          card.pieces.push(piece);
        });
      });
    });
    // ML Kit can still fuse several Chinese words into one element across a
    // gutter. Split the observed glyphs geometrically into existing cards;
    // uncertain narrow-screen titles remain explicitly marked for review.
    if (compact) unplaced.forEach(function (piece) {
      var chars = Array.from(norm(piece.text));
      if (!chars.length || piece.right - piece.left < gap * .9) return;
      var fragments = {};
      chars.forEach(function (ch, index) {
        var x = piece.left + (piece.right - piece.left) * (index + .5) / chars.length;
        var day = pickCol(cols, x, rows);
        var card = cards.find(function (c) { return c.day === day && cyOf(piece) >= c.bounds.top && cyOf(piece) <= c.bounds.bottom; });
        if (!card) return;
        var key = cards.indexOf(card);
        fragments[key] = (fragments[key] || '') + ch;
      });
      Object.keys(fragments).forEach(function (key) {
        var card = cards[+key];
        card.pieces.push(Object.assign({}, piece, { text: fragments[key], left: card.bounds.left, right: card.bounds.right }));
      });
    });
    var courses = [];
    cards.forEach(function (card) {
      var lines = [];
      card.pieces.sort(function (a, b) { return a.top - b.top || a.left - b.left; }).forEach(function (piece) {
        var text = norm(piece.text);
        if (!text) return;
        var line = lines.find(function (l) { return Math.abs(l.y - cyOf(piece)) < Math.max(7, (piece.bottom - piece.top) * .5); });
        if (!line) { line = { y: cyOf(piece), pieces: [] }; lines.push(line); }
        line.pieces.push(piece);
      });
      var texts = lines.sort(function (a, b) { return a.y - b.y; }).map(function (line) {
        return line.pieces.sort(function (a, b) { return a.left - b.left; }).map(function (piece) { return norm(piece.text); }).join('');
      });
      var nonCurrent = texts.some(function (text) { return /非本周/.test(text); });
      if (!nonCurrent) nonCurrent = cards.some(function (other) {
        return other.day === card.day && Math.abs(other.bounds.bottom - card.bounds.top) < rowGap * .1
          && other.pieces.some(function (piece) { return /非本周/.test(piece.text); });
      });
      var roomAt = texts.findIndex(function (text) { return /^[@＠]/.test(text); });
      var name, info;
      if (compact) {
        var title = (roomAt < 0 ? texts : texts.slice(0, roomAt)).filter(function (text) {
          return !/^(?:非本周|单周|双周|每周)$/.test(text);
        });
        name = title.join('').replace(/數/g, '数').replace(/學/g, '学');
        var room = roomAt < 0 ? '' : texts.slice(roomAt).join('').replace(/^[@＠]/, '');
        info = { name: name, room: room, week: extractWeek(texts, texts.join(' ')), note: nonCurrent ? '截图标为非本周，请核对单双周' : '' };
      } else {
        name = pickCourseName(texts);
        info = parseCourseInfo(texts.join(' '), name, texts);
      }
      if (!isMeaningfulCourseName(info.name)) return;
      courses.push({
        id: 'tt' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
        day: card.day, start: rows[pickRow(rows, card.bounds.top + rowGap * .15)].n,
        end: rows[pickRow(rows, card.bounds.bottom - rowGap * .15)].n,
        name: info.name, room: info.room, week: info.week, note: info.note, remindMin: 15,
        needsReview: compact || /[つ敖梭]|[(（][^()（）]*$/.test(info.name),
        reviewReason: nonCurrent ? '截图只标注非本周，无法推断单双周；请手动选择' : compact ? '窄屏截图文字有截断，请对照原图核对' : ''
      });
    });
    return courses.sort(function (a, b) { return a.day - b.day || a.start - b.start; });
  }

  function cxOf(b) { return b.cx != null ? b.cx : ((b.left || 0) + (b.right || 0)) / 2; }
  function cyOf(b) { return b.cy != null ? b.cy : ((b.top || 0) + (b.bottom || 0)) / 2; }
  function norm(s) { return String(s == null ? '' : s).replace(/\s+/g, '').replace(/^[|丨]+/, '').trim(); }

  function similarCourseName(a,b) {
    a=norm(a).replace(/[|丨()（）ⅠI一\-]/g,'');
    b=norm(b).replace(/[|丨()（）ⅠI一\-]/g,'');
    if (a===b) return true;
    if (!a || !b) return false;
    var row=Array.from({length:b.length+1},function(_,i){return i;});
    for (var i=1;i<=a.length;i++) {
      var next=[i];
      for(var j=1;j<=b.length;j++) next[j]=Math.min(next[j-1]+1,row[j]+1,row[j-1]+(a[i-1]===b[j-1]?0:1));
      row=next;
    }
    var distance=row[b.length], length=Math.max(a.length,b.length);
    return (length>=3 && distance<=1) || (length>=8 && distance/length<=0.28);
  }

  // Repeated period cells provide independent readings of the same title.
  // Align them before voting, so a missing glyph does not shift the whole name.
  function consensusName(texts, fallback) {
    var names=texts.filter(function(t){return isMeaningfulCourseName(t) && similarCourseName(t,fallback);})
      .map(function(t){return norm(t).replace(/^[|丨]+/,'');});
    if (names.length<2) return names[0] || fallback;
    var aligned=[Array.from(names[0])];
    for(var k=1;k<names.length;k++) {
      var base=aligned[0].join('').replace(/\u0000/g,''), incoming=names[k];
      var dp=Array.from({length:base.length+1},function(_,i){return [i];});
      for(var j=1;j<=incoming.length;j++) dp[0][j]=j;
      for(var i=1;i<=base.length;i++) for(j=1;j<=incoming.length;j++)
        dp[i][j]=Math.min(dp[i-1][j]+1,dp[i][j-1]+1,dp[i-1][j-1]+(base[i-1]===incoming[j-1]?0:1));
      i=base.length;j=incoming.length;
      var pairs=[];
      while(i || j) {
        if(i && j && dp[i][j]===dp[i-1][j-1]+(base[i-1]===incoming[j-1]?0:1)) {pairs.unshift([i-1,incoming[j-1]]);i--;j--;}
        else if(j && dp[i][j]===dp[i][j-1]+1) {pairs.unshift([-1,incoming[j-1]]);j--;}
        else {pairs.unshift([i-1,'\u0000']);i--;}
      }
      // The pairwise base has no gaps; map existing aligned columns through it.
      var oldColumns=[], position=0;
      for(var c=0;c<aligned[0].length;c++) {
        if(aligned[0][c]!=='\u0000') oldColumns[position++]=c;
      }
      var rebuilt=aligned.map(function(){return [];});
      var next=[];
      pairs.forEach(function(pair){
        aligned.forEach(function(row,index){rebuilt[index].push(pair[0]<0?'\u0000':row[oldColumns[pair[0]]]);});
        next.push(pair[1]);
      });
      rebuilt.push(next); aligned=rebuilt;
    }
    var output='';
    for(var col=0;col<aligned[0].length;col++) {
      var counts={}; aligned.forEach(function(row){var ch=row[col]||'\u0000';counts[ch]=(counts[ch]||0)+1;});
      var options=Object.keys(counts).sort(function(a,b){
        return counts[b]-counts[a] || (+/[\u3400-\u9fff]/.test(b))-(+/[\u3400-\u9fff]/.test(a)) || (a==='\u0000'?1:0)-(b==='\u0000'?1:0);
      });
      if(options[0]!=='\u0000') output+=options[0];
    }
    return output || fallback;
  }

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
   * Some web timetables render section labels as vertical individual glyphs.
   * ML Kit may return those as separate lines/blocks and OCR can omit several.
   * Once weekday columns are known, use the image's table extent and the app's
   * fixed 12-period model to recover equal-height row centres.
   */
  function inferRows(blocks, dayHeads, imageMeta) {
    var height = imageMeta && Number(imageMeta.height);
    if (!(height > 100) || !dayHeads.length) return [];
    var headerBottom = 0;
    dayHeads.forEach(function (head) {
      if (Number.isFinite(head.bottom)) headerBottom = Math.max(headerBottom, head.bottom);
    });
    var top = headerBottom > 0 ? headerBottom : height * 0.02;
    var bottom = height * 0.97;
    blocks.forEach(function (block) {
      (block.lines && block.lines.length ? block.lines : [block]).forEach(function (line) {
        if (/^(?:课程时间|注意事项)[:：]?$/.test(norm(line.text)) && line.top > top + 120) {
          bottom = Math.min(bottom, line.top);
        }
      });
    });
    if (bottom <= top + 120) return [];
    var rowHeight = (bottom - top) / 12;
    var out = [];
    for (var i = 0; i < 12; i++) {
      out.push({ n: i + 1, y: top + rowHeight * (i + 0.5) });
    }
    return out;
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
    // Assign by cell boundaries, not row-label centres. The old centre-to-
    // centre interval put every line in the upper half of a cell into the
    // previous period, collapsing second periods into the first.
    if (y < (rows[0].y + rows[1].y) / 2) return 0;
    for (var i = 0; i < rows.length - 1; i++) {
      if (y < (rows[i].y + rows[i + 1].y) / 2) return i;
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
    for (var i = 0; i < texts.length; i++) {
      var t = String(texts[i] == null ? '' : texts[i]).trim();
      if (!t) continue;
      if (!isMeaningfulCourseName(t)) continue;
      // The first strong line is the title in a timetable cell. Choosing the
      // longest line repeatedly promoted notes and room lists instead.
      return t;
    }
    // Returning no name is safer than turning a room/note continuation into a
    // fake course. The review screen then asks the user to correct the title.
    return '';
  }

  function isMeaningfulCourseName(value) {
    var t = norm(value);
    if (!t || t.length < 2) return false;
    if (/^[（(]/.test(t)) return false;
    if (looksLikeRoom(t) && /\d/.test(t)) return false;
    if (/(版权|@|https?:)/.test(t)) return false;
    if (/^(暂无|考试方式|考试时间|上课教室|星期|节数|周$|每$|[|丨]?第)/.test(t)) return false;
    if (/(备注|考试|上课时间|上课教室|每周|单周|双周|学分|教师|教学班|课程代码)/.test(t)) return false;
    if (/^\d+$/.test(t)) return false;
    if (/^第?[一二三四五六七八九十\d]+(?:大)?节?$/.test(t)) return false;
    // Room-list continuations such as "308、理教419、理教215）每周"
    // are often longer than the actual course title.
    if (/(教|楼|馆|室|机房|中心|厅|场|廊)/.test(t) && /[、，,）)]/.test(t)) return false;
    if (/^\d{2,4}[、，,]/.test(t)) return false;
    return /[\u3400-\u9fffA-Za-z]{2}/.test(t);
  }

  /** 从合并文本里抽教室、单双周、备注，并清理课程名里的附属内容 */
  function parseCourseInfo(raw, name, texts) {
    var room = extractRoom(texts || [], raw);
    var week = extractWeek(texts || [], raw);

    // 备注：抓「备注：...」片段
    var note = '';
    var noteRe = /备注[:：]?\s*([^）)]{0,60})/g;
    var noteMatch;
    while ((noteMatch = noteRe.exec(raw)) !== null) note = noteMatch[1].trim();

    return { name: cleanCourseName(name, room), room: room, week: week, note: note };
  }

  function extractRoom(texts, raw) {
    var candidates = [];
    texts.forEach(function (value, index) {
      var t = norm(value);
      if (!t) return;
      if (/^(备注|考试|上课时间)/.test(t) || /暂无上课教室/.test(t)) return;
      var exact = t.match(/^[（(]([^（()）]{1,32})[）)](?:[（(](?:单周|双周)[）)])?$/);
      var roomText = exact ? exact[1] : (/^(?:上课)?教室[:：]/.test(t) ? t.split(/[:：]/, 2)[1] : t);
      if (!looksLikeRoom(roomText)) return;
      candidates.push({ text: normalizeRoom(roomText), exact: !!exact, index: index });
    });
    candidates.sort(function (a, b) {
      if (a.exact !== b.exact) return a.exact ? -1 : 1;
      return a.index - b.index || a.text.length - b.text.length;
    });
    if (candidates.length) return firstRoom(candidates[0].text);

    // A few systems put the only usable room in the note itself:
    // "(暂无上课教室数据) (备注：计算中心二层7-10号机房)".
    var noteRoom = String(raw || '').match(/(?:上课教室|教室)[:：]\s*([^；;）)]+)/);
    if (noteRoom && !/暂无上课教室/.test(noteRoom[1])) {
      var normalized = normalizeRoom(noteRoom[1]);
      if (looksLikeRoom(normalized)) return firstRoom(normalized);
    }
    var noteRe = /备注[:：]?\s*([^）)]+)/g;
    var noteBody;
    while ((noteBody = noteRe.exec(raw)) !== null) {
      var fromNote = normalizeRoom(noteBody[1]);
      if (looksLikeRoom(fromNote)) return firstRoom(fromNote);
    }
    return '';
  }

  function looksLikeRoom(value) {
    var t = norm(value);
    if (!t || t.length > 32) return false;
    if (/暂无|备注|考试|上课时间|每周|单周|双周/.test(t)) return false;
    if (/^[A-Za-zⅠⅡⅢIVXivx]+$/.test(t)) return false;
    return /(?:[一二三四五六七八九十\d]+教|教(?:室|学楼)?|楼|馆|室|机房|中心|厅|场|廊|校区|[^\d]{1,10}\d{2,4})/.test(t);
  }

  function normalizeRoom(value) {
    var room = norm(value)
      .replace(/^[（(]+|[）)]+$/g, '')
      .replace(/^(?:上课)?教室[:：]?/, '')
      .replace(/[。．]/g, '-')
      .replace(/(\d)[－—–]/g, '$1-');
    if (/\d/.test(room)) {
      room = room.replace(/[Oo]/g, '0').replace(/[Il]/g, '1');
    }
    return room.trim();
  }

  function firstRoom(value) {
    // Keep one room for the editable field; notes can list several alternatives.
    return String(value || '').split(/[、，,]/)[0].replace(/[；;]$/, '').trim();
  }

  function extractWeek(texts, raw) {
    var compact = texts.map(function (value) { return norm(value); }).filter(Boolean);
    // The final "每周" on a note line is the course's actual recurrence;
    // "单周周一10-11节" inside the same note only describes recitation time.
    if (compact.some(function (t) { return /(?:^|[)）])每周/.test(t) || /^每周$/.test(t); })) return 'all';
    var odd = compact.some(function (t) { return /^[（(]?\s*单周/.test(t); });
    var even = compact.some(function (t) { return /^[（(]?\s*双周/.test(t); });
    if (!odd && !even) {
      odd = /单周/.test(raw) && !/每周/.test(raw);
      even = /双周/.test(raw) && !/每周/.test(raw);
    }
    if (odd && even) return 'all';
    return odd ? 'odd' : even ? 'even' : 'all';
  }

  /**
   * 清理课程名里混进来的附属内容。
   *
   * OCR 常把一行识别成「单周课（A101）单周」这种混合体。直接当课名很难看，
   * 也不能无脑删所有括号 —— 「高等数学（B）（一）」里的括号是课程名的一部分。
   * 策略：只删「内容是教室名（与抽出的 room 相同或含教室关键词）」的括号，
   * 以及结尾的单周/双周/每周标记。
   */
  function cleanCourseName(name, room) {
    if (!name) return '';
    var out = String(name);
    // 删掉与已识别教室一致的括号段
    if (room) {
      out = out.split('（' + room + '）').join('').split('(' + room + ')').join('');
    }
    // 删掉「含教室关键词」的括号段
    out = out.replace(/[（(][^（()）]{0,20}?(?:教|楼|馆|室|机房|中心|厅|场|廊)[^（()）]{0,12}[）)]/g, '');
    // 删掉结尾的单双周标记
    out = out.replace(/[（(]?\s*(单周|双周|每周)\s*[）)]?\s*$/g, '');
    out = out.trim();
    // 全删空了就退回原名（宁可名字带括号，也不能空）
    return out || String(name).trim();
  }

  /**
   * 解析「课程时间」表。
   * 实际格式多样：`1 8:00~8:50`、`1 8:00-8:50`、`第1节 08:00~08:50`
   * 早前的正则要求「节号:时间段」严格三段式，且把「1」当成小时解析，
   * 完全匹配不上真实数据 —— 导致课程时间表永远解析失败、退回默认时刻，
   * 对非北大作息（比如 8:00 变 8:30 开学）的学校就会整体错位。
   */
  function parseSlotTimes(blocks, rowHeads) {
    var slots = [];
    var found = 0;
    (rowHeads || []).forEach(function (row) {
      if (row.slot) { slots[row.n - 1] = row.slot; found++; }
    });
    blocks.forEach(function (b) {
      var lines = b.lines && b.lines.length ? b.lines : [{ text: b.text }];
      lines.forEach(function (l) {
        // ⚠️ 这里不能用 norm()：norm 会去掉空格，把「1 8:00~8:50」
        // 压成「18:00~8:50」，节号和时间粘连后就再也分不开（实测就是这个 bug）。
        // 用原文 + 保留分隔符的正则。
        var raw = String(l.text == null ? '' : l.text).trim();
        if (!raw) return;
        var m = raw.match(/^(?:第)?(\d{1,2})(?:节)?[\s：:]*(\d{1,2})[:：](\d{2})[\s~～\-—至°^`]*(\d{1,2})[:：](\d{2})$/);
        if (!m) return;
        var idx = parseInt(m[1], 10) - 1;
        if (idx < 0 || idx > 19) return;
        var from = +m[2] * 60 + +m[3], to = +m[4] * 60 + +m[5];
        if (+m[2] > 23 || +m[4] > 23 || +m[3] > 59 || +m[5] > 59 || to <= from) return;
        if (!slots[idx]) found++;
        slots[idx] = pad2(m[2]) + ':' + m[3] + '-' + pad2(m[4]) + ':' + m[5];
      });
    });
    // 至少要认出 8 节才算有效（避免误把别的数字当节次）
    if (found < 4) return null;
    var out = [];
    var count = Math.min(20, Math.max(4, slots.length,
      (rowHeads || []).reduce(function (last, row) { return Math.max(last, row.n); }, 0)));
    for (var i = 0; i < count; i++) out.push(slots[i] || null);
    return out;
  }
  function pad2(s) { s = String(s); return s.length < 2 ? '0' + s : s; }

  /**
   * 找不到表头时的退化解析：只抽「看起来像课程」的行。
   *
   * 这条路径很粗糙（星期/节次全是猜的），但必须尽量别把垃圾当课程：
   * 早前只过滤了「节次/星期/课程时间/注意事项」四个关键词，
   * 实测会把「十一」「十二」这类节次标签、纯数字、单字符当成课程录入，
   * 用户导入后看到一堆莫名其妙的条目。
   */
  function fallbackParse(blocks) {
    var out = [];
    var CN_NUM_RE = /^第?[一二三四五六七八九十\d]{1,3}节?$/;   // 「十一」「第十二节」「3」
    blocks.forEach(function (b) {
      var lines = b.lines && b.lines.length ? b.lines : [{ text: b.text }];
      var texts = lines.map(function (l) { return norm(l.text); }).filter(Boolean);
      var name = pickCourseName(texts);
      if (!name || name.length < 2) return;
      // 表头/说明类
      if (/^(节次|星期|课程时间|注意事项|备注|时间)/.test(name)) return;
      // 节次标签、纯数字、纯标点
      if (CN_NUM_RE.test(name)) return;
      if (/^[\d\s:：~～\-—.]+$/.test(name)) return;
      if (/^[（(].*[）)]$/.test(name) && name.length < 8) return;  // 纯括号短文本
      // 时间段行
      if (/\d{1,2}[:：]\d{2}\s*[~～\-—至]\s*\d{1,2}[:：]\d{2}/.test(name)) return;
      // 步行/课间说明
      if (/(min|分钟|课间|下课)/.test(name)) return;
      var info = parseCourseInfo(texts.join(' '), name, texts);
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
    var termInput = document.getElementById('ttTermStart');
    if (termInput) termInput.value = tt && tt.termStart ? String(tt.termStart).slice(0, 10) : '';
    var presetSelect = document.getElementById('ttSchoolPreset');
    if (presetSelect && tt) presetSelect.value = tt.schoolPreset || 'custom';
    updateTermHint();
    var grid = document.getElementById('ttGrid');
    var empty = document.getElementById('ttEmpty');
    if (!grid) return;
    if (!tt || !tt.courses.length) {
      grid.innerHTML = '';
      if (empty) empty.classList.remove('hidden');
      return;
    }
    if (empty) empty.classList.add('hidden');

    var html = '<div class="tt-corner" style="grid-row:1;grid-column:1"></div>';
    for (var d = 1; d <= 7; d++) html += '<div class="tt-dayhead" style="grid-row:1;grid-column:' + (d + 1) + '">' + DAY_CN[d] + '</div>';
    var periodCount = Math.max(tt.slots.length, tt.courses.reduce(function (max, c) {
      return Math.max(max, Number(c.end) || 0);
    }, 0));
    periodCount = Math.max(4, Math.min(20, periodCount || DEFAULT_SLOTS.length));
    for (var r = 1; r <= periodCount; r++) {
      html += '<div class="tt-rowhead" style="grid-row:' + (r + 1) + ';grid-column:1">' + r + '</div>';
      for (var dd = 1; dd <= 7; dd++) {
        var cs = tt.courses.filter(function (c) { return c && c.day === dd && r >= c.start && r <= c.end; });
        if (!cs.length) { html += '<div class="tt-cell" style="grid-row:' + (r + 1) + ';grid-column:' + (dd + 1) + '"></div>'; continue; }
      }
    }
    for (var day = 1; day <= 7; day++) {
      var groups = [];
      tt.courses.filter(function (c) { return c && c.day === day && c.start >= 1 && c.end >= c.start; })
        .sort(function (a, b) { return a.start - b.start || a.end - b.end; })
        .forEach(function (c) {
          var group = groups[groups.length - 1];
          if (!group || c.start > group.end) {
            group = { start: c.start, end: c.end, courses: [], laneEnds: [] };
            groups.push(group);
          }
          var lane = group.laneEnds.findIndex(function (end) { return end < c.start; });
          if (lane < 0) lane = group.laneEnds.length;
          group.laneEnds[lane] = c.end;
          group.end = Math.max(group.end, c.end);
          group.courses.push({ course: c, lane: lane });
        });
      groups.forEach(function (group) {
        var span = group.end - group.start + 1;
        var lanes = group.laneEnds.length;
        var inner = '';
        group.courses.forEach(function (entry) {
          var c = entry.course;
          var wk = c.week === 'odd' ? '单周' : c.week === 'even' ? '双周' : '';
          var placement = lanes > 1 ? ' style="min-width:0;grid-column:' + (entry.lane + 1)
            + ';grid-row:' + (c.start - group.start + 1) + ' / span ' + (c.end - c.start + 1) + '"' : '';
          inner += '<div class="tt-course-item" data-cid="' + esc(c.id) + '"' + placement + '>'
            + '<div class="tt-cname">' + esc(c.name) + '</div>'
            + (c.room ? '<div class="tt-croom">' + esc(c.room) + '</div>' : '')
            + (wk ? '<div class="tt-cweek">' + wk + '</div>' : '')
            + '</div>';
        });
        var layout = lanes > 1 ? ';display:grid;gap:2px;grid-template-columns:repeat(' + lanes
          + ',minmax(0,1fr));grid-template-rows:repeat(' + span + ',minmax(0,1fr))' : '';
        // Alternating-week courses can share periods, including different
        // start rows. Put them in distinct lanes rather than overlapping grid cells.
        html += '<div class="tt-cell has-course" style="grid-row:' + (group.start + 1) + ' / span ' + span
          + ';grid-column:' + (day + 1) + layout + '">' + inner + '</div>';
      });
    }
    grid.innerHTML = html;
    grid.querySelectorAll('.tt-course-item').forEach(function (el) {
      el.addEventListener('click', function (e) {
        e.stopPropagation();
        openEditor(el.dataset.cid);
      });
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
    var day = Math.max(1, Math.min(7, Math.floor(+document.getElementById('ttEdDay').value || 1)));
    var start = lessonNumber(document.getElementById('ttEdStart').value, 1);
    var end = Math.max(start, lessonNumber(document.getElementById('ttEdEnd').value, start));
    var data = {
      name: name,
      room: document.getElementById('ttEdRoom').value.trim(),
      day: day, start: start, end: end,
      week: document.getElementById('ttEdWeek').value,
      note: document.getElementById('ttEdNote').value.trim(),
      remindMin: 15
    };
    if (tt.courses.some(function (existing) { return existing.id !== editingId && coursesOverlap(existing, data); })) {
      if (window.toast) toast('课程时间冲突', '该时段已有课程，请调整星期、节次或单双周');
      return;
    }
    if (editingId) {
      var c = tt.courses.find(function (x) { return x.id === editingId; });
      if (!c) { closeEditor(); return; }
      data.remindMin = c.remindMin == null ? 15 : c.remindMin;
      Object.assign(c, data);
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
  var pendingSlots = null;
  var importRequest = 0;

  function lessonNumber(value, fallback, limit) {
    var tt = ensureState();
    var maximum = Math.max(4, Math.min(20, limit || (tt && tt.slots ? tt.slots.length : 12)));
    return Math.max(1, Math.min(maximum, Math.floor(+value || fallback)));
  }

  function cancelImport() {
    importRequest++;
    pendingCourses = null;
    pendingSlots = null;
    var review = document.getElementById('ttReview');
    if (review) review.classList.add('hidden');
  }

  async function importFromImage(source) {
    cancelImport();
    var request = importRequest;
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
      if (request !== importRequest) return;
      say('📐 正在还原课表网格…');
      var parsed = parseBlocks(res.blocks || [], {
        width: res.imageWidth,
        height: res.imageHeight
      });
      if (!parsed.courses.length) {
        say('❌ 没识别出课程。可能是图片太模糊，或不是课表截图。可改用「手动添加」。');
        return;
      }
      // 预览不修改现有作息；和课程一起确认后再保存，取消必须保持原状态。
      pendingSlots = parsed.slots;
      pendingCourses = parsed.courses;
      document.getElementById('ttImportMode').querySelectorAll('input').forEach(function (input) {
        input.checked = input.value === 'merge';
      });
      renderReview(parsed.courses, parsed.degraded, parsed.rowsInferred);
      say('✅ 识别到 ' + parsed.courses.length + ' 门课，请核对后确认导入');
    } catch (e) {
      if (request !== importRequest) return;
      say('❌ 识别失败：' + (e && e.message ? e.message : e));
    }
  }

  function renderReview(courses, degraded, rowsInferred) {
    var box = document.getElementById('ttReview');
    var list = document.getElementById('ttReviewList');
    if (!box || !list) return;
    var maximum = Math.max(4, Math.min(20, pendingSlots ? pendingSlots.length : ensureState().slots.length));
    var html = '';
    if (pendingSlots && pendingSlots.some(function (slot) { return !slot; })) {
      html += '<div class="tt-warn">⚠️ 图片未提供第 '
        + pendingSlots.map(function (slot, i) { return slot ? '' : i + 1; }).filter(Boolean).join('、')
        + ' 节的上课时间；这些节次暂不安排提醒，请在「编辑时间」中补全。</div>';
    }
    if (degraded) {
      html += '<div class="tt-warn">⚠️ 没能定位到课表的行列结构，已按文字顺序提取课程名。'
        + '下面的「星期/节次」需要你手动修正。</div>';
    } else if (rowsInferred) {
      html += '<div class="tt-warn">⚠️ 课表的竖排行号无法稳定识别，已按图片中的 12 节网格自动对齐。'
        + '请在确认前快速核对星期和节次。</div>';
    }
    courses.forEach(function (c, i) {
      html += '<div class="tt-rv-row" data-i="' + i + '">'
        + (c.needsReview ? '<small style="grid-column:1/-1;color:#b45a17">⚠️ '
          + esc(c.reviewReason || '文字存在差异或教室不完整，请对照原图核对') + '</small>' : '')
        + '<input class="tt-rv-name" value="' + esc(c.name) + '" placeholder="课程名" />'
        + '<input class="tt-rv-room" value="' + esc(c.room || '') + '" placeholder="教室" />'
        + '<div class="tt-rv-line">'
        + '<select class="tt-rv-day">' + [1,2,3,4,5,6,7].map(function (d) {
            return '<option value="' + d + '"' + (c.day === d ? ' selected' : '') + '>' + DAY_CN[d] + '</option>';
          }).join('') + '</select>'
        + '<input class="tt-rv-start" type="number" min="1" max="' + maximum + '" value="' + c.start + '" />'
        + '<span>-</span>'
        + '<input class="tt-rv-end" type="number" min="1" max="' + maximum + '" value="' + c.end + '" />'
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
        readReview();
        pendingCourses.splice(i, 1);
        renderReview(pendingCourses, degraded, rowsInferred);
      });
    });
  }

  function readReview() {
    if (!pendingCourses) return;
    document.getElementById('ttReviewList').querySelectorAll('.tt-rv-row').forEach(function (row) {
      var c = pendingCourses[+row.dataset.i];
      if (!c) return;
      c.name = row.querySelector('.tt-rv-name').value.trim();
      c.room = row.querySelector('.tt-rv-room').value.trim();
      c.day = Math.max(1, Math.min(7, Math.floor(+row.querySelector('.tt-rv-day').value || 1)));
      var maximum = pendingSlots ? pendingSlots.length : ensureState().slots.length;
      c.start = lessonNumber(row.querySelector('.tt-rv-start').value, 1, maximum);
      c.end = Math.max(c.start, lessonNumber(row.querySelector('.tt-rv-end').value, c.start, maximum));
      c.week = row.querySelector('.tt-rv-week').value;
    });
  }

  async function confirmImport(skipReview) {
    if (!pendingCourses || !pendingCourses.length) return;
    if (skipReview !== true) readReview();
    if (!pendingCourses.some(function (c) { return !!c.name; })) {
      if (window.toast) toast('⚠️ 课程名不能为空', '请填写课程名后再确认导入');
      return;
    }
    var tt = ensureState();
    var mode = Array.from(document.getElementById('ttImportMode').querySelectorAll('input')).find(function (input) {
      return input.checked;
    });
    var replacing = skipReview !== true && mode && mode.value === 'replace';
    var hadCourses = tt.courses.length > 0;
    if (replacing) tt.courses = [];
    var added = 0;
    var duplicates = 0;
    var conflicts = 0;
    pendingCourses.forEach(function (c) {
      var name = c.name;
      if (!name) return;
      var duplicate = tt.courses.some(function (existing) {
        return existing.name === name && existing.room === c.room && existing.day === c.day
          && existing.start === c.start && existing.end === c.end && existing.week === c.week;
      });
      if (duplicate) { duplicates++; return; }
      var conflict = tt.courses.some(function (existing) { return coursesOverlap(existing, c); });
      if (conflict) { conflicts++; return; }
      tt.courses.push({
        id: 'tt' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
        name: name,
        room: c.room,
        day: c.day,
        start: c.start, end: c.end,
        week: c.week,
        note: c.note || '',
        remindMin: 15
      });
      added++;
    });
    if (pendingSlots && (!hadCourses || replacing)) {
      tt.slots = pendingSlots.slice(0, 20);
      tt.schoolPreset = 'custom';
    }
    var scheduled = window.save ? await save() : true;
    cancelImport();
    document.getElementById('ttImportHint').classList.add('hidden');
    document.getElementById('ttOcrStatus').textContent = '';
    render();
    if (window.toast) {
      var skipped = [];
      if (duplicates) skipped.push('重复 ' + duplicates + ' 条');
      if (conflicts) skipped.push('冲突 ' + conflicts + ' 条已跳过');
      toast('📚 课表导入完成', '新增 ' + added + ' 个时段'
        + (skipped.length ? '；' + skipped.join('，') : '')
        + (scheduled === false ? '；后台提醒未排定，请运行诊断重试' : replacing ? '；已替换当前课表' : '；已有课程与作息已保留'));
    }
  }

  /* ---------------- 绑定 ---------------- */

  /** 显示「现在是第几周」，让用户能核对单双周是否与学校一致 */
  function updateTermHint() {
    var el = document.getElementById('ttTermHint');
    if (!el) return;
    var tt = ensureState();
    if (!tt || !tt.termStart) {
      el.textContent = '未设置时按自然周序算';
      return;
    }
    var wn = weekNumber(tt, new Date());
    el.textContent = wn > 0 ? ('本周第 ' + wn + ' 周 · ' + (wn % 2 ? '单周' : '双周')) : '';
  }

  function openSlotEditor() {
    var tt = ensureState();
    var text = document.getElementById('ttSlotsText');
    if (!text) return;
    text.value = tt.slots.map(function (slot) { return slot || '待设置'; }).join('\n');
    document.getElementById('ttSlotsEditor').classList.remove('hidden');
  }
  function closeSlotEditor() {
    var sheet = document.getElementById('ttSlotsEditor');
    if (sheet) sheet.classList.add('hidden');
  }
  function saveSlotEditor() {
    var raw = document.getElementById('ttSlotsText').value.split(/\r?\n/).map(function (line) {
      return line.trim().replace(/[~～至]/g, '-').replace(/\s+/g, '');
    }).filter(Boolean);
    if (raw.length < 4 || raw.length > 20 ||
        raw.some(function (line) { return line !== '待设置' && (!/^([01]\d|2[0-3]):[0-5]\d-([01]\d|2[0-3]):[0-5]\d$/.test(line)
          || line.slice(0, 5) >= line.slice(6)); })) {
      if (window.toast) toast('⚠️ 作息格式不正确', '每行写成 08:00-08:45，共 4 到 20 节');
      return;
    }
    var tt = ensureState();
    tt.slots = raw.map(function (slot) { return slot === '待设置' ? null : slot; });
    tt.schoolPreset = 'custom';
    if (window.save) save();
    closeSlotEditor();
    render();
    if (window.NativeNotify && window.NativeNotify.syncTimetable) {
      try { window.NativeNotify.syncTimetable(window.S); } catch (e) {}
    }
    if (window.toast) toast('🕘 作息已更新', '只调整每节课时间，未改动已有课程');
  }
  function applySchoolPreset() {
    var select = document.getElementById('ttSchoolPreset');
    var key = select ? select.value : 'generic';
    if (key === 'custom') { openSlotEditor(); return; }
    var slots = SCHOOL_PRESETS[key];
    if (!slots) return;
    var tt = ensureState();
    tt.slots = slots.slice();
    tt.schoolPreset = key;
    if (window.save) save();
    render();
    if (window.NativeNotify && window.NativeNotify.syncTimetable) {
      try { window.NativeNotify.syncTimetable(window.S); } catch (e) {}
    }
    if (window.toast) toast('🏫 作息模板已应用', key === 'pku' ? '已使用北京大学 12 节作息；课程不会自动导入' : '已使用通用 12 节作息；课程不会自动导入');
  }

  function bind() {
    // 开学日期：单双周判定的基准
    var termInput = document.getElementById('ttTermStart');
    if (termInput) {
      var tt0 = ensureState();
      if (tt0 && tt0.termStart) {
        // 存的是 ISO 字符串（yyyy-mm-dd），直接喂给 date input
        termInput.value = String(tt0.termStart).slice(0, 10);
      }
      termInput.addEventListener('change', function () {
        var tt = ensureState();
        tt.termStart = termInput.value || null;
        if (window.save) save();
        updateTermHint();
        render();
        // 开学日期变了 → 单双周的奇偶可能整体翻转，必须重排提醒
        if (window.NativeNotify && window.NativeNotify.syncTimetable) {
          try { window.NativeNotify.syncTimetable(window.S); } catch (e) {}
        }
        if (window.toast) toast('📅 开学日期已更新', '单双周提醒已按新基准重排');
      });
    }
    updateTermHint();

    var applyPreset = document.getElementById('ttApplyPreset');
    if (applyPreset) applyPreset.addEventListener('click', applySchoolPreset);
    var editSlots = document.getElementById('ttEditSlotsBtn');
    if (editSlots) editSlots.addEventListener('click', openSlotEditor);
    var slotsSave = document.getElementById('ttSlotsSave');
    if (slotsSave) slotsSave.addEventListener('click', saveSlotEditor);
    var slotsClose = document.getElementById('ttSlotsClose');
    if (slotsClose) slotsClose.addEventListener('click', closeSlotEditor);
    var slotsCancel = document.getElementById('ttSlotsCancel');
    if (slotsCancel) slotsCancel.addEventListener('click', closeSlotEditor);

    var reference = document.getElementById('ttReferenceBtn');
    if (reference) reference.addEventListener('click', async function () {
      if (reference.disabled) return;
      if (!(window.S && window.S.profile && window.S.profile.role === 'admin')) {
        if (window.toast) toast('普通用户不显示参考课表', '参考课表仅用于管理员测试');
        return;
      }
      var current = ensureState();
      if (current.courses.length) {
        if (window.toast) toast('参考课表未导入', '当前课表已有课程。管理员测试前请先清空，避免覆盖或重叠');
        return;
      }
      reference.disabled = true;
      try {
        var response = await fetch('data/timetable-reference.json');
        if (!response.ok) throw new Error('参考课表读取失败');
        var sample = await response.json();
        cancelImport();
        pendingCourses = sample.courses;
        pendingSlots = null; // The supplied screenshot has no lesson clock times.
        await confirmImport(true);
      } catch (error) {
        if (window.toast) toast('课表导入未完成', error.message || String(error));
      } finally { reference.disabled = false; }
    });

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
        try {
          var b64 = await blobToDataUrl(f);
          await importFromImage(b64);
        } catch (e) {
          document.getElementById('ttOcrStatus').textContent = '读取图片失败，请重新选择图片。';
        } finally { fileInput.value = ''; }
      });
    }
    var cancelBtn = document.getElementById('ttOcrCancel');
    if (cancelBtn) cancelBtn.addEventListener('click', function () {
      cancelImport();
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
      cancelImport();
      document.getElementById('ttOcrStatus').textContent = '';
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
    _parseBlocks: parseBlocks,
    _coursesOverlap: coursesOverlap,
    /** 生成某天的课程提醒点：[{key, at, title, body}] */
    planFor: function (dayTs) {
      var tt = ensureState();
      if (!tt || !tt.courses.length) return [];
      var d = new Date(dayTs);
      if (isNaN(d.getTime())) return [];
      var termStart = localDate(tt.termStart);
      if (termStart && new Date(d.getFullYear(), d.getMonth(), d.getDate()) < termStart) return [];
      var jsDay = d.getDay();          // 0=周日
      var myDay = jsDay === 0 ? 7 : jsDay;
      var weekNo = weekNumber(tt, d);  // 第几周（用于单双周判定）
      var out = [];
      tt.courses.forEach(function (c) {
        if (!c || c.day !== myDay || !Number.isInteger(c.start) || c.start < 1
            || c.start > Math.min(20, tt.slots.length)) return;
        // 单双周过滤：早前只在界面上显示「单周/双周」标签，
        // 生成提醒时完全没判断 —— 结果单周课在双周也会提醒、反之亦然。
        // 这是「提醒有时准有时不准」的一个真实成因（周期性课表尤其明显）。
        if (c.week === 'odd' && weekNo % 2 === 0) return;
        if (c.week === 'even' && weekNo % 2 === 1) return;
        var slot = tt.slots[c.start - 1];
        var m = String(slot).match(/(\d{1,2}):(\d{2})/);
        if (!m || +m[1] > 23 || +m[2] > 59) return;
        var at = new Date(d.getFullYear(), d.getMonth(), d.getDate(), +m[1], +m[2], 0).getTime();
        var minutes = c.remindMin == null ? 15 : Number(c.remindMin);
        if (!Number.isFinite(minutes) || minutes < 0) minutes = 15;
        var remind = at - minutes * 60000;
        // 前台也用这个函数取到点提醒，必须保留刚过去的补弹窗口。
        if (remind < Date.now() - 15 * 60000) return;
        out.push({
          key: 'tt:' + c.id + ':' + at,
          nativeKey: 'tt:' + c.id + ':recurring',
          repeatDays: c.week === 'odd' || c.week === 'even' ? 14 : 7,
          at: remind,
          title: minutes ? '📚 ' + minutes + ' 分钟后上课' : '📚 上课时间到',
          body: c.name + (c.room ? ' · ' + c.room : '') + ' · 第' + c.start + '-' + c.end + '节 ' + slot
            + (c.week === 'odd' ? '（单周）' : c.week === 'even' ? '（双周）' : ''),
          kind: 'daily'
        });
      });
      return out;
    }
  };

  function localDate(value) {
    var m = typeof value === 'string' && value.match(/^(\d{4})-(\d{2})-(\d{2})$/);
    if (!m) return null;
    var d = new Date(+m[1], +m[2] - 1, +m[3]);
    return d.getFullYear() === +m[1] && d.getMonth() === +m[2] - 1 && d.getDate() === +m[3] ? d : null;
  }

  /**
   * 计算某天是学期第几周（用于单双周判定）。
   *
   * 基准优先级：
   *  1) 用户在课表里设置的学期开始日（tt.termStart）
   *  2) 没设置时，退化为「该日期所在年的第几周」（ISO 周序）
   *
   * 说明：没有 termStart 时，单双周的绝对奇偶可能与学校口径不一致
   * （不同学校开学周不同），但至少保证「同一周内一致、隔周交替」，
   * 这比完全不过滤要正确得多。界面上应引导用户设置开学日期。
   */
  function weekNumber(tt, d) {
    var base = null;
    if (tt.termStart) {
      base = localDate(tt.termStart);
      if (base) {
        // 归一到那一周的周一
        var bd = base.getDay() === 0 ? 7 : base.getDay();
        base = new Date(base.getFullYear(), base.getMonth(), base.getDate() - (bd - 1));
        var day0 = new Date(d.getFullYear(), d.getMonth(), d.getDate());
        var diffDays = Math.round((day0 - base) / 86400000);
        return Math.floor(diffDays / 7) + 1;
      }
    }
    // 退化：ISO 周序
    var t = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    var dayNum = t.getDay() === 0 ? 7 : t.getDay();
    t = new Date(t.getFullYear(), t.getMonth(), t.getDate() + 4 - dayNum); // 该周周四
    var yearStart = new Date(t.getFullYear(), 0, 1);
    return Math.ceil(((t - yearStart) / 86400000 + 1) / 7);
  }

  window.addEventListener('DOMContentLoaded', function () {
    try { bind(); render(); } catch (e) { console.warn('课表模块初始化失败', e); }
  });
})();
