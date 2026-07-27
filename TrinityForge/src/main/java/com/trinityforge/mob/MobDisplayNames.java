package com.trinityforge.mob;

import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.pdc.MobData;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.LivingEntity;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the name TrinityForge should show for a mob, in one place, so every TF-side surface
 * (頭上表示・ログ) agrees on it.
 *
 * <p><b>Why this exists</b> (2026-07-26「ダンジョン名とモブの表示名も設定可能にし、すべて日本語で」):
 * EliteMobs のカスタムボスは英語名(色コード込み)を {@code customName()} に持つ。出荷 {@code
 * combat/mob-overrides.yml} は 396 体すべてに日本語の {@code display-name} を持っているので、TF が
 * 名前を出すところではそちらを優先する。fork 側の表示(ボスバー/nametag)には触れない — あれは
 * EliteMobs の資産で、変更にはフォークの改修が要るため意図的に対象外にしてある。
 *
 * <p><b>解決順</b>:
 * <ol>
 *   <li>{@code mob-overrides.yml} の {@code display-name}（そのモブの居るワールドのスコープ →
 *       {@code default} スコープ の順で、{@link MobOverridesConfig#mobDisplayName} が解決する）</li>
 *   <li>エンティティの {@code customName()}（ネームタグ付き個体・未設定の EliteMobs ボス等）</li>
 *   <li>{@code Component.translatable(EntityType)}（クライアント側の公式ローカライズに任せる。
 *       Java 側に独自の英日対応表を新設しないための選択 — {@code FocusHpDisplay} の javadoc 参照）</li>
 * </ol>
 *
 * <p>すべて表示専用。戦闘計算・スコープ解決には一切影響しない。
 */
public final class MobDisplayNames {

    private final MobOverridesConfig mobOverrides;

    public MobDisplayNames(MobOverridesConfig mobOverrides) {
        this.mobOverrides = Objects.requireNonNull(mobOverrides, "mobOverrides");
    }

    /**
     * TF が設定した日本語表示名。設定が無い（TF管理外のモブ・display-name 未設定）なら空。
     * ログ用のプレーン文字列がほしい呼び出し側のために {@link Optional}{@code <String>} で返す。
     */
    public Optional<String> configuredName(LivingEntity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        Optional<String> profileId = MobData.of(entity).profileId();
        if (profileId.isEmpty()) {
            return Optional.empty();
        }
        return mobOverrides.mobDisplayName(entity.getWorld().getName(), profileId.get());
    }

    /** 表示用の名前 Component。解決順は class javadoc のとおり。 */
    public Component displayName(LivingEntity entity) {
        Optional<String> configured = configuredName(entity);
        if (configured.isPresent()) {
            return Component.text(configured.get());
        }
        if (entity != null && entity.customName() != null) {
            return entity.customName();
        }
        return entity == null ? Component.empty() : Component.translatable(entity.getType());
    }

    /**
     * ログ1行に載せるための識別子。日本語名があれば {@code 蜘蛛型構造体 (the_mines_arachnidian_construct)}、
     * 無ければモブidだけ、それも無ければ EntityType 名を返す。
     * 「どのモブの設定が効いているか」をログから追えるようにするための整形。
     */
    public String logLabel(LivingEntity entity) {
        if (entity == null) {
            return "<null>";
        }
        Optional<String> profileId = MobData.of(entity).profileId();
        if (profileId.isEmpty()) {
            return entity.getType().name();
        }
        String id = profileId.get();
        return mobOverrides.mobDisplayName(entity.getWorld().getName(), id)
                .map(name -> name + " (" + id + ")")
                .orElse(id);
    }
}
