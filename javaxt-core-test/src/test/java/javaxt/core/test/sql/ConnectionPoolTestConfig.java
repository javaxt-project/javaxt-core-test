package javaxt.core.test.sql;

import com.mysql.cj.jdbc.MysqlConnectionPoolDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import javax.sql.ConnectionPoolDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.postgresql.ds.PGConnectionPoolDataSource;

/**
 * Shared configuration for database tests supporting multiple database types.
 * Provides centralized database connection settings and DataSource creation.
 *
 * <p><strong>Built-in Database:</strong></p>
 * <ul>
 *   <li><strong>H2</strong> - In-memory database, always available (no configuration needed)</li>
 * </ul>
 *
 * <p><strong>Optional Databases:</strong></p>
 * <p>Additional databases can be configured via properties files:</p>
 * <ul>
 *   <li>postgresql.properties - PostgreSQL configuration (optional)</li>
 *   <li>mysql.properties - MySQL configuration (optional)</li>
 * </ul>
 *
 * <p>Tests run against H2 by default. If additional databases are configured,
 * tests will run against all of them (H2 + PostgreSQL + MySQL).</p>
 *
 * <p>To add optional databases:</p>
 * <ol>
 *   <li>Copy postgresql.properties.template to postgresql.properties (and/or mysql.properties.template to mysql.properties)</li>
 *   <li>Update the properties file(s) with your actual database credentials</li>
 *   <li>The properties files are ignored by git for security</li>
 * </ol>
 */
public class ConnectionPoolTestConfig {

    /**
     * Supported database types for testing.
     */
    public enum DatabaseType {
        POSTGRESQL("postgresql.properties", "PostgreSQL"),
        MYSQL("mysql.properties", "MySQL"),
        H2("h2.properties", "H2"),
        H2_FILE("h2-file.properties", "H2-File");

        private final String configFile;
        private final String displayName;

        DatabaseType(String configFile, String displayName) {
            this.configFile = configFile;
            this.displayName = displayName;
        }

