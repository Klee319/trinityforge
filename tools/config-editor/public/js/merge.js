"use strict";

// 3-way マージ (base / local / remote)。双方が別内容に変えた葉は local 優先。
// 配列は葉扱いにせず、(1)双方末尾追加は両取り (2)id等で同定できる要素はキー単位で
// マージする。かつては配列全体を local 丸勝ちにしていたため、同時編集で相手が
// 保存済みのリスト追加分が黙って消えていた (2026-07-22 修正)。

(function () {
  function isPlainObject(v) {
    return v !== null && typeof v === "object" && !Array.isArray(v);
  }

  window.cloneData = function cloneData(v) {
    if (v === undefined) return undefined;
    if (typeof structuredClone === "function") {
      try { return structuredClone(v); } catch (_) { /* fall through */ }
    }
    return JSON.parse(JSON.stringify(v));
  };

  window.deepEqual = function deepEqual(a, b) {
    if (a === b) return true;
    if (a == null || b == null) return a === b;
    if (typeof a !== typeof b) return false;
    if (Array.isArray(a)) {
      if (!Array.isArray(b) || a.length !== b.length) return false;
      for (let i = 0; i < a.length; i++) {
        if (!window.deepEqual(a[i], b[i])) return false;
      }
      return true;
    }
    if (isPlainObject(a)) {
      if (!isPlainObject(b)) return false;
      const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
      for (const k of keys) {
        if (!window.deepEqual(a[k], b[k])) return false;
      }
      return true;
    }
    return false;
  };

  /**
   * @param {*} base 読み込み時点
   * @param {*} local 自分の未保存
   * @param {*} remote 相手の最新
   * @returns {{ data: *, conflicts: string[] }}
   */
  function isScalar(v) {
    return v === null || typeof v === "string" || typeof v === "number" || typeof v === "boolean";
  }

  /** prefix の全要素が arr の先頭に同順で並んでいるか (末尾追加のみの検出用)。 */
  function isPrefixOf(prefix, arr) {
    if (prefix.length > arr.length) return false;
    for (let i = 0; i < prefix.length; i++) {
      if (!window.deepEqual(prefix[i], arr[i])) return false;
    }
    return true;
  }

  // 配列要素の同定キー候補 (エディタの各リストで実際に使われている識別子)。
  const IDENTITY_KEY_CANDIDATES = ["id", "key", "name", "stat", "material", "item", "skill"];

  /**
   * 3配列すべての要素が「同じスカラーキーを持つオブジェクトで、各配列内で値が一意」なら
   * そのキー名を返す (キー単位マージ可能)。無理なら null。
   */
  function identityKeyOf(arrays) {
    const all = [];
    for (const arr of arrays) for (const e of arr) all.push(e);
    if (all.length === 0) return null;
    if (!all.every(isPlainObject)) return null;
    for (const k of IDENTITY_KEY_CANDIDATES) {
      if (!all.every((e) => Object.prototype.hasOwnProperty.call(e, k) && isScalar(e[k]))) continue;
      let unique = true;
      for (const arr of arrays) {
        const ids = arr.map((e) => String(e[k]));
        if (new Set(ids).size !== ids.length) { unique = false; break; }
      }
      if (unique) return k;
    }
    return null;
  }

  window.threeWayMerge = function threeWayMerge(base, local, remote) {
    const conflicts = [];

    function mergeAt(b, l, r, path) {
      // 双方とも base と同じ → base
      if (window.deepEqual(l, b) && window.deepEqual(r, b)) return window.cloneData(b);
      // local のみ変更
      if (window.deepEqual(r, b)) return window.cloneData(l);
      // remote のみ変更
      if (window.deepEqual(l, b)) return window.cloneData(r);
      // 双方同じ結果
      if (window.deepEqual(l, r)) return window.cloneData(l);

      // 双方オブジェクトならキー単位で再帰
      if (isPlainObject(l) && isPlainObject(r) && isPlainObject(b || {})) {
        const bb = isPlainObject(b) ? b : {};
        const out = {};
        const keys = new Set([...Object.keys(bb), ...Object.keys(l), ...Object.keys(r)]);
        for (const k of keys) {
          const hasB = Object.prototype.hasOwnProperty.call(bb, k);
          const hasL = Object.prototype.hasOwnProperty.call(l, k);
          const hasR = Object.prototype.hasOwnProperty.call(r, k);
          const bv = hasB ? bb[k] : undefined;
          const lv = hasL ? l[k] : undefined;
          const rv = hasR ? r[k] : undefined;
          const sub = path ? path + "." + k : k;

          // 削除の扱い
          if (!hasL && !hasR) continue;
          if (!hasL && hasR) {
            // local が削除、remote が残す/変更
            if (!hasB) {
              // base に無く local にも無い → remote の追加を採用
              out[k] = window.cloneData(rv);
            } else if (window.deepEqual(rv, bv)) {
              // remote 未変更・local 削除 → 削除
            } else {
              // local 削除 vs remote 変更 → 衝突、local(削除)優先
              conflicts.push(sub);
            }
            continue;
          }
          if (hasL && !hasR) {
            if (!hasB) {
              out[k] = window.cloneData(lv);
            } else if (window.deepEqual(lv, bv)) {
              // local 未変更・remote 削除 → 削除
            } else {
              conflicts.push(sub);
              out[k] = window.cloneData(lv);
            }
            continue;
          }
          // 双方にキーあり
          out[k] = mergeAt(bv, lv, rv, sub);
        }
        return out;
      }

      // 双方とも配列 → 追加両取り / キー単位マージを試みる
      if (Array.isArray(l) && Array.isArray(r)) {
        return mergeArrayAt(Array.isArray(b) ? b : [], l, r, path);
      }

      // プリミティブ・型不一致: 双方変更で異なる → local 優先
      conflicts.push(path || "(root)");
      return window.cloneData(l);
    }

    /**
     * 双方が変更した配列のマージ。
     * 1) 双方とも base への末尾追加のみ → base + 自分の追加 + 相手の追加 (同一要素は重複させない)。
     * 2) 全要素が id 等で同定できる → 要素単位の 3-way (別要素の編集・追加・削除は両立)。
     * 3) それ以外 → 従来通り衝突として記録し local 優先。
     */
    function mergeArrayAt(b, l, r, path) {
      // (1) 双方末尾追加のみ
      if (isPrefixOf(b, l) && isPrefixOf(b, r)) {
        const lAdd = l.slice(b.length);
        const rAdd = r.slice(b.length);
        const out = l.map((e) => window.cloneData(e));
        for (const rv of rAdd) {
          if (!lAdd.some((lv) => window.deepEqual(lv, rv))) out.push(window.cloneData(rv));
        }
        return out;
      }

      // (2) 同定キーによる要素単位マージ
      const key = identityKeyOf([b, l, r]);
      if (key != null) {
        const toMap = (arr) => {
          const m = new Map();
          for (const e of arr) m.set(String(e[key]), e);
          return m;
        };
        const bMap = toMap(b);
        const lMap = toMap(l);
        const rMap = toMap(r);
        // 並びは local の順を優先し、remote だけにある要素 (相手の追加) を末尾へ。
        const order = [];
        const seen = new Set();
        for (const e of l) { const id = String(e[key]); if (!seen.has(id)) { seen.add(id); order.push(id); } }
        for (const e of r) { const id = String(e[key]); if (!seen.has(id)) { seen.add(id); order.push(id); } }
        const out = [];
        for (const id of order) {
          const hasB = bMap.has(id);
          const hasL = lMap.has(id);
          const hasR = rMap.has(id);
          const sub = (path || "") + "[" + key + "=" + id + "]";
          if (hasL && hasR) {
            out.push(mergeAt(hasB ? bMap.get(id) : undefined, lMap.get(id), rMap.get(id), sub));
            continue;
          }
          if (!hasL && hasR) {
            if (!hasB) {
              out.push(window.cloneData(rMap.get(id))); // remote の追加
            } else if (!window.deepEqual(rMap.get(id), bMap.get(id))) {
              conflicts.push(sub); // local 削除 vs remote 変更 → 削除(local)優先
            }
            // remote 未変更・local 削除 → 削除を採用 (何も足さない)
            continue;
          }
          if (hasL && !hasR) {
            if (!hasB) {
              out.push(window.cloneData(lMap.get(id))); // local の追加
            } else if (!window.deepEqual(lMap.get(id), bMap.get(id))) {
              conflicts.push(sub);
              out.push(window.cloneData(lMap.get(id))); // local 変更 vs remote 削除 → local 優先
            }
            // local 未変更・remote 削除 → 削除を採用
          }
        }
        return out;
      }

      // (3) 同定不能: 従来通り local 優先
      conflicts.push(path || "(root)");
      return window.cloneData(l);
    }

    const data = mergeAt(
      base === undefined ? {} : base,
      local === undefined ? {} : local,
      remote === undefined ? {} : remote,
      ""
    );
    return { data, conflicts };
  };
})();
