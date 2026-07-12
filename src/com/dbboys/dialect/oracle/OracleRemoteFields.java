package com.dbboys.dialect.oracle;

public final class OracleRemoteFields {
    // Installation paths
    public static final String ORACLE_ROOT_PASSWORD = "oracle_root_password";
    public static final String ORACLE_ORACLE_BASE = "oracle_oracle_base";
    public static final String ORACLE_ORACLE_HOME = "oracle_oracle_home";
    public static final String ORACLE_DATA_DIR = "oracle_data_dir";

    // Instance identifiers
    public static final String ORACLE_SID = "oracle_sid";

    // Passwords
    public static final String ORACLE_SYS_PASSWORD = "oracle_sys_password";
    public static final String ORACLE_SYSTEM_PASSWORD = "oracle_system_password";

    // Character sets
    public static final String ORACLE_CHARACTER_SET = "oracle_character_set";
    public static final String ORACLE_NATIONAL_CHARACTER_SET = "oracle_national_character_set";

    // Listener and memory
    public static final String ORACLE_LISTENER_PORT = "oracle_listener_port";
    public static final String ORACLE_MEMORY_MB = "oracle_memory_mb";

    // Tablespace and recovery
    public static final String ORACLE_DEFAULT_TABLESPACE_DATAFILE = "oracle_default_tablespace_datafile";
    public static final String ORACLE_RECOVERY_AREA = "oracle_recovery_area";

    // OS user for login
    public static final String LOGIN_USERNAME = "oracle";

    private OracleRemoteFields() {
    }
}
