"use strict";

// 依存追加なしの決定論的 zip ライター (node:zlib のみ使用)。
// - エントリはパス昇順ソート
// - DOS日時は固定 (1980-01-01 00:00:00) — mtimeを含めないことで同一入力→バイト同一出力にする
// - 圧縮は zlib.deflateRawSync (level 9)
// - CRC32 は自前実装 (テーブル方式)
// - UTF-8 フラグ (bit 11) を立てて日本語ファイル名等を許容する

const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

const DOS_DATE_1980 = 0x0021; // 1980-01-01 (year<<9 | month<<5 | day)
const DOS_TIME_0000 = 0x0000; // 00:00:00

// CRC32テーブル (IEEE 802.3 多項式 0xEDB88320)
const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    }
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buf) {
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) {
    crc = CRC_TABLE[(crc ^ buf[i]) & 0xFF] ^ (crc >>> 8);
  }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

function buildLocalHeader(nameBuf, crc, compSize, uncompSize) {
  const header = Buffer.alloc(30);
  header.writeUInt32LE(0x04034b50, 0); // local file header signature
  header.writeUInt16LE(20, 4); // version needed
  header.writeUInt16LE(0x0800, 6); // general purpose flag: bit11 = UTF-8
  header.writeUInt16LE(8, 8); // compression method: deflate
  header.writeUInt16LE(DOS_TIME_0000, 10);
  header.writeUInt16LE(DOS_DATE_1980, 12);
  header.writeUInt32LE(crc, 14);
  header.writeUInt32LE(compSize, 18);
  header.writeUInt32LE(uncompSize, 22);
  header.writeUInt16LE(nameBuf.length, 26);
  header.writeUInt16LE(0, 28); // extra field length
  return header;
}

function buildCentralHeader(nameBuf, crc, compSize, uncompSize, localOffset) {
  const header = Buffer.alloc(46);
  header.writeUInt32LE(0x02014b50, 0); // central directory signature
  header.writeUInt16LE(20, 4); // version made by
  header.writeUInt16LE(20, 6); // version needed
  header.writeUInt16LE(0x0800, 8); // general purpose flag
  header.writeUInt16LE(8, 10); // compression method
  header.writeUInt16LE(DOS_TIME_0000, 12);
  header.writeUInt16LE(DOS_DATE_1980, 14);
  header.writeUInt32LE(crc, 16);
  header.writeUInt32LE(compSize, 20);
  header.writeUInt32LE(uncompSize, 24);
  header.writeUInt16LE(nameBuf.length, 28);
  header.writeUInt16LE(0, 30); // extra field length
  header.writeUInt16LE(0, 32); // comment length
  header.writeUInt16LE(0, 34); // disk number start
  header.writeUInt16LE(0, 36); // internal file attributes
  header.writeUInt32LE(0, 38); // external file attributes
  header.writeUInt32LE(localOffset, 42);
  return header;
}

// entries: [{path, data:Buffer}]。path は "/" 区切りの相対パス。
// 同一入力 (path群+内容) から常に同一バイト列の zip を生成し、outPath へ書き込む。
function buildZip(entries, outPath) {
  const sorted = [...entries].sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));

  const localChunks = [];
  const centralChunks = [];
  let offset = 0;

  for (const entry of sorted) {
    const nameBuf = Buffer.from(entry.path, "utf8");
    const data = entry.data;
    const crc = crc32(data);
    const compressed = zlib.deflateRawSync(data, { level: 9 });

    const localHeader = buildLocalHeader(nameBuf, crc, compressed.length, data.length);
    localChunks.push(localHeader, nameBuf, compressed);

    centralChunks.push(buildCentralHeader(nameBuf, crc, compressed.length, data.length, offset));
    centralChunks.push(nameBuf);

    offset += localHeader.length + nameBuf.length + compressed.length;
  }

  const centralStart = offset;
  const centralBuf = Buffer.concat(centralChunks);
  const centralSize = centralBuf.length;

  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4); // disk number
  eocd.writeUInt16LE(0, 6); // disk with central dir
  eocd.writeUInt16LE(sorted.length, 8); // entries on this disk
  eocd.writeUInt16LE(sorted.length, 10); // total entries
  eocd.writeUInt32LE(centralSize, 12);
  eocd.writeUInt32LE(centralStart, 16);
  eocd.writeUInt16LE(0, 20); // comment length

  const out = Buffer.concat([...localChunks, centralBuf, eocd]);
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, out);
  return out;
}

module.exports = { buildZip, crc32 };
