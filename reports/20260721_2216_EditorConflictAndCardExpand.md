# Task

Config Editor の同時編集時の上書き防止と、折りたたみカードの展開操作改善。

# Files Changed

- `tools/config-editor/public/js/app.js`
- `tools/config-editor/public/js/util.js`
- `tools/config-editor/public/style.css`
- `tools/config-editor/README.md`

# Details

- 未編集状態で保存を押した場合は PUT を行わず、サーバ上の最新内容を再読込するようにした。
- 統合ビューでは、読み込み時点から実際に変更された primary / companion config だけを保存対象にした。
- usage-gate / progression の companion config の変更も未保存判定へ含め、変更済みデータが「未編集」と誤判定されないようにした。
- 保存競合モーダルから、他の編集者の変更を失う「強制上書き」を削除した。
- 競合モーダル外のクリックは破棄ではなくキャンセルとして扱うようにした。
- 3-way マージ後のベースラインを相手の最新データに更新し、マージ結果を未保存差分として維持するよう修正した。
- 画面移動時の警告を「自分の未保存の変更」と明示した。
- 折りたたみ中のカードは、入力部品・ボタン等を除くヘッダ部分のクリックでも展開できるようにした。
- ドラッグ並べ替え直後のクリックによる誤展開を抑止した。
- README に同時編集、未保存変更、カード展開操作の仕様を追記した。

# Verification

- `node --check public/js/app.js`
- `node --check public/js/util.js`
- `node --check server.js`
- すべて成功。
- ローカルサーバの起動を確認。ブラウザによる画面操作確認は認証設定により未実施。
