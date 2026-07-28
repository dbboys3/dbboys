package com.dbboys.core;

/**
 * 平台解析器的进程级持有器。由组合根（app 层）在启动时注入，
 * 使 core 无需反向依赖 app 即可提供统一访问入口。
 */
public final class PlatformResolvers {

    private static volatile DatabasePlatformResolver instance;

    private PlatformResolvers() {
    }

    public static void init(DatabasePlatformResolver resolver) {
        if (resolver == null) {
            throw new IllegalArgumentException("resolver must not be null");
        }
        instance = resolver;
    }

    public static DatabasePlatformResolver get() {
        DatabasePlatformResolver resolver = instance;
        if (resolver == null) {
            throw new IllegalStateException(
                    "DatabasePlatformResolver not initialized. AppContext.init() must run first.");
        }
        return resolver;
    }
}
