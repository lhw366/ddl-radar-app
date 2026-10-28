const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

test('APK feedback uses external HTTPS navigation without the unsupported App.openUrl method', () => {
  let popups = 0;
  const location = { href: '' };
  const h = harness(['app.js'], { globals: {
    location, open: () => popups++,
    Capacitor: { isNativePlatform: () => true, Plugins: { App: {} } }
  } });
  h.run("openExternal('https://github.com/lhw366/ddl-radar-app/issues')");
  assert.equal(location.href, 'https://github.com/lhw366/ddl-radar-app/issues');
  assert.equal(popups, 0);
  h.run("openExternal('javascript:alert(1)')");
  assert.equal(location.href, 'https://github.com/lhw366/ddl-radar-app/issues');
});

test('the task sheet uses an in-app date and time picker instead of the native white dialog', () => {
  const h = harness(['nativenotify.js', 'app.js']);
  h.run('openEditor(null)');
  h.run('openDateTimePicker("fDue")');
  assert.equal(h.el('dateTimeWrap').classList.contains('hidden'), false);
  assert.equal(h.el('dtDays').children.length, 42);
  assert.match(h.el('dtSummary').textContent, /年.*月.*日/);
  h.run('applyDateTimeQuick("tomorrow9"); confirmDateTimePicker()');
  assert.match(h.el('fDue').value, /T09:00$/);
  assert.equal(h.el('dateTimeWrap').classList.contains('hidden'), true);
  assert.match(h.el('fDuePicker').textContent, /09:00/);
});

test('calendar day clicks select the intended date and allow an exact minute', async () => {
  const h = harness(['nativenotify.js', 'app.js']);
  h.run('bindDateTimePicker(); openEditor(null)');
  await h.el('fDuePicker').click();
  h.run('moveDateTimeMonth(3)');
  const day = h.el('dtDays').children.find(button => button.dataset.dtDay === '2027,0,31');
  assert.ok(day, 'January 31 is present in the next-year calendar');
  await h.el('dtDays').dispatch('click', { target: { closest: () => day } });
  h.el('dtHour').value = '14';
  h.el('dtMinute').value = '17';
  await h.el('dtConfirm').click();
  assert.equal(h.el('fDue').value, '2027-01-31T14:17');
  assert.match(h.el('fDuePicker').textContent, /2027年1月31日.*14:17/);

  h.run('openDateTimePicker("fDue")');
  h.el('dtHour').value = '25';
  h.el('dtMinute').value = '';
  await h.el('dtConfirm').click();
  assert.equal(h.el('fDue').value, '2027-01-31T14:17', 'Invalid clock input preserves the selected time');
});

test('closing the date picker keeps the task editor and its original time', () => {
  const h = harness(['nativenotify.js', 'app.js']);
  h.run('openEditor(null)');
  const due = h.el('fDue').value;
  h.run('openDateTimePicker("fDue"); applyDateTimeQuick("week"); closeTopSheet()');
  assert.equal(h.el('dateTimeWrap').classList.contains('hidden'), true);
  assert.equal(h.el('editorWrap').classList.contains('hidden'), false);
  assert.equal(h.el('fDue').value, due);
  h.el('editor').scrollTop = 500;
  h.run('closeTopSheet(); openEditor(null)');
  assert.equal(h.el('editor').scrollTop, 0, 'A new task starts at the top of its form');
});

test('task sheets scroll vertically and can be dismissed by pull-down or edge-back gestures', async () => {
  const h = harness(['gestures.js'], { globals: { innerWidth: 390 } });
  const closed = [];
  h.context.AppBridge = { closeSheet: name => closed.push(name) };
  const mask = h.el('editorWrap').querySelector('.mask');
  mask.addEventListener('click', () => h.context.AppBridge.closeSheet('editor'));
  h.run('AppGestures.init()');
  h.el('editorWrap').classList.remove('hidden');
  const sheet = h.el('editor');
  sheet.scrollTop = 0;
  const point = (x, y) => ({ identifier: 1, clientX: x, clientY: y });
  await sheet.dispatch('touchstart', { touches: [point(160, 120)] });
  await sheet.dispatch('touchmove', { touches: [point(164, 230)] });
  await sheet.dispatch('touchend', { changedTouches: [point(164, 230)] });
  await sheet.dispatch('touchstart', { touches: [point(12, 160)] });
  await sheet.dispatch('touchmove', { touches: [point(110, 164)] });
  await sheet.dispatch('touchend', { changedTouches: [point(110, 164)] });
  assert.deepEqual(closed, ['editor', 'editor']);

  sheet.scrollTop = 150;
  await sheet.dispatch('touchstart', { touches: [point(160, 120)] });
  await sheet.dispatch('touchmove', { touches: [point(164, 230)] });
  await sheet.dispatch('touchend', { changedTouches: [point(164, 230)] });
  assert.equal(closed.length, 2, 'Scrolling down inside the form does not dismiss it');
});

test('accounts migrate existing installs to administrators while fresh installs stay simple', () => {
  const h = harness(['nativenotify.js', 'app.js']);
  const old = h.run(`normalizeState({ onboarded: true, tasks: [], fired: [], settings: {} })`);
  const fresh = h.run(`normalizeState({ onboarded: false, tasks: [], fired: [], settings: {} })`);
  assert.equal(old.profile.role, 'admin');
  assert.equal(fresh.profile.role, 'user');
  assert.match(old.profile.id, /^usr/);

  h.context.S.profile = fresh.profile;
  h.run('syncRoleUI()');
  assert.equal(h.el('testRemBtn').hidden, true);
  assert.equal(h.el('ttAdminTools').hidden, true);
  assert.equal(h.el('vibrationNowBtn').hidden, true);
  h.context.S.profile.role = 'admin';
  h.run('syncRoleUI()');
  assert.equal(h.el('testRemBtn').hidden, false);
  assert.equal(h.el('ttAdminTools').hidden, false);
  assert.equal(h.el('vibrationNowBtn').hidden, false);

  const restored = h.run('normalizeState(JSON.parse(JSON.stringify(S)))');
  assert.equal(restored.profile.id, fresh.profile.id);
  assert.equal(restored.profile.role, 'admin');
  const malformed = h.run('normalizeState({ tasks: [], profile: [] })');
  assert.equal(Array.isArray(malformed.profile), false, 'Recovery always stores a plain account record');
});

test('feedback excludes task content and UI toasts deduplicate without merging reminders', () => {
  const h = harness(['nativenotify.js', 'app.js']);
  h.context.S.tasks = [{ id: 'secret', name: '不应上传的事项', dueAt: Date.now() + 3600000 }];
  h.el('feedbackText').value = '课表导入出现重叠';
  const payload = h.run('feedbackPayload()');
  assert.match(payload, /课表导入出现重叠/);
  assert.doesNotMatch(payload, /不应上传的事项/);
  assert.match(payload, /未附带事项、课表或本机账号 ID/);

  h.run(`toast('🗑️ 已删除课程',''); toast('🗑️ 已删除课程','')`);
  assert.equal(h.el('toastWrap').children.length, 1);
  h.run(`toast('事项 A 到点','', 'urgent'); toast('事项 B 到点','', 'urgent')`);
  assert.equal(h.el('toastWrap').children.length, 3, 'Actual reminders remain separate');
});
