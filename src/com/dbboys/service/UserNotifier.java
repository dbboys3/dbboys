package com.dbboys.service;

/**
 * service 层向用户报告错误的通道。由 app/ui 层在启动时注册实现，
 * 使 service 无需依赖 ui 即可反馈错误。默认实现仅记录日志。
 */
@FunctionalInterface
public interface UserNotifier {

    void notifyError(String title, String message);
}
