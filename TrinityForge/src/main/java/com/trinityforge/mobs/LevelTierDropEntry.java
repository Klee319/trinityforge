package com.trinityforge.mobs;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Objects;
import java.util.Set;

/**
 * One {@code add-drops} entry of a {@code combat/mob-level-table.yml} level band (2026-07-25 レベル
 * テーブルのモブ別ドロップ指定): a vanilla {@link Material} OR a {@code custom:<catalogId>} reference
 * (mirrors {@code com.trinityforge.stats.RecipeIngredient}'s "exactly one of material/catalogId" shape),
 * a per-death roll chance, an inclusive stack-size range, and an optional {@code mobs} filter.
 *
 * <p>{@link #targets()} empty (the default — neither {@code mobs:} nor {@code mob-ids:} in the config)
 * means "applies to every mob", preserving the pre-existing "レベル値だけを見て横断的に適用する" behavior
 * exactly ({@code combat/mob-level-table.yml} 後方互換). {@code mobs:} narrows by vanilla
 * {@link EntityType}; {@code mob-ids:} narrows by EliteMobs モブid (2026-07-26 — EntityType alone cannot
 * tell one dungeon mob from another, since a whole roster is typically re-skinned {@code ZOMBIE}s, so
 * the earlier "EntityType covers dungeon mobs too" reasoning held only for whole-species rules). See
 * {@link MobTargetFilter} for the AND semantics when both axes are set.
 *
 * <p>Deliberately NOT {@link MobDropEntry} (used by {@code combat/mob-types.yml}'s {@code drops:}):
 * that record requires a non-null {@link Material} and has no {@code mobs}/{@code custom:} concept,
 * and widening it would let an unrelated caller accidentally construct a custom/mob-filtered entry it
 * has no code path to honour. Chance/min/max validation is intentionally identical to {@link MobDropEntry}.
 *
 * @param material  the vanilla item to drop, or {@code null} when {@link #catalogId()} is set instead
 * @param catalogId the {@code items/catalog.yml} id (or ArsPaper custom item id — see
 *                  {@code com.trinityforge.stats.CrossPluginItemResolver}, which resolves this at
 *                  drop-roll time) to drop, or {@code null} when {@link #material()} is set instead
 * @param chance    per-death roll chance [0,1]
 * @param min       minimum stack size (inclusive), &gt;= 0
 * @param max       maximum stack size (inclusive), &gt;= {@code min}
 * @param targets   which mobs this entry applies to ({@code mobs:} EntityType axis + {@code mob-ids:}
 *                  EliteMobsモブid axis); {@link MobTargetFilter#EMPTY} = every mob (back-compat default)
 * @param roles     {@code roles:} — キルしたプレイヤーの職業がこの集合に含まれるときだけロールする
 *                  (2026-08-02 柱7)。空 = 職業を問わない(後方互換の既定)。IDは
 *                  {@code progression/role-buffs.yml} のキーと同じ正規化(小文字)で保持する
 * @param chanceByLevel {@code chance-by-level:} — 討伐したモブのレベルで確率を線形補間する
 *                  (2026-08-14 フィールドドロップ配線)。{@code null} なら {@link #chance()} を
 *                  そのまま使う(後方互換)。詳細は {@link ChanceCurve}
 * @param where     {@code where:} — このエントリを適用する場所(フィールド/ダンジョン/両方)。
 *                  {@code null} は {@link DropScope#ANY} に丸める(後方互換の既定)
 * @param baby      {@code baby:} — 子供個体だけ({@code true})／大人個体だけ({@code false})に絞る。
 *                  {@code null} = 区別しない(後方互換の既定)。判定は
 *                  {@link org.bukkit.entity.Ageable#isAdult()} なので、{@code Ageable} を実装しない
 *                  モブに書くと<b>常に不一致</b>(1個も落ちない)になる
 */
