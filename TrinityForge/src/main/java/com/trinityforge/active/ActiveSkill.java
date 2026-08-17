package com.trinityforge.active;

import org.bukkit.entity.Player;

import java.util.Set;

/**
 * One activated (player-triggered, not passive) ability registered onto the shared framework
 * (2026-07-25 gather-rework-active-framework §3 component 1). fork {@code SpellCaster}/{@code ManaManager}
 * are the design reference (concept, not code, porting — TF has no mana/resource layer yet, §3 component
 * 6/§6 Q4).
 *
 * <p>Implementations are expected to be stateless/config-backed (the framework — {@link CooldownManager} —
 * owns all per-player mutable state), so a single instance is registered once at plugin enable and reused
 * for every player.
 *
 * <p>Deviation from the 2026-07-25 design doc §3 component 1 interface sketch: this adds
 * {@link #targetSkills()} and {@link #cooldownMillis(int)}. The design doc's Q2 (正式トリガー =
 * {@code /tf active}+GUI) was overridden by the orchestrating brief in favor of "sneak + 右クリック with
 * the matching {@code use-skill} item" as the ONLY player-facing trigger (GUI dropped entirely,
 * {@code /tf active <id>} kept debug-only) — that trigger rule requires matching the held item's
 * {@code use-skill} tag against the skill-tree(s) the active belongs to, hence {@link #targetSkills()}.
 * {@link #cooldownMillis(int)} makes concrete the design doc's "CT/コストは宣言的メタで外部管理" comment: the
 * {@link ActivationDispatcher} needs a cooldown length before it can consult {@link CooldownManager}, and
 * that length is tier-dependent (haste-active-mining's tiers table), so it cannot live purely inside
 * {@link CooldownManager} itself.
 *
 * <h2>Multi-skill activation is a first-class, expected shape — not an edge case</h2>
 * <p>2026-07-25 regression fix: a single gate ({@code gateEffectId()}) can legitimately be placed on
 * MULTIPLE skill trees (e.g. {@code haste-active-mining} is gated by both {@code mining.yml} A-1 AND
 * {@code digging.yml} A-1 — a shared "採掘速度上昇" ability meant to fire off either a pickaxe or a
 * shovel). {@link #targetSkills()} therefore returns a {@link Set}, not a single id, and
 * {@link #id()}/{@link CooldownManager} stay keyed by the ONE skill id regardless of which tree's item
 * triggered it — a player who activates via a pickaxe and immediately tries again via a shovel must be
 * refused (single shared cooldown per active, never per-trigger-skill).
 *
 * <p><b>2026-08-01 実サーバ報告の修正 — CTは共有だが「解放」は共有ではない</b>: 上の「共有」は
 * <em>クールダウン</em>にだけ掛かる規則で、<em>解放判定</em>には掛からない。
 * {@link ActivationDispatcher} は {@link #gateEffectId()} を
 * <b>持ち替えたツールの {@code use-skill} と同じスキルツリーに置かれた配置だけ</b>に絞って解決する
 * ({@code DedicatedEffectsConfig#valueMax(Player, String, String)})。そうしないと
 * <b>{@code mining.yml} A-1 しか取っていないプレイヤーがシャベルでも発動できて</b>しまい、
 * 「シャベルを持っていても採掘速度上昇が発動する」という実サーバ報告になっていた
 * (tier も同様に、そのツリー内の配置の最大値だけを見る)。
 *
 * <p>Any new {@link ActiveSkill}
 * implementation, and any future migration of another gathering feature onto this framework, MUST treat
 * "one active reachable from several {@code use-skill} tags" as the default assumption, not a special
 * case to opt into — {@link ActiveSkillRegistry#forTargetSkill(String)} and
 * {@link ActivationDispatcher} are both written against that assumption. Do NOT "solve" a multi-tree gate
 * by registering the same ability twice under two ids: that reintroduces a per-registration cooldown and
 * lets a player double-dip (fire once per held-item type instead of once per ability).
 */
public interface ActiveSkill {

    /** Stable identifier used as the {@code /tf active} debug target (and the default {@link #cooldownGroup()}). */
    String id();

    /**
     * The {@link CooldownManager} key actually consumed/checked by {@link ActivationDispatcher} and
     * {@link ActiveCooldownDisplay}. Defaults to {@link #id()} (one skill = one private CT bucket, the
     * original framework shape).
     *
     * <p><b>2026-08-18 (W-59)</b> — override this to share a CT bucket with a DIFFERENT {@link ActiveSkill}
     * that has its own independent {@link #id()}, {@link #gateEffectId()}, unlock level and
     * {@link #cooldownMillis(int)}. This is how {@code haste-active-mining} (pickaxe) and
     * {@code haste-active-digging} (shovel) are kept as two genuinely separate, separately-tunable skills
     * while still guaranteeing "switching tools never grants extra combined uptime": both return the same
     * constant from {@code cooldownGroup()}, so {@link CooldownManager} treats one activation as consuming
     * the other's cooldown too — a player alternating pickaxe/shovel hits the shared lock on the second
     * attempt instead of getting a fresh CT from the tool switch. Do NOT achieve this by registering the same
     * ability under {@link #targetSkills()} covering both trees (see the class doc's warning above) — that
     * reintroduces the tree-scoped-unlock bug this design fixed on 2026-08-01; two independent skills whose
     * gates are each scoped to their own tree is the only way both requirements (shared CT, independent
     * unlock/config) hold at once.
     */
    default String cooldownGroup() {
        return id();
    }

    /**
     * プレイヤーに見せる名前(CT残り表示など)。既定は {@link #id()} — 新しいアクティブスキルを
     * 足すときは必ず日本語名で上書きすること(既定のままだと生IDがアクションバーに出る)。
     */
    default String displayName() {
        return id();
    }

    /**
     * The {@code feature:<id>} (bare, no prefix) this skill's unlock/tier is gated on.
     *
     * <p>実トリガー({@link ActivationDispatcher})はこれを<b>ツリー限定</b>で解決する:
     * {@code DedicatedEffectsConfig#valueMax(player, gateEffectId(), useSkill)} — {@code useSkill} は
     * 持ち替えたメインハンドの {@code use-skill}。ツリーを問わない2引数版
     * ({@code valueMax(player, gateEffectId())}) を使ってよいのは、持ち物を前提にしない
     * {@code /tf active <id>}(デバッグ専用、{@code com.trinityforge.command.ActiveCommand})だけである。
     * 理由はクラスjavadocの「CTは共有だが『解放』は共有ではない」節を参照。
     */
    String gateEffectId();

    /**
     * The skill-tree {@code skill:} id(s) (e.g. {@code {"MINING", "DIGGING"}}) this active can be
     * triggered from. The {@link ActivationDispatcher} trigger rule requires the held main-hand item's
     * {@code use-skill} PDC tag ({@code ItemData#useSkill}, matched case-insensitively, never inferred
     * from Material — see {@code PerkBuffResolver#matchesMainHandSkill}) to be a member of this set
     * before an activation attempt is even considered. Never empty. See the class doc for why this is a
     * set and why that must not be worked around via duplicate registration.
     */
    Set<String> targetSkills();

    /** The cooldown (ms) for a player who resolved to {@code tier} (see {@link ActiveContext#tier()}). */
    long cooldownMillis(int tier);

    /**
     * Applies the skill's effect. Only ever called by {@link ActivationDispatcher} after the gate (tier
     * resolved) and cooldown checks already passed — implementations do not need to re-check either.
     */
    ActivationResult activate(Player player, ActiveContext ctx);
}
