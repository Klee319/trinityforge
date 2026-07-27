# TrinityForge Skill GUI resource pack

TFネイティブスキルツリーの操作・状態表示に必要な最小リソースパックです。

## 収録物

- 9×5ツリー移動: 上下左右＋斜めの8方向ボタン
- 接続線: locked / unlockable / unlocked の3状態と16形状
  （直線/端点7、曲角4、三方向分岐4、四方向交差1）
- スキル選択: Valhallaで専用モデルが定義されていた10スキル（共用1種を含む9モデル）
- Valhalla互換のスキルGUI背景・負方向スペース文字
- ノード状態: ロック、解放確認、解放済み

元画像・モデルはユーザー承認に基づき、公式ValhallaMMO 1.21.4+パック
（SHA-1 `9ad5df64c86f0d5f1636e8a2bb8922dba6055b27`）から必要分のみ抽出しています。
曲角・分岐・交差は既存接続線と同じ太さ/状態色でTF側が生成した補完素材です。
実行時にValhallaMMO JARや設定ファイルは参照しません。

## ビルド

接続線素材を再生成する場合は先に `python resourcepack/generate_connector_assets.py`、
続けて `python resourcepack/build_skill_gui_pack.py` を実行します。
JSONと参照先を検証して `resourcepack/dist/TrinityForge-SkillGUI.zip` を決定的に生成します。
現在の成果物は240ファイル、SHA-1
`3b9bc3e7b35c5a87bca792424ef66f53a639e133`です。
配布時は既存のサーバーリソースパックへマージするか、ZIPをHTTPSで配信し、
Paperのリソースパック設定からクライアントへ適用します。

パックを拒否・未導入の場合も、GUIはバニラMaterialとLoreで操作できます。
