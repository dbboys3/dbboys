package com.dbboys.ssh.jediterm;

import com.jcraft.jsch.ChannelShell;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Minimal TtyConnector that bridges JSch ChannelShell to JediTerm.
 *
 * <p>JediTerm reads from {@link #read(char[], int, int)} and writes
 * to {@link #write(String)}.  We delegate to the JSch shell streams.
 *
 * <p>No pty4j dependency — JSch handles PTY allocation via
 * {@code channel.setPty(true)}.
 */
public class JSchTtyConnector {

    private final ChannelShell channel;
    private final OutputStream outputStream;
    private final Reader reader;
    private final Writer writer;
    private final Charset charset;

    public JSchTtyConnector(ChannelShell channel) throws IOException {
        this(channel, StandardCharsets.UTF_8);
    }

    public JSchTtyConnector(ChannelShell channel, Charset charset) throws IOException {
        this.channel = channel;
        this.charset = charset;
        this.outputStream = channel.getOutputStream();
        this.reader = new InputStreamReader(channel.getInputStream(), charset);
        this.writer = new OutputStreamWriter(outputStream, charset);
    }

    /** Read from remote shell — called by the JediTerm emulator thread. */
    public int read(char[] buf, int offset, int length) throws IOException {
        return reader.read(buf, offset, length);
    }

    /** Write to remote shell — called when user types. */
    public void write(String str) throws IOException {
        writer.write(str);
        writer.flush();
    }

    public void write(byte[] bytes) throws IOException {
        outputStream.write(bytes);
        outputStream.flush();
    }

    public boolean isConnected() {
        return channel != null && channel.isConnected();
    }

    public void close() {
        try { reader.close(); } catch (IOException ignored) {}
        try { writer.close(); } catch (IOException ignored) {}
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
    }

    /** Resize PTY. */
    public void resize(int cols, int rows, int width, int height) {
        if (channel != null && channel.isConnected()) {
            channel.setPtySize(cols, rows, width, height);
        }
    }

    public Reader getReader() { return reader; }
    public Writer getWriter() { return writer; }
    public ChannelShell getChannel() { return channel; }
}
