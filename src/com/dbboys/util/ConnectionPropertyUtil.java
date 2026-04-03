package com.dbboys.util;

import com.dbboys.api.ConnectionService;
import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.vo.Connect;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ConnectionPropertyUtil {

    private ConnectionPropertyUtil() {
    }

    public static void applySupportedConnectionProperty(ConnectionService connectionService,
                                                        DatabasePlatformResolver platformResolver,
                                                        Connect connect,
                                                        String propName,
                                                        String propValue) {
        if (connectionService == null || platformResolver == null || connect == null) {
            return;
        }
        if (!supportsConnectionProperty(platformResolver, connect, propName)) {
            return;
        }
        connect.setProps(connectionService.modifyProps(connect, propName, propValue));
    }

    public static boolean supportsConnectionProperty(DatabasePlatformResolver platformResolver,
                                                     Connect connect,
                                                     String propName) {
        if (platformResolver == null || connect == null || propName == null || propName.isBlank()) {
            return false;
        }
        if (containsConnectionProperty(connect.getProps(), propName)) {
            return true;
        }
        DatabasePlatform platform = platformResolver.getPlatform(connect.getDbtype());
        return platform != null && containsConnectionProperty(platform.defaultConnectionProps(), propName);
    }

    private static boolean containsConnectionProperty(String propsJson, String propName) {
        if (propsJson == null || propsJson.isBlank()) {
            return false;
        }
        try {
            JSONArray jsonArray = new JSONArray(propsJson);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                if (propName.equalsIgnoreCase(jsonObject.optString("propName"))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
