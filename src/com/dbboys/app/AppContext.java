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

            var dialectServices = com.dbboys.impl.DialectServices.createDefault();
            var adminRepo = new com.dbboys.db.AdminRepository();

            register(com.dbboys.impl.DialectServices.class, dialectServices);
            register(com.dbboys.api.MetadataRepositoryProvider.class, dialectServices);
            register(com.dbboys.api.SqlexeRepositoryProvider.class, dialectServices);
            register(com.dbboys.db.AdminRepository.class, adminRepo);

            var connService = new com.dbboys.impl.ConnectionServiceImpl(dialectServices);
            register(com.dbboys.api.ConnectionService.class, connService);

            register(com.dbboys.service.DatabaseService.class, new com.dbboys.service.DatabaseService(dialectServices));
            register(com.dbboys.service.TableService.class, new com.dbboys.service.TableService(dialectServices));
            register(com.dbboys.service.IndexService.class, new com.dbboys.service.IndexService(dialectServices));
            register(com.dbboys.service.ViewService.class, new com.dbboys.service.ViewService(dialectServices));
            register(com.dbboys.service.SequenceService.class, new com.dbboys.service.SequenceService(dialectServices));
            register(com.dbboys.service.SynonymService.class, new com.dbboys.service.SynonymService(dialectServices));
            register(com.dbboys.service.FunctionService.class, new com.dbboys.service.FunctionService(dialectServices));
            register(com.dbboys.service.ProcedureService.class, new com.dbboys.service.ProcedureService(dialectServices));
            register(com.dbboys.service.TriggerService.class, new com.dbboys.service.TriggerService(dialectServices));
            register(com.dbboys.service.PackageService.class, new com.dbboys.service.PackageService(dialectServices));
            register(com.dbboys.service.UserService.class, new com.dbboys.service.UserService(dialectServices));

            var dbService = get(com.dbboys.service.DatabaseService.class);
            register(com.dbboys.service.SqlexeService.class, new com.dbboys.service.SqlexeService(connService, dbService, dialectServices));
            register(com.dbboys.service.AdminService.class, new com.dbboys.service.AdminService(connService, adminRepo));

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
