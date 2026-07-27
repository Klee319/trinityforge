package com.trinityforge.progression.repository;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Distinguishes missing data from storage failure. Callers must not treat {@link #failed()} as
 * an empty/new player.
 */
public final class LoadResult<T> {

    public enum Kind { FOUND, MISSING, FAILED }

    private final Kind kind;
    private final T value;
    private final Throwable error;

    private LoadResult(Kind kind, T value, Throwable error) {
        this.kind = kind;
        this.value = value;
        this.error = error;
    }

    public static <T> LoadResult<T> found(T value) {
        return new LoadResult<>(Kind.FOUND, Objects.requireNonNull(value, "value"), null);
    }

    public static <T> LoadResult<T> missing() {
        return new LoadResult<>(Kind.MISSING, null, null);
    }

    public static <T> LoadResult<T> failed(Throwable error) {
        return new LoadResult<>(Kind.FAILED, null, Objects.requireNonNull(error, "error"));
    }

    public Kind kind() {
        return kind;
    }

    public boolean isFound() {
        return kind == Kind.FOUND;
    }

    public boolean isMissing() {
        return kind == Kind.MISSING;
    }

    public boolean isFailed() {
        return kind == Kind.FAILED;
    }

    public T orElseThrow() {
        if (kind == Kind.FOUND) return value;
        if (kind == Kind.MISSING) {
            throw new IllegalStateException("expected progression data was missing");
        }
        throw new IllegalStateException("progression storage failure", error);
    }

    public T orElseGet(Supplier<T> missingSupplier) {
        Objects.requireNonNull(missingSupplier, "missingSupplier");
        if (kind == Kind.FOUND) return value;
        if (kind == Kind.MISSING) return missingSupplier.get();
        throw new IllegalStateException("progression storage failure", error);
    }

    public Optional<T> toOptional() {
        if (kind == Kind.FAILED) {
            throw new IllegalStateException("progression storage failure", error);
        }
        return kind == Kind.FOUND ? Optional.of(value) : Optional.empty();
    }

    public Throwable error() {
        return error;
    }
}
