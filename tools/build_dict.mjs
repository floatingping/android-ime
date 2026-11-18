// 由小麥注音 (McBopomofo, MIT License) 的詞庫原始資料產生輸入法用的字典與詞典
// 用法：node tools/build_dict.mjs <McBopomofo/Source/Data 目錄> <輸出 assets 目錄>
// 需要的檔案：BPMFBase.txt、BPMFMappings.txt、phrase.occ（以及 LICENSE.txt 一併複製）
import fs from 'node:fs';
import path from 'node:path';

const [srcDir, outDir] = process.argv.slice(2);
if (!srcDir || !outDir) {
  console.error('usage: node tools/build_dict.mjs <McBopomofo data dir> <assets dir>');
  process.exit(1);
}

const READING = /^[ㄅ-ㄩ]+[ˊˇˋ˙]?$/u;
const isBmp = (s) => [...s].every((c) => c.codePointAt(0) <= 0xffff);
const lines = (file) => fs.readFileSync(path.join(srcDir, file), 'utf8').split(/\r?\n/).filter((l) => l && !l.startsWith('#'));

// 字詞頻率
const occ = new Map();
for (const line of lines('phrase.occ')) {
  const i = line.lastIndexOf(' ');
  occ.set(line.slice(0, i), Number(line.slice(i + 1)) || 0);
}

// 單字讀音：char -> Set(reading)
const charReadings = new Map();
for (const line of lines('BPMFBase.txt')) {
  const [ch, reading] = line.split(' ');
  if (!ch || [...ch].length !== 1 || !isBmp(ch) || !READING.test(reading)) continue;
  if (!charReadings.has(ch)) charReadings.set(ch, new Set());
  charReadings.get(ch).add(reading);
}

// 詞讀音
const phrases = [];
for (const line of lines('BPMFMappings.txt')) {
  const [text, ...readings] = line.split(' ');
  if (text.length < 2 || !isBmp(text) || text.length !== readings.length) continue;
  if (!readings.every((r) => READING.test(r))) continue;
  phrases.push({ text, readings });
}

// 破音字各讀音的權重：以含該字的詞的頻率推估（加一平滑）
const weight = new Map();
const key = (ch, r) => ch + ' ' + r;
for (const { text, readings } of phrases) {
  const f = occ.get(text) || 0;
  if (!f) continue;
  [...text].forEach((ch, i) => {
    if (charReadings.get(ch)?.has(readings[i])) weight.set(key(ch, readings[i]), (weight.get(key(ch, readings[i])) || 0) + f);
  });
}

// 依讀音分組、組內頻率由高到低，輸入法載入時就不必再排序
const byReadingThenFreq = (a, b) => (a.reading < b.reading ? -1 : a.reading > b.reading ? 1 : b.freq - a.freq);

const chars = [];
for (const [ch, readings] of charReadings) {
  const total = occ.get(ch) || 0;
  const ws = [...readings].map((r) => [r, 1 + (weight.get(key(ch, r)) || 0)]);
  const sum = ws.reduce((a, [, w]) => a + w, 0);
  for (const [r, w] of ws) chars.push({ text: ch, reading: r, freq: Math.max(1, Math.round(total * w / sum)) });
}
chars.sort(byReadingThenFreq);
const charLines = chars.map((c) => `${c.text} ${c.reading} ${c.freq}`);

const seen = new Set();
const words = [];
for (const { text, readings } of phrases) {
  const reading = readings.join(' ');
  if (seen.has(text + ' ' + reading)) continue;
  seen.add(text + ' ' + reading);
  words.push({ text, reading, freq: occ.get(text) || 1 });
}
words.sort(byReadingThenFreq);
const wordLines = words.map((w) => `${w.text} ${w.freq} ${w.reading}`);

const header = (desc, fmt) =>
  `# ${desc}\n# 格式：${fmt}\n# 資料來源：McBopomofo 小麥注音輸入法詞庫 (MIT License)，見 McBopomofo-LICENSE.txt\n`;
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'chars.txt'), header('字典', '字 讀音 頻率') + charLines.join('\n') + '\n');
fs.writeFileSync(path.join(outDir, 'words.txt'), header('詞典', '詞 頻率 讀音…') + wordLines.join('\n') + '\n');
fs.copyFileSync(path.join(srcDir, 'LICENSE.txt'), path.join(outDir, 'McBopomofo-LICENSE.txt'));
console.log(`chars: ${charLines.length} (${charReadings.size} 字), words: ${wordLines.length}`);
