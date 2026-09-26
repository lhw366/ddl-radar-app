/* ============================================================
   DDL雷达 · 原生系统提醒桥（Capacitor LocalNotifications）
   - 精确闹钟调度（allowWhileIdle，杀后台/重启后由插件恢复）
   - 提醒方式分通道：响铃+震动 / 仅震动 / 仅响铃 / 仅应用内横幅
   网页版（file:// / http）自动跳过，走应用内引擎。
   ============================================================ */
(function () {
  'use strict';

  function hashId(str) {
    var h = 2166136261;
    for (var i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 16777619); }
    return (h >>> 0) % 2147483647;
  }

  var LN = function () {
    return (window.Capacitor && Capacitor.Plugins && Capacitor.Plugins.LocalNotifications) || null;
  };

  var N = {
    available: function () {
      return !!(window.Capacitor && Capacitor.isNativePlatform && Capacitor.isNativePlatform() && LN());
    },
    _sig: null,
    _exactOn: null,

    init: async function () {
      if (!this.available()) return;
      var ln = LN();
      // 提醒方式通道：插件 createChannel 不支持 vibration（审计确认），
      // 用自建 DdlNotify 原生插件正确创建（删除重建保证设置生效）
      var defs = [
        { id: 'ddlr_full', name: '提醒 · 响铃+震动', importance: 4, vibration: true, sound: 'ddlr_chime.wav' },
        { id: 'ddlr_vib',  name: '提醒 · 仅震动',   importance: 4, vibration: true, sound: '' },
        { id: 'ddlr_ring', name: '提醒 · 仅响铃',   importance: 4, vibration: false, sound: 'ddlr_chime.wav' }
      ];
      var ddp = window.Capacitor && Capacitor.Plugins.DdlNotify;
      this._channelOk = [];
      for (var i = 0; i < defs.length; i++) {
        var d = defs[i];
        try {
          if (ddp) {
            await ddp.ensureChannel(d);
            this._channelOk.push({ id: d.id });
          } else {
            await ln.createChannel({
              id: d.id, name: d.name, importance: 4,
              sound: d.sound || undefined, visibility: 1
            });
            this._channelOk.push({ id: d.id });
          }
        } catch (e) { /* 单个通道失败不阻断 */ }
      }
      await this.ensurePermissions();
      await this.sync(window.S);
      // 插件带开机恢复接收器；这里再兜底刷一次
      document.addEventListener('visibilitychange', function () {
        if (!document.hidden && window.S) N.sync(window.S);
      });
    },

    ensurePermissions: async function () {
      var ln = LN();
      try {
        var p = await ln.checkPermissions();
        if (p.display !== 'granted') { await ln.requestPermissions(); }
      } catch (e) { /* ignore */ }
      var exact = await this.refreshExact();
      // 精确闹钟未授权 → 每次启动提醒一次（退 App 后通知可能延迟/丢失）
      if (exact === false && window.S && !window.S.settings.exactWarned) {
        window.S.settings.exactWarned = true;
        if (window.save) window.save();
        if (window.toast) {
          window.toast('⏰ 建议开启精确闹钟', '否则退 App 后提醒可能延迟或丢失：去「我的」页点黄色按钮', 'tt-urgent');
        }
      }
    },

    refreshExact: async function () {
      this._exactOn = null;
      // 首选自建插件的精确布尔值；退回官方插件（注意其字段是 exact_alarm: 'granted'/'denied'）
      var ddp = window.Capacitor && Capacitor.Plugins.DdlNotify;
      try {
        if (ddp) {
          var r = await ddp.exactStatus();
          this._exactOn = !!r.granted;
          return this._exactOn;
        }
      } catch (e) { /* fallback */ }
      try {
        var ln = LN();
        if (ln && typeof ln.checkExactNotificationSetting === 'function') {
          var s = await ln.checkExactNotificationSetting();
          this._exactOn = s && s.exact_alarm === 'granted';
          return this._exactOn;
        }
      } catch (e) { /* fallback */ }
      this._exactOn = true;
      return true;
    },

    openExactAlarm: async function () {
      var ddp = window.Capacitor && Capacitor.Plugins.DdlNotify;
      try {
        if (ddp) {
          var r = await ddp.openExactSettings();
          if (r && r.opened) return true;
        }
      } catch (e) { /* fallback */ }
      var ln = LN();
      try {
        if (ln && typeof ln.changeExactNotificationSetting === 'function') {
          await ln.changeExactNotificationSetting();
          return true;
        }
      } catch (e) { /* ignore */ }
      return false;
    },

    // 诊断：各链路状态（通知权限/精确闹钟/通道/已排定条数）
    diag: async function () {
      var out = { native: this.available() };
      if (!out.native) return out;
      var ln = LN();
      try { var p = await ln.checkPermissions(); out.notify = p.display === 'granted'; } catch (e) { out.notify = null; }
      out.exact = await this.refreshExact();
      try {
        var ddp = window.Capacitor && Capacitor.Plugins.DdlNotify;
        var l = ddp ? await ddp.listChannelIds() : await ln.listChannels();
        var ids = l.ids || (l.channels || []).map(function (c) { return c.id; });
        out.channels = ['ddlr_full', 'ddlr_vib', 'ddlr_ring'].filter(function (x) { return ids.indexOf(x) >= 0; }).length;
      } catch (e) { out.channels = 0; }
      try { var pend = await ln.getPending(); out.pending = (pend.notifications || []).length; } catch (e) { out.pending = -1; }
      return out;
    },

    // 10 秒后发一条系统通知，用于验证提醒链路（含退 App 场景）
    test: async function () {
      var ln = LN();
      if (!ln) return false;
      var mode = (window.S && S.settings.remindMode) || 'full';
      if (mode === 'silent') {
        setTimeout(function () {
          if (window.toast) toast('🧪 测试提醒', '静默模式：只显示应用内横幅，无系统通知', 'urgent');
        }, 10000);
        return true;
      }
      try {
        await ln.schedule({
          notifications: [{
            id: 990000001,
            title: '🧪 测试提醒',
            body: '看到这条 = 提醒链路通 ✓（退 App 也能弹）',
            schedule: { at: new Date(Date.now() + 10000), allowWhileIdle: true },
            channelId: 'ddlr_' + mode
          }]
        });
        if (window.toast) {
          window.toast('🧪 已排定测试', '现在退出 App 试试：10 秒后锁屏/桌面应弹出系统通知并震动');
        }
        return true;
      } catch (e) {
        console.warn('[DDL雷达] 测试提醒失败', e);
        if (window.toast) window.toast('⚠️ 排定失败', '请检查通知权限与精确闹钟权限');
        return false;
      }
    },

    // 任务/设置变化后重排系统提醒（带签名去抖）
    sync: async function (S) {
      if (!this.available() || !S) return;
      var ln = LN();
      var mode = (S.settings && S.settings.remindMode) || 'full';
      var sig = mode + '|' + S.tasks.map(function (t) {
        return t.id + ':' + (t.updatedAt || 0) + ':' + (t.done ? 1 : 0);
      }).join(',');
      if (this._sig === sig) return;
      this._sig = sig;

      try {
        var wanted = {};   // id -> true
        var notes = [];
        if (mode !== 'silent') {
          var now = Date.now();
          for (var i = 0; i < S.tasks.length; i++) {
            var list = (typeof planReminders === 'function') ? planReminders(S.tasks[i]) : [];
            for (var j = 0; j < list.length; j++) {
              var r = list[j];
              if (r.at <= now + 3000) continue;
              var id = hashId(r.key);
              if (wanted[id]) continue;
              wanted[id] = true;
              notes.push({
                id: id,
                title: r.title.replace(/<[^>]+>/g, ''),
                body: r.body.replace(/<[^>]+>/g, ''),
                schedule: { at: new Date(r.at), allowWhileIdle: true },
                channelId: 'ddlr_' + mode
              });
            }
          }
          notes.sort(function (a, b) { return a.schedule.at - b.schedule.at; });
          if (notes.length > 120) notes = notes.slice(0, 120);
          for (var k = 0; k < notes.length; k++) wanted[notes[k].id] = true;
        }
        var pend = await ln.getPending();
        var stale = (pend.notifications || []).filter(function (n) { return !wanted[n.id]; })
          .map(function (n) { return { id: n.id }; });
        if (stale.length) { try { await ln.cancel({ notifications: stale }); } catch (e) { /* ignore */ } }
        if (notes.length) { await ln.schedule({ notifications: notes }); }
      } catch (e) {
        console.warn('[DDL雷达] 系统提醒调度失败', e);
      }
    }
  };

  window.NativeNotify = N;
})();
