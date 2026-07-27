package com.trinityforge.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Accumulates non-fatal validation issues found while resolving a config domain. */
public final class ValidationResult {

    private final List<String> errors = new ArrayList<>();

    public void addError(String message) {
        errors.add(message);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> errors() {
        return Collections.unmodifiableList(errors);
    }
}
