/* ============================================================
   DDL雷达 · 原生系统提醒桥（v1.8：AlarmManager.setAlarmClock 原生闹钟）
   - 调度/恢复全部走自研 DdlAlarm 原生插件（系统时钟同款 setAlarmClock API）
     免精确闹钟授权、Doze 深度休眠保证准时、状态栏常驻闹钟图标，
     杀后台/重启/覆盖安装后由原生 BootReceiver 自动恢复
   - 声音/震动由通知通道携带：响铃+震动 / 仅震动 / 仅响铃 / 仅应用内横幅
   - 通道创建仍用 DdlNotify 原生插件（插件自带的不支持 vibration）
   网页版（file:// / http）自动跳过，走应用内引擎。
   ============================================================ */
(function () {
  'use strict';

  function hashId(str) {
    var h = 2166136261;
    for (var i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 16777619); }
    return (h >>> 0) % 2147483647;
  }

  function DDL() {
    return (window.Capacitor && Capacitor.Plugins && Capacitor.Plugins.DdlAlarm) || null;
  }
  function DDP() {
    return (window.Capacitor && Capacitor.Plugins && Capacitor.Plugins.DdlNotify) || null;
  }

  var CHANNELS = ['ddlr_full', 'ddlr_vib', 'ddlr_ring'];

  var N = {
    available: function () {
      return !!(window.Capacitor && Capacitor.isNativePlatform && Capacitor.isNativePlatform() && DDL());
    },
    _sig: null,
    // 最近一次 apply() 是否成功把闹钟真正排进系统。
    // 只有 true 时 tickReminders 才敢把「到点」完全交给原生、自己不弹；
    // 否则（从未同步 / 上次失败 / 还没跑过）一律由应用内兜底弹窗，
    // 宁可重复也不能静默丢失。
    _synced: false,
    _syncedAt: 0,

    /**
     * 原生闹钟是否处于「已同步且新鲜」状态。
     * 超过 10 分钟没成功同步就视为不可信：可能任务变了但 sync 失败，
     * 此时系统里排的还是旧闹钟，不能据此放弃应用内补弹。
     */
    isSynced: function () {
      if (!this._synced) return false;
      return (Date.now() - this._syncedAt) < 10 * 60 * 1000;
    },

    init: async function () {
      if (!this.available()) return;
      await this.ensureChannels();
      await this.ensureNotifyPermission();
      // 启动时强制重排一次：SCHED_VER（原生调度规格版本）升级后，
      // 旧版排定的闹钟带着旧的 PendingIntent 结构，必须整表重排才会生效。
      // 但 sync() 有签名去抖，签名没变就直接 return → apply() 永远不被调用
      // → 版本迁移形同虚设（这是「装了新版还是老毛病」的隐藏成因）。
      // 这里在每次冷启动时清掉签名，保证至少对账一次。
      this._sig = null;
      await this.sync(window.S);
      // 原生 BootReceiver 负责重启/覆盖安装后恢复；这里兜底：切回前台时对账一次
      document.addEventListener('visibilitychange', function () {
        if (!document.hidden && window.S) N.sync(window.S);
      });
    },

    // force=true 时强制删旧建新（用户在诊断面板点「修复通道」才会走到）
    ensureChannels: async function (force) {
      var ddp = DDP();
      if (!ddp) return;
      // 通道承载一切表现（系统闹钟模式）：HIGH 重要性弹横幅、铃声/震动由系统
      // 在通知送达时播放（不依赖进程存活）；到点路径只负责把通知交给系统
      var defs = [
        { id: 'ddlr_full', name: '提醒 · 响铃+震动', importance: 4, vibration: true, sound: 'ddlr_chime.wav' },
        { id: 'ddlr_vib',  name: '提醒 · 仅震动',   importance: 4, vibration: true, sound: '' },
        { id: 'ddlr_ring', name: '提醒 · 仅响铃',   importance: 4, vibration: false, sound: 'ddlr_chime.wav' }
      ];
      // 通道设置签名：与上次一致则跳过（避免反复删通道影响已排定的提醒）。
      // 注意：原生侧现在默认复用已存在通道，force 才删重建——用户手动调整过的
      // 通道设置（静音/换铃声）不再被每次启动抹掉。
      var chSig = JSON.stringify(defs.map(function (d) { return [d.id, d.importance, d.vibration, d.sound]; }));
      var chSigStored = null;
      try { chSigStored = localStorage.getItem('ddlr_chsig'); } catch (e) {}
      if (force || chSig !== chSigStored) {
        for (var i = 0; i < defs.length; i++) {
          var d = defs[i];
          try {
            await ddp.ensureChannel({
              id: d.id, name: d.name, importance: d.importance,
              vibration: d.vibration, sound: d.sound, force: !!force
            });
          } catch (e) { /* 单个通道失败不阻断 */ }
        }
        try { localStorage.setItem('ddlr_chsig', chSig); } catch (e) {}
      }
    },

    // 通知权限（Android 13+ 运行时权限）：没开时申请一次，被拒则引导
    ensureNotifyPermission: async function () {
      var ddp = DDP();
      if (!ddp) return;
      try {
        var s = await ddp.notifyStatus();
        if (s && s.granted) return;
        if (typeof ddp.requestPermissions === 'function') {
          var r = await ddp.requestPermissions();
          if (!(r && r.notifications === 'granted') && window.toast) {
            window.toast('🔔 通知权限未开启', '没有通知权限，系统提醒弹不出来：去系统设置 → 应用 → DDL雷达 → 通知', 'tt-urgent');
          }
        }
      } catch (e) { /* ignore */ }
    },

    // 诊断：通知权限 / 已排定系统闹钟（条数+下一条时刻）/ 通道 / 系统版本
    diag: async function () {
      var out = { native: this.available() };
      if (!out.native) return out;
      var ddp = DDP();
      try { var s = await ddp.notifyStatus(); out.notify = !!s.granted; } catch (e) { out.notify = null; }
      try {
        var mode = (window.S && window.S.settings && window.S.settings.remindMode) || 'full';
        var channelId = mode === 'silent' ? 'ddlr_full' : 'ddlr_' + mode;
        var p = await DDL().pending({ channelId: channelId });
        out.pending = (p.items || []).length;
        out.queued = p.queued || 0;
        out.channelId = mode === 'silent' ? null : (p.channelId || channelId);
        out.nextAt = p.nextAt || 0;
        out.sysNextAt = p.sysNextAt || 0;
        out.lastFireAt = p.lastFireAt || 0;
        out.lastFireId = p.lastFireId || 0;
        out.lastFireVia = p.lastFireVia || '';
        out.guard = p.guard === true;
        out.sdk = p.sdk || null;
        // 新增诊断项：解释「为什么没横幅 / 为什么不准时」
        out.channelImportance = p.channelImportance;
        out.headsUp = p.headsUp;
        out.canFullScreen = p.canFullScreen;
        out.exactOk = p.exactOk;
        out.missed = p.missed || 0;
      } catch (e) { out.pending = -1; out.nextAt = 0; }
      try {
        var l = await ddp.listChannelIds();
        var ids = l.ids || (l.channels || []).map(function (c) { return c.id; });
        out.channels = CHANNELS.filter(function (x) { return ids.indexOf(x) >= 0; }).length;
      } catch (e) { out.channels = 0; }
      return out;
    },

    // 10 秒后由系统闹钟弹一条真实通知，用于验证提醒链路（含退 App 场景）。
    // 自验证：排定后回读原生存储确认生效，toast 给出确定结论（单一出口）。
    test: async function () {
      var ddl = DDL();
      if (!ddl) return false;
      var mode = (window.S && S.settings.remindMode) || 'full';
      if (mode === 'silent') {
        setTimeout(function () {
          if (window.toast) toast('🧪 测试提醒', '静默模式：只显示应用内横幅，无系统通知', 'urgent');
        }, 10000);
        return true;
      }
      var at = Date.now() + 10000;
      try {
        // 权限预检：被拒时系统会静默丢弃通知横幅（原生接收器仍会响铃/震动兜底）
        var granted = true;
        var ddp = DDP();
        if (ddp) {
          try { var st = await ddp.notifyStatus(); granted = !!(st && st.granted); } catch (e) {}
        }
        await ddl.scheduleOne({
          id: 990000001,
          at: at,
          title: '🧪 测试提醒',
          body: '看到这条 = 系统闹钟提醒链路通 ✓（退 App 也能弹）',
          channelId: 'ddlr_' + mode
        });
        try { localStorage.setItem('ddlr_testAt', String(at)); } catch (e) {}
        // 回读验证：这条闹钟确实在原生存储里
        var ok = false;
        try {
          var p = await ddl.pending();
          ok = (p.items || []).some(function (i) { return i.id === 990000001; });
        } catch (e) { /* 验证失败不否定排定，按已排定提示 */ ok = true; }
        if (ok) {
          if (window.toast) {
            var head = granted ? '✅ 已排定，10 秒后应响'
              : '✅ 已排定 · 10 秒后应响铃/震动（横幅需通知权限）';
            window.toast(head, '验证中：退 App 也行。若毫无动静，开「运行诊断」看「上次闹钟触发」', 'urgent');
          }
          // +13s 自报告（留在 App 内时）：确证闹钟真的触发了，不用再开诊断
          setTimeout(function () {
            if (document.hidden) return; // 退到后台的验证场景以实际通知为准
            ddl.pending().then(function (p) {
              var gone = !(p.items || []).some(function (i) { return i.id === 990000001; });
              var fired = p.lastFireId === 990000001 && p.lastFireAt >= at - 2000;
              if (fired && gone && window.toast) {
                var viaTxt = p.lastFireVia === 'exact' ? '经精确闹钟备份' : '经系统闹钟';
                window.toast('🔔 测试闹钟已触发（' + viaTxt + '）',
                  '刚才应已响铃/震动。若什么都没感觉到，请把「运行诊断」截图反馈', 'urgent');
              } else if (!gone && window.toast) {
                window.toast('⚠️ 闹钟到点未触发', '系统未派发广播：请把「运行诊断」截图反馈', 'tt-urgent');
              }
            }).catch(function () {});
          }, 13000);
        } else if (window.toast) {
          window.toast('⚠️ 排定未生效', '点「运行诊断」查看系统闹钟状态', 'tt-urgent');
        }
        return ok;
      } catch (e) {
        console.warn('[DDL雷达] 测试提醒失败', e);
        if (window.toast) window.toast('⚠️ 排定失败', '点「运行诊断」查看通知权限与闹钟状态');
        return false;
      }
    },

    /**
     * 课表变更后强制重排。
     * 不能只靠 sync() 的签名去抖：课表字段虽已纳入签名，但用户可能
     * 在「签名恰好相同」的情况下修改了不影响签名的细节，强制清空更保险。
     */
    syncTimetable: function (S) {
      this._sig = null;
      return this.sync(S || window.S);
    },

    // 任务/设置变化后整表协调系统闹钟（带签名去抖；原生侧多退少补）
    sync: async function (S) {
      if (!this.available() || !S) return;
      var mode = (S.settings && S.settings.remindMode) || 'full';
      // 签名必须覆盖所有会影响 planReminders() 输出的因素：
      // · 日期/时刻档 —— planReminders 结果随时间推移变化（每日提醒按天生成），
      //   签名若不含时间，跨天不会重排，7 天窗口用完后提醒体系直接归零。
      // · tiered / dailyTime —— 改这两个设置会改变提醒点，早前不进签名，
      //   用户改了等于没改（save→sync→签名不变→直接 return）。
      var nowMs = Date.now();
      var dayKey = new Date(nowMs).toDateString();
      var slot10 = Math.floor(nowMs / 600000); // 10 分钟一档
      var tiered = (S.settings && S.settings.tiered) ? 1 : 0;
      var dailyTime = (S.settings && S.settings.dailyTime) || '';
      var sig = mode + '|' + tiered + '|' + dailyTime + '|' + dayKey + '|' + slot10 + '|'
        + S.tasks.map(function (t) {
          return t.id + ':' + (t.updatedAt || 0) + ':' + (t.done ? 1 : 0);
        }).join(',')
        // 课表也要进签名：改了课程/时间必须重排，否则系统里还是旧课表
        + '|tt:' + ((S.timetable && S.timetable.courses) || []).map(function (c) {
          return c.id + ':' + c.day + ':' + c.start + ':' + c.end + ':' + (c.week || 'all') + ':' + (c.remindMin || 15);
        }).join(',');
      if (this._sig === sig) return;
      // ⚠️ 注意：这里**不能**先给 _sig 赋值。
      // 早前是 `this._sig = sig` 写在 try 之前，一旦下面 apply() 抛异常，
      // 签名已经被记账 → 后续所有 sync 都因「签名相同」直接 return，
      // 系统闹钟永久停止更新（一次失败 = 永久静默）。
      // 正确做法：成功拿到 apply 结果之后再记账，失败则保持 null 以便重试。

      try {
        var alarms = [];
        if (mode !== 'silent') {
          var now = Date.now();
          // 关键：只排「近 7 天内」的提醒。
          // planReminders() 的远期每日提醒会为每个任务生成最多 400 天、
          // 每天一条的条目可能超过系统适合同时调度的数量；原生侧会保留队列，
          // 并在每次提醒触发后按时间顺序递补。7 天窗口让本地队列保持有界。
          var HORIZON = 7 * 86400000;
          var scheduledIds = new Set();
          for (var i = 0; i < S.tasks.length; i++) {
            var list = (typeof planReminders === 'function') ? planReminders(S.tasks[i]) : [];
            for (var j = 0; j < list.length; j++) {
              var r = list[j];
              if (r.at <= now - 1000) continue;      // 已过期
              if (r.at > now + HORIZON) continue;    // 超出 7 天窗口
              var id = hashId(r.key);
              if (scheduledIds.has(id)) continue;
              scheduledIds.add(id);
              alarms.push({
                id: id,
                at: r.at,
                title: r.title.replace(/<[^>]+>/g, ''),
                body: r.body.replace(/<[^>]+>/g, ''),
                channelId: 'ddlr_' + mode
              });
            }
          }
          // 课表提醒：未来 7 天里每天的上课提醒。
          // 课程是周期性的（每周同一天同一节），所以要把窗口内每一天都算一遍，
          // 而不是只算「今天」——否则周四设的课，周一不会出现在系统闹钟里。
          try {
            if (window.Timetable && window.Timetable.planFor) {
              for (var dOff = 0; dOff <= 7; dOff++) {
                var dayTs = now + dOff * 86400000;
                var cs = window.Timetable.planFor(dayTs);
                for (var ci = 0; ci < cs.length; ci++) {
                  var cr = cs[ci];
                  if (cr.at <= now - 1000 || cr.at > now + HORIZON) continue;
                  var cid = hashId(cr.key);
                  if (scheduledIds.has(cid)) continue;
                  scheduledIds.add(cid);
                  alarms.push({
                    id: cid,
                    at: cr.at,
                    title: cr.title,
                    body: cr.body,
                    channelId: 'ddlr_' + mode
                  });
                }
              }
            }
          } catch (e) { console.warn('[DDL雷达] 课表提醒生成失败', e); }
          alarms.sort(function (a, b) { return a.at - b.at; });
        }
        // 未触发的测试闹钟并入对账列表，防止被 apply() 当作多余条目撤销
        // （静默模式不并入：跟随当前模式被撤销，符合「不发系统通知」语义）
        try {
          var testAt = parseInt(localStorage.getItem('ddlr_testAt') || '0', 10);
          if (mode !== 'silent' && testAt > Date.now() + 3000) {
            if (!scheduledIds.has(990000001)) {
              alarms.push({
                id: 990000001,
                at: testAt,
                title: '🧪 测试提醒',
                body: '看到这条 = 系统闹钟提醒链路通 ✓（退 App 也能弹）',
                channelId: 'ddlr_' + mode
              });
              alarms.sort(function (a, b) { return a.at - b.at; });
            }
          } else if (testAt) {
            localStorage.removeItem('ddlr_testAt'); // 已触发或过期：清标记
          }
        } catch (e) { /* ignore */ }
        var r = await DDL().apply({ alarms: alarms });
        // apply() 真正成功后才记账（见上方注释：先记账会导致一次失败永久静默）
        this._sig = sig;
        this._synced = true;
        this._syncedAt = Date.now();
        if (r && (r.added > 0 || r.cancelled > 0)) {
          console.log('[DDL雷达] 系统闹钟对账完成：+' + r.added + ' -' + r.cancelled +
            '，下一条 ' + (r.nextAt ? new Date(r.nextAt).toLocaleString() : '无'));
        }
      } catch (e) {
        // 失败时清空签名，保证下次一定会重试（而不是被去抖挡掉）；
        // 同时把 _synced 置 false，让 tickReminders 立刻接管补弹——
        // 否则会出现「系统没排上 + 应用内又不弹」的静默窗口。
        this._sig = null;
        this._synced = false;
        console.warn('[DDL雷达] 系统闹钟调度失败', e);
      }
    }
  };

  window.NativeNotify = N;
})();
