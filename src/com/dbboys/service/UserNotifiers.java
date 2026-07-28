package com.dbboys.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * {@link UserNotifier} 的进程级持有器。app/ui 层启动时注册弹窗实现；
 * 未注册前退化为日志输出，保证 service 层任何时刻调用都安全。
 */
public final class UserNotifiers {

    private static final Logger log = LogManager.getLogger(UserNotifiers.class);

    private static volatile UserNotifier instance =
            (title, message) -> log.warn("{}: {}", title, message);

    private UserNotifiers() {
    }

    public static void register(UserNotifier notifier) {
        if (notifier != null) {
            instance = notifier;
        }
    }

    public static void error(String title, String message) {
        instance.notifyError(title, message);
    }
}
