/* A strict cloud boundary: no wallpaper, credentials, diagnostics or local roles. */
(function (root) {
  'use strict';
  const taskKeys = ['id', 'name', 'detail', 'quadrant', 'isRange', 'startAt', 'dueAt', 'tiers',
    'custom', 'daily', 'done', 'doneAt', 'createdAt', 'updatedAt', 'repeat', 'nextRepeatId'];
  const courseKeys = ['id', 'name', 'room', 'day', 'start', 'end', 'week', 'note', 'remindMin', 'needFix'];
  const pick = (obj, keys) => Object.fromEntries(keys.filter(k => obj[k] !== undefined).map(k => [k, obj[k]]));
  const copy = obj => JSON.parse(JSON.stringify(obj));
  function snapshot(state) {
    const tt = state.timetable;
    return copy({ schema: 1, name: state.profile.name,
      tasks: state.tasks.map(t => pick(t, taskKeys)),
      timetable: tt ? { courses: tt.courses.map(c => pick(c, courseKeys)),
        slots: Array.isArray(tt.slots) ? tt.slots : [], termStart: tt.termStart || null,
        schoolPreset: tt.schoolPreset || 'custom' } : null });
  }
  // Stable comparison ignores object key order, never array order.
  function fingerprint(value) {
    if (Array.isArray(value)) return '[' + value.map(fingerprint).join(',') + ']';
    if (value && typeof value === 'object') return '{' + Object.keys(value).sort()
      .map(k => JSON.stringify(k) + ':' + fingerprint(value[k])).join(',') + '}';
    return JSON.stringify(value);
  }
  function validate(payload) {
    if (!payload || payload.schema !== 1 || typeof payload.name !== 'string' ||
        payload.name.length > 24 || !Array.isArray(payload.tasks) || payload.tasks.length > 5000)
      throw new Error('云端数据格式不兼容，请先更新 App');
    if (payload.timetable != null && (!Array.isArray(payload.timetable.courses) ||
        !Array.isArray(payload.timetable.slots) || payload.timetable.slots.length > 20 ||
        payload.timetable.slots.some(s => s !== null && !/^([01]\d|2[0-3]):[0-5]\d-([01]\d|2[0-3]):[0-5]\d$/.test(s))))
      throw new Error('云端课表格式不正确');
    return payload;
  }
  function apply(payload, local, normalize) {
    validate(payload);
    const next = copy(local);
    next.tasks = copy(payload.tasks);
    next.timetable = copy(payload.timetable);
    next.profile.name = payload.name;
    // Normalize all reminder/task fields using the existing app validator.
    // Local fired markers and device-specific settings stay on this device.
    return normalize(next);
  }
  const api = { snapshot, fingerprint, validate, apply };
  root.CloudModel = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window === 'undefined' ? globalThis : window);
