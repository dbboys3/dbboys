package com.dbboys.model;

import com.dbboys.model.TreeData;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Model for an SSH connection entry, stored in t_ssh and displayed in the SSH tree view.
 * Follows the same naming/documentation conventions as {@link com.dbboys.model.Connect}.
 *
 * <pre>
 * t_ssh:
 *   c_id            INTEGER PRIMARY KEY AUTOINCREMENT  -- 连接ID
 *   c_parentid      INTEGER DEFAULT 0                   -- 所属分类ID（预留，0=未分类）
 *   c_name          VARCHAR(100)                        -- 连接名称
 *   c_host          VARCHAR(100)                        -- SSH 主机地址
 *   c_port          VARCHAR(10)                         -- SSH 端口（默认22）
 *   c_username      VARCHAR(100)                        -- SSH 用户名
 *   c_password      VARCHAR(100)                        -- SSH 密码
 *   c_auth_type     VARCHAR(10)                         -- 认证方式：password / key
 *   c_key_path      VARCHAR(500)                        -- 私钥路径（认证方式 key 时必填）
 *   c_key_passphrase VARCHAR(100)                       -- 私钥密码（可为空）
 *   c_info          VARCHAR(3200)                       -- 备注信息
 * </pre>
 */
public class SshConnect extends TreeData {
    public static final String AUTH_PASSWORD = "password";
    public static final String AUTH_KEY = "key";

    private IntegerProperty id = new SimpleIntegerProperty();
    private IntegerProperty parentId = new SimpleIntegerProperty(0);
    private StringProperty host = new SimpleStringProperty();
    private StringProperty port = new SimpleStringProperty("22");
    private StringProperty username = new SimpleStringProperty();
    private StringProperty password = new SimpleStringProperty();
    private StringProperty authType = new SimpleStringProperty(AUTH_PASSWORD);
    private StringProperty keyPath = new SimpleStringProperty();
    private StringProperty keyPassphrase = new SimpleStringProperty();
    private StringProperty info = new SimpleStringProperty();

    public SshConnect() {}

    public SshConnect(String name) {
        super(name);
    }

    // --- id ---
    public int getId() { return id.get(); }
    public IntegerProperty idProperty() { return id; }
    public void setId(int id) { this.id.set(id); }

    // --- parentId ---
    public int getParentId() { return parentId.get(); }
    public IntegerProperty parentIdProperty() { return parentId; }
    public void setParentId(int parentId) { this.parentId.set(parentId); }

    // --- host ---
    public String getHost() { return host.get(); }
    public StringProperty hostProperty() { return host; }
    public void setHost(String host) { this.host.set(host); }

    // --- port ---
    public String getPort() { return port.get(); }
    public StringProperty portProperty() { return port; }
    public void setPort(String port) { this.port.set(port); }

    // --- username ---
    public String getUsername() { return username.get(); }
    public StringProperty usernameProperty() { return username; }
    public void setUsername(String username) { this.username.set(username); }

    // --- password ---
    public String getPassword() { return password.get(); }
    public StringProperty passwordProperty() { return password; }
    public void setPassword(String password) { this.password.set(password); }

    // --- authType ---
    public String getAuthType() { return authType.get(); }
    public StringProperty authTypeProperty() { return authType; }
    public void setAuthType(String authType) { this.authType.set(authType); }
    public boolean isAuthKey() { return AUTH_KEY.equals(authType.get()); }
    public boolean isAuthPassword() { return AUTH_PASSWORD.equals(authType.get()); }

    // --- keyPath ---
    public String getKeyPath() { return keyPath.get(); }
    public StringProperty keyPathProperty() { return keyPath; }
    public void setKeyPath(String keyPath) { this.keyPath.set(keyPath); }

    // --- keyPassphrase ---
    public String getKeyPassphrase() { return keyPassphrase.get(); }
    public StringProperty keyPassphraseProperty() { return keyPassphrase; }
    public void setKeyPassphrase(String keyPassphrase) { this.keyPassphrase.set(keyPassphrase); }

    // --- info ---
    public String getInfo() { return info.get(); }
    public StringProperty infoProperty() { return info; }
    public void setInfo(String info) { this.info.set(info); }

    @Override
    public String toString() {
        return getName();
    }
}
