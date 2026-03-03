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

            var metaRepo = new com.dbboys.impl.MetadataRepositoryImpl();
            var adminRepo = new com.dbboys.db.AdminRepository();
            var sqlexeRepo = new com.dbboys.impl.SqlexeRepositoryImpl();

            register(com.dbboys.api.MetadataRepository.class, metaRepo);
            register(com.dbboys.db.AdminRepository.class, adminRepo);
            register(com.dbboys.api.SqlexeRepository.class, sqlexeRepo);

            var connService = new com.dbboys.impl.ConnectionServiceImpl(metaRepo);
            register(com.dbboys.api.ConnectionService.class, connService);

            register(com.dbboys.service.DatabaseService.class, new com.dbboys.service.DatabaseService(metaRepo));
            register(com.dbboys.service.TableService.class, new com.dbboys.service.TableService(metaRepo));
            register(com.dbboys.service.IndexService.class, new com.dbboys.service.IndexService(metaRepo));
            register(com.dbboys.service.ViewService.class, new com.dbboys.service.ViewService(metaRepo));
            register(com.dbboys.service.SequenceService.class, new com.dbboys.service.SequenceService(metaRepo));
            register(com.dbboys.service.SynonymService.class, new com.dbboys.service.SynonymService(metaRepo));
            register(com.dbboys.service.FunctionService.class, new com.dbboys.service.FunctionService(metaRepo));
            register(com.dbboys.service.ProcedureService.class, new com.dbboys.service.ProcedureService(metaRepo));
            register(com.dbboys.service.TriggerService.class, new com.dbboys.service.TriggerService(metaRepo));
            register(com.dbboys.service.PackageService.class, new com.dbboys.service.PackageService(metaRepo));
            register(com.dbboys.service.UserService.class, new com.dbboys.service.UserService(metaRepo));

            var dbService = get(com.dbboys.service.DatabaseService.class);
            register(com.dbboys.service.SqlexeService.class, new com.dbboys.service.SqlexeService(connService, dbService, sqlexeRepo));
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
