package com.trinityforge.mail;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * 運営から個人へ宛てた1通のメール（2026-08-19 / W-155）。
 *
 * <p><b>宛先は必ず1人</b>。「全鯖民へ一斉送信」もこの型を人数ぶん作って配る
 * （ユーザー確定: 一斉送信は<b>送信時点で存在するプレイヤー</b>限定）。共有の1通を全員で
 * 参照する形にすると「誰が受け取ったか」を別表で持つ必要があり、受け取りの原子性
 * （{@link MailStore#claim}）を2箇所で守らなければならなくなる。
 *
 * @param id                 DBの主キー。未保存なら {@code 0}
 * @param recipientId        受信者
 * @param senderName         送信者の表示名（コンソール送信なら "サーバー" など）
 * @param subject            件名（MiniMessage 不可。GUIのアイテム名になる）
 * @param body               本文（MiniMessage 可）
 * @param attachments        添付アイテム。空可
 * @param createdAtMillis    送信時刻
 * @param expiresAtMillis    失効時刻。{@code 0} なら無期限
 * @param claimedAtMillis    受け取り時刻。{@code 0} なら未受取
 */
public record MailMessage(long id, UUID recipientId, String senderName, String subject, String body,
                          List<ItemStack> attachments, long createdAtMillis, long expiresAtMillis,
                          long claimedAtMillis) {

    public MailMessage {
        senderName = senderName == null ? "" : senderName;
        subject = subject == null ? "" : subject;
        body = body == null ? "" : body;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** 新規送信用（id と受け取り時刻は未確定）。 */
    public static MailMessage outgoing(UUID recipientId, String senderName, String subject, String body,
                                       List<ItemStack> attachments, long nowMillis, long expiresAtMillis) {
        return new MailMessage(0L, recipientId, senderName, subject, body, attachments,
                nowMillis, expiresAtMillis, 0L);
    }

    public boolean claimed() {
        return claimedAtMillis > 0L;
    }

    /** {@code nowMillis} 時点で失効しているか（無期限なら常に false）。 */
    public boolean expired(long nowMillis) {
        return expiresAtMillis > 0L && nowMillis >= expiresAtMillis;
    }

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }
}
