# -*- coding: utf-8 -*-
"""スレッド(全75種)のアイテムテクスチャを、バニラの鍛冶型19種から機械生成する。

【背景】2026-08-21 のユーザー指示:
  ・スレッドはバニラの鍛冶型を素材にしていたため lore にバニラの説明が入って圧迫されていた
    → 素材を一律 STRING(糸)へ変更した(T6)
  ・「上記に伴いそれぞれカスタムテクスチャを割り当てる。バニラの鍛冶型のテクスチャを
     色調やコントラスト・明るさ補正で各スレッド異なるデザインに変えるのがプログラムで
     機械的にできデザイン的にもよいだろう」

【やり方】
  ・形は鍛冶型19種を使い回す(スレッドは 75 種あるので、形だけでは足りない)。
  ・色は【カタログの表示名の色】へ寄せる。アイテム名の色とアイコンの色が一致するので、
    インベントリで名前を読まなくても系統が分かる。
  ・同じ表示色のスレッドが複数あるので、明度・彩度・コントラストを CMD から決定的に
    散らして重複を避ける。乱数を使わない ── 再実行しても同じ絵が出ないと差分が読めない。

【アルファ】バニラの鍛冶型は alpha が 0 か 255 しかない(統合版のアタッチャブルは半透明を
  正しく扱えないことがある)。この生成器も alpha には一切触らず、RGB だけを書き換える。

実行:
    python resourcepack/generate_thread_sprites.py            # 生成のみ(既定)
    python resourcepack/generate_thread_sprites.py --register # respack へ登録まで行う
"""
import argparse
import colorsys
import io
import json
import os
import pathlib
import re
import subprocess
import sys
import zipfile

from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
CATALOG = ROOT / 'TrinityForge/src/main/resources/items/catalog.yml'
REGISTRY = HERE / 'cmd-registry.json'
OUT_DIR = HERE / 'trinityforge-items/assets/trinityforge/textures/item'

CLIENT_JAR = pathlib.Path(
    os.environ.get('MC_CLIENT_JAR',
                   r'C:/Users/T-319/AppData/Roaming/.minecraft/versions/1.21.11/1.21.11.jar'))

# 形の素材にする鍛冶型。並び順が assetName → 形の対応を決めるので、
# 【並べ替えないこと】(並べ替えると全スレッドの絵が入れ替わる)。
TEMPLATES = [
    'sentry', 'dune', 'coast', 'wild', 'ward', 'eye', 'vex', 'tide', 'snout',
    'rib', 'spire', 'wayfinder', 'shaper', 'silence', 'raiser', 'host', 'flow', 'bolt',
]
NETHERITE_TEMPLATE = 'netherite_upgrade_smithing_template'

# MiniMessage の色名 → 色相(度)と彩度の下駄。TF の表示名で実際に使われている色だけ。
# grey/white/black は色相を持たないので、彩度をほぼ 0 にして「無彩色の型」にする。
COLOR_HUES = {
    'dark_red': (0, 1.00), 'red': (5, 0.95), 'gold': (38, 0.95), 'yellow': (52, 0.90),
    'dark_green': (110, 0.90), 'green': (95, 0.85), 'aqua': (180, 0.80),
    'dark_aqua': (188, 0.95), 'blue': (220, 0.90), 'dark_blue': (232, 1.00),
    'light_purple': (305, 0.80), 'dark_purple': (280, 0.95),
    'white': (0, 0.05), 'gray': (0, 0.08), 'dark_gray': (0, 0.10), 'black': (0, 0.12),
}
DEFAULT_COLOR = ('white', (0, 0.05))

# CMD から決定的に散らす3系列。同じ表示色のスレッドを見分けるための微差で、
# 大きく振ると「名前の色と違う色のアイコン」になるので幅は意図的に狭い。
VALUE_STEPS = (1.00, 1.14, 0.88, 1.07, 0.94)
SAT_STEPS = (1.00, 0.86, 1.12, 0.94)
HUE_JITTER = (0, 9, -9, 18, -18, 4)


