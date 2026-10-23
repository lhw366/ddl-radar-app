const fs = require('node:fs');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');
const source = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const h = harness(['timetable.js']);
const result = h.context.Timetable._parseBlocks(source.blocks, {width: source.imageWidth, height: source.imageHeight});
fs.writeFileSync(process.argv[2].replace(/\.json$/, '-courses.json'), JSON.stringify(result, null, 2));
console.log(JSON.stringify(result.courses.map(c => ({day:c.day,start:c.start,end:c.end,name:c.name,room:c.room})), null, 2));
const expected = [
  [1,3,4,'线性代数','理教408'],
  [2,1,2,'高等数学','二教309'], [2,5,6,'英语词汇与英文文化','二教313'],
  [2,7,8,'周易','一教203'], [2,10,11,'地球系统模拟','地学304'],
  [3,1,2,'线性代数','理教408'], [3,5,6,'计算概论A','理教403'],
  [3,7,9,'习近平新时代中国特色社会主义思想概论','哲学201'],
  [4,1,2,'太极拳',''], [4,3,4,'高等数学','二教309'], [4,10,11,'人工智能初级研讨班','三教203'],
  [5,3,4,'计算概论A','理教403'], [5,5,6,'计算概论A上机实习课','计算中心二层7-10号机房']
];
assert.equal(result.degraded, false, 'Reference timetable must retain its two-dimensional grid');
assert.equal(result.courses.length, expected.length, 'Room/remark lines must never become extra courses');
for (const [day,start,end,name,room] of expected) {
  const course = result.courses.find(c => c.day === day && c.start === start && c.end === end);
  assert.ok(course, `Missing weekday ${day}, lessons ${start}-${end}: ${name}`);
  assert.ok(course.name.includes(name), `Wrong course: ${JSON.stringify(course)}`);
  assert.equal(course.room, room, `Wrong room: ${JSON.stringify(course)}`);
  assert.equal(course.week, 'all', 'Recitation notes must not change the regular course week');
}
console.log('PASS: real ML Kit reference image → all 13 courses, rooms, days and lesson spans');
