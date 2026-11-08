package com.dbboys.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 可选的错误定位能力。方言通过驱动异常/连接自省与错误消息文本，
 * 指出执行错误落在所发送语句的哪个位置；UI 层只负责映射到编辑器坐标。
 */
public interface SqlErrorPositionCapability {

    /**
     * @param conn         the connection the statement ran on (may be SSH-wrapped)
     * @param error        the exception thrown by the driver, if any
     * @param sentSql      the exact statement text sent to the driver
     * @param errorMessage the formatted error message shown to the user
     * @return the error position, or {@code null} when it cannot be located
     */
    SqlErrorPosition locateErrorPosition(Connection conn, SQLException error, String sentSql, String errorMessage);
}
