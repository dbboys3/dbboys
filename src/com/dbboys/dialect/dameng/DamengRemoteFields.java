package com.dbboys.dialect.dameng;

public final class DamengRemoteFields {
    // Install configuration field IDs
    public static final String PORT = "dameng_port";
    public static final String SYSDBA_PASSWORD = "dameng_sysdba_password";
    public static final String INSTALL_PATH = "dameng_install_path";
    public static final String DATA_PATH = "dameng_data_path";
    public static final String INSTANCE_NAME = "dameng_instance_name";
    public static final String CHARSET = "dameng_charset";
    public static final String PAGE_SIZE = "dameng_page_size";
    public static final String CASE_SENSITIVE = "dameng_case_sensitive";
    public static final String COMPATIBLE_MODE = "dameng_compatible_mode";
    public static final String EXTENT_SIZE = "dameng_extent_size";
    public static final String BLANK_PAD_MODE = "dameng_blank_pad_mode";
    public static final String LENGTH_IN_CHAR = "dameng_length_in_char";
    public static final String LOG_SIZE = "dameng_log_size";
    public static final String BUFFER = "dameng_buffer";

    // Default credentials
    public static final String DEFAULT_USERNAME = "SYSDBA";
    public static final String DEFAULT_PASSWORD = "SYSDBA";

    // System group/user names
    public static final String GROUP_NAME = "dinstall";
    public static final String USER_NAME = "dmdba";
    public static final String SERVICE_PREFIX = "DmService";

    private DamengRemoteFields() {
    }
}
