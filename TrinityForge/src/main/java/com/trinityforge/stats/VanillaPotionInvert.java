package com.trinityforge.stats;

import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * バニラの発酵したクモの目による効果反転(1.21.11 の {@code PotionBrewing#addVanillaMixes} 相当)。
 *
 * <p>TF は品質を乗せるときベースを {@code WATER} へ倒す。倒した後のビンにクモの目を入れると
 * バニラは {@code WATER + FERMENTED_SPIDER_EYE → WEAKNESS} しか見ず、元の俊敏→鈍化などが消える。
 * さらに W-116 のカスタム効果ガードが醸造自体を止める。この表で「倒す前の {@link PotionType}」から
 * 反転先を引き、品質で伸びた持続/効力の差分だけを載せ替える。
 *
 * <p>解放式カスタムポーション({@code brew-unlocks})は倒す前の種類を持たないので、この表には載らない
 * ── 反転しない(W-116 のまま止める)。
 */
public final class VanillaPotionInvert {

    /**
     * バニラ既定の効果(持続 tick / 効力)。差分計算の引き数。即時効果は duration=1。
     */
    public record VanillaSpec(PotionEffectType type, int durationTicks, int amplifier, boolean instant) {
        public PotionEffect toEffect(int durationDelta, int amplifierDelta) {
            int duration = instant ? durationTicks : Math.max(1, durationTicks + durationDelta);
            int amp = Math.max(0, amplifier + amplifierDelta);
            return new PotionEffect(type, duration, amp);
        }
    }

    private static final Map<PotionType, PotionType> INVERT = Map.ofEntries(
            Map.entry(PotionType.NIGHT_VISION, PotionType.INVISIBILITY),
            Map.entry(PotionType.LONG_NIGHT_VISION, PotionType.LONG_INVISIBILITY),
            Map.entry(PotionType.SWIFTNESS, PotionType.SLOWNESS),
            Map.entry(PotionType.LONG_SWIFTNESS, PotionType.LONG_SLOWNESS),
            Map.entry(PotionType.STRONG_SWIFTNESS, PotionType.STRONG_SLOWNESS),
            Map.entry(PotionType.LEAPING, PotionType.SLOWNESS),
            Map.entry(PotionType.LONG_LEAPING, PotionType.LONG_SLOWNESS),
            Map.entry(PotionType.STRONG_LEAPING, PotionType.STRONG_SLOWNESS),
            Map.entry(PotionType.HEALING, PotionType.HARMING),
            Map.entry(PotionType.STRONG_HEALING, PotionType.STRONG_HARMING),
            Map.entry(PotionType.POISON, PotionType.HARMING),
            Map.entry(PotionType.LONG_POISON, PotionType.HARMING),
            Map.entry(PotionType.STRONG_POISON, PotionType.STRONG_HARMING),
            Map.entry(PotionType.REGENERATION, PotionType.WEAKNESS),
            Map.entry(PotionType.LONG_REGENERATION, PotionType.LONG_WEAKNESS),
            Map.entry(PotionType.STRENGTH, PotionType.WEAKNESS),
            Map.entry(PotionType.LONG_STRENGTH, PotionType.LONG_WEAKNESS));

    private static final Map<PotionType, PotionType> LONG_VARIANT = Map.ofEntries(
            Map.entry(PotionType.NIGHT_VISION, PotionType.LONG_NIGHT_VISION),
            Map.entry(PotionType.SWIFTNESS, PotionType.LONG_SWIFTNESS),
            Map.entry(PotionType.LEAPING, PotionType.LONG_LEAPING),
            Map.entry(PotionType.POISON, PotionType.LONG_POISON),
            Map.entry(PotionType.REGENERATION, PotionType.LONG_REGENERATION),
            Map.entry(PotionType.STRENGTH, PotionType.LONG_STRENGTH));

    private static final Map<PotionType, PotionType> STRONG_VARIANT = Map.ofEntries(
            Map.entry(PotionType.SWIFTNESS, PotionType.STRONG_SWIFTNESS),
            Map.entry(PotionType.LEAPING, PotionType.STRONG_LEAPING),
            Map.entry(PotionType.HEALING, PotionType.STRONG_HEALING),
            Map.entry(PotionType.POISON, PotionType.STRONG_POISON));

    private static final Map<PotionType, VanillaSpec> SPECS = Map.ofEntries(
            spec(PotionType.NIGHT_VISION, PotionEffectType.NIGHT_VISION, 3600, 0, false),
            spec(PotionType.LONG_NIGHT_VISION, PotionEffectType.NIGHT_VISION, 9600, 0, false),
            spec(PotionType.INVISIBILITY, PotionEffectType.INVISIBILITY, 3600, 0, false),
            spec(PotionType.LONG_INVISIBILITY, PotionEffectType.INVISIBILITY, 9600, 0, false),
            spec(PotionType.SWIFTNESS, PotionEffectType.SPEED, 3600, 0, false),
            spec(PotionType.LONG_SWIFTNESS, PotionEffectType.SPEED, 9600, 0, false),
            spec(PotionType.STRONG_SWIFTNESS, PotionEffectType.SPEED, 1800, 1, false),
            spec(PotionType.SLOWNESS, PotionEffectType.SLOWNESS, 1800, 0, false),
            spec(PotionType.LONG_SLOWNESS, PotionEffectType.SLOWNESS, 4800, 0, false),
            spec(PotionType.STRONG_SLOWNESS, PotionEffectType.SLOWNESS, 400, 3, false),
            spec(PotionType.LEAPING, PotionEffectType.JUMP_BOOST, 3600, 0, false),
            spec(PotionType.LONG_LEAPING, PotionEffectType.JUMP_BOOST, 9600, 0, false),
            spec(PotionType.STRONG_LEAPING, PotionEffectType.JUMP_BOOST, 1800, 1, false),
            spec(PotionType.HEALING, PotionEffectType.INSTANT_HEALTH, 1, 0, true),
            spec(PotionType.STRONG_HEALING, PotionEffectType.INSTANT_HEALTH, 1, 1, true),
            spec(PotionType.HARMING, PotionEffectType.INSTANT_DAMAGE, 1, 0, true),
            spec(PotionType.STRONG_HARMING, PotionEffectType.INSTANT_DAMAGE, 1, 1, true),
            spec(PotionType.POISON, PotionEffectType.POISON, 900, 0, false),
            spec(PotionType.LONG_POISON, PotionEffectType.POISON, 1800, 0, false),
            spec(PotionType.STRONG_POISON, PotionEffectType.POISON, 432, 1, false),
            spec(PotionType.REGENERATION, PotionEffectType.REGENERATION, 900, 0, false),
            spec(PotionType.LONG_REGENERATION, PotionEffectType.REGENERATION, 1800, 0, false),
            spec(PotionType.STRENGTH, PotionEffectType.STRENGTH, 3600, 0, false),
            spec(PotionType.LONG_STRENGTH, PotionEffectType.STRENGTH, 9600, 0, false),
            spec(PotionType.WEAKNESS, PotionEffectType.WEAKNESS, 1800, 0, false),
            spec(PotionType.LONG_WEAKNESS, PotionEffectType.WEAKNESS, 4800, 0, false));

    private VanillaPotionInvert() {
    }

    /**
     * 延長/強化済みなら、倒す前の種類を長い/強い側へ寄せてから反転表を引く。
     * バニラに反転 mix が無い種類は {@code null}(カスタム扱いで止める)。
     */
    public static PotionType invert(PotionType source, String brewUpgrade) {
        return invert(keyedSource(source, brewUpgrade));
    }

    public static PotionType invert(PotionType keyedSource) {
        if (keyedSource == null) {
            return null;
        }
        return INVERT.get(keyedSource);
    }

    public static PotionType keyedSource(PotionType source, String brewUpgrade) {
        if (source == null) {
            return null;
        }
        String upgrade = brewUpgrade == null ? "" : brewUpgrade.trim().toUpperCase(Locale.ROOT);
        if ("AMPLIFIED".equals(upgrade)) {
            return STRONG_VARIANT.getOrDefault(source, source);
        }
        if ("EXTENDED".equals(upgrade)) {
            return LONG_VARIANT.getOrDefault(source, source);
        }
        return source;
    }

    /**
     * 現在のカスタム効果から品質差分を拾い、反転先のバニラ効果へ載せる。
     */
    public static List<PotionEffect> invertedEffects(PotionType keyedSource, PotionType inverted,
                                                     List<PotionEffect> current) {
        VanillaSpec dest = SPECS.get(inverted);
        if (dest == null) {
            return List.of();
        }
        VanillaSpec src = SPECS.get(keyedSource);
        int durationDelta = 0;
        int amplifierDelta = 0;
        if (src != null && current != null && !current.isEmpty()) {
            PotionEffect live = current.get(0);
            if (live != null) {
                if (!src.instant()) {
                    durationDelta = live.getDuration() - src.durationTicks();
                }
                amplifierDelta = live.getAmplifier() - src.amplifier();
            }
        }
        return List.of(dest.toEffect(durationDelta, amplifierDelta));
    }

    private static Map.Entry<PotionType, VanillaSpec> spec(PotionType type, PotionEffectType effect,
                                                           int durationTicks, int amplifier, boolean instant) {
        return Map.entry(type, new VanillaSpec(effect, durationTicks, amplifier, instant));
    }
}
