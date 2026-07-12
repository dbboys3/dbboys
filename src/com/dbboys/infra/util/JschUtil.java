package com.dbboys.infra.util;

import com.dbboys.model.Connect;
import com.dbboys.ssh.SshConnect;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import javafx.concurrent.Task;

import java.io.*;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JschUtil {
    //private static JSch jsch = new JSch();
    //private static Session session;
    public static Session getConnect(Connect connect) throws Exception {
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
        Session session = new JSch().getSession(user, host, port);
                    session.setPassword(pass);
                    session.setPassword(pass);
                    Properties config = new Properties();
                    config.put("StrictHostKeyChecking", "no");
                    session.setConfig(config);
                    session.connect(5000); // 5秒超时
        return session;
    }

    public static void disConnect(Session session) throws Exception {
        session.disconnect();
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
    public static String executeCommand(Session session,String command) throws JSchException, IOException {
        ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
        channelExec.setCommand(command);

        InputStream in = channelExec.getInputStream();
        channelExec.connect();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder output = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }

        channelExec.disconnect();
        return output.toString().trim();
    }

    public static String executeCommand(Session session, String command,boolean appenErrorOurPut) throws JSchException, IOException {
        ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
        channelExec.setCommand(command);

        // 步骤1：创建字节流捕获stderr（你已做，但未使用）
        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        channelExec.setErrStream(errOut);

        // 捕获stdout
        InputStream in = channelExec.getInputStream();
        channelExec.connect();

        // 步骤2：读取stdout（原有逻辑保留）
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder stdoutBuilder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            stdoutBuilder.append(line).append("\n");
        }

        // ========== 你缺失的核心步骤 ==========
        // 步骤3：等待命令完全执行，确保stderr全部写入（关键！）
        // 不加这步，errOut可能还没写入就被关闭，导致空值
        while (!channelExec.isClosed()) {
            try {
                Thread.sleep(100); // 短暂等待，避免CPU空转
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 步骤4：读取并合并stderr到返回结果（关键！）
        String stderrStr = new String(errOut.toByteArray(), "UTF-8").trim(); // 转成字符串
        String stdoutStr = stdoutBuilder.toString().trim();

        // 步骤5：合并stdout + stderr，确保错误输出不丢失
        StringBuilder fullResult = new StringBuilder();
        if (!stdoutStr.isEmpty()) {
            fullResult.append(stdoutStr);
        }
        if (!stderrStr.isEmpty()) {
            // 加[ERROR]标记，便于业务层识别错误输出
            fullResult.append("\n").append(stderrStr);
        }
        // =====================================

        // 步骤6：释放所有资源（避免内存泄漏）
        reader.close();
        in.close();
        errOut.close();
        channelExec.disconnect();

        // 返回包含错误输出的完整结果
        return fullResult.toString().trim();
    }
    public static int executeCommandWithExitStatus(Session session,String command) throws JSchException, InterruptedException {
        ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
        channelExec.setCommand(command);
        channelExec.connect();

        while (!channelExec.isClosed()) {
            Thread.sleep(100);
        }

        int exitStatus = channelExec.getExitStatus();
        channelExec.disconnect();
        return exitStatus;
    }

    /**
     * Create a JSch Session directly from an SshConnect model.
     * Supports both password and key-based authentication.
     *
     * @param sc the SSH connection configuration
     * @return an authenticated JSch Session
     * @throws JSchException if connection or authentication fails
     */
    public static Session getSshSession(SshConnect sc) throws JSchException {
        JSch jsch = new JSch();
        int port;
        try {
            port = Integer.parseInt(sc.getPort());
        } catch (NumberFormatException e) {
            port = 22;
        }
        Session session = jsch.getSession(sc.getUsername(), sc.getHost(), port);
        if (sc.isAuthKey()) {
            if (sc.getKeyPassphrase() != null && !sc.getKeyPassphrase().isBlank()) {
                jsch.addIdentity(sc.getKeyPath(), sc.getKeyPassphrase());
            } else {
                jsch.addIdentity(sc.getKeyPath());
            }
        } else {
            session.setPassword(sc.getPassword());
        }
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        session.connect(5000); // 5-second timeout
        return session;
    }

    /**
     * Safely disconnect a JSch session if it is connected.
     *
     * @param session the session to disconnect (may be null)
     */
    public static void disconnectSession(Session session) {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }
}
