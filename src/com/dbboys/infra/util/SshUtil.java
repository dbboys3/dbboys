package com.dbboys.infra.util;

import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.model.SshConnect;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.compression.BuiltinCompressions;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SshUtil {
    private static final long DEFAULT_TIMEOUT_MS = 5000;
    private static volatile SshClient client;

    /**
     * Shared SSH client. Equivalent to the old JSch setup with
     * StrictHostKeyChecking=no; compression preference matches the previous
     * tunnel configuration (zlib@openssh.com,zlib,none).
     */
    public static SshClient getClient() {
        SshClient c = client;
        if (c == null) {
            synchronized (SshUtil.class) {
                c = client;
                if (c == null) {
                    c = SshClient.setUpDefaultClient();
                    c.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
                    c.setCompressionFactories(Arrays.asList(
                            BuiltinCompressions.delayedZlib, BuiltinCompressions.zlib, BuiltinCompressions.none));
                    c.start();
                    final SshClient started = c;
                    Runtime.getRuntime().addShutdownHook(new Thread(started::stop));
                    client = c;
                }
            }
        }
        return c;
    }

    //这个connect只有实例管理在用，其他地方没用
    public static ClientSession getConnect(Connect connect) throws Exception {
        String sshHost = connect.getSshHost();
        boolean useSsh = sshHost != null && !sshHost.isBlank();
        String host;
        String user;
        String pass;
        int port;
        if (useSsh) {
            host = sshHost;
            user = connect.getSshUser();
            pass = connect.getSshPassword();
        } else {
            host = connect.getIp();
            user = connect.getUsername();
            pass = connect.getPassword();
        }
        String portStr = connect.getSshPort();
        try {
            port = (portStr != null && !portStr.isBlank()) ? Integer.parseInt(portStr.trim()) : 22;
        } catch (NumberFormatException e) {
            port = 22;
        }
        boolean keyAuth = useSsh && connect.isSshAuthKey();
        if (keyAuth) {
            validateKeyPath(connect.getSshKeyPath());
        }
        try {
            return openSession(user, host, port, pass, keyAuth,
                    connect.getSshKeyPath(), connect.getSshKeyPassphrase(), DEFAULT_TIMEOUT_MS);
        } catch (Exception e) {
            // 实例管理等操作依赖 SSH 直连数据库服务器，连接失败时给出可操作的提示
            throw new Exception(I18n.t("instance.error.ssh_connect_failed",
                    "该操作需要ssh连接数据库服务器，ssh连接失败，如果默认端口不是22，可以修改数据库连接里的ssh隧道端口来连接。")
                    , e);
        }
    }

    public static ClientSession getSshSession(SshConnect sc) throws Exception {
        return getSshSession(sc, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Create a ClientSession directly from an SshConnect model.
     * Supports both password and key-based authentication.
     *
     * @param sc the SSH connection configuration
     * @return an authenticated ClientSession
     * @throws Exception if connection or authentication fails
     */
    public static ClientSession getSshSession(SshConnect sc, long timeoutMs) throws Exception {
        int port;
        try {
            port = Integer.parseInt(sc.getPort());
        } catch (NumberFormatException e) {
            port = 22;
        }
        if (sc.isAuthKey()) {
            validateKeyPath(sc.getKeyPath());
        }
        return openSession(sc.getUsername(), sc.getHost(), port, sc.getPassword(),
                sc.isAuthKey(), sc.getKeyPath(), sc.getKeyPassphrase(), timeoutMs);
    }

    /**
     * Create a password-authenticated ClientSession.
     */
    public static ClientSession getPasswordSession(String user, String host, int port,
                                                   String password, long timeoutMs) throws Exception {
        return openSession(user, host, port, password, false, null, null, timeoutMs);
    }

    /**
     * Create a public-key-authenticated ClientSession.
     */
    public static ClientSession getKeySession(String user, String host, int port,
                                              String keyPath, String keyPassphrase,
                                              long timeoutMs) throws Exception {
        validateKeyPath(keyPath);
        return openSession(user, host, port, null, true, keyPath, keyPassphrase, timeoutMs);
    }

    public static void disConnect(ClientSession session) {
        if (session != null) {
            session.close(false);
        }
    }

    /**
     * Safely disconnect a session if it is connected.
     *
     * @param session the session to disconnect (may be null)
     */
    public static void disconnectSession(ClientSession session) {
        if (session != null && session.isOpen()) {
            session.close(false);
        }
    }

    /**
     * Open an SFTP client on the given session (replaces the old SFTP channel).
     */
    public static SftpClient createSftpClient(ClientSession session) throws IOException {
        return SftpClientFactory.instance().createSftpClient(session);
    }

    public static String extractEnvValue(String configStr) {
        // 动态拼接正则（替换 key 部分）
        String exportString="";
        String regex = "\\b(GBASEDBTDIR|GBASEDBTSERVER|GBASEDBTSQLHOSTS|GBASEDBTTERM|INFORMIXDIR|INFORMIXSERVER|INFORMIXSQLHOSTS|PATH|TERMCAP|DB_LOCALE|CLIENT_LOCALE|NODEFDAC|ONCONFIG|GL_USEGLU|DBDELIMITER|DELIMIDENT|DBDATE)\\b\\s+(.+?)\\s*$";
        Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(configStr);
        while (matcher.find()){
            exportString += ("export "+matcher.group(1)+"=\""+matcher.group(2)+"\"&&");
        }
        //exportString+="ls";
        return exportString;
    }

    // 以下为原有工具方法（保持不变）
    public static String executeCommand(ClientSession session, String command) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        session.executeRemoteCommand(command, out, err, StandardCharsets.UTF_8);
        return out.toString(StandardCharsets.UTF_8).trim();
    }

    public static String executeCommand(ClientSession session, String command, boolean appenErrorOurPut) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        session.executeRemoteCommand(command, out, err, StandardCharsets.UTF_8);

        String stderrStr = err.toString(StandardCharsets.UTF_8).trim();
        String stdoutStr = out.toString(StandardCharsets.UTF_8).trim();

        // 合并stdout + stderr，确保错误输出不丢失
        StringBuilder fullResult = new StringBuilder();
        if (!stdoutStr.isEmpty()) {
            fullResult.append(stdoutStr);
        }
        if (!stderrStr.isEmpty()) {
            fullResult.append("\n").append(stderrStr);
        }
        return fullResult.toString().trim();
    }

    public static int executeCommandWithExitStatus(ClientSession session, String command) throws IOException {
        try (ChannelExec channelExec = session.createExecChannel(command);
             ByteArrayOutputStream out = new ByteArrayOutputStream();
             ByteArrayOutputStream err = new ByteArrayOutputStream()) {
            // 挂接收流，避免远端输出占满窗口导致命令卡住
            channelExec.setOut(out);
            channelExec.setErr(err);
            channelExec.open().verify(DEFAULT_TIMEOUT_MS);
            channelExec.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0);
            Integer exitStatus = channelExec.getExitStatus();
            return exitStatus != null ? exitStatus : -1;
        }
    }

    private static ClientSession openSession(String user, String host, int port, String password,
                                             boolean keyAuth, String keyPath, String keyPassphrase,
                                             long timeoutMs) throws Exception {
        ClientSession session = getClient().connect(user, host, port).verify(timeoutMs).getSession();
        try {
            if (keyAuth) {
                addKeyIdentity(session, keyPath, keyPassphrase);
            } else {
                session.addPasswordIdentity(password == null ? "" : password);
            }
            session.auth().verify(timeoutMs);
            return session;
        } catch (Exception e) {
            session.close(false);
            throw e;
        }
    }

    private static void validateKeyPath(String keyPath) {
        if (keyPath == null || keyPath.isBlank()) {
            throw new IllegalArgumentException(
                I18n.t("ssh.error.key_path_empty", "SSH private key path is empty"));
        }
        if (!Files.exists(Paths.get(keyPath))) {
            throw new IllegalArgumentException(
                I18n.t("ssh.error.key_path_not_found", "SSH private key not found") + ": " + keyPath);
        }
    }

    private static void addKeyIdentity(ClientSession session, String keyPath, String keyPassphrase) throws Exception {
        FilePasswordProvider passwordProvider = (keyPassphrase != null && !keyPassphrase.isBlank())
                ? FilePasswordProvider.of(keyPassphrase)
                : FilePasswordProvider.EMPTY;
        try (InputStream keyStream = Files.newInputStream(Paths.get(keyPath))) {
            Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(
                    session, NamedResource.ofName(keyPath), keyStream, passwordProvider);
            for (KeyPair key : keys) {
                session.addPublicKeyIdentity(key);
            }
        }
    }
}
