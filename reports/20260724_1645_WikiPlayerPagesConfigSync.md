# Wiki Player Pages Config Sync

## Task
プレイヤー向けWiki（02/03/04/08/09）を現行TrinityForge configに合わせて更新した。

## Files Changed
- trinityforge-wiki/02-戦闘のしくみ.md
- trinityforge-wiki/03-ステータスと厳選.md
- trinityforge-wiki/04-アイテム図鑑.md
- trinityforge-wiki/08-育成と解放.md
- trinityforge-wiki/09-モブとダンジョン.md
- trinityforge/wiki/ 上記5ファイルを同期

## Details
- 02: level-scaling +5%→+1%、装備非依存表現を修正（attack-power等も効く）、防具強度のバニラ頑丈さ変換無効を反映
- 03: 品質16段階（quality-tiers.yml）に差し替え、釣り=FISHINGスキル、ツールクラフト=SMITHING、敵ドロップ経路追加、防具ゲート有効を反映
- 04: ドミニオンワンド作業台レシピ・テレポートコンパス儀式をcatalog.yml準拠で追記、「要確認」削除
- 08: 表示名をconfig display-nameに合わせ（軽装備/切削/総合/Ars魔法/Ars鍛冶）、戦闘レベルと装備ステの関係を訂正
- 09: 入場ゲートをワールド/region/EliteMobs内部名の統一説明に整理

## Not updated (out of scope)
- 11 管理者設定リファレンス、14 スキルツリー詳細、10/12 管理者・実装状況

## README
プレイヤー向けWikiのREADME目次に変更なし（ページ追加なし）。