package com.thirdstone.commerce.platform.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Time between Java and MySQL without a time zone in the way.
 *
 * <p>{@code DATETIME} columns hold UTC. Read or written as a {@code Timestamp}, the
 * driver converts through the JVM's or the session's zone, and a value shifts by
 * hours when either is not UTC. A {@code LocalDateTime} is passed through
 * untouched, so these helpers are correct whatever zone the container runs in.
 */
public final class Sql {

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /** An {@link Instant} as a statement parameter. */
    public static LocalDateTime param(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Sql() {
    }
}
