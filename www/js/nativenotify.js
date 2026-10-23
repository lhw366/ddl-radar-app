/* DDL雷达原生提醒：正常事件和测试统一 apply → AlarmManager 精确提醒。
   仅在事件到点时唤醒并短时启动服务；无预唤醒、常驻轮询或电池优化豁免。
   顶部横幅需要用户授权悬浮窗，关闭后显示服务自动退出。 */
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

  var CHANNELS = [];
  ['high','gentle'].forEach(function(level) {
    ['vib','still'].forEach(function(vibration) {
      ['sound','silent'].forEach(function(sound) {CHANNELS.push('ddlr_'+level+'_v4_'+vibration+'_'+sound);});
    });
    ['sound','silent'].forEach(function(sound) {CHANNELS.push('ddlr_'+level+'_v5_native_'+sound);});
    CHANNELS.push('ddlr_'+level+'_v6_quiet');
  });

  function settingEnabled(settings, key) {
    return !!(settings && settings[key] !== false);
  }

  function outputsEnabled(settings) {
    if (!settings || settings.remindSilent === true) return false;
    return settingEnabled(settings, 'sound') || settingEnabled(settings, 'vibrate') ||
      settingEnabled(settings, 'banner');
  }

  function channelIdFor(settings) {
    var banner = settingEnabled(settings, 'banner');
    var sound = settingEnabled(settings, 'sound');
    // Compatibility vibration is an explicit user choice. Keep existing system
    // channel settings until the user chooses the finite native alarm route.
    if (settings && settings.vibrationMode === 'native') {
      return 'ddlr_' + (banner ? 'high' : 'gentle') + '_v5_native_' + (sound ? 'sound' : 'silent');
    }
    return 'ddlr_' + (banner ? 'high' : 'gentle') + '_v4_' + (settingEnabled(settings, 'vibrate') ? 'vib_' : 'still_') + (sound ? 'sound' : 'silent');
  }

  function vibrationModeFor(settings) {
    return settings && settings.vibrationMode === 'native' ? 'native' : 'notification';
  }

  function quietChannel(result) {
    return !!(result && typeof result.channelId === 'string' && result.channelId.indexOf('_v6_quiet') >= 0);
  }

  var N = {
    available: function () {
      return !!(window.Capacitor && Capacitor.isNativePlatform && Capacitor.isNativePlatform() && DDL());
    },
    outputsEnabled: outputsEnabled,
    channelIdFor: channelIdFor,
    _sig: null,
    // 最近一次 apply() 是否成功把闹钟真正排进系统。
    // 只有 true 时 tickReminders 才敢把「到点」完全交给原生、自己不弹；
    // 否则（从未同步 / 上次失败 / 还没跑过）一律由应用内兜底弹窗，
    // 宁可重复也不能静默丢失。
    _synced: false,
    _syncedAt: 0,

    /** 提醒 key → 原生闹钟 id（与原生侧同一套 FNV-1a 哈希） */
    idOf: hashId,

    // 原生最近投递过的事件：Map<id, at>。
    // tickReminders 用它区分「系统闹钟已经响过」和「系统闹钟真的漏了」，
    // 从而既不重复震动、也不静默丢提醒。
    _delivered: null,
    _deliveredAt: 0,

    /**
     * 原生闹钟是否处于「已同步且新鲜」状态。
     * 超过 10 分钟没成功同步就视为不可信：可能任务变了但 sync 失败，
     * 此时系统里排的还是旧闹钟，不能据此放弃应用内补弹。
     */
    isSynced: function () {
      if (!this._synced) return false;
      return (Date.now() - this._syncedAt) < 10 * 60 * 1000;
    },

    /**
     * 拉取原生「已投递事件」日志。
     * 失败时保留上一次的结果：宁可多弹一次，也不能因为一次查询失败就静默。
     */
    refreshDelivered: async function () {
      var ddl = DDL();
      if (!ddl) return;
      try {
        var p = await ddl.pending();
        var log = (p && p.deliveredLog) || [];
        var map = new Map();
        for (var i = 0; i < log.length; i++) {
          if (log[i] && log[i].length >= 2) map.set(log[i][0], log[i][1]);
        }
        this._delivered = map;
        this._deliveredAt = Date.now();
      } catch (e) { /* 保留旧值 */ }
    },

    /** 这条提醒点是否已经由原生投递过（id 与 at 必须同时相同）。 */
    wasDeliveredNatively: function (id, at) {
      if (!this._delivered || !id) return false;
      return this._delivered.get(id) === at;
    },

    init: async function () {
      if (!this.available()) return;
      // Register native alarms before permission dialogs can suspend the WebView.
      this._sig = null;
      await this.sync(window.S);
      await this.ensureChannels();
      await this.ensureNotifyPermission();
      // 启动时强制重排一次：SCHED_VER（原生调度规格版本）升级后，
      // 旧版排定的闹钟带着旧的 PendingIntent 结构，必须整表重排才会生效。
      // 但 sync() 有签名去抖，签名没变就直接 return → apply() 永远不被调用
      // → 版本迁移形同虚设（这是「装了新版还是老毛病」的隐藏成因）。
      // 这里在每次冷启动时清掉签名，保证至少对账一次。
      this._sig = null;
      await this.sync(window.S);
      await this.refreshDelivered();
      // 原生 BootReceiver 负责重启/覆盖安装后恢复；这里兜底：切回前台时对账一次
      document.addEventListener('visibilitychange', function () {
        if (document.hidden || !window.S) return;
        // 先同步「已投递」日志，再对账：顺序反了会把刚响过的提醒算成漏掉。
        N.refreshDelivered().then(function () { N.sync(window.S); });
      });
    },

    // force=true 重新核对通道；已有通道的用户设置由系统保留。
    ensureChannels: async function (force) {
      var ddp = DDP();
      if (!ddp) return;
      // Keep existing channels and user settings. The opt-in native route has
      // its own non-vibrating channels so one alarm never vibrates twice.
      var defs = CHANNELS.map(function(id) {
        var banner=id.indexOf('ddlr_high_')===0, vibrate=id.indexOf('_vib_')>=0;
        var nativeVibration=id.indexOf('_v5_native_')>=0;
        var quiet=id.indexOf('_v6_quiet')>=0;
        return {id:id, name:quiet?'提醒 · 系统静音'+(banner?'横幅':'通知'):
          '提醒 · '+(banner?'横幅':'通知')+(nativeVibration?' · 原生兼容震动':(vibrate?' · 震动':' · 无震动'))
          +(id.endsWith('_sound')?' · 响铃':' · 静音'),importance:banner?4:3,vibration:vibrate,
          sound:quiet?(banner?'android.resource://com.ddlradar.app/raw/ddlr_silence':''):
            !nativeVibration && (banner||id.endsWith('_sound'))?'ddlr_silence.wav':''};
      });
      // 通道设置签名：与上次一致则跳过（避免反复删通道影响已排定的提醒）。
      // 注意：原生侧复用已存在通道，用户手动调整过的
      // 通道设置（静音/换铃声）不再被每次启动抹掉。
      var chSig = JSON.stringify(defs.map(function (d) { return [d.id, d.importance, d.vibration, d.sound]; }));
      var chSigStored = null;
      try { chSigStored = localStorage.getItem('ddlr_chsig'); } catch (e) {}
      if (force || chSig !== chSigStored) {
        var allOk = true;
        for (var i = 0; i < defs.length; i++) {
          var d = defs[i];
          try {
            await ddp.ensureChannel({
              id: d.id, name: d.name, importance: d.importance,
              vibration: d.vibration, sound: d.sound, force: !!force
            });
          } catch (e) {
            // 没建成功就不能记签名，否则后续启动会误以为通道已经完整。
            allOk = false;
          }
        }
        if (allOk) {
          try { localStorage.setItem('ddlr_chsig', chSig); } catch (e) {}
        }
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
      try { var s = await ddp.notifyStatus(); out.notify = !!s.granted; out.overlayAllowed = s.overlayAllowed === true; } catch (e) { out.notify = null; }
      try {
        var settings = (window.S && window.S.settings) || {};
        var channelId = channelIdFor(settings);
        var mode = vibrationModeFor(settings);
        var p = await DDL().pending({ channelId: channelId, vibrationMode: mode,
          vibrateInSilent: settings.vibrateInSilent !== false });
        out.pending = (p.items || []).length;
        out.queued = p.queued || 0;
        out.channelId = outputsEnabled(settings) ? (p.channelId || channelId) : null;
        out.nextAt = p.nextAt || 0;
        out.sysNextAt = p.sysNextAt || 0;
        out.schedMode = p.schedMode || '';
        out.clockMode = p.clockMode === true;
        out.deliveredLog = (p.deliveredLog || []).slice(-12);
        out.lastFireAt = p.lastFireAt || 0;
        out.lastFireId = p.lastFireId || 0;
        out.lastFireVia = p.lastFireVia || '';
        out.lastDelayMs = p.lastDelayMs;
        out.lastNotificationAllowed = p.lastNotificationAllowed;
        out.lastReceiverId = p.lastReceiverId || 0;
        out.lastReceiverAt = p.lastReceiverAt || 0;
        out.guard = p.guard === true;
        out.wakeHeld = p.wakeHeld === true;
        out.sdk = p.sdk || null;
        out.environment = p.environment || {};
        out.channelVibration = p.channelVibration;
        out.vibrationMode = p.vibrationMode || mode;
        out.vibrationStatus = p.vibrationStatus;
        out.lastVibrationStatus = p.lastVibrationStatus;
        out.lastVibrationChannel = p.lastVibrationChannel;
        out.lastVibrationUsage = p.lastVibrationUsage || '';
        out.vibrateInSilent = typeof p.vibrateInSilent === 'boolean' ? p.vibrateInSilent : settings.vibrateInSilent !== false;
        out.device = p.device || '';
        out.appVersion = p.appVersion;
        out.appBuild = p.appBuild;
        out.androidRelease = p.androidRelease || '';
        out.trace = p.trace || [];
        out.overlayAllowed = p.overlayAllowed === true;
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

    // 5 秒后由系统闹钟投递一条真实提醒，用于验证退 App 场景。
    // 自验证：排定后回读原生存储确认生效，toast 给出确定结论（单一出口）。
    test: async function (seconds) {
      seconds = seconds === 60 ? 60 : 5;
      var ddl = DDL();
      if (!ddl) return false;
      var settings = (window.S && S.settings) || {};
      if (!outputsEnabled(settings)) {
        setTimeout(function () {
          if (window.toast) toast('🧪 测试提醒', '提醒已关闭，因此不会响铃、震动或显示横幅', 'urgent');
        }, 5000);
        return true;
      }
      var at = Date.now() + seconds * 1000;
      try {
        // 通知通道模式尊重系统通知权限；原生兼容震动须由用户显式启用。
        var granted = true;
        var ddp = DDP();
        if (ddp) {
          try { var st = await ddp.notifyStatus(); granted = !!(st && st.granted); } catch (e) {}
        }
        if (ddp && ddp.configureDisplay) await ddp.configureDisplay({ bannerDuration: settings.bannerDuration || 'manual' });
        localStorage.setItem('ddlr_testAt', String(at));
        this._sig = null;
        // Exactly the same reconciliation and delivery route as a saved event.
        if (await this.sync(window.S) === false) throw new Error('后台排定失败');
        // 回读验证：这条闹钟确实在原生存储里
        var ok = false, readback = null;
        try {
          var p = await ddl.pending({ channelId: channelIdFor(settings), vibrationMode: vibrationModeFor(settings),
            vibrateInSilent: settings.vibrateInSilent !== false });
          readback = p;
          ok = (p.items || []).some(function (i) { return i.id === 990000001; });
        } catch (e) { throw new Error('无法确认系统闹钟，请重试'); }
        if (ok) {
          if (window.toast) {
            var head = '✅ 已排定，' + seconds + ' 秒后触发系统闹钟';
            var detail = '请退到桌面验收；闹钟送达与手机实际响铃、震动需分别确认。';
            if (!granted) detail += quietChannel(readback) && settings.vibrateInSilent !== false
              ? '系统通知权限尚未开启，静音时震动仍按你选择的偏好尝试。'
              : vibrationModeFor(settings) === 'native'
              ? '系统通知权限尚未开启，原生兼容震动将独立尝试。'
              : '系统通知权限未开启，系统通知与通知通道震动不可用。';
            if (settingEnabled(settings, 'vibrate') && vibrationModeFor(settings) === 'notification'
                && readback.channelVibration === false && !quietChannel(readback)) {
              detail += '当前系统提醒通道的震动已关闭，请打开「运行诊断」修复。';
            }
            window.toast(head, detail, 'urgent');
          }
          // +8s 自报告（留在 App 内时）：确证闹钟真的触发了，不用再开诊断
          setTimeout(function () {
            if (document.hidden) return; // 退到后台的验证场景以实际通知为准
            ddl.pending({ channelId: channelIdFor(settings), vibrationMode: vibrationModeFor(settings),
              vibrateInSilent: settings.vibrateInSilent !== false }).then(function (p) {
              var gone = !(p.items || []).some(function (i) { return i.id === 990000001; });
              var fired = p.lastFireId === 990000001 && p.lastFireAt >= at - 2000;
              if (fired && gone && window.toast) {
                var viaTxt = p.lastFireVia === 'exact' ? '经精确闹钟备份' : '经系统闹钟';
                var detail = '提醒已到达原生接收器；请以手机实际声音、震动和横幅为准。';
                if (settingEnabled(settings, 'vibrate')) {
                  if (p.lastVibrationStatus === 'blocked_preference' || p.vibrationStatus === 'blocked_preference') {
                    detail = '你已关闭“系统静音时震动”，此次按偏好保持静默；闹钟已送达。';
                  } else if (p.lastVibrationStatus === 'blocked_policy' || p.vibrationStatus === 'blocked_policy') {
                    detail = '此次震动被系统策略阻止；闹钟已送达，请查看运行诊断。';
                  } else if (quietChannel(p)) {
                    detail = '当前使用系统静音时的纯触觉路径；调用记录不能证明硬件已震动，请以实际触感为准。';
                  } else if (vibrationModeFor(settings) === 'native') {
                    detail = '当前使用原生兼容震动；调用记录不能证明硬件已震动，请以实际触感为准。';
                  } else if (p.channelVibration === false) {
                    detail = '系统已关闭当前提醒通道的震动，请打开「运行诊断」修复。闹钟送达不代表手机已震动。';
                  } else {
                    detail = '震动由系统通知通道处理；闹钟送达不能证明硬件已震动，请以实际触感为准。';
                  }
                }
                window.toast('🔔 测试闹钟已送达（' + viaTxt + '）', detail, 'urgent');
              } else if (!gone && window.toast) {
                window.toast('⚠️ 闹钟到点未触发', '系统未派发广播：请把「运行诊断」截图反馈', 'tt-urgent');
              }
            }).catch(function () {});
          }, seconds * 1000 + 3000);
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
    _queue: Promise.resolve(),
    sync: function (state) {
      // Serialize native reconciliations so an older apply cannot overwrite a
      // newly saved event. Capture scheduling data, excluding large wallpaper.
      var snapshot = state && JSON.parse(JSON.stringify(state, function (key, value) {
        return key === 'bgImage' ? undefined : value;
      }));
      var run = this._queue.catch(function () {}).then(function () { return N._sync(snapshot); });
      this._queue = run;
      return run;
    },
    _sync: async function (S) {
      if (!this.available() || !S) return;
      var settings = S.settings || {};
      var enabled = outputsEnabled(settings);
      var channelId = channelIdFor(settings);
      var vibrate = settingEnabled(settings, 'vibrate');
      var vibrateInSilent = settings.vibrateInSilent !== false;
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
      var sig = JSON.stringify([enabled, channelId, vibrate, vibrateInSilent, settings.bannerDuration,
        tiered, dailyTime, dayKey, slot10, localStorage.getItem('ddlr_testAt'), S.tasks, S.timetable || null]);
      if (this._sig === sig) return true;
      // ⚠️ 注意：这里**不能**先给 _sig 赋值。
      // 早前是 `this._sig = sig` 写在 try 之前，一旦下面 apply() 抛异常，
      // 签名已经被记账 → 后续所有 sync 都因「签名相同」直接 return，
      // 系统闹钟永久停止更新（一次失败 = 永久静默）。
      // 正确做法：成功拿到 apply 结果之后再记账，失败则保持 null 以便重试。

      try {
        var ddp = DDP();
        if (ddp && ddp.configureDisplay) await ddp.configureDisplay({ bannerDuration: settings.bannerDuration || 'manual' });
        var alarms = [];
        if (enabled) {
          var now = Date.now();
          // 关键：只排「近 7 天内」的提醒。
          // planReminders() 的远期每日提醒会为每个任务生成最多 400 天、
          // 每天一条的条目可能超过系统适合同时调度的数量；原生侧会保留队列，
          // 并在每次提醒触发后按时间顺序递补。7 天窗口让本地队列保持有界。
          var HORIZON = 7 * 86400000;
          var scheduledIds = new Set();
          for (var i = 0; i < S.tasks.length; i++) {
            var list = (typeof planReminders === 'function') ? planReminders(S.tasks[i], settings) : [];
            for (var j = 0; j < list.length; j++) {
              var r = list[j];
              // The web catch-up window must not schedule already-past tiers
              // as a burst of native alarms when a task is first saved.
              if (r.at < now - 2000) continue;
              var id = hashId(r.nativeKey || r.key);
              if (scheduledIds.has(id)) continue;
              scheduledIds.add(id);
              alarms.push({
                id: id,
                at: r.at,
                title: r.repeatDays === 1 ? '📅 每日提醒' : r.title.replace(/<[^>]+>/g, ''),
                body: r.body.replace(/<[^>]+>/g, ''),
                repeatDays: r.repeatDays || 0,
                repeatUntil: r.repeatUntil || 0,
                channelId: channelId,
                vibrate: vibrate,
                vibrateInSilent: vibrateInSilent
              });
            }
          }
          // 课表提醒：未来 7 天里每天的上课提醒。
          // 课程是周期性的（每周同一天同一节），所以要把窗口内每一天都算一遍，
          // 而不是只算「今天」——否则周四设的课，周一不会出现在系统闹钟里。
          try {
            if (window.Timetable && window.Timetable.planFor) {
              var term = S.timetable && S.timetable.termStart;
              var termBase = term ? new Date(term + 'T00:00:00').getTime() : 0;
              var base = Math.max(now, Number.isFinite(termBase) ? termBase : 0);
              for (var dOff = 0; dOff <= 14; dOff++) {
                var dayTs = base + dOff * 86400000;
                var cs = window.Timetable.planFor(dayTs);
                for (var ci = 0; ci < cs.length; ci++) {
                  var cr = cs[ci];
                  if (cr.at < now - 2000) continue;
                  var cid = hashId(cr.nativeKey || cr.key);
                  if (scheduledIds.has(cid)) continue;
                  scheduledIds.add(cid);
                  alarms.push({
                    id: cid,
                    at: cr.at,
                    title: cr.title,
                    body: cr.body,
                    repeatDays: cr.repeatDays || 0,
                    repeatUntil: cr.repeatUntil || 0,
                    channelId: channelId,
                    vibrate: vibrate,
                    vibrateInSilent: vibrateInSilent
                  });
                }
              }
            }
          } catch (e) { console.warn('[DDL雷达] 课表提醒生成失败', e); }
          alarms.sort(function (a, b) { return a.at - b.at; });
        }
        // 未触发的测试闹钟并入对账列表，防止被 apply() 当作多余条目撤销
        // （关闭提醒时不并入：跟随当前设置被撤销）
        try {
          var testAt = parseInt(localStorage.getItem('ddlr_testAt') || '0', 10);
          if (enabled && testAt > 0 && testAt >= Date.now() - 15 * 60000) {
            if (!scheduledIds.has(990000001)) {
              alarms.push({
                id: 990000001,
                at: testAt,
                title: '🧪 测试提醒',
                body: '与正常事件使用同一派发机制，请在桌面或锁屏验收',
                channelId: channelId,
                vibrate: vibrate,
                vibrateInSilent: vibrateInSilent
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
        return true;
      } catch (e) {
        // 失败时清空签名，保证下次一定会重试（而不是被去抖挡掉）；
        // 同时把 _synced 置 false，让 tickReminders 立刻接管补弹——
        // 否则会出现「系统没排上 + 应用内又不弹」的静默窗口。
        this._sig = null;
        this._synced = false;
        console.warn('[DDL雷达] 系统闹钟调度失败', e);
        return false;
      }
    }
  };

  window.NativeNotify = N;
})();
