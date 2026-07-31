package com.trinityforge.pdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 累計カウンタキーの<b>文字列そのもの</b>を固定する。
 *
 * <p>ArsPaper フォークの {@code TrinityForgeBridge#recordSourceSpent} は、TF API の
 * compileOnly jar を差し替えずにビルドできるようにするため
 * {@code new NamespacedKey("trinityforge", "counter_source_spent")} を<b>文字列で組んでいる</b>。
 * つまりこちらの命名を変えると、フォークは黙って別のキーへ書き続け、
 * 「儀式でソースを使っているのにアチーブメントの進捗が一切増えない」という
 * 原因の見えない不具合になる。ここで綴りを固定してその事故を落とす。
 */
class LifetimeCounterKeyTest {

    @Test
    @DisplayName("source_spent のキー文字列は trinityforge:counter_source_spent 固定")
    void sourceSpentKeyIsPinned() {
        assertEquals("trinityforge:counter_source_spent",
                PdcKeys.lifetimeCounterKey("source_spent").toString());
    }

    @Test
    @DisplayName("カウンタIDは小文字へ正規化される（yml の大小揺れでキーが割れない）")
    void counterIdIsLowerCased() {
        assertEquals(PdcKeys.lifetimeCounterKey("source_spent"),
                PdcKeys.lifetimeCounterKey("  SOURCE_SPENT  "));
    }
}
