package com.trinityforge.gacha;

import java.util.Objects;

/**
 * A ticket item definition ({@code gacha.yml tickets.<catalogId>}). {@code catalogId} is the
 * {@code items/catalog.yml} id the ticket item is matched against (via its {@code ITEM_CATALOG_ID}
 * PDC tag, not its display name), and {@code poolId} names the {@link GachaPool} it draws from.
 *
 * @param catalogId itemCatalog id of the physical ticket item
 * @param poolId    id of the {@link GachaPool} this ticket draws from
 */
public record GachaTicket(String catalogId, String poolId) {

    public GachaTicket {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(poolId, "poolId");
        if (catalogId.isBlank()) {
            throw new IllegalArgumentException("catalogId must not be blank");
        }
        if (poolId.isBlank()) {
            throw new IllegalArgumentException("poolId must not be blank");
        }
    }
}
