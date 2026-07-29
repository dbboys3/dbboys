package com.dbboys.dialect.postgresql;

import com.dbboys.dialect.common.PostgreSqlFamilyDdlRepository;

/**
 * PostgreSQL DDL export repository. All shared logic lives in {@link PostgreSqlFamilyDdlRepository};
 * only PostgreSQL-specific hook values remain here.
 */
public final class PostgresqlDdlRepository extends PostgreSqlFamilyDdlRepository {

    @Override
    protected String defaultSchemaName() {
        return "public";
    }
}