public record LevelTierDropEntry(Material material, String catalogId, double chance, int min, int max,
                                  MobTargetFilter targets, Set<String> roles,
                                  ChanceCurve chanceByLevel, DropScope where, Boolean baby) {

    /**
     * {@code where:} — このドロップを適用する場所。判定は「討伐したワールドが
     * {@code DungeonWorldRegistry} に登録されたダンジョンインスタンスか」の一点で、
     * {@code MOB_TYPE_STAMPED} や {@code MOB_PROFILE_ID} の有無では判定しない。
     *
     * <p>そうしている理由: {@code combat/mob-import.yml} の {@code unknown-mobs.synthesize: true} に
     * より、EliteMobs のダンジョンモブにも {@code MOB_LEVEL} が合成付与される。つまり
     * {@code mobs: [RAVAGER]} と書いただけでは、ダンジョン内の「見た目替え RAVAGER」にも当たってしまう。
     * ワールドで切るのが、フィールド専用ドロップを構造的に保証できる唯一の手段。
     */
    public enum DropScope {
        /** ダンジョンインスタンスワールド<b>以外</b>の討伐だけに適用する。 */
        FIELD,
        /** ダンジョンインスタンスワールド<b>内</b>の討伐だけに適用する。 */
        DUNGEON,
        /** 場所を問わない(既定。従来どおりの挙動)。 */
        ANY;

        /**
         * {@code "field"} / {@code "dungeon"} / {@code "any"}(大文字小文字・前後空白は無視)を解釈する。
         * 未知の値は {@code null} を返す — 呼び出し側が警告して {@link #ANY} へ倒すため、
         * ここで例外を投げたり黙って ANY を返したりはしない(タイプミスが無言で全ワールド適用に
         * 化けるのを防ぐ)。
         */
        public static DropScope parse(String raw) {
            if (raw == null) {
                return null;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "field" -> FIELD;
                case "dungeon" -> DUNGEON;
                case "any" -> ANY;
                default -> null;
            };
        }
    }

    /**
     * {@code chance-by-level:} — モブのレベルに対する確率の線形カーブ(2026-08-14)。
     *
     * <p>これが無いと「レベルが高いほど落ちやすい」を帯(tier)の数だけエントリを複製して近似するしかなく、
     * 17素材 × 6帯 = 102 エントリに膨れるうえ帯境界で確率が段差になる。カーブなら
     * {@code min-level: 0} の帯へ1本書くだけで滑らかに上がる。
     *
     * <p>範囲外はクランプする({@code level <= fromLevel} なら {@code fromChance}、
     * {@code level >= toLevel} なら {@code toChance})。外挿はしない —— レベル0のモブに負の確率、
     * レベル200のモブに100%超、といった事故を防ぐため。
     *
     * @param fromLevel  カーブの下端レベル(0以上)
     * @param fromChance 下端での確率 [0,1]
     * @param toLevel    カーブの上端レベル({@code fromLevel} より大きいこと。等しいと傾きが定義できない)
     * @param toChance   上端での確率 [0,1]
     */
    public record ChanceCurve(int fromLevel, double fromChance, int toLevel, double toChance) {

        public ChanceCurve {
            if (fromLevel < 0) {
                throw new IllegalArgumentException("from-level must be >= 0: " + fromLevel);
            }
            if (toLevel <= fromLevel) {
                throw new IllegalArgumentException("to-level must be > from-level: from-level=" + fromLevel
                        + " to-level=" + toLevel);
            }
            if (!Double.isFinite(fromChance) || fromChance < 0.0 || fromChance > 1.0) {
                throw new IllegalArgumentException("from-chance must be in [0,1]: " + fromChance);
            }
            if (!Double.isFinite(toChance) || toChance < 0.0 || toChance > 1.0) {
                throw new IllegalArgumentException("to-chance must be in [0,1]: " + toChance);
            }
        }

        /** {@code level} における確率。両端の外側はクランプ(外挿しない)。 */
        public double chanceAt(int level) {
            if (level <= fromLevel) {
                return fromChance;
            }
            if (level >= toLevel) {
                return toChance;
            }
            double ratio = (double) (level - fromLevel) / (double) (toLevel - fromLevel);
            return fromChance + (toChance - fromChance) * ratio;
        }
    }

    public LevelTierDropEntry {
        int itemSet = (material != null ? 1 : 0) + (catalogId != null ? 1 : 0);
        if (itemSet != 1) {
            throw new IllegalArgumentException("exactly one of material/catalogId must be set");
        }
        if (catalogId != null && catalogId.isBlank()) {
            throw new IllegalArgumentException("custom catalog id must not be blank");
        }
        if (!Double.isFinite(chance) || chance < 0.0 || chance > 1.0) {
            throw new IllegalArgumentException("chance must be in [0,1]: " + chance);
        }
        if (min < 0) {
            throw new IllegalArgumentException("min must be >= 0: " + min);
        }
        if (min > max) {
            throw new IllegalArgumentException("min must be <= max: min=" + min + " max=" + max);
        }
        targets = targets == null ? MobTargetFilter.EMPTY : targets;
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        // 2026-08-14: where は null を ANY へ丸める(既存の yml/呼び出し側は where を書かない)。
        where = where == null ? DropScope.ANY : where;
    }

    public static LevelTierDropEntry ofMaterial(Material material, double chance, int min, int max,
                                                 MobTargetFilter targets) {
        return ofMaterial(material, chance, min, max, targets, Set.of());
    }

    public static LevelTierDropEntry ofCatalog(String catalogId, double chance, int min, int max,
                                                MobTargetFilter targets) {
        return ofCatalog(catalogId, chance, min, max, targets, Set.of());
    }

    public static LevelTierDropEntry ofMaterial(Material material, double chance, int min, int max,
                                                 MobTargetFilter targets, Set<String> roles) {
        return ofMaterial(material, chance, min, max, targets, roles, null, DropScope.ANY, null);
    }

    public static LevelTierDropEntry ofCatalog(String catalogId, double chance, int min, int max,
                                                MobTargetFilter targets, Set<String> roles) {
        return ofCatalog(catalogId, chance, min, max, targets, roles, null, DropScope.ANY, null);
    }

    /** 2026-08-14 フィールドドロップ配線: {@code chance-by-level} / {@code where} / {@code baby} 込み。 */
    public static LevelTierDropEntry ofMaterial(Material material, double chance, int min, int max,
                                                 MobTargetFilter targets, Set<String> roles,
                                                 ChanceCurve chanceByLevel, DropScope where, Boolean baby) {
        return new LevelTierDropEntry(Objects.requireNonNull(material, "material"), null, chance, min, max,
                targets, roles, chanceByLevel, where, baby);
    }

    /** 2026-08-14 フィールドドロップ配線: {@code chance-by-level} / {@code where} / {@code baby} 込み。 */
    public static LevelTierDropEntry ofCatalog(String catalogId, double chance, int min, int max,
                                                MobTargetFilter targets, Set<String> roles,
                                                ChanceCurve chanceByLevel, DropScope where, Boolean baby) {
        return new LevelTierDropEntry(null, Objects.requireNonNull(catalogId, "catalogId"), chance, min, max,
                targets, roles, chanceByLevel, where, baby);
    }

    /** Back-compat overload for callers/tests that only narrow by {@link EntityType}. */
    public static LevelTierDropEntry ofMaterial(Material material, double chance, int min, int max,
                                                 Set<EntityType> mobs) {
        return ofMaterial(material, chance, min, max, MobTargetFilter.of(mobs, null));
    }

    /** Back-compat overload for callers/tests that only narrow by {@link EntityType}. */
    public static LevelTierDropEntry ofCatalog(String catalogId, double chance, int min, int max,
                                                Set<EntityType> mobs) {
        return ofCatalog(catalogId, chance, min, max, MobTargetFilter.of(mobs, null));
    }

    public boolean isCustom() {
        return catalogId != null;
    }

    /** The {@link EntityType} axis of {@link #targets()} — kept as a named accessor for readability. */
    public Set<EntityType> mobs() {
        return targets.entityTypes();
    }

    /**
     * {@code true} when this entry applies to a kill of {@code type} carrying {@code profileId} — an
     * empty {@link #targets()} always matches.
     */
    public boolean appliesTo(EntityType type, String profileId) {
        return targets.matches(type, profileId);
    }

    /** Back-compat: matches on the {@link EntityType} axis only (no mob id available at the call site). */
    public boolean appliesTo(EntityType type) {
        return appliesTo(type, null);
    }

    /**
     * 討伐したモブのレベルにおけるドロップ確率(2026-08-14)。{@code chance-by-level:} が無ければ
     * {@link #chance()} をそのまま返す(後方互換)。
     */
    public double chanceAt(int level) {
        return chanceByLevel == null ? chance : chanceByLevel.chanceAt(level);
    }

    /**
     * {@code where:} の判定。{@code inDungeonWorld} は
     * {@code DungeonWorldRegistry#isDungeonWorld(worldUid)} の結果を渡す。
     */
    public boolean appliesInWorld(boolean inDungeonWorld) {
        return switch (where) {
            case FIELD -> !inDungeonWorld;
            case DUNGEON -> inDungeonWorld;
            case ANY -> true;
        };
    }

    /**
     * {@code baby:} の判定。{@code isBaby} には討伐した個体の子供判定を渡す。判定できないモブ
     * ({@link org.bukkit.entity.Ageable} を実装しない) は {@code null} を渡すこと —— その場合
     * {@code baby:} を書いたエントリは<b>一致しない</b>(＝落ちない)。「子供限定と書いたのに
     * 大人にも落ちる」より「1個も落ちない」ほうが、設定ミスとして気づけるため。
     */
    public boolean appliesToAge(Boolean isBaby) {
        if (baby == null) {
            return true;
        }
        return isBaby != null && baby.booleanValue() == isBaby.booleanValue();
    }

    /**
     * {@code true} when the killer's roles satisfy {@code roles:} — 空の {@code roles:} は常に一致する
     * (職業を問わない従来どおりの挙動)。{@code killerRoles} は戦闘職・補助職の両方を渡してよい。
     */
    public boolean allowsRoles(Set<String> killerRoles) {
        if (roles.isEmpty()) {
            return true;
        }
        if (killerRoles == null || killerRoles.isEmpty()) {
            return false;
        }
        for (String role : killerRoles) {
            if (role != null && roles.contains(role.trim().toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
