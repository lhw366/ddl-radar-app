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

    init: async function () {
      if (!this.available()) return;
      await this.ensureChannels();
      await this.ensureNotifyPermission();
      await this.sync(window.S);
      // 原生 BootReceiver 负责重启/覆盖安装后恢复；这里兜底：切回前台时对账一次
      document.addEventListener('visibilitychange', function () {
        if (!document.hidden && window.S) N.sync(window.S);
      });
    },

    ensureChannels: async function () {
      var ddp = DDP();
      if (!ddp) return;
      // 通道承载一切表现（系统闹钟模式）：HIGH 重要性弹横幅、铃声/震动由系统
      // 在通知送达时播放（不依赖进程存活）；到点路径只负责把通知交给系统
      var defs = [
        { id: 'ddlr_full', name: '提醒 · 响铃+震动', importance: 4, vibration: true, sound: 'ddlr_chime.wav' },
        { id: 'ddlr_vib',  name: '提醒 · 仅震动',   importance: 4, vibration: true, sound: '' },
        { id: 'ddlr_ring', name: '提醒 · 仅响铃',   importance: 4, vibration: false, sound: 'ddlr_chime.wav' }
      ];
      // 通道设置签名：与上次一致则跳过删除重建（避免反复删通道影响已排定的提醒）
      var chSig = JSON.stringify(defs.map(function (d) { return [d.id, d.importance, d.vibration, d.sound]; }));
      var chSigStored = null;
      try { chSigStored = localStorage.getItem('ddlr_chsig'); } catch (e) {}
      if (chSig !== chSigStored) {
        for (var i = 0; i < defs.length; i++) {
          try { await ddp.ensureChannel(defs[i]); } catch (e) { /* 单个通道失败不阻断 */ }
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
        var p = await DDL().pending();
        out.pending = (p.items || []).length;
        out.nextAt = p.nextAt || 0;
        out.sysNextAt = p.sysNextAt || 0;
        out.lastFireAt = p.lastFireAt || 0;
        out.lastFireId = p.lastFireId || 0;
        out.lastFireVia = p.lastFireVia || '';
        out.guard = p.guard === true;
        out.sdk = p.sdk || null;
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

    // 任务/设置变化后整表协调系统闹钟（带签名去抖；原生侧多退少补）
    sync: async function (S) {
      if (!this.available() || !S) return;
      var mode = (S.settings && S.settings.remindMode) || 'full';
      var sig = mode + '|' + S.tasks.map(function (t) {
        return t.id + ':' + (t.updatedAt || 0) + ':' + (t.done ? 1 : 0);
      }).join(',');
      if (this._sig === sig) return;
      this._sig = sig;

      try {
        var alarms = [];
        if (mode !== 'silent') {
          var now = Date.now();
          for (var i = 0; i < S.tasks.length; i++) {
            var list = (typeof planReminders === 'function') ? planReminders(S.tasks[i]) : [];
            for (var j = 0; j < list.length; j++) {
              var r = list[j];
              if (r.at <= now + 3000) continue;
              var id = hashId(r.key);
              var dup = alarms.some(function (a) { return a.id === id; });
              if (dup) continue;
              alarms.push({
                id: id,
                at: r.at,
                title: r.title.replace(/<[^>]+>/g, ''),
                body: r.body.replace(/<[^>]+>/g, ''),
                channelId: 'ddlr_' + mode
              });
            }
          }
          alarms.sort(function (a, b) { return a.at - b.at; });
          if (alarms.length > 120) alarms = alarms.slice(0, 120);
        }
        // 未触发的测试闹钟并入对账列表，防止被 apply() 当作多余条目撤销
        // （静默模式不并入：跟随当前模式被撤销，符合「不发系统通知」语义）
        try {
          var testAt = parseInt(localStorage.getItem('ddlr_testAt') || '0', 10);
          if (mode !== 'silent' && testAt > Date.now() + 3000) {
            if (!alarms.some(function (a) { return a.id === 990000001; })) {
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
        if (r && (r.added > 0 || r.cancelled > 0)) {
          console.log('[DDL雷达] 系统闹钟对账完成：+' + r.added + ' -' + r.cancelled +
            '，下一条 ' + (r.nextAt ? new Date(r.nextAt).toLocaleString() : '无'));
        }
      } catch (e) {
        console.warn('[DDL雷达] 系统闹钟调度失败', e);
      }
    }
  };

  window.NativeNotify = N;
})();
