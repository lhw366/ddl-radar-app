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
      // 提醒方式通道（Android 通道一经创建不可改，每种方式一个固定通道）
      var defs = [
        { id: 'ddlr_full', name: '提醒 · 响铃+震动', importance: 5, visibility: 1, sound: 'ddlr_chime.wav', vibration: true },
        { id: 'ddlr_vib',  name: '提醒 · 仅震动',   importance: 5, visibility: 1, vibration: true },
        { id: 'ddlr_ring', name: '提醒 · 仅响铃',   importance: 5, visibility: 1, sound: 'ddlr_chime.wav' }
      ];
      for (var i = 0; i < defs.length; i++) {
        try { await ln.createChannel(defs[i]); } catch (e) { /* 已存在 */ }
      }
      // 通道创建结果校验：缺失时调度将不带 channelId（走默认通道），避免通知静默失败
      try {
        var list = await ln.listChannels();
        var ok = {};
        (list.channels || []).forEach(function (c) { ok[c.id] = true; });
        defs.forEach(function (d) { if (!ok[d.id]) d._missing = true; });
        this._channelOk = defs;
      } catch (e) { this._channelOk = null; }
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
      await this.refreshExact();
    },

    refreshExact: async function () {
      var ln = LN();
      this._exactOn = null;
      try {
        if (typeof ln.checkExactNotificationSetting === 'function') {
          var s = await ln.checkExactNotificationSetting();
          this._exactOn = s && (s.exactNotificationSetting === 'ENABLED' || s.exactNotificationSetting === 'ENABLED_V2' || s.exactNotificationSetting === 'GRANTED');
        } else {
          this._exactOn = true; // 插件较旧无此 API，视为可用
        }
      } catch (e) { /* ignore */ }
      return this._exactOn;
    },

    openExactAlarm: async function () {
      var ln = LN();
      try {
        if (typeof ln.changeExactNotificationSetting === 'function') {
          await ln.changeExactNotificationSetting();
          return true;
        }
      } catch (e) { /* ignore */ }
      return false;
    },

    // 10 秒后发一条系统通知，用于验证提醒链路
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
            body: '看到这条系统通知 = 提醒链路正常 ✓',
            schedule: { at: new Date(Date.now() + 10000), allowWhileIdle: true },
            channelId: 'ddlr_' + mode
          }]
        });
        return true;
      } catch (e) {
        console.warn('[DDL雷达] 测试提醒失败', e);
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
                channelId: (this._channelOk || []).filter(function (c) { return !c._missing; }).map(function (c) { return c.id; }).indexOf('ddlr_' + mode) >= 0
                  ? 'ddlr_' + mode : undefined
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
