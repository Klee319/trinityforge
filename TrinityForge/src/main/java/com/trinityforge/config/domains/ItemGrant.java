package com.trinityforge.config.domains;

import java.util.Objects;

/**
 * アチーブメント/コレクション報酬で付与するアイテム1件({@code rewards.items[]})。
 *
 * @param id     {@link com.trinityforge.stats.CrossPluginItemResolver} が解決できるID
 *               (カタログID / ArsPaper登録ID / バニラMaterial名)
 * @param amount 付与個数。1未満は常に1へクランプする(省略時デフォルトの表現も兼ねる)。
 */
public record ItemGrant(String id, int amount) {
    public ItemGrant {
        Objects.requireNonNull(id, "id");
        if (amount < 1) {
            amount = 1;
        }
    }
}
