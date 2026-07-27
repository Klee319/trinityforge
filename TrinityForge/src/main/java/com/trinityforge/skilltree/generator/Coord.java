package com.trinityforge.skilltree.generator;

/**
 * An immutable integer grid coordinate in the ValhallaMMO skill-tree canvas. Serialized as the
 * {@code "x,y"} string ValhallaMMO's {@code coords} / {@code position} fields use.
 */
public record Coord(int x, int y) {

    /** The {@code "x,y"} form used by ValhallaMMO {@code coords} and {@code connection_line} positions. */
    public String format() {
        return x + "," + y;
    }
}