        public String getConfigFile() {
            return configFile;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Configuration for a specific database instance.
     */
    public static class DatabaseConfig {
        private final DatabaseType type;
        private final Properties properties;
        private final String error;
        private final boolean valid;

        private DatabaseConfig(DatabaseType type, Properties properties, String error, boolean valid) {
            this.type = type;
            this.properties = properties;
            this.error = error;
            this.valid = valid;
        }

        public DatabaseType getType() {
            return type;
        }

        public boolean isValid() {
            return valid;
        }

        public String getError() {
            return error;
        }

        public String getHost() {
            return properties.getProperty("db.host");
        }

        public int getPort() {
            return Integer.parseInt(properties.getProperty("db.port"));
        }

        public String getDatabaseName() {
            return properties.getProperty("db.name");
        }

        public String getUser() {
            return properties.getProperty("db.user");
        }

        public String getPassword() {
            return properties.getProperty("db.password");
        }

        public String getProperty(String key, String defaultValue) {
            return properties.getProperty(key, defaultValue);
        }

        public int getIntProperty(String key, int defaultValue) {
            String value = properties.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        /**
         * Creates a ConnectionPoolDataSource for this database configuration.
         */
        public ConnectionPoolDataSource getConnectionPoolDataSource() {
            if (!valid) {
                throw new IllegalStateException("Database configuration is not valid: " + error);
            }

            switch (type) {
                case POSTGRESQL:
                    PGConnectionPoolDataSource pgDataSource = new PGConnectionPoolDataSource();
                    pgDataSource.setServerName(getHost());
                    pgDataSource.setPortNumber(getPort());
                    pgDataSource.setDatabaseName(getDatabaseName());
                    pgDataSource.setUser(getUser());
                    pgDataSource.setPassword(getPassword());
                    return pgDataSource;

                case MYSQL:
                    MysqlConnectionPoolDataSource mysqlDataSource = new MysqlConnectionPoolDataSource();
                    mysqlDataSource.setServerName(getHost());
                    mysqlDataSource.setPortNumber(getPort());
                    mysqlDataSource.setDatabaseName(getDatabaseName());
                    mysqlDataSource.setUser(getUser());
                    mysqlDataSource.setPassword(getPassword());
                    // Performance optimizations for MySQL
                    try {
                        mysqlDataSource.setUseSSL(false);
                        mysqlDataSource.setAllowPublicKeyRetrieval(true);
                        mysqlDataSource.setUseLocalSessionState(true);
                        mysqlDataSource.setCacheServerConfiguration(true);
                    } catch (java.sql.SQLException e) {
                        // These are configuration methods that shouldn't throw in practice
                        throw new RuntimeException("Failed to configure MySQL DataSource: " + e.getMessage(), e);
                    }
                    return mysqlDataSource;

                case H2:
                case H2_FILE:
                    // H2's JdbcDataSource handles both mem: and file: URLs.
                    String h2Url = "jdbc:h2:" + getDatabaseName();
                    JdbcDataSource h2DataSource = new JdbcDataSource();
                    h2DataSource.setURL(h2Url);
                    h2DataSource.setUser(getUser());
                    h2DataSource.setPassword(getPassword());
                    return h2DataSource;

                default:
                    throw new IllegalStateException("Unsupported database type: " + type);
            }
        }

        /**
         * Gets the JDBC URL for this database.
         */
        public String getUrl() {
            if (!valid) {
                throw new IllegalStateException("Database configuration is not valid: " + error);
            }

            switch (type) {
                case POSTGRESQL:
                    return "jdbc:postgresql://" + getHost() + ":" + getPort() + "/" + getDatabaseName();
                case MYSQL:
                    return "jdbc:mysql://" + getHost() + ":" + getPort() + "/" + getDatabaseName();
                case H2:
                case H2_FILE:
                    return "jdbc:h2:" + getDatabaseName();
                default:
                    throw new IllegalStateException("Unsupported database type: " + type);
            }
        }
    }

    // Static configuration storage
    private static final Map<DatabaseType, DatabaseConfig> configurations = new HashMap<>();
    private static String globalError = null;

    // Load all database configurations on class initialization
    static {
        for (DatabaseType dbType : DatabaseType.values()) {
            configurations.put(dbType, loadConfiguration(dbType));
        }

        // Check if at least one database is configured
        boolean anyValid = false;
        for (DatabaseConfig config : configurations.values()) {
            if (config.isValid()) {
                anyValid = true;
                break;
            }
        }

        if (!anyValid) {
            StringBuilder sb = new StringBuilder();
            sb.append("No valid database configurations found. ");
            sb.append("Please configure at least one database:\n");
            for (DatabaseType dbType : DatabaseType.values()) {
                DatabaseConfig config = configurations.get(dbType);
                sb.append("  - ").append(dbType.getDisplayName()).append(": ");
                sb.append(config.getError()).append("\n");
            }
            globalError = sb.toString();
        }

        // Clean up H2_FILE artifacts at JVM shutdown so they don't accumulate
        // in /tmp across runs. Best-effort: H2 has its own shutdown hook that
        // releases file handles, but shutdown-hook order is undefined. On
        // Windows in particular, attempting to delete a still-locked .mv.db
        // silently fails; in that case the artifact is harmless and will be
        // overwritten by the next run's setUp(). We wait briefly so H2's hook
        // is more likely to have released first, then attempt cleanup.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            DatabaseConfig fileConfig = configurations.get(DatabaseType.H2_FILE);
            if (fileConfig == null || !fileConfig.isValid()) return;
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            String dbName = fileConfig.getDatabaseName();
            String path = dbName.startsWith("file:") ? dbName.substring(5) : dbName;
            int semi = path.indexOf(';');
            if (semi >= 0) path = path.substring(0, semi);
            for (String suffix : new String[]{".mv.db", ".trace.db", ".lock.db"}) {
                java.io.File f = new java.io.File(path + suffix);
                if (f.exists()) f.delete(); // best-effort; ignore failures
            }
        }, "ConnectionPoolTestConfig-H2FileCleanup"));
    }