def read_threads():
    """catalog.yml から thread_* の (id, cmd, 表示色) を読む。

    正規表現で読むのは、この生成器のためだけに PyYAML を要求したくないから
    (resourcepack/ の他の生成器も同じ流儀)。
    """
    text = CATALOG.read_text(encoding='utf-8')
    out = []
    cur = None
    for line in text.split('\n'):
        m = re.match(r'^  (thread_[a-z0-9_]+):\s*$', line)
        if m:
            cur = {'id': m.group(1), 'cmd': None, 'color': None}
            out.append(cur)
            continue
        if cur is None:
            continue
        if re.match(r'^  [a-z_]', line):          # 別のアイテムに入った
            cur = None
            continue
        m = re.match(r'^    custom-model-data:\s*(\d+)\s*$', line)
        if m:
            cur['cmd'] = int(m.group(1))
            continue
        m = re.match(r'^    display-name:\s*(.+)$', line)
        if m:
            raw = m.group(1)
            c = re.search(r'<color:([a-z_]+)>', raw) or re.search(r'^<([a-z_]+)>', raw)
            cur['color'] = c.group(1) if c else None
    threads = [t for t in out if t['cmd'] is not None]
    missing = [t['id'] for t in out if t['cmd'] is None]
    if missing:
        raise SystemExit('custom-model-data の無い thread_*: %s' % missing)
    return threads


def load_templates():
    if not CLIENT_JAR.is_file():
        raise SystemExit('クライアント jar が見つからない: %s\n'
                         '別の場所にあるなら環境変数 MC_CLIENT_JAR で指定する。' % CLIENT_JAR)
    images = []
    with zipfile.ZipFile(CLIENT_JAR) as z:
        for name in TEMPLATES:
            path = 'assets/minecraft/textures/item/%s_armor_trim_smithing_template.png' % name
            images.append((name, Image.open(io.BytesIO(z.read(path))).convert('RGBA')))
        path = 'assets/minecraft/textures/item/%s.png' % NETHERITE_TEMPLATE
        images.append(('netherite', Image.open(io.BytesIO(z.read(path))).convert('RGBA')))
    return images


def recolor(img, hue_deg, sat_scale, value_scale, contrast):
    """色相を hue_deg へ寄せ、彩度・明度・コントラストを補正する。alpha は触らない。"""
    src = img.copy()
    px = src.load()
    w, h = src.size
    hue = (hue_deg % 360) / 360.0
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            _, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
            # コントラストは明度に対して 0.5 を中心に掛ける(暗部はより暗く、明部はより明るく)。
            v = 0.5 + (v - 0.5) * contrast
            v = max(0.0, min(1.0, v * value_scale))
            s = max(0.0, min(1.0, s * sat_scale))
            nr, ng, nb = colorsys.hsv_to_rgb(hue, s, v)
            px[x, y] = (int(round(nr * 255)), int(round(ng * 255)), int(round(nb * 255)), a)
    return src


# 無彩色の表示名(白/灰/黒/色指定なし)は 17 種ある。そのまま彩度ほぼ0にすると
# 「形だけ違う灰色の札」が 17 枚並んで見分けがつかないので、【並び順で色相を散らした
# くすんだ色】を当てる。名前が無彩色なら何色を当てても名前と矛盾しないので安全で、
# 彩度を低く抑えてあるので有彩色のスレッドとは一目で区別できる。
ACHROMATIC = {'white', 'gray', 'dark_gray', 'black', None}
NEUTRAL_SAT = 0.38


