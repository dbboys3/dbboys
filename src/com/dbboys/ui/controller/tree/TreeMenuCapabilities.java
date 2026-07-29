package com.dbboys.ui.controller.tree;

import com.dbboys.ui.treemodel.*;

import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.core.InstanceTabCapability;
import com.dbboys.model.*;
import javafx.scene.control.TreeItem;

import java.util.List;

class TreeMenuCapabilities {

    static boolean isTableType(String tableTypeCode, String expectedType) {
        if (tableTypeCode == null || expectedType == null) {
            return false;
        }
        return expectedType.equalsIgnoreCase(tableTypeCode.trim());
    }

    static boolean supportsInstanceAdmin(Connect connect) {
        if (connect == null) {
            return false;
        }
        try {
            return resolvePlatformResolver().admin(connect).supportsAdminFeatures(connect);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean supportsLockSession(Connect connect) {
        if (connect == null) {
            return false;
        }
        try {
            return resolvePlatformResolver().admin(connect).supportsLockSession(connect);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean supportsHealthCheck(Connect connect) {
        if (connect == null) {
            return false;
        }
        try {
            return resolvePlatformResolver().admin(connect).supportsHealthCheck(connect);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean supportsOnlineLog(Connect connect) {
        if (connect == null) {
            return false;
        }
        try {
            return resolvePlatformResolver().admin(connect).supportsOnlineLog(connect);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean supportsSpaceManager(Connect connect) {
        if (connect == null) {
            return false;
        }
        try {
            return resolvePlatformResolver().admin(connect).supportsSpaceManager(connect);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean supportsConfigManagement(Connect connect) {
        if (connect == null) {
            return false;
        }
        try {
            return resolvePlatformResolver().admin(connect).supportsConfigManagement(connect);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean supportsStartStop(Connect connect) {
        if (connect == null) {
            return false;
        }
        try {
            // Check dialect capability (InstanceTabCapability) first
            DatabasePlatform platform = resolvePlatformResolver().requirePlatform(connect);
            if (platform instanceof InstanceTabCapability) {
                return ((InstanceTabCapability) platform).supportsStartStopTab(connect);
            }
            // Fallback to admin repository
            return resolvePlatformResolver().admin(connect).supportsStartStop(connect);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isGeneralJdbcMetadataSelection(List<TreeItem<TreeData>> selectedItems) {
        if (selectedItems == null || selectedItems.isEmpty()) {
            return false;
        }
        for (TreeItem<TreeData> item : selectedItems) {
            if (!isGeneralJdbcMetadataItem(item)) {
                return false;
            }
        }
        return true;
    }

    static boolean isGeneralJdbcMetadataItem(TreeItem<TreeData> item) {
        if (item == null || item.getValue() == null) {
            return false;
        }
        TreeData value = item.getValue();
        if (value instanceof ConnectFolder || value instanceof Connect) {
            return false;
        }
        try {
            Connect connect = TreeNavigator.getMetaConnect(item);
            if (connect == null || connect.getDbtype() == null) return false;
            DatabasePlatform platform = resolvePlatformResolver().getPlatform(connect.getDbtype());
            return platform != null && platform.connection().connectionAddressType() == com.dbboys.core.ConnectionAddressType.JDBC_URL;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean canShowGeneralJdbcCopyItem(List<TreeItem<TreeData>> selectedItems) {
        if (selectedItems == null || selectedItems.isEmpty()) {
            return false;
        }
        if (selectedItems.size() == 1) {
            return TreeNavigator.canCopyItem(selectedItems.get(0));
        }
        TreeItem<TreeData> firstSelected = selectedItems.get(0);
        TreeItem<TreeData> anchorParent = firstSelected == null ? null : firstSelected.getParent();
        Class<?> anchorType = firstSelected == null || firstSelected.getValue() == null
                ? null
                : firstSelected.getValue().getClass();
        if (anchorType == Database.class || anchorType == ObjectFolder.class) {
            return false;
        }
        for (TreeItem<TreeData> item : selectedItems) {
            if (item == null
                    || item.getValue() == null
                    || !TreeNavigator.isDatabaseMenuObject(item.getValue())
                    || item.getParent() != anchorParent
                    || anchorType == null
                    || item.getValue().getClass() != anchorType) {
                return false;
            }
        }
        return true;
    }

    static DatabasePlatformResolver resolvePlatformResolver() {
        return DatabasePlatformResolver.getInstance();
    }
}
