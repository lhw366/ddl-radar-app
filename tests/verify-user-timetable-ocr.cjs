// Verify the actual bundled ML Kit output for both timetables in the user's bug report.
const fs = require('node:fs');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');
const profiles = {
  mobile: [
    [1, 2, 2, '高等代数'], [1, 6, 7, '习近平'],
    [2, 1, 1, '程序设计'], [2, 4, 5, '高等数学'], [2, 6, 7, '程序设计'],
    [3, 2, 2, '高等代数'], [3, 3, 3, '数字时代'],
    [4, 1, 1, '高等数学'], [4, 2, 2, '程序设计'], [4, 5, 5, '大学英语'],
    [5, 1, 1, '篮球'], [5, 4, 4, '大学生思想文化素养']
  ],
  table: [
    [1, 3, 4, '英语公众演讲'], [1, 5, 6, '普通生物学'], [1, 7, 8, '军事理论'],
    [2, 1, 2, '高等数学'], [2, 5, 6, '普通化学'], [2, 7, 8, '神经动物行为学'], [2, 10, 11, '习题课'],
    [3, 5, 8, '普通生物学实验'], [3, 10, 11, '普通生物学', 'even'],
    [4, 3, 4, '体适能'], [4, 10, 11, '普通化学'],
    [5, 3, 4, '高等数学'], [5, 10, 11, '大学生思想文化素养'],
    [6, 3, 4, '神经生物学研究前沿', 'odd']
  ]
};
const [profile, filename] = process.argv.slice(2);
assert.ok(profiles[profile] && filename, 'Usage: node tests/verify-user-timetable-ocr.cjs mobile|table output.json');
const source = JSON.parse(fs.readFileSync(filename, 'utf8'));
const h = harness(['timetable.js']);
const result = h.context.Timetable._parseBlocks(source.blocks, { width: source.imageWidth, height: source.imageHeight });
fs.writeFileSync(filename.replace(/\.json$/, '-courses.json'), JSON.stringify(result, null, 2));
console.log(JSON.stringify(result, null, 2));
assert.equal(result.degraded, false, 'Both documented screenshots must retain their row and weekday structure');
assert.equal(result.courses.length, profiles[profile].length, 'Room, times and footer notes must never become courses');
for (const [day, start, end, name, week] of profiles[profile]) {
  const match = result.courses.find(course => course.day === day && course.start === start && course.end === end);
  assert.ok(match, `Missing weekday ${day}, periods ${start}-${end}, course ${name}`);
  if (!match.name.includes(name)) {
    // ML Kit confuses one small glyph in this supplied screenshot. Do not
    // replace the observed text with a hardcoded course; require visible review.
    const observed = Array.from(match.name.slice(0, name.length));
    const differences = Array.from(name).filter((glyph, i) => glyph !== observed[i]).length;
    assert.equal(observed.length, name.length, 'A truncated/missing title must not pass');
    assert.equal(differences, 1, `Unexpected OCR title: ${match.name}`);
    assert.equal(match.needsReview, true, 'An uncertain glyph must be flagged');
    assert.ok(match.reviewReason, 'The importer must explain the uncertainty');
    console.log(`REVIEW REQUIRED: ${match.name}`);
  }
  if (week) assert.equal(match.week, week, 'Explicit odd/even markers must be retained');
}
assert.equal(result.slots?.length, profile === 'mobile' ? 7 : 12, 'Retain every printed lesson time; do not fabricate the empty final row');
assert.equal(result.slots[0], profile === 'mobile' ? '08:00-09:30' : '08:00-08:50');
if (profile === 'mobile') assert.equal(result.slots[7], undefined, 'No time was printed or recognized for the empty eighth row');
console.log(`PASS: actual user ${profile} screenshot, ${result.courses.length} placements, no bogus room courses`);
