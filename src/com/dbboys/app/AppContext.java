package com.dbboys.app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AppContext {
    private static final Map<Class<?>, Object> REGISTRY = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;

    private AppContext() {}

    public static void init() {
        if (initialized) return;
        synchronized (AppContext.class) {
            if (initialized) return;

            var platforms = com.dbboys.impl.DatabasePlatforms.createDefault();

            register(com.dbboys.api.DatabasePlatformResolver.class, platforms);
            register(com.dbboys.impl.DatabasePlatforms.class, platforms);

            var connService = new com.dbboys.impl.ConnectionServiceImpl(platforms);
            register(com.dbboys.api.ConnectionService.class, connService);

            register(com.dbboys.service.DatabaseService.class, new com.dbboys.service.DatabaseService(platforms));
            register(com.dbboys.service.TableService.class, new com.dbboys.service.TableService(platforms));
            register(com.dbboys.service.IndexService.class, new com.dbboys.service.IndexService(platforms));
            register(com.dbboys.service.ViewService.class, new com.dbboys.service.ViewService(platforms));
            register(com.dbboys.service.SequenceService.class, new com.dbboys.service.SequenceService(platforms));
            register(com.dbboys.service.SynonymService.class, new com.dbboys.service.SynonymService(platforms));
            register(com.dbboys.service.FunctionService.class, new com.dbboys.service.FunctionService(platforms));
            register(com.dbboys.service.ProcedureService.class, new com.dbboys.service.ProcedureService(platforms));
            register(com.dbboys.service.TriggerService.class, new com.dbboys.service.TriggerService(platforms));
            register(com.dbboys.service.PackageService.class, new com.dbboys.service.PackageService(platforms));
            register(com.dbboys.service.ObjectTypeService.class, new com.dbboys.service.ObjectTypeService(platforms));
            register(com.dbboys.service.QueueService.class, new com.dbboys.service.QueueService(platforms));
            register(com.dbboys.service.SchedulerJobService.class, new com.dbboys.service.SchedulerJobService(platforms));
            register(com.dbboys.service.RecycleBinService.class, new com.dbboys.service.RecycleBinService(platforms));
            register(com.dbboys.service.UserService.class, new com.dbboys.service.UserService(platforms));

            var dbService = get(com.dbboys.service.DatabaseService.class);
            register(com.dbboys.service.SqlexeService.class, new com.dbboys.service.SqlexeService(connService, dbService, platforms));
            register(com.dbboys.service.AdminService.class, new com.dbboys.service.AdminService(connService, platforms));

            initialized = true;
        }
    }

    public static <T> void register(Class<T> type, T instance) {
        REGISTRY.put(type, instance);
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Class<T> type) {
        T instance = (T) REGISTRY.get(type);
        if (instance == null) {
            throw new IllegalStateException("No instance registered for " + type.getName() + ". Call AppContext.init() first.");
        }
        return instance;
    }
}