def variant_params(thread, index):
    color = thread['color'] if thread['color'] in COLOR_HUES else DEFAULT_COLOR[0]
    base_hue, base_sat = COLOR_HUES.get(color, DEFAULT_COLOR[1])
    if thread['color'] in ACHROMATIC:
        base_hue = (index * 360.0 / 17.0) % 360.0
        base_sat = NEUTRAL_SAT
    cmd = thread['cmd']
    hue = base_hue + HUE_JITTER[cmd % len(HUE_JITTER)]
    sat = base_sat * SAT_STEPS[(cmd // 7) % len(SAT_STEPS)]
    value = VALUE_STEPS[(cmd // 3) % len(VALUE_STEPS)]
    contrast = 1.0 + 0.12 * ((cmd // 11) % 3)
    return hue, sat, value, contrast, index % (len(TEMPLATES) + 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--register', action='store_true',
                    help='生成後に respack(cmd-registry + モデル + items 定義)へ登録する')
    args = ap.parse_args()

    threads = sorted(read_threads(), key=lambda t: t['cmd'])
    templates = load_templates()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    written = []
    seen_signature = {}
    for i, thread in enumerate(threads):
        hue, sat, value, contrast, tpl_index = variant_params(thread, i)
        tpl_name, tpl_img = templates[tpl_index]
        sig = (tpl_name, round(hue, 1), round(sat, 3), round(value, 3), round(contrast, 3))
        if sig in seen_signature:
            raise SystemExit('同じ見た目が2件出た: %s と %s (%s)'
                             % (seen_signature[sig], thread['id'], sig))
        seen_signature[sig] = thread['id']
        img = recolor(tpl_img, hue, sat, value, contrast)
        out = OUT_DIR / ('%s.png' % thread['id'])
        img.save(out)
        written.append((thread['id'], thread['cmd'], tpl_name, round(hue), round(sat, 2),
                        round(value, 2), round(contrast, 2)))

    print('生成: %d 件 → %s' % (len(written), OUT_DIR))
    for row in written:
        print('  %-28s CMD=%-7d 型=%-10s 色相=%-4d 彩度=%.2f 明度=%.2f コントラスト=%.2f' % row)

    if args.register:
        register(threads)


def register(threads):
    """respack.js の writeTexture / regenerateItemDefinitions を Node 経由で呼ぶ。

    モデル JSON と cmd-registry の assetName / parent、
    assets/minecraft/items/string.json(range_dispatch)の再生成まで面倒を見るのは
    editor 本体と同じこの経路だけなので、自前で JSON を書かない。
    """
    payload = [{'id': t['id'], 'cmd': t['cmd'],
                'png': str((OUT_DIR / ('%s.png' % t['id'])).resolve())} for t in threads]
    script = HERE / '.thread-sprites-register.js'
    script.write_text(REGISTER_JS, encoding='utf-8')
    try:
        proc = subprocess.run(
            ['node', str(script)], input=json.dumps(payload), text=True,
            encoding='utf-8', capture_output=True, cwd=str(ROOT))
        sys.stdout.write(proc.stdout)
        sys.stderr.write(proc.stderr)
        if proc.returncode != 0:
            raise SystemExit('respack への登録に失敗した (exit %d)' % proc.returncode)
    finally:
        if script.exists():
            script.unlink()


REGISTER_JS = r"""
// resourcepack/generate_thread_sprites.py が一時生成して実行する。手で置かない。
const fs = require("fs");
const path = require("path");
const respack = require(path.join(process.cwd(), "tools", "config-editor", "lib", "respack.js"));

const packRoot = path.join(process.cwd(), "resourcepack");
const registryPath = path.join(packRoot, "cmd-registry.json");
const items = JSON.parse(fs.readFileSync(0, "utf8"));

let ok = 0;
for (const it of items) {
  respack.writeTexture({
    material: "STRING",
    cmd: it.cmd,
    id: it.id,
    pngBuffer: fs.readFileSync(it.png),
    parent: "generated"
  }, packRoot, registryPath);
  ok++;
}
const regenerated = respack.regenerateItemDefinitions(packRoot, registryPath);
console.log(`respack 登録: ${ok} 件 / items 定義を再生成: ${JSON.stringify(regenerated)}`);
"""


if __name__ == '__main__':
    main()
