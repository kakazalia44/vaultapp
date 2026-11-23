// Проверка страницы open-container.html на контейнере, созданном независимой реализацией (Python).
// Запуск: node tools/test-container.js путь/к/vector.bin
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const html = fs.readFileSync(path.join(__dirname, 'open-container.html'), 'utf8');
const js = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const mod = { exports: {} };
new Function('module', 'crypto', 'document', js)(mod, globalThis.crypto, undefined);
const { openBox, listEntries, streamEntry } = mod.exports;

(async () => {
  const blob = new Blob([fs.readFileSync(process.argv[2])]);

  let failed = false;
  try { await openBox(blob, 'неверный-пароль'); } catch (e) { failed = true; }
  assert(failed, 'неверный пароль не отвергнут');

  const box = await openBox(blob, 'тест-пароль-123');
  const entries = await listEntries(box);
  assert.deepStrictEqual(entries.map((e) => e.path), ['hello.txt', 'sub', 'sub/data.bin', 'sub/empty-dir', '😀 имя.txt']);
  assert.deepStrictEqual(entries.map((e) => e.dir), [false, true, false, true, false]);

  const read = async (e) => {
    const parts = [];
    for await (const p of streamEntry(box, e, 1000)) parts.push(Buffer.from(p));
    return Buffer.concat(parts);
  };
  assert.strictEqual((await read(entries[0])).toString('utf8'), 'Привет, мир! 😀\n');
  const data = await read(entries[2]);
  assert.strictEqual(data.length, 70000);
  for (let i = 0; i < 70000; i++) assert.strictEqual(data[i], (i * 31) % 251);
  assert.strictEqual((await read(entries[4])).toString('utf8'), 'emoji');
  assert.strictEqual(entries[0].mtime, 1700000000000);
  console.log('JS: все проверки пройдены');
})().catch((e) => { console.error('JS: ОШИБКА', e); process.exit(1); });
