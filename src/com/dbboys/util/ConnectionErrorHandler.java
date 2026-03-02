package com.dbboys.util;

import java.sql.SQLException;
import java.util.Set;

public final class ConnectionErrorHandler {
    private static final Set<Integer> DISCONNECT_CODES = Set.of(-79716, -79730);

    private ConnectionErrorHandler() {}

    public static boolean isDisconnectError(SQLException e) {
        return e != null && DISCONNECT_CODES.contains(e.getErrorCode());
    }
}
