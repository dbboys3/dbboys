package com.dbboys.infra.util;

import com.dbboys.core.ChangeDatabaseFailureKind;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.model.Connect;

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
            return resolvePlatforms()
                    .requirePlatform(connect)
                    .connection()
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
            for (DatabasePlatform platform : resolvePlatforms().allPlatforms()) {
                if (platform.connection().classifyChangeDatabaseFailure(e) == expectedKind) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static DatabasePlatformResolver resolvePlatforms() {
        return DatabasePlatformResolver.getInstance();
    }
}
