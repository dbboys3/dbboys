package com.dbboys.impl.dialect.genericjdbc;

import com.dbboys.api.ConnectionSupport;
import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DdlRepository;
import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.api.MetadataRepository;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.ui.IconPaths;
import com.dbboys.vo.Connect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class GeneralJdbcDialect implements DatabasePlatform, ConnectionSupport {
    private static final String DB_TYPE = "GENERAL JDBC";

    private final MetadataRepository metadataRepository = new GeneralJdbcMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new GeneralJdbcSqlexeRepository();
    private final DdlRepository ddlRepository = new GeneralJdbcDdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new GeneralJdbcInstanceAdminRepository();

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public IconInfo iconInfo() {
        return new IconInfo(IconPaths.CONNECTION_LINK, 0.55, 0.55);
    }

    @Override
    public ConnectionSupport connection() {
        return this;
    }

    @Override
    public ConnectionParams getConnectionParams(Connect connect) throws Exception {
        String url = connect == null ? "" : trimToEmpty(connect.getIp());
        if (url.isEmpty()) {
            throw new IllegalArgumentException("JDBC URL is empty");
        }
        String driverName = connect == null ? "" : trimToEmpty(connect.getDriver());
        if (driverName.isEmpty()) {
            throw new IllegalArgumentException("JDBC driver jar is not selected");
        }
        String jarFilePath = Path.of("extlib", DB_TYPE, driverName).toUri().toString();
        return new ConnectionParams(url, resolveDriverClassName(driverName), jarFilePath);
    }

    @Override
    public void sessionInit(java.sql.Connection conn, Connect connect) {
        // General JDBC does not require dialect-specific session initialization.
    }

    @Override
    public boolean supportsSessionInit() {
        return false;
    }

    @Override
    public String defaultConnectionProps() {
        return "[]";
    }

    @Override
    public String testConnectionSql() {
        return "SELECT 1";
    }

    @Override
    public boolean canCreateDatabase() {
        return false;
    }

    @Override
    public boolean supportsTableTypeModification() {
        return false;
    }

    @Override
    public boolean supportsSetDefaultDatabase() {
        return false;
    }

    @Override
    public boolean showMetadataDescriptions() {
        return false;
    }

    @Override
    public boolean showMetadataWarnings() {
        return false;
    }

    @Override
    public boolean showMetadataTooltips() {
        return false;
    }

    @Override
    public MetadataRepository metadata() {
        return metadataRepository;
    }

    @Override
    public SqlexeRepository sql() {
        return sqlexeRepository;
    }

    @Override
    public DdlRepository ddl() {
        return ddlRepository;
    }

    @Override
    public InstanceAdminRepository admin() {
        return instanceAdminRepository;
    }

    private String resolveDriverClassName(String driverJarName) throws IOException {
        Path jarPath = Path.of("extlib", DB_TYPE, driverJarName);
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            var serviceEntry = jarFile.getJarEntry("META-INF/services/java.sql.Driver");
            if (serviceEntry != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        jarFile.getInputStream(serviceEntry), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String className = trimToEmpty(stripComment(line));
                        if (!className.isEmpty()) {
                            return className;
                        }
                    }
                }
            }
            String fallback = findDriverClassByScanningJar(jarFile, jarPath);
            if (!fallback.isEmpty()) {
                return fallback;
            }
        }
        throw new IllegalArgumentException("No JDBC driver class found in " + driverJarName);
    }

    private String findDriverClassByScanningJar(JarFile jarFile, Path jarPath) throws IOException {
        List<String> candidates = new ArrayList<>();
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String entryName = entry.getName();
            String lowerName = entryName.toLowerCase(Locale.ROOT);
            if (!entryName.endsWith(".class") || lowerName.contains("$")) {
                continue;
            }
            if (!lowerName.endsWith("driver.class")) {
                continue;
            }
            candidates.add(entryName.substring(0, entryName.length() - 6).replace('/', '.'));
        }
        if (candidates.isEmpty()) {
            return "";
        }
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            for (String candidate : candidates) {
                try {
                    Class<?> clazz = Class.forName(candidate, false, loader);
                    if (Driver.class.isAssignableFrom(clazz)) {
                        return candidate;
                    }
                } catch (Throwable ignored) {
                    // try next candidate
                }
            }
        }
        return "";
    }

    private static String stripComment(String line) {
        if (line == null) {
            return "";
        }
        int idx = line.indexOf('#');
        return idx >= 0 ? line.substring(0, idx) : line;
    }

    private static String trimToEmpty(String text) {
        return text == null ? "" : text.trim();
    }
}
