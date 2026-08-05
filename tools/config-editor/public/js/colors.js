"use strict";

// 色ユーティリティ (全色部品の土台)。すべて純関数・依存なし・ブラウザ/Node 双方で動作する。
//
// ロスレス原則: parse は「対応できない装飾コード / 未対応タグ」を含む値を丸めず、
//   ok:false + raw(原文) を返す。呼び出し側 (richTextInput 等) は ok:false なら生テキスト編集へ
//   フォールバックすることで既存 config の記法を壊さない。
// 記法モードはフィールドごとに固定する (legacy=&コード / minimessage=<color>タグ)。
// 全体を1記法へ正規化しない (プラグインごとにパーサが異なるため)。

(function (root) {
  // 16色。code=&コード / name=MiniMessage色名 / ja=日本語 / hex=表示色。
  const MC_COLORS = [
    { code: "0", name: "black", ja: "黒", hex: "#000000" },
    { code: "1", name: "dark_blue", ja: "濃い青", hex: "#0000AA" },
    { code: "2", name: "dark_green", ja: "濃い緑", hex: "#00AA00" },
    { code: "3", name: "dark_aqua", ja: "濃い水色", hex: "#00AAAA" },
    { code: "4", name: "dark_red", ja: "濃い赤", hex: "#AA0000" },
    { code: "5", name: "dark_purple", ja: "濃い紫", hex: "#AA00AA" },
    { code: "6", name: "gold", ja: "金", hex: "#FFAA00" },
    { code: "7", name: "gray", ja: "灰色", hex: "#AAAAAA" },
    { code: "8", name: "dark_gray", ja: "濃い灰色", hex: "#555555" },
    { code: "9", name: "blue", ja: "青", hex: "#5555FF" },
    { code: "a", name: "green", ja: "緑", hex: "#55FF55" },
    { code: "b", name: "aqua", ja: "水色", hex: "#55FFFF" },
    { code: "c", name: "red", ja: "赤", hex: "#FF5555" },
    { code: "d", name: "light_purple", ja: "明るい紫", hex: "#FF55FF" },
    { code: "e", name: "yellow", ja: "黄", hex: "#FFFF55" },
    { code: "f", name: "white", ja: "白", hex: "#FFFFFF" }
  ];

  // MiniMessage の色名エイリアス (16色の別名)。表示hexは16色に合わせる。
  const MM_ALIASES = { grey: "gray", dark_grey: "dark_gray" };

  const HEX_BY_CODE = {};
  const CODE_BY_HEX = {};
  const COLOR_BY_NAME = {};
  for (const c of MC_COLORS) {
    HEX_BY_CODE[c.code] = c.hex;
    CODE_BY_HEX[c.hex.toUpperCase()] = c.code;
    COLOR_BY_NAME[c.name] = c;
  }

  // レガシー装飾コード (色以外)。'r'(リセット) のみ未対応でリッチ編集不可 (フォールバック)。
  // klmno は下記 DECO_SPECS の legacyCode として個別に対応する。
  const LEGACY_RESET_CODE = "r";
  const HEX6_RE = /^#[0-9a-fA-F]{6}$/;

  // ============================================================
  // 文字装飾 (italic/bold/underline/strikethrough/obfuscated)
  // ============================================================
  // key: 内部共通キー。mmTag: MiniMessage短縮タグ名。legacyCode: &コード文字。
  // 単一の定義から MiniMessage/legacy 両方の対応表を導出する (定義の二重管理を避ける)。
  const DECO_SPECS = [
    { key: "bold", label: "太字", mmTag: "b", legacyCode: "l" },
    { key: "italic", label: "斜体", mmTag: "i", legacyCode: "o" },
    { key: "underline", label: "下線", mmTag: "u", legacyCode: "n" },
    { key: "strikethrough", label: "取消線", mmTag: "st", legacyCode: "m" },
    { key: "obfuscated", label: "難読化", mmTag: "obf", legacyCode: "k" }
  ];
  const DECO_ORDER = DECO_SPECS.map((d) => d.key); // 新規生成時のタグ入れ子/コード出力の固定順
  const DECO_LABEL_BY_KEY = {};
  const MM_TAG_TO_DECO = {};
  const MM_DECO_TAG_BY_KEY = {};
  const LEGACY_CODE_TO_DECO = {};
  const LEGACY_DECO_CODE_BY_KEY = {};
  for (const d of DECO_SPECS) {
    DECO_LABEL_BY_KEY[d.key] = d.label;
    MM_TAG_TO_DECO[d.mmTag] = d.key;
    // Adventure MiniMessage の長名・別名 (<italic> / <bold> / <underlined> 等)
    MM_TAG_TO_DECO[d.key] = d.key;
    MM_DECO_TAG_BY_KEY[d.key] = d.mmTag;
    LEGACY_CODE_TO_DECO[d.legacyCode] = d.key;
    LEGACY_DECO_CODE_BY_KEY[d.key] = d.legacyCode;
  }
  MM_TAG_TO_DECO.em = "italic";
  MM_TAG_TO_DECO.underlined = "underline";
  MM_TAG_TO_DECO.strike = "strikethrough";

  // 装飾キー配列 → 正規順で連結した比較/保存用の文字列 ("bold,italic" 等)。空なら "".
  function decoStr(decos) {
    if (!decos || decos.length === 0) return "";
    return DECO_ORDER.filter((k) => decos.indexOf(k) >= 0).join(",");
  }

  function isHex6(s) {
    return typeof s === "string" && HEX6_RE.test(s);
  }

  function hexToRgb(hex) {
    const s = String(hex).replace("#", "");
    return {
      r: parseInt(s.slice(0, 2), 16),
      g: parseInt(s.slice(2, 4), 16),
      b: parseInt(s.slice(4, 6), 16)
    };
  }

  function rgbToHex(r, g, b) {
    const to2 = (n) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, "0");
    return `#${to2(r)}${to2(g)}${to2(b)}`;
  }

  // MiniMessage 色名 / #hex → 表示hex。未知なら null。
  function colorTokenToHex(token) {
    const t = String(token == null ? "" : token).trim().toLowerCase();
    if (isHex6(t)) return t.toUpperCase();
    const name = MM_ALIASES[t] || t;
    if (COLOR_BY_NAME[name]) return COLOR_BY_NAME[name].hex;
    return null;
  }

  // ============================================================
  // レガシー (&コード)
  // ============================================================

  // parseLegacy(str) → { ok, segments:[{text, color(hex|null), decos(string[]), codes:[{sigil,code}]}], raw, hasUnsupported }
  //   ok=false は 'r'(リセット) 等の未対応コードでリッチ編集不可 (呼び出し側で生テキストへフォールバック)。
  //   色コード(0-9a-f)と装飾コード(klmno)は併存でき、どちらも積算状態として保持する。
  //   segments は常にロスレスに構築され serializeLegacy(segments)===raw を満たす。
  function parseLegacy(str) {
    const raw = String(str == null ? "" : str);
    const segments = [];
    let cur = { text: "", codes: [], color: null, decos: [] };
    let curColor = null;
    let curDecos = [];
    let hasUnsupported = false;

    // 現在の cur が既に何か持っていれば確定して新セグメントを開始し、コードを積む。
    function beginCode(entry, newColor, newDecos) {
      if (cur.text !== "" || cur.codes.length > 0) {
        segments.push(cur);
        cur = { text: "", codes: [], color: newColor, decos: newDecos };
      } else {
        cur.color = newColor;
        cur.decos = newDecos;
      }
      cur.codes.push(entry);
    }

    let i = 0;
    while (i < raw.length) {
      const ch = raw[i];
      const next = i + 1 < raw.length ? raw[i + 1] : "";
      if ((ch === "&" || ch === "§") && next) {
        const low = next.toLowerCase();
        if (/[0-9a-f]/.test(low)) {
          // 色コード: 装飾状態は維持したまま色のみ切り替える。
          curColor = HEX_BY_CODE[low];
          beginCode({ sigil: ch, code: low }, curColor, curDecos);
          i += 2;
          continue;
        }
        if (Object.prototype.hasOwnProperty.call(LEGACY_CODE_TO_DECO, low)) {
          // 装飾コード: 色は維持したまま装飾を積算する (重複は無視)。
          const decoKey = LEGACY_CODE_TO_DECO[low];
          if (curDecos.indexOf(decoKey) < 0) curDecos = curDecos.concat([decoKey]);
          beginCode({ sigil: ch, code: low }, curColor, curDecos);
          i += 2;
          continue;
        }
        if (low === LEGACY_RESET_CODE) {
          // リセット: 未対応。リッチ編集不可フラグを立て、文字は原文のまま保持する。
          hasUnsupported = true;
        }
        // 未知コードや裸の & は literal として保持 (ロスレス)。
      }
      cur.text += ch;
      i += 1;
    }
    segments.push(cur);
    // 構築上 serialize(segments)===raw のはずだが、保険として検証する。
    const ok = !hasUnsupported && serializeLegacy(segments) === raw;
    return { ok, segments, raw, hasUnsupported };
  }

  function serializeLegacy(segments) {
    let out = "";
    for (const seg of segments || []) {
      for (const c of seg.codes || []) out += (c.sigil || "&") + c.code;
      out += seg.text == null ? "" : seg.text;
    }
    return out;
  }

  // 文字ごとに解決済みの (color, decos) を持つ「ラン」配列 → 保存用 legacy セグメント配列。
  // 新規生成 (編集後) 用の正規表現: 色コードを先頭に、装飾コードは DECO_ORDER の固定順で続ける。
  function buildLegacySegs(runs) {
    return (runs || []).map((r) => {
      const codes = [];
      if (r.color) {
        const code = CODE_BY_HEX[String(r.color).toUpperCase()];
        if (code) codes.push({ sigil: "&", code });
      }
      for (const key of DECO_ORDER) {
        if (r.decos && r.decos.indexOf(key) >= 0) codes.push({ sigil: "&", code: LEGACY_DECO_CODE_BY_KEY[key] });
      }
      return { text: r.text == null ? "" : r.text, codes, color: r.color || null, decos: r.decos || [] };
    });
  }

  // ============================================================
  // MiniMessage (<color>タグ)
  // ============================================================

  // 開きタグ文字列 → 表示hex。色系でなければ null。
  function parseOpenColorTag(openRaw) {
    const inner = openRaw.slice(1, -1).trim(); // <...> の中身
    if (inner === "") return null;
    if (/^color:/i.test(inner)) return colorTokenToHex(inner.slice(6));
    if (inner[0] === "#") return isHex6(inner) ? inner.toUpperCase() : null;
    return colorTokenToHex(inner); // <green> 等の短縮色名
  }

  // gradient タグの中身 ("gradient" / "gradient:c1:c2:..." / 末尾に phase 数値) → 生の引数配列。
  // 引数は解釈しすぎず (色として解決できないものが混ざっていても) そのまま保持し、ロスレス往復を優先する。
  const GRADIENT_TAG_RE = /^gradient(?::(.*))?$/i;

  // 開きタグ文字列 → { kind:"color", color } | { kind:"deco", decoKey } | { kind:"gradient", args } | null(非対応)。
  function classifyOpenTag(openRaw) {
    const inner = openRaw.slice(1, -1).trim();
    if (inner === "") return null;
    const low = inner.toLowerCase();
    if (Object.prototype.hasOwnProperty.call(MM_TAG_TO_DECO, low)) {
      return { kind: "deco", decoKey: MM_TAG_TO_DECO[low] };
    }
    const gm = GRADIENT_TAG_RE.exec(inner);
    if (gm) {
      return { kind: "gradient", args: gm[1] == null ? [] : gm[1].split(":") };
    }
    const color = parseOpenColorTag(openRaw);
    if (color !== null) return { kind: "color", color };
    return null;
  }

  // 木構造 (ノード配列) の再帰下降パーサ。色タグ/装飾タグ/gradientタグは任意の順序・深さで入れ子にできる。
  // ノード: { kind:"text", text } | { kind:"color"|"deco"|"gradient", color?/decoKey?/gradientArgs?, openRaw, closeRaw, children }
  // 閉じタグは (名前照合せず) スタック最上位を1段閉じるものとして扱う。これにより <b></i> のような
  // 実際の記法ゆれも「原文どおりに」ロスレス往復できる (原文をそのまま echo するだけのため)。
  // 未対応タグ / 迷子の閉じタグ / 未終端のタグは全体を null で不可判定にする。
  function parseNodesMM(s, i, stack, placeholders) {
    const nodes = [];
    while (i < s.length) {
      if (s[i] === "<") {
        const gt = s.indexOf(">", i);
        if (gt < 0) return null;
        if (s[i + 1] === "/") {
          if (stack.length === 0) return null; // 迷子の閉じタグ
          return { nodes, endIndex: gt + 1, closeRaw: s.slice(i, gt + 1) };
        }
        const openRaw = s.slice(i, gt + 1);
        const info = classifyOpenTag(openRaw);
        if (!info) {
          // 2026-08-05: そのフィールドが宣言した差し込みタグ(<icon> <name> <value> 等)は
          // 閉じタグを持たない「ただの文字」として扱う。以前は未知タグとして非対応判定になり、
          // 出荷の line-template のように差し込みタグを含む値では GUI モードが常に無効
          // (「対応外のタグ/装飾があるため GUI に切り替えられません」)になっていた。
          // text ノードとして原文をそのまま持つのでロスレス往復も崩れない。
          if (isPlaceholderTag(openRaw, placeholders)) {
            nodes.push({ kind: "text", text: openRaw });
            i = gt + 1;
            continue;
          }
          return null; // 未知タグ等は非対応
        }
        const child = parseNodesMM(s, gt + 1, stack.concat([info]), placeholders);
        if (!child) return null;
        const extra = info.kind === "color" ? { color: info.color }
          : info.kind === "gradient" ? { gradientArgs: info.args }
          : { decoKey: info.decoKey };
        const node = Object.assign(
          { kind: info.kind, openRaw, closeRaw: child.closeRaw, children: child.nodes },
          extra
        );
        nodes.push(node);
        i = child.endIndex;
      } else {
        const lt = s.indexOf("<", i);
        const text = lt < 0 ? s.slice(i) : s.slice(i, lt);
        if (text) nodes.push({ kind: "text", text });
        i = lt < 0 ? s.length : lt;
      }
    }
    if (stack.length > 0) return null; // 未終端のタグ
    return { nodes, endIndex: i, closeRaw: null };
  }

  /**
   * そのフィールドが差し込みタグとして宣言した名前か。
   *
   * <p>allowlist 方式にしているのは、GUI で表現できない本物の MiniMessage タグ
   * ({@code <click:...>} / {@code <hover:...>} / {@code <font:...>} 等)まで「ただの文字」に
   * 化けさせないため。宣言はフィールド側の責務(呼び出し元が placeholders を渡す)で、
   * 渡されなければ従来どおり全ての未知タグが非対応判定になる。
   */
  function isPlaceholderTag(openRaw, placeholders) {
    if (!placeholders || !placeholders.length) return false;
    const inner = openRaw.slice(1, -1).trim().toLowerCase();
    if (!inner) return false;
    for (let i = 0; i < placeholders.length; i++) {
      if (String(placeholders[i]).trim().toLowerCase() === inner) return true;
    }
    return false;
  }

  function tryParseMiniMessage(s, placeholders) {
    const result = parseNodesMM(s, 0, [], placeholders);
    return result ? result.nodes : null;
  }

  // parseMiniMessage(str) → { ok, segments, raw }
  //   segments は木構造のノード配列。ok=true のときのみリッチ編集可能。ok=false は raw フォールバック。
  //   parse 成功後に再シリアライズして原文一致を検証する (ロスレス保証)。
  function parseMiniMessage(str, placeholders) {
    const raw = String(str == null ? "" : str);
    const attempt = tryParseMiniMessage(raw, placeholders);
    if (attempt && serializeMiniMessage(attempt) === raw) {
      return { ok: true, segments: attempt, raw };
    }
    return { ok: false, segments: [{ kind: "text", text: raw }], raw };
  }

  // プレビュー専用: 往復一致に失敗しても木が取れれば着色表示する (保存はしない)。
  function parseMiniMessageForPreview(str, placeholders) {
    const raw = String(str == null ? "" : str);
    const attempt = tryParseMiniMessage(raw, placeholders);
    if (attempt) return { ok: true, segments: attempt, raw };
    return { ok: false, segments: [{ kind: "text", text: raw }], raw };
  }

  // 開きタグ省略時 (編集で新規生成したノード) の正規タグ文字列。
  function canonicalMMTag(node) {
    if (node.kind === "color") {
      const token = node.token || node.color;
      return { open: `<color:${token}>`, close: "</color>" };
    }
    if (node.kind === "gradient") {
      const args = node.gradientArgs || [];
      return { open: args.length ? `<gradient:${args.join(":")}>` : "<gradient>", close: "</gradient>" };
    }
    const tag = MM_DECO_TAG_BY_KEY[node.decoKey];
    return { open: `<${tag}>`, close: `</${tag}>` };
  }

  // 木構造ノード配列 → MiniMessage文字列。openRaw/closeRaw があれば原文をそのまま復元し (ロスレス)、
  // 無ければ (編集で新規生成されたノード) 正規形のタグを生成する。
  function serializeMiniMessage(nodes) {
    let out = "";
    for (const node of nodes || []) {
      if (!node) continue;
      if (node.kind === "text") {
        out += node.text == null ? "" : node.text;
        continue;
      }
      const inner = serializeMiniMessage(node.children || []);
      if (node.openRaw) {
        out += node.openRaw + inner + (node.closeRaw != null ? node.closeRaw : canonicalMMTag(node).close);
      } else {
        const tag = canonicalMMTag(node);
        out += tag.open + inner + tag.close;
      }
    }
    return out;
  }

  // 木構造ノード配列 → { text, color, token, decos:[...], gradient?:string[] } の葉ラン配列へ平坦化
  // (表示/文字モデル用)。祖先の色/装飾/gradientタグを合成して各葉に割り当てる。
  // gradient は色と違い「範囲全体で1色に決まらない」ため、生の引数配列 (rawArgs) をそのまま
  // 葉に持たせる (実際の各文字色への展開は gradientEndpoints+gradientSpans で行う)。
  // シリアライズには使わない (入れ子の開閉が失われるため)。
  function flattenMMNodes(nodes) {
    const runs = [];
    (function walk(list, color, token, decos, gradient) {
      for (const node of list || []) {
        if (node.kind === "text") {
          if (node.text) runs.push({ text: node.text, color, token, decos, gradient });
        } else if (node.kind === "color") {
          const tok = openRawToken(node.openRaw) || node.color;
          walk(node.children, node.color, tok, decos, gradient);
        } else if (node.kind === "deco") {
          const nd = decos.indexOf(node.decoKey) >= 0 ? decos : decos.concat([node.decoKey]);
          walk(node.children, color, token, nd, gradient);
        } else if (node.kind === "gradient") {
          walk(node.children, color, token, decos, node.gradientArgs || []);
        }
      }
    })(nodes, null, null, [], null);
    return runs;
  }

  // <color:X> の X / <#hex> / <name> → トークン文字列。装飾タグには使わない。
  function openRawToken(openRaw) {
    if (!openRaw) return null;
    const inner = openRaw.slice(1, -1);
    if (/^color:/i.test(inner)) return inner.slice(6);
    return inner;
  }

  // 文字ごとに解決済みの (color, decos) または (gradient, decos) を持つ「ラン」配列 →
  // 保存用 MiniMessage ノード配列 (新規生成)。固定順: gradient/色タグを最外周、
  // 装飾タグは DECO_ORDER の順で内側へ重ねる。r.gradient (生の引数配列) があれば
  // r.color は無視して <gradient:...> ノードとして復元する (gradient 優先)。
  function buildMMNodes(runs) {
    return (runs || []).map((r) => {
      let node = { kind: "text", text: r.text == null ? "" : r.text };
      for (let idx = DECO_ORDER.length - 1; idx >= 0; idx--) {
        const key = DECO_ORDER[idx];
        if (r.decos && r.decos.indexOf(key) >= 0) {
          node = { kind: "deco", decoKey: key, openRaw: null, closeRaw: null, children: [node] };
        }
      }
      if (r.gradient) {
        node = { kind: "gradient", gradientArgs: r.gradient, openRaw: null, closeRaw: null, children: [node] };
      } else if (r.color) {
        node = { kind: "color", color: r.color, token: r.token || r.color, openRaw: null, closeRaw: null, children: [node] };
      }
      return node;
    });
  }

  // ============================================================
  // グラデーション / 単色トークン
  // ============================================================

  // text の各文字を from→to で線形補間した色を割り当てる (プレビュー用)。
  function gradientSpans(text, from, to) {
    const chars = Array.from(String(text == null ? "" : text));
    const a = hexToRgb(from);
    const b = hexToRgb(to);
    const n = chars.length;
    return chars.map((ch, idx) => {
      const t = n <= 1 ? 0 : idx / (n - 1);
      return {
        char: ch,
        hex: rgbToHex(a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t)
      };
    });
  }

  // gradient タグの生引数配列 (色トークン以外の phase 数値等が混ざっていてもよい) → 表示用の
  // 開始/終了hex。色として解決できるトークンが1つも無ければ null (呼び出し側でフォールバック)。
  // 中間ストップは (プレビュー同様) 表示上は無視し、両端だけを線形補間に使う
  // (個々のストップ編集は非対応。GUI/プレビューで共通のこの関数だけを使う)。
  function gradientEndpoints(rawArgs) {
    const hexes = (rawArgs || []).map((a) => colorTokenToHex(a)).filter((v) => v);
    if (hexes.length === 0) return null;
    return { from: hexes[0], to: hexes[hexes.length - 1] };
  }

  // 単色フィールドの値を判定する。&d / #hex / 色名 / gradient:... を分類。
  // { kind: "empty"|"legacy"|"hex"|"name"|"raw", hex?, code?, name?, raw }
  function normalizeColorToken(v) {
    const raw = String(v == null ? "" : v).trim();
    if (raw === "") return { kind: "empty", raw };
    if (/^[&§][0-9a-fA-F]$/.test(raw)) {
      const code = raw[1].toLowerCase();
      return { kind: "legacy", code, hex: HEX_BY_CODE[code], raw };
    }
    if (isHex6(raw)) return { kind: "hex", hex: raw.toUpperCase(), raw };
    const name = raw.toLowerCase();
    const canon = MM_ALIASES[name] || name;
    if (COLOR_BY_NAME[canon]) return { kind: "name", name: raw, hex: COLOR_BY_NAME[canon].hex, raw };
    return { kind: "raw", raw }; // gradient:... など復元専用の生値
  }

  // 単色トークンを保存文字列へ戻す (colorPicker mm-color/legacy-or-hex 等が使用)。
  function serializeColorToken(token) {
    if (!token) return "";
    switch (token.kind) {
      case "empty": return "";
      case "legacy": return token.raw;
      case "hex": return token.raw != null ? token.raw : token.hex; // 原文の大小文字を保持
      case "name": return token.name != null ? token.name : token.raw;
      default: return token.raw; // raw: gradient 等は原文そのまま
    }
  }

  // ============================================================
  // legacy(&コード) ⇄ MiniMessage 変換 (素材⇄カタログのタブ間移動用)
  // ============================================================

  // hex → MiniMessage色トークン。16色に一致すれば色名 (<green> 等)、それ以外は #hex。
  function mmTokenForHex(hex) {
    const up = String(hex).toUpperCase();
    const code = CODE_BY_HEX[up];
    if (code) {
      const found = MC_COLORS.find((c) => c.code === code);
      if (found) return found.name;
    }
    return up.toLowerCase();
  }

  // legacy(&コード)文字列 → MiniMessage文字列。&r 等の未対応コードを含み
  // 安全に変換できない場合は null (呼び出し側でフォールバック判断)。
  function legacyToMiniMessage(str) {
    const raw = String(str == null ? "" : str);
    if (raw === "") return "";
    const p = parseLegacy(raw);
    if (!p.ok) return null;
    const runs = p.segments
      .filter((seg) => seg.text !== "")
      .map((seg) => ({
        text: seg.text,
        color: seg.color || null,
        token: seg.color ? mmTokenForHex(seg.color) : null,
        decos: seg.decos || []
      }));
    return serializeMiniMessage(buildMMNodes(runs));
  }

  // MiniMessage文字列 → legacy(&コード)文字列。gradient等の未対応タグ、または
  // 16色へ丸められない hex 色を含む場合は null (黙って色を落とさない)。
  function miniMessageToLegacy(str) {
    const raw = String(str == null ? "" : str);
    if (raw === "") return "";
    const p = parseMiniMessage(raw);
    if (!p.ok) return null;
    const runs = flattenMMNodes(p.segments);
    for (const r of runs) {
      // gradient は legacy(&コード) に相当する記法が無いため変換不可 (色を黙って落とさない)。
      if (r.gradient) return null;
      if (r.color && !CODE_BY_HEX[String(r.color).toUpperCase()]) return null;
    }
    return serializeLegacy(buildLegacySegs(runs));
  }

  root.MC_COLORS = MC_COLORS;
  root.COLORS = {
    MC_COLORS,
    HEX_BY_CODE,
    CODE_BY_HEX,
    COLOR_BY_NAME,
    isHex6,
    hexToRgb,
    rgbToHex,
    colorTokenToHex,
    parseLegacy,
    serializeLegacy,
    parseMiniMessage,
    serializeMiniMessage,
    gradientSpans,
    gradientEndpoints,
    normalizeColorToken,
    serializeColorToken,
    // 文字装飾 (テスト/デバッグ用に公開。UIからは richTextInput 経由で使う)
    DECO_SPECS,
    DECO_ORDER,
    decoStr,
    flattenMMNodes,
    buildMMNodes,
    buildLegacySegs,
    legacyToMiniMessage,
    miniMessageToLegacy
  };

  // ============================================================
  // UI 部品 (ブラウザ専用。Node require では読み込まない)
  // ============================================================
  if (typeof window === "undefined" || typeof document === "undefined") return;

  const DEFAULT_LORE_HEX = "#AAAAAA"; // MC lore 既定色
  const DEFAULT_NAME_HEX = "#FFFFFF";

  function h() { return window.h.apply(null, arguments); }

  // 装飾文字列 ("bold,italic" 等) → CSS用インライン style 文字列 (color 以外)。
  // underline/strikethrough は同時指定時に1つの text-decoration-line へ合成する
  // (別々のCSSクラスでは text-decoration-line が上書きされ合成できないため)。
  // richTextInput の編集ボックスと buildTooltipPreview の両方で共有する。
  function buildPreviewDecoStyle(dstr) {
    if (!dstr) return "";
    const set = dstr.split(",");
    const parts = [];
    if (set.indexOf("bold") >= 0) parts.push("font-weight:700");
    if (set.indexOf("italic") >= 0) parts.push("font-style:italic");
    const lines = [];
    if (set.indexOf("underline") >= 0) lines.push("underline");
    if (set.indexOf("strikethrough") >= 0) lines.push("line-through");
    if (lines.length) parts.push(`text-decoration-line:${lines.join(" ")}`);
    return parts.join(";");
  }

  // ---- ポップオーバー管理 (1つだけ開く / 外側click・Escapeで閉じる) ----
  let activePopover = null;
  function closeActivePopover() {
    if (activePopover) {
      const p = activePopover;
      activePopover = null;
      if (p.parentNode) p.parentNode.removeChild(p);
      document.removeEventListener("mousedown", onDocDown, true);
      document.removeEventListener("keydown", onDocKey, true);
    }
  }
  function onDocDown(e) {
    if (activePopover && !activePopover.contains(e.target) && !(activePopover._anchor && activePopover._anchor.contains(e.target))) {
      closeActivePopover();
    }
  }
  function onDocKey(e) { if (e.key === "Escape") closeActivePopover(); }

  function openPopover(anchorEl, contentEl) {
    closeActivePopover();
    const pop = h("div", { class: "color-popover" }, [contentEl]);
    pop._anchor = anchorEl;
    document.body.appendChild(pop);
    const r = anchorEl.getBoundingClientRect();
    // 画面下にはみ出す場合は上側へ。fixed 配置。
    pop.style.left = Math.round(Math.min(r.left, window.innerWidth - pop.offsetWidth - 8)) + "px";
    let top = r.bottom + 4;
    if (top + pop.offsetHeight > window.innerHeight - 8) top = Math.max(8, r.top - pop.offsetHeight - 4);
    pop.style.top = Math.round(top) + "px";
    activePopover = pop;
    // 直後の同一 mousedown で即閉じないよう capture で次tickから購読する。
    setTimeout(() => {
      if (activePopover === pop) {
        document.addEventListener("mousedown", onDocDown, true);
        document.addEventListener("keydown", onDocKey, true);
      }
    }, 0);
    return pop;
  }

  // 16色グリッド。onPick(colorEntry) を呼ぶ。
  function colorGrid(onPick) {
    const grid = h("div", { class: "color-grid" });
    for (const c of MC_COLORS) {
      grid.appendChild(h("button", {
        type: "button", class: "color-cell", title: `${c.ja} (${c.name} / &${c.code})`,
        style: `background:${c.hex}`,
        onclick: () => onPick(c)
      }));
    }
    return grid;
  }

  // ---- colorPickerInput(value, mode, onInput, opts) ----
  // mode: "legacy" | "hex" | "legacy-or-hex" | "mm-color"
  // onInput(newString) は保存記法の文字列を受け取る。空文字はフィールド削除を意味する。
  // opts.defaultValue: 未設定(空文字)時に「実際に適用されるデフォルト色」を表示する
  //   (保存はしない。Java側デフォルトをUIへ反映するためのプレースホルダ表示)。
  function colorPickerInput(value, mode, onInput, opts) {
    const allowHex = mode === "hex" || mode === "legacy-or-hex" || mode === "mm-color";
    const defaultValue = opts && opts.defaultValue != null ? String(opts.defaultValue) : "";
    const wrap = h("span", { class: "color-picker" });
    let current = value == null ? "" : String(value);

    const swatch = h("span", { class: "color-swatch" });
    const valLabel = h("span", { class: "color-val" });
    const trigger = h("button", { type: "button", class: "color-trigger", title: "色を選ぶ" }, [swatch, valLabel]);

    function paint() {
      let tok = normalizeColorToken(current);
      let labelText = current;
      let isDefault = false;
      if (current === "" && defaultValue) {
        // 未設定 → デフォルト色をスウォッチ+「(既定)」ラベルで表示する。
        tok = normalizeColorToken(defaultValue);
        labelText = defaultValue + " (既定)";
        isDefault = true;
      } else if (current === "") {
        labelText = "(未設定)";
      }
      const hex = tok.hex || null;
      // 空のときは inline background を消して .color-swatch.is-empty の市松模様を出す
      // (background ショートハンドを残すと CSS の background-image が上書きされて消える)。
      swatch.style.background = hex || "";
      swatch.classList.toggle("is-empty", !hex);
      valLabel.textContent = labelText;
      valLabel.classList.toggle("is-default", isDefault);
    }

    // グリッド選択を保存記法へ変換する。
    function outputForColor(c) {
      if (mode === "hex") return c.hex;
      if (mode === "mm-color") return c.name;
      return "&" + c.code; // legacy / legacy-or-hex
    }

    function commit(str) {
      current = str;
      paint();
      onInput(str);
    }

    function buildPanel() {
      const panel = h("div", { class: "color-panel" });
      panel.appendChild(colorGrid((c) => { commit(outputForColor(c)); closeActivePopover(); }));

      if (allowHex) {
        const tok = normalizeColorToken(current);
        const startHex = tok.hex ? tok.hex.toLowerCase() : "#ffffff";
        const native = h("input", { type: "color", class: "color-native", value: startHex });
        const hexText = h("input", { class: "field-input color-hex-text", value: tok.kind === "hex" ? current : "", placeholder: "#RRGGBB", spellcheck: "false" });
        native.addEventListener("input", () => { hexText.value = native.value; });
        native.addEventListener("change", () => { commit(native.value); });
        hexText.addEventListener("change", () => {
          const v = hexText.value.trim();
          if (v === "" || isHex6(v)) commit(v);
          else { hexText.value = tok.kind === "hex" ? current : ""; }
        });
        panel.appendChild(h("div", { class: "color-hex-row" }, [
          h("span", { class: "mini-label", text: "任意色" }), native, hexText
        ]));
      }

      // raw フォールバック (gradient 等) の編集欄。
      const tok2 = normalizeColorToken(current);
      if (tok2.kind === "raw") {
        const rawText = h("input", { class: "field-input", value: current, spellcheck: "false" });
        rawText.addEventListener("change", () => commit(rawText.value));
        panel.appendChild(h("div", { class: "color-raw-row" }, [
          h("span", { class: "mini-label", text: "カスタム", title: "gradient 等のパース不能値" }), rawText
        ]));
      }

      panel.appendChild(h("button", { type: "button", class: "btn-small", text: "クリア", onclick: () => { commit(""); closeActivePopover(); } }));
      return panel;
    }

    trigger.addEventListener("click", () => {
      if (activePopover && activePopover._anchor === trigger) { closeActivePopover(); return; }
      openPopover(trigger, buildPanel());
    });

    paint();
    wrap.appendChild(trigger);
    return wrap;
  }

  // ============================================================
  // richTextInput(value, mode, onInput)  mode: "legacy" | "minimessage"
  // ============================================================
  // 内部は「文字ごとの色配列 + 装飾配列」をモデルとして保持する。IME(日本語入力)を壊さないため
  // 通常のテキスト編集は contentEditable のネイティブ挙動に任せ、input 後に DOM から
  // モデルを読み戻して serialize する。色/装飾適用(パレット)時のみ再描画する。
  //
  // UIモード: GUI (既定・着色パレット) / 簡易 (タグ生テキスト)。いつでも切替可能。
  // 未対応タグで GUI 解析不能なときは簡易へフォールバックし、GUI ボタンは無効化する。
  function richTextInput(value, mode, onInput, opts) {
    const host = h("span", { class: "rich-host" });
    let current = value == null ? "" : String(value);
    // "gui" | "simple" — 既定は GUI。解析不能なら自動で simple。
    let uiMode = "gui";
    let simpleInput = null;
    let guiSerialize = null;
    // 2026-08-05: そのフィールドが持つ差し込みタグ名(<icon> <name> <value> 等)。
    // 宣言すると GUI モードでも「ただの文字」として通る(理由は isPlaceholderTag)。
    const placeholders = opts && Array.isArray(opts.placeholders) ? opts.placeholders : null;

    function parseCurrent() {
      return mode === "minimessage" ? parseMiniMessage(current, placeholders) : parseLegacy(current);
    }

    function notify(v) {
      current = v == null ? "" : String(v);
      if (typeof onInput === "function") onInput(current);
    }

    function readLiveValue() {
      if (uiMode === "simple" && simpleInput) return simpleInput.value;
      if (uiMode === "gui" && typeof guiSerialize === "function") {
        try { return guiSerialize(); } catch (_) { /* fall through */ }
      }
      return current;
    }

    function buildModeBar(canGui, activeMode) {
      const bar = h("div", { class: "rich-mode-toggle", role: "group", title: "編集モード" });
      const guiBtn = h("button", {
        type: "button",
        class: "rich-mode-btn" + (activeMode === "gui" ? " is-active" : ""),
        text: "GUI",
        title: canGui
          ? "着色パレットで編集（既定）"
          : "対応外のタグ/装飾があるため GUI に切り替えられません"
      });
      guiBtn.disabled = !canGui;
      const simpleBtn = h("button", {
        type: "button",
        class: "rich-mode-btn" + (activeMode === "simple" ? " is-active" : ""),
        text: "簡易",
        title: "MiniMessage / &コードをそのまま編集"
      });
      guiBtn.addEventListener("click", () => {
        if (!canGui || activeMode === "gui") return;
        current = readLiveValue();
        const again = parseCurrent();
        if (!again.ok) {
          alert("対応外のタグや装飾があるため GUI モードに切り替えられません。簡易編集のまま修正してください。");
          return;
        }
        uiMode = "gui";
        remount();
      });
      simpleBtn.addEventListener("click", () => {
        if (activeMode === "simple") return;
        current = readLiveValue();
        notify(current);
        uiMode = "simple";
        remount();
      });
      bar.appendChild(guiBtn);
      bar.appendChild(simpleBtn);
      return bar;
    }

    function buildSimpleEditor(noteText) {
      simpleInput = null;
      guiSerialize = null;
      const wrap = h("span", { class: "rich-fallback" });
      const input = window.textInput(current, (v) => notify(v), mode === "minimessage" ? "MiniMessage" : "&色コード");
      simpleInput = input;
      wrap.appendChild(input);
      if (noteText) {
        wrap.appendChild(h("span", {
          class: "rich-note",
          title: noteText,
          text: "簡易"
        }));
      }
      return wrap;
    }

    function buildGuiEditor(parsed) {
      simpleInput = null;
      const wrap = h("span", { class: "rich-input" });
      const box = h("div", { class: "rich-box", contenteditable: "true", spellcheck: "false" });

      // gradient を持つラン (r.gradient = 生の引数配列) は、各文字ごとに補間色を割り当てて展開する。
      // 「どの文字がどの gradient に属するか」は gradients[] (引数配列そのもの、無所属は null) で
      // 別トラックとして持ち、色配列 (colors[]) は表示専用の補間結果として扱う (直接編集された色とは区別する)。
      function runsToChars(runs) {
        const chars = [], colors = [], tokens = [], decos = [], gradients = [];
        for (const r of runs) {
          const txt = r.text == null ? "" : r.text;
          if (r.gradient) {
            const ep = gradientEndpoints(r.gradient);
            const spans = ep ? gradientSpans(txt, ep.from, ep.to) : null;
            for (let k = 0; k < txt.length; k++) {
              chars.push(txt[k]);
              colors.push(spans ? spans[k].hex : null);
              tokens.push(null);
              decos.push(r.decos || []);
              gradients.push(r.gradient);
            }
            continue;
          }
          for (let k = 0; k < txt.length; k++) {
            chars.push(txt[k]);
            colors.push(r.color || null);
            tokens.push(r.color ? (r.token || r.color) : null);
            decos.push(r.decos || []);
            gradients.push(null);
          }
        }
        return { text: chars.join(""), colors, tokens, decos, gradients };
      }

      // gradients[i] が同じ (JSON文字列として一致する) 連続文字は同じ gradient 所属とみなす。
      function gradKeyAt(gradients, idx) {
        return gradients && gradients[idx] ? JSON.stringify(gradients[idx]) : null;
      }

      function render(text, colors, tokens, decos, gradients) {
        box.innerHTML = "";
        let i = 0;
        while (i < text.length) {
          const col = colors[i];
          const dstr = decoStr(decos[i]);
          const gradKey = gradKeyAt(gradients, i);
          let j = i;
          while (
            j < text.length &&
            gradKeyAt(gradients, j) === gradKey &&
            colors[j] === col &&
            decoStr(decos[j]) === dstr
          ) j++;
          const chunk = text.slice(i, j);
          if (col || dstr || gradKey) {
            const styleParts = [];
            if (col) styleParts.push(`color:${col}`);
            const decoStyle = buildPreviewDecoStyle(dstr);
            if (decoStyle) styleParts.push(decoStyle);
            const span = h("span", { style: styleParts.join(";") });
            if (col) {
              span.dataset.color = col;
              if (tokens[i]) span.dataset.token = tokens[i];
            }
            if (dstr) {
              span.dataset.deco = dstr;
              if (dstr.indexOf("obfuscated") >= 0) { span.classList.add("mc-deco-obf"); span.title = "難読化 (プレビュー簡易表示)"; }
            }
            if (gradKey) {
              span.dataset.gradient = gradKey;
              span.title = span.title ? span.title : "グラデーション (個別ストップの編集は簡易モードで)";
            }
            span.textContent = chunk;
            box.appendChild(span);
          } else {
            box.appendChild(document.createTextNode(chunk));
          }
          i = j;
        }
        if (text.length === 0) box.appendChild(document.createTextNode(""));
      }

      function readChars() {
        const chars = [], colors = [], tokens = [], decos = [], gradients = [];
        box.childNodes.forEach((node) => {
          const t = node.textContent || "";
          const isEl = node.nodeType === 1;
          const col = isEl ? (node.dataset.color || null) : null;
          const tok = isEl ? (node.dataset.token || null) : null;
          const dstr = isEl ? (node.dataset.deco || "") : "";
          const darr = dstr ? dstr.split(",") : [];
          let grad = null;
          if (isEl && node.dataset.gradient) {
            try { grad = JSON.parse(node.dataset.gradient); } catch (_) { grad = null; }
          }
          for (let k = 0; k < t.length; k++) { chars.push(t[k]); colors.push(col); tokens.push(col ? tok : null); decos.push(darr); gradients.push(grad); }
        });
        return { text: chars.join(""), colors, tokens, decos, gradients };
      }

      // gradient 所属の文字は色(補間結果)ではなく所属そのものでグルーピングし、
      // <gradient:元の引数> ...text... </gradient> として復元する (per-char の色タグへ分解しない)。
      function serialize() {
        const { text, colors, tokens, decos, gradients } = readChars();
        const runs = [];
        let i = 0;
        while (i < text.length) {
          const gradKey = gradKeyAt(gradients, i);
          const dstr0 = decoStr(decos[i]);
          let j = i;
          if (gradKey) {
            while (j < text.length && gradKeyAt(gradients, j) === gradKey && decoStr(decos[j]) === dstr0) j++;
            runs.push({ text: text.slice(i, j), gradient: gradients[i], decos: dstr0 ? dstr0.split(",") : [] });
            i = j;
            continue;
          }
          const col = colors[i];
          while (j < text.length && !gradKeyAt(gradients, j) && colors[j] === col && decoStr(decos[j]) === dstr0) j++;
          runs.push({ text: text.slice(i, j), color: col, token: tokens[i] || col, decos: dstr0 ? dstr0.split(",") : [] });
          i = j;
        }
        return mode === "minimessage" ? serializeMiniMessage(buildMMNodes(runs)) : serializeLegacy(buildLegacySegs(runs));
      }

      guiSerialize = serialize;
      function emit() { notify(serialize()); }

      function offsetOf(node, off) {
        const range = document.createRange();
        range.selectNodeContents(box);
        try { range.setEnd(node, off); } catch (_) { return null; }
        return range.toString().length;
      }
      function getSelectionRange() {
        const sel = window.getSelection();
        if (!sel || sel.rangeCount === 0) return null;
        const r = sel.getRangeAt(0);
        if (!box.contains(r.startContainer) || !box.contains(r.endContainer)) return null;
        const start = offsetOf(r.startContainer, r.startOffset);
        const end = offsetOf(r.endContainer, r.endOffset);
        if (start == null || end == null) return null;
        return { start: Math.min(start, end), end: Math.max(start, end) };
      }
      function restoreSelection(start, end) {
        const range = document.createRange();
        const pt = locate(start), pe = locate(end);
        if (!pt || !pe) return;
        range.setStart(pt.node, pt.off);
        range.setEnd(pe.node, pe.off);
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      }
      function locate(target) {
        let acc = 0, found = null;
        for (const node of box.childNodes) {
          const len = (node.textContent || "").length;
          const textNode = node.nodeType === 1 ? node.firstChild : node;
          if (target <= acc + len) { found = { node: textNode || node, off: Math.max(0, target - acc) }; break; }
          acc += len;
        }
        if (!found) {
          const last = box.childNodes[box.childNodes.length - 1];
          if (last) { const tn = last.nodeType === 1 ? last.firstChild : last; found = { node: tn || last, off: (tn ? tn.textContent.length : 0) }; }
          else found = { node: box, off: 0 };
        }
        return found;
      }

      function applyColor(colHex, token) {
        const rangeSel = getSelectionRange();
        if (!rangeSel || rangeSel.start === rangeSel.end) return;
        const { text, colors, tokens, decos, gradients } = readChars();
        for (let k = rangeSel.start; k < rangeSel.end && k < colors.length; k++) {
          colors[k] = colHex || null;
          tokens[k] = colHex ? (token || null) : null;
          // 選択範囲へ明示的に色を適用/クリアする操作は、その範囲を gradient 所属から外す
          // (gradient は「範囲全体の自動彩色」なので、部分的な手動着色と両立しない)。
          gradients[k] = null;
        }
        render(text, colors, tokens, decos, gradients);
        restoreSelection(rangeSel.start, rangeSel.end);
        emit();
      }

      function applyDecoration(decoKey) {
        const rangeSel = getSelectionRange();
        if (!rangeSel || rangeSel.start === rangeSel.end) return;
        const { text, colors, tokens, decos, gradients } = readChars();
        let allOn = true;
        for (let k = rangeSel.start; k < rangeSel.end && k < decos.length; k++) {
          if (!decos[k] || decos[k].indexOf(decoKey) < 0) { allOn = false; break; }
        }
        for (let k = rangeSel.start; k < rangeSel.end && k < decos.length; k++) {
          const cur = decos[k] || [];
          decos[k] = allOn ? cur.filter((d) => d !== decoKey) : (cur.indexOf(decoKey) >= 0 ? cur : cur.concat([decoKey]));
        }
        render(text, colors, tokens, decos, gradients);
        restoreSelection(rangeSel.start, rangeSel.end);
        emit();
      }

      // この編集ボックス内に gradient 所属の文字が1つでもあるか。
      function hasAnyGradient() {
        return readChars().gradients.some((g) => !!g);
      }

      // gradient を丸ごと解除して単色/無色のプレーンテキストへ戻す (選択不要・全文対象)。
      // 個々の色ストップの編集は非対応 (YAGNI) だが、解除だけは唯一必須の操作として提供する。
      function dissolveGradient() {
        const { text, colors, tokens, decos, gradients } = readChars();
        const newColors = colors.map((c, idx) => (gradients[idx] ? null : c));
        const newTokens = tokens.map((t, idx) => (gradients[idx] ? null : t));
        const newGradients = gradients.map(() => null);
        render(text, newColors, newTokens, decos, newGradients);
        emit();
      }

      function decoSection(savedSel) {
        const hasSel = !!(savedSel && savedSel.start !== savedSel.end);
        const row = h("div", { class: "deco-row" });
        for (const spec of DECO_SPECS) {
          const btn = h("button", {
            type: "button", class: "btn-small deco-toggle-btn", text: spec.label,
            title: `選択範囲の${spec.label}をON/OFF切り替え`
          });
          btn.disabled = !hasSel;
          btn.addEventListener("click", () => {
            if (savedSel) restoreSelection(savedSel.start, savedSel.end);
            applyDecoration(spec.key);
            closeActivePopover();
          });
          row.appendChild(btn);
        }
        return h("div", { class: "deco-section" }, [h("span", { class: "mini-label", text: "装飾" }), row]);
      }

      function openPalette(anchor) {
        const savedSel = getSelectionRange();
        const panel = h("div", { class: "color-panel" });
        panel.appendChild(colorGrid((c) => {
          if (savedSel) restoreSelection(savedSel.start, savedSel.end);
          applyColor(c.hex, mode === "minimessage" ? c.name : c.code);
          closeActivePopover();
        }));
        if (mode === "minimessage") {
          const native = h("input", { type: "color", class: "color-native", value: "#ffffff" });
          native.addEventListener("change", () => {
            if (savedSel) restoreSelection(savedSel.start, savedSel.end);
            applyColor(native.value.toUpperCase(), native.value);
            closeActivePopover();
          });
          panel.appendChild(h("div", { class: "color-hex-row" }, [h("span", { class: "mini-label", text: "任意色" }), native]));
        }
        panel.appendChild(h("button", { type: "button", class: "btn-small", text: "色を消す", onclick: () => { if (savedSel) restoreSelection(savedSel.start, savedSel.end); applyColor(null, null); closeActivePopover(); } }));
        if (mode === "minimessage" && hasAnyGradient()) {
          panel.appendChild(h("button", {
            type: "button", class: "btn-small", text: "グラデーション解除",
            title: "この項目のグラデーションを解除して単色/無色のテキストに戻します (選択範囲は問いません)",
            onclick: () => { dissolveGradient(); closeActivePopover(); }
          }));
        }
        panel.appendChild(decoSection(savedSel));
        openPopover(anchor, panel);
      }

      box.addEventListener("contextmenu", (e) => { e.preventDefault(); openPalette(box); });
      box.addEventListener("mousedown", (e) => {
        if (e.button !== 0) return;
        box.focus();
      });
      box.addEventListener("input", () => emit());
      box.addEventListener("keydown", (e) => { if (e.key === "Enter") e.preventDefault(); });
      box.addEventListener("paste", (e) => {
        e.preventDefault();
        const txt = (e.clipboardData || window.clipboardData).getData("text").replace(/\r?\n/g, " ");
        document.execCommand("insertText", false, txt);
      });
      box.addEventListener("drop", (e) => {
        e.preventDefault();
        const dt = e.dataTransfer;
        const txt = (dt ? dt.getData("text") : "").replace(/\r?\n/g, " ");
        if (txt) document.execCommand("insertText", false, txt);
      });

      const initRuns = mode === "minimessage" ? flattenMMNodes(parsed.segments) : parsed.segments;
      const init = runsToChars(initRuns);
      render(init.text, init.colors, init.tokens, init.decos, init.gradients);

      const colorBtn = h("button", { type: "button", class: "rich-color-btn", title: "選択範囲に色を付ける", text: "色" });
      colorBtn.addEventListener("mousedown", (e) => { e.preventDefault(); });
      colorBtn.addEventListener("click", (e) => {
        e.preventDefault();
        box.focus();
        const sel = getSelectionRange();
        if (!sel || sel.start === sel.end) {
          const prev = colorBtn.title;
          colorBtn.title = "文字を選択してから色を付けてください";
          setTimeout(() => { colorBtn.title = prev; }, 2000);
          return;
        }
        openPalette(colorBtn);
      });

      wrap.appendChild(box);
      wrap.appendChild(colorBtn);
      return wrap;
    }

    function remount() {
      const parsed = parseCurrent();
      const canGui = !!parsed.ok;
      if (uiMode === "gui" && !canGui) uiMode = "simple";
      const active = uiMode;
      host.innerHTML = "";
      host.appendChild(buildModeBar(canGui, active));
      if (active === "gui" && canGui) {
        host.appendChild(buildGuiEditor(parsed));
      } else {
        host.appendChild(buildSimpleEditor(
          canGui ? null : "対応外の装飾/タグを含むため簡易編集のみ利用できます"
        ));
      }
    }

    remount();
    return host;
  }

  // ============================================================
  // buildTooltipPreview() → { element, update({name, nameMode, loreLines, loreMode, prefix?}) }
  // ============================================================
  function buildTooltipPreview() {
    const nameEl = h("div", { class: "mc-tt-name" });
    const loreEl = h("div", { class: "mc-tt-lore" });
    const element = h("div", { class: "mc-tooltip" }, [nameEl, loreEl]);

    // value を nameMode/loreMode で着色スパン配列へ。既定色 defHex。
    // gradient ラン (run.gradient = 生の引数配列) は richTextInput のGUI編集ボックスと同じ
    // gradientEndpoints/gradientSpans を使って着色する (プレビューとGUIで別実装を持たせない)。
    function renderInto(container, value, mode, defHex) {
      container.innerHTML = "";
      const str = value == null ? "" : String(value);
      const parsed = mode === "minimessage" ? parseMiniMessageForPreview(str) : parseLegacy(str);
      if (parsed.ok) {
        const runs = mode === "minimessage" ? flattenMMNodes(parsed.segments) : parsed.segments;
        for (const run of runs) {
          if (run.text == null || run.text === "") continue;
          const dstr = decoStr(run.decos || []);
          const decoStyle = buildPreviewDecoStyle(dstr);
          if (run.gradient) {
            const ep = gradientEndpoints(run.gradient);
            if (ep) {
              for (const sp of gradientSpans(run.text, ep.from, ep.to)) {
                const styleParts = [`color:${sp.hex}`];
                if (decoStyle) styleParts.push(decoStyle);
                container.appendChild(h("span", { style: styleParts.join(";"), text: sp.char }));
              }
              continue;
            }
          }
          const styleParts = [`color:${run.color || defHex}`];
          if (decoStyle) styleParts.push(decoStyle);
          const span = h("span", { style: styleParts.join(";"), text: run.text });
          if (dstr.indexOf("obfuscated") >= 0) { span.title = "難読化 (プレビュー簡易表示)"; span.classList.add("mc-deco-obf"); }
          container.appendChild(span);
        }
      } else {
        // M-5: 装飾コード (太字/打消し等) や未対応タグを含む行は色のみ近似表示し、
        // 装飾自体は未表現である旨を title で注記する。
        container.appendChild(h("span", { style: `color:${defHex}`, title: "太字/打消し等の装飾はプレビュー未表現です", text: str }));
      }
    }

    function update(opts) {
      const o = opts || {};
      const prefix = o.prefix != null ? String(o.prefix) : "";
      // prefix があれば name の前に既定色で付す (接頭辞付き表示名など)。
      nameEl.innerHTML = "";
      if (prefix) nameEl.appendChild(h("span", { style: `color:${DEFAULT_NAME_HEX}`, text: prefix }));
      const nameHolder = h("span");
      renderInto(nameHolder, o.name, o.nameMode || "legacy", DEFAULT_NAME_HEX);
      while (nameHolder.firstChild) nameEl.appendChild(nameHolder.firstChild);

      loreEl.innerHTML = "";
      const lines = Array.isArray(o.loreLines) ? o.loreLines : [];
      for (const line of lines) {
        const row = h("div", { class: "mc-tt-line" });
        if (line == null || line === "") { row.appendChild(document.createTextNode(" ")); }
        else renderInto(row, line, o.loreMode || "legacy", DEFAULT_LORE_HEX);
        loreEl.appendChild(row);
      }
    }

    update({});
    return { element, update };
  }

  window.colorPickerInput = colorPickerInput;
  window.richTextInput = richTextInput;
  window.buildTooltipPreview = buildTooltipPreview;
})(typeof window !== "undefined" ? window : (typeof module !== "undefined" ? module.exports : this));