    /**
     * Loads configuration for a specific database type.
     */
    private static DatabaseConfig loadConfiguration(DatabaseType dbType) {
        Properties properties = new Properties();
        String error = null;
        boolean valid = false;

        // H2 is built-in and always available (no external config needed)
        if (dbType == DatabaseType.H2) {
            properties.setProperty("db.host", "localhost");
            properties.setProperty("db.port", "9092");
            properties.setProperty("db.name", "mem:testdb;DB_CLOSE_DELAY=-1");
            properties.setProperty("db.user", "sa");
            properties.setProperty("db.password", "");
            return new DatabaseConfig(dbType, properties, null, true);
        }

        // H2_FILE is also built-in. Uses a fixed path in the temp dir so the
        // file artifacts are easy to find and clean up. DB_CLOSE_DELAY=-1 keeps
        // the database open across connection closes (matches the H2 in-memory
        // semantics; without it, the DB would be torn down between each
        // checkout and warm-up table state would vanish).
        //
        // NOTE: db.host and db.port are dummy placeholders. The full file path
        // (and any URL params) is encoded into db.name. Callers should use
        // getDatabaseName() or getUrl() for H2_FILE configs; getHost()/getPort()
        // will return "file"/0 which are not meaningful.
        if (dbType == DatabaseType.H2_FILE) {
            String tmpDir = System.getProperty("java.io.tmpdir");
            if (!tmpDir.endsWith(java.io.File.separator)) tmpDir += java.io.File.separator;
            String filePath = tmpDir + "javaxt_pool_test_h2";
            properties.setProperty("db.host", "file");
            properties.setProperty("db.port", "0");
            properties.setProperty("db.name", "file:" + filePath + ";DB_CLOSE_DELAY=-1");
            properties.setProperty("db.user", "sa");
            properties.setProperty("db.password", "");
            return new DatabaseConfig(dbType, properties, null, true);
        }

        // For other databases, load from properties file
        try {
            InputStream input = ConnectionPoolTestConfig.class.getClassLoader()
                .getResourceAsStream(dbType.getConfigFile());

            if (input == null) {
                error = "Configuration file '" + dbType.getConfigFile() + "' not found. " +
                    "Copy " + dbType.getConfigFile() + ".template to " + dbType.getConfigFile() +
                    " and configure your " + dbType.getDisplayName() + " database settings.";
            } else {
                properties.load(input);
                input.close();

                // Validate required properties (password can be empty for H2)
                String[] requiredProps = {"db.host", "db.port", "db.name", "db.user"};
                for (String prop : requiredProps) {
                    if (properties.getProperty(prop) == null || properties.getProperty(prop).trim().isEmpty()) {
                        error = "Required property '" + prop + "' is missing or empty in " + dbType.getConfigFile();
                        break;
                    }
                }

                // Password must exist as a property but can be empty (for H2)
                if (error == null && properties.getProperty("db.password") == null) {
                    error = "Required property 'db.password' is missing in " + dbType.getConfigFile();
                }

                // Check if using template values
                if (error == null) {
                    String user = properties.getProperty("db.user", "").trim();
                    String password = properties.getProperty("db.password", "").trim();
                    if (user.equals("your_username") || password.equals("your_password")) {
                        error = "Database credentials appear to be template values in " + dbType.getConfigFile() + ". " +
                            "Please update with your actual " + dbType.getDisplayName() + " database credentials.";
                    }
                }

                if (error == null) {
                    valid = true;
                }
            }
        } catch (IOException e) {
            error = "Error loading " + dbType.getConfigFile() + ": " + e.getMessage();
        } catch (Exception e) {
            error = "Unexpected error loading " + dbType.getConfigFile() + ": " + e.getMessage();
        }

        return new DatabaseConfig(dbType, properties, error, valid);
    }

    /**
     * Checks if at least one database configuration is valid.
     *
     * @return true if at least one database is configured, false otherwise
     */
    public static boolean hasValidConfiguration() {
        return globalError == null;
    }

    /**
     * Gets the global configuration error message if no databases are configured.
     *
     * @return error message, or null if at least one database is configured
     */
    public static String getGlobalError() {
        return globalError;
    }

    /**
     * Gets the configuration for a specific database type.
     *
     * @param dbType the database type
     * @return the database configuration
     */
    public static DatabaseConfig getConfiguration(DatabaseType dbType) {
        return configurations.get(dbType);
    }

    /**
     * Gets all valid database configurations.
     *
     * @return list of valid database configurations
     */
    public static List<DatabaseConfig> getValidConfigurations() {
        List<DatabaseConfig> validConfigs = new ArrayList<>();
        for (DatabaseConfig config : configurations.values()) {
            if (config.isValid()) {
                validConfigs.add(config);
            }
        }
        return validConfigs;
    }

    /**
     * Gets a summary of all database configurations.
     *
     * @return configuration summary string
     */
    public static String getConfigurationSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Database Configuration Summary:\n");
        for (DatabaseType dbType : DatabaseType.values()) {
            DatabaseConfig config = configurations.get(dbType);
            sb.append("  ").append(dbType.getDisplayName()).append(": ");
            if (config.isValid()) {
                sb.append("✓ Configured (").append(config.getHost()).append(":").append(config.getPort()).append(")");
            } else {
                sb.append("✗ Not configured");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
