package com.trinityforge.ops;

import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * {@link SqliteProgressionRepository} が握り潰した SQL 例外を捕まえるためのログ傍受器。
 *
 * <p><b>なぜ必要か</b>: {@code unlockPerk} / {@code prestige} は {@link java.sql.SQLException} を
 * catch して {@code SEVERE} ログを吐いたうえで {@code false} を返す。つまり
 * <b>{@code database is locked}（SQLITE_BUSY）は例外ではなく「解放に失敗した」という戻り値に化ける</b>。
 * 戻り値だけを見ていると「ポイント不足で拒否された」正常系と区別できないため、ログ側から拾う。
 */
final class SqliteErrorProbe implements AutoCloseable {

    private final Logger logger = Logger.getLogger(SqliteProgressionRepository.class.getName());
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private final Handler handler;

    SqliteErrorProbe() {
        this.handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    Throwable thrown = record.getThrown();
                    failures.add(record.getMessage() + (thrown == null ? "" : " :: " + thrown));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
    }

    /** 記録された SEVERE の内容（空ならリポジトリは 1 件も SQL 例外を握り潰していない）。 */
    List<String> failures() {
        return List.copyOf(failures);
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
    }
}
