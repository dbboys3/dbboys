package com.dbboys.dialect.postgresql;

import com.dbboys.dialect.common.PostgreSqlFamilyInstanceAdminRepository;

/**
 * PostgreSQL instance-admin repository. All shared logic lives in {@link PostgreSqlFamilyInstanceAdminRepository};
 * only PostgreSQL-specific hook values remain here.
 */
public final class PostgresqlInstanceAdminRepository extends PostgreSqlFamilyInstanceAdminRepository {

    @Override
    protected String systemOwnerName() {
        return "postgres";
    }
}
