package com.dbboys.util;

import com.dbboys.api.ChangeDatabaseFailureKind;
import com.dbboys.api.DatabasePlatform;
import com.dbboys.app.AppContext;
import com.dbboys.impl.DialectServices;
import com.dbboys.vo.Connect;

import java.sql.SQLException;

public final class SqlErrorUtil {

    private SqlErrorUtil() {
    }

    public static boolean isDisconnectError(Connect connect, SQLException e) {
        if (e == null) {
            return false;
        }
        if (connect == null) {
            return isDisconnectError(e);
        }
        try {
            return resolveDialectServices()
                    .requirePlatform(connect)
                    .classifyChangeDatabaseFailure(e) == ChangeDatabaseFailureKind.DISCONNECTED;
        } catch (Exception ex) {
            return isDisconnectError(e);
        }
    }

    public static boolean isDisconnectError(SQLException e) {
        if (e == null) {
            return false;
        }
        int errorCode = e.getErrorCode();
        if (errorCode == -79716 || errorCode == -79730) {
            return true;
        }
        return matchesFailureKind(e, ChangeDatabaseFailureKind.DISCONNECTED);
    }

    public static boolean requiresSessionRecovery(SQLException e) {
        return matchesFailureKind(e, ChangeDatabaseFailureKind.RETRY_WITH_NEW_CONNECTION);
    }

    private static boolean matchesFailureKind(SQLException e, ChangeDatabaseFailureKind expectedKind) {
        if (e == null || expectedKind == null) {
            return false;
        }
        try {
            for (DatabasePlatform platform : resolveDialectServices().getPlatformRegistry().getAllPlatforms()) {
                if (platform.classifyChangeDatabaseFailure(e) == expectedKind) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static DialectServices resolveDialectServices() {
        try {
            return AppContext.get(DialectServices.class);
        } catch (IllegalStateException e) {
            return DialectServices.createDefault();
        }
    }
}
