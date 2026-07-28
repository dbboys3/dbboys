package com.dbboys.infra.zmodem;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Minimal ZModem session engine, aligned with lrzsz behaviour. Runs over the
 * raw byte stream of an SSH shell channel; the terminal hands the stream to
 * this engine after detecting a ZRQINIT ({@code sz}) or ZRINIT ({@code rz})
 * hex header and gets it back when the session ends.
 *
 * Supported subset:
 * <ul>
 *   <li>HEX headers (used for ZRQINIT/ZRINIT/ZFIN/ZABORT) and binary headers
 *       with CRC16 ({@code **ZDLE'A'}) or CRC32 ({@code **ZDLE'C'})</li>
 *   <li>data subpackets ZCRCG/ZCRCQ/ZCRCW/ZCRCE with ZDLE escaping</li>
 *   <li>receive role: remote {@code sz} downloads to a local file</li>
 *   <li>send role: remote {@code rz} uploads local files</li>
 * </ul>
 */
public final class ZModemSession {

    private static final Logger log = LogManager.getLogger(ZModemSession.class);

    // Frame types
    private static final int ZRQINIT = 0, ZRINIT = 1, ZSINIT = 2, ZACK = 3, ZFILE = 4, ZSKIP = 5,
            ZNAK = 6, ZABORT = 7, ZFIN = 8, ZRPOS = 9, ZDATA = 10, ZEOF = 11, ZFERR = 12,
            ZCRC = 13, ZCHALLENGE = 14, ZCOMPL = 15, ZCAN = 16, ZFREECNT = 17, ZCOMMAND = 18, ZSTDERR = 19;
    // Header markers: binary header = ZPAD ZDLE 'A'/'C', hex header = ZPAD ZPAD ZDLE 'B'
    private static final int ZPAD = 0x2A, ZDLE = 0x18, ZHEX = 0x42, ZBIN = 0x41, ZBIN32 = 0x43;
    // Subpacket end markers
    private static final int ZCRCE = 0x68, ZCRCG = 0x69, ZCRCQ = 0x6A, ZCRCW = 0x6B;
    private static final int XON = 0x11, XOFF = 0x13, CAN = 0x18;
    // ZRINIT capability bits (F0)
    private static final int CANOVIO = 0x02, CANFC32 = 0x20, ESCCTL = 0x40, ESC8 = 0x80;
    // ZFILE conversion option (F0)
    private static final int ZCBIN = 0x01;

    private static final int OUR_CAPS = CANFC32 | CANOVIO;
    // Frame headers carry a 4-byte little-endian position, but flag fields live in
    // the same 4 bytes in reverse order (ZF0 = last byte) — see zmodem.h ZF0/ZP0.
    // ZRINIT advertises capabilities in ZF0; ZFILE carries the conversion option in ZF0.
    private static final long ZRINIT_FLAGS = (long) OUR_CAPS << 24;
    private static final long ZFILE_FLAGS = (long) ZCBIN << 24;
    private static final long IDLE_TIMEOUT_MS = 20000;
    // lrzsz peers accept subpackets up to MAX_BLOCK (8192); larger subpackets mean
    // fewer flushes/acks and much better throughput than the classic 1024
    private static final int SUBPACKET_LEN = 8192;

    private final In in;
    private final OutputStream out;
    private final ZModemHandler handler;

    // outbound staging: protocol bytes are composed here and flushed in batches —
    // per-byte stream writes plus per-subpacket flushes were the upload bottleneck
    private final ByteArrayOutputStream txBuf = new ByteArrayOutputStream(65536 + 8192);

    // negotiated receiver capabilities (send role)
    private boolean peerCanFcs32 = true;
    private boolean peerEscCtl;
    private boolean peerEsc8;

    private Frame pendingFrame;      // one-frame pushback for out-of-order arrivals
    private int subpacketBytes;      // bytes written by the last readSubpacket call
    private int lastWritten = -1;    // last raw byte written (for the '@' CR escape rule)

    /** Thrown when no byte arrives within the idle window; callers may retry. */
    private static final class ReadTimeout extends IOException {
        ReadTimeout() {
            super("ZModem read timeout");
        }
    }

    /** Thrown when the local user cancelled the transfer. */
    public static final class CancelledException extends IOException {
        CancelledException() {
            super("transfer cancelled");
        }
    }

    private static final class Frame {
        int type;
        long pos; // F0..F3, little-endian
    }

    private static final class FileOffer {
        final String name;
        final long size;

        FileOffer(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    /**
     * @param in     raw stream from the remote side
     * @param out    raw stream to the remote side
     * @param prefix bytes already consumed by the terminal's beacon detection
     *               (they start with the ZRQINIT/ZRINIT signature and are read first)
     */
    public ZModemSession(InputStream in, OutputStream out, byte[] prefix, ZModemHandler handler) {
        this.in = new In(in, prefix);
        this.out = out;
        this.handler = handler;
    }

    /** Bytes the engine buffered past the end of the session (e.g. the returning shell prompt). */
    public byte[] drainPending() {
        return in.leftover();
    }

    /**
     * After an abort, read and discard incoming bytes until the stream goes quiet
     * (peer died and in-flight data was drained), so protocol bytes don't end up
     * rendered as terminal garbage. Bounded: returns on EOF, after ~800ms of
     * silence, or after a 10s cap.
     */
    public void drainQuiet() {
        long deadline = System.currentTimeMillis() + 10000;
        for (;;) {
            int c;
            try {
                c = in.read(800);
            } catch (IOException e) { // ReadTimeout: stream went quiet — done
                return;
            }
            if (c < 0 || System.currentTimeMillis() > deadline) {
                return;
            }
        }
    }

    // ==================== receive role (remote sz -> local download) ====================

    public void receive() throws IOException {
        log.info("ZModem receive session started");
        awaitBeacon(ZRQINIT); // consume the sz beacon that triggered the detection
        sendHexHeader(ZRINIT, ZRINIT_FLAGS);
        for (;;) {
            checkCancelled();
            Frame hdr = readFrame();
            if (hdr == null) {
                throw new EOFException("connection closed");
            }
            switch (hdr.type) {
                case ZRQINIT -> sendHexHeader(ZRINIT, ZRINIT_FLAGS); // peer lost our ZRINIT, resend
                case ZFILE -> receiveOfferedFile();
                case ZFIN -> {
                    sendHexHeader(ZFIN, 0);
                    readOverAndOut();
                    log.info("ZModem receive session finished");
                    return;
                }
                case ZSINIT -> sendBinHeader(ZACK, 0);
                case ZFREECNT -> sendBinHeader(ZACK, freeSpace());
                case ZCHALLENGE -> sendBinHeader(ZACK, hdr.pos);
                case ZCOMMAND -> sendBinHeader(ZFERR, 0);
                case ZCAN, ZABORT -> throw new IOException("transfer aborted by peer");
                default -> { /* ZCOMPL/ZACK/stale ZEOF etc.: ignore */ }
            }
        }
    }

    private void receiveOfferedFile() throws IOException {
        FileOffer offer = readFileInfo();
        File target = handler.chooseSaveFile(offer.name, offer.size);
        if (target == null) {
            log.info("user skipped file {}", offer.name);
            sendBinHeader(ZSKIP, 0);
            return;
        }
        long received = 0;
        for (int attempt = 0; attempt < 4; attempt++) {
            checkCancelled();
            sendBinHeader(ZRPOS, attempt == 0 ? 0 : received);
            Frame d = readFrame();
            if (d == null) {
                throw new EOFException("connection closed");
            }
            // sz retransmits ZFILE while waiting for ZRPOS (e.g. while the save dialog was open)
            while (d.type == ZFILE) {
                readFileInfo();
                d = readFrame();
                if (d == null) {
                    throw new EOFException("connection closed");
                }
            }
            switch (d.type) {
                case ZDATA -> {
                    received = receiveFileData(target, d.pos, offer);
                    Frame e = readFrame();
                    if (e == null) {
                        throw new EOFException("connection closed");
                    }
                    if (e.type == ZEOF && e.pos == received) {
                        sendHexHeader(ZRINIT, ZRINIT_FLAGS);
                        handler.onProgress(offer.name, received, offer.size);
                        log.info("received {} ({} bytes)", offer.name, received);
                        return;
                    }
                    if (e.type == ZEOF) {
                        log.warn("length mismatch for {}: declared {}, got {}; requesting tail from {}",
                                offer.name, e.pos, received, received);
                        continue; // next attempt sends ZRPOS(received)
                    }
                    if (e.type == ZCAN || e.type == ZABORT) {
                        throw new IOException("transfer aborted by peer");
                    }
                    pendingFrame = e; // unexpected frame: let the outer loop reprocess it
                    return;
                }
                case ZEOF -> {
                    // empty file: no ZDATA phase at all
                    long len = target.exists() ? target.length() : 0;
                    if (d.pos == len) {
                        sendHexHeader(ZRINIT, ZRINIT_FLAGS);
                        handler.onProgress(offer.name, len, offer.size);
                        return;
                    }
                    received = len; // mismatch: next attempt asks for the missing tail
                }
                case ZCAN, ZABORT -> throw new IOException("transfer aborted by peer");
                case ZFIN -> {
                    pendingFrame = d;
                    return;
                }
                default -> throw new IOException("unexpected frame type " + d.type + " while expecting ZDATA");
            }
        }
        throw new IOException("receive failed for " + offer.name + " (length mismatch)");
    }

    /** Receive ZDATA subpackets into the target file; returns the file position reached. */
    private long receiveFileData(File target, long startPos, FileOffer offer) throws IOException {
        long count = startPos;
        // buffered: a raw FileOutputStream would syscall once per subpacket (or worse)
        try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(target, startPos > 0), 65536)) {
            for (;;) {
                checkCancelled();
                int end = readSubpacket(fos);
                count += subpacketBytes;
                handler.onProgress(offer.name, count, offer.size);
                if (end == ZCRCQ || end == ZCRCW) {
                    fos.flush(); // data on disk before we ack it
                    sendBinHeader(ZACK, count);
                }
                if (end == ZCRCW || end == ZCRCE) {
                    return count;
                }
                // ZCRCG: streaming continues with the next subpacket
            }
        }
    }

    /** Read and parse a ZFILE info subpacket: "name\0size mtime mode serial remaining type\0". */
    private FileOffer readFileInfo() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        readSubpacket(bos);
        byte[] raw = bos.toByteArray();
        int nul = -1;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == 0) {
                nul = i;
                break;
            }
        }
        String name = nul < 0 ? new String(raw, StandardCharsets.UTF_8)
                : new String(raw, 0, nul, StandardCharsets.UTF_8);
        long size = -1;
        if (nul >= 0 && nul + 1 < raw.length) {
            String meta = new String(raw, nul + 1, raw.length - nul - 1, StandardCharsets.UTF_8)
                    .replace('\0', ' ').trim();
            String[] tokens = meta.split("\\s+");
            if (tokens.length > 0) {
                try {
                    size = Long.parseLong(tokens[0]);
                } catch (NumberFormatException ignored) {
                    // size stays unknown
                }
            }
        }
        // keep the basename only, never a remote path
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isEmpty()) {
            name = "zmodem-download";
        }
        return new FileOffer(name, size);
    }

    // ==================== send role (remote rz -> local upload) ====================

    public void send() throws IOException {
        log.info("ZModem send session started");
        awaitBeacon(ZRINIT); // consume the rz beacon that triggered the detection
        handler.onMessage("\r\nZModem: choose files to upload\r\n");
        List<File> files = handler.chooseUploadFiles();
        if (files == null || files.isEmpty()) {
            log.info("upload cancelled by user");
            abortPeer();
            return;
        }
        log.info("uploading {} file(s)", files.size());
        negotiateSenderCaps();
        log.info("receiver ready, starting transfer");
        int remaining = files.size();
        for (File file : files) {
            remaining--;
            sendOneFile(file, remaining);
        }
        finishSession();
        log.info("ZModem send session finished");
    }

    private void negotiateSenderCaps() throws IOException {
        for (int attempt = 0; attempt < 6; attempt++) {
            sendHexHeader(ZRQINIT, 0);
            for (;;) {
                Frame f;
                try {
                    f = readFrame();
                } catch (ReadTimeout t) {
                    break; // resend ZRQINIT
                }
                if (f == null) {
                    throw new EOFException("connection closed");
                }
                if (f.type == ZRINIT) {
                    long caps = (f.pos >> 24) & 0xFF; // capability flags live in ZF0 (last header byte)
                    peerCanFcs32 = (caps & CANFC32) != 0;
                    peerEscCtl = (caps & ESCCTL) != 0;
                    peerEsc8 = (caps & ESC8) != 0;
                    log.debug("receiver caps: fcs32={} escCtl={} esc8={} buflen={}",
                            peerCanFcs32, peerEscCtl, peerEsc8, f.pos & 0xFFFF);
                    return;
                }
                if (f.type == ZCAN || f.type == ZABORT) {
                    throw new IOException("transfer aborted by peer");
                }
                // stale ZRQINIT beacons / ZACK / ZCOMPL: keep waiting
            }
        }
        throw new IOException("receiver did not respond (no ZRINIT)");
    }

    private void sendOneFile(File file, int remaining) throws IOException {
        long size = file.length();
        long offset = awaitZrpos(file, size, remaining);
        if (offset < 0) {
            handler.onMessage("\r\n" + file.getName() + ": skipped by receiver\r\n");
            return;
        }
        handler.onProgress(file.getName(), 0, size);
        streamFileData(file, offset, size);
        finishFileEof(file, size);
        handler.onProgress(file.getName(), size, size);
        log.info("sent {} ({} bytes)", file.getName(), size);
    }

    /** Send ZFILE + info subpacket until the receiver answers; returns resume offset, or -1 when skipped. */
    private long awaitZrpos(File file, long size, int remaining) throws IOException {
        for (int attempt = 0; attempt < 6; attempt++) {
            checkCancelled();
            sendBinHeader(ZFILE, ZFILE_FLAGS);
            writeFileInfoSubpacket(file, size, remaining);
            for (;;) {
                Frame f;
                try {
                    f = readFrame();
                } catch (ReadTimeout t) {
                    break; // resend ZFILE
                }
                if (f == null) {
                    throw new EOFException("connection closed");
                }
                switch (f.type) {
                    case ZRPOS -> {
                        return f.pos;
                    }
                    case ZSKIP -> {
                        return -1;
                    }
                    case ZCRC -> sendBinHeader(ZCRC, fileCrc32(file));
                    case ZNAK -> {
                        // receiver wants the ZFILE again
                    }
                    case ZCAN, ZABORT, ZFERR -> throw new IOException("transfer aborted by peer");
                    default -> {
                        // noise (stale ZRQINIT/ZRINIT/ZACK/ZCOMPL): keep waiting
                    }
                }
                if (f.type == ZNAK) {
                    break;
                }
            }
        }
        throw new IOException("receiver did not accept " + file.getName());
    }

    /** Stream file content as ZDATA with ZCRCG subpackets, ending with an empty ZCRCW. */
    private void streamFileData(File file, long offset, long size) throws IOException {
        long pos = offset;
        int canCount = 0; // CAN bytes seen while peeking at the peer mid-stream
        byte[] chunk = new byte[SUBPACKET_LEN];
        InputStream fis = new BufferedInputStream(new FileInputStream(file), 65536);
        try {
            skipFully(fis, pos);
            sendBinHeader(ZDATA, pos);
            for (;;) {
                int n = 0;
                while (n < chunk.length) {
                    int r = fis.read(chunk, n, chunk.length - n);
                    if (r < 0) {
                        break;
                    }
                    n += r;
                }
                if (n <= 0) {
                    break;
                }
                writeSubpacket(chunk, 0, n, ZCRCG);
                pos += n;
                handler.onProgress(file.getName(), pos, size);
                checkCancelled();
                if (txBuf.size() >= 262144) {
                    flushTx(); // batch: many subpackets per flush while streaming
                }
                // Peek at the peer between subpackets without ever blocking: count
                // CAN bytes (peer abort) and drop everything else (rz's progress
                // spam, stray tty noise). Parsing frames here could trap us in a
                // blocking read while the peer silently waits for more data;
                // a resync ZRPOS shows up at the ZACK wait below if needed.
                while (in.available() > 0) {
                    int b;
                    try {
                        b = in.read(1);
                    } catch (ReadTimeout t) {
                        break;
                    }
                    if (b < 0) {
                        throw new EOFException("connection closed");
                    }
                    if (b == CAN && ++canCount >= 5) {
                        throw new IOException("transfer aborted by peer (CAN)");
                    }
                }
            }
            // end of this file's data: empty ZCRCW subpacket, wait for ZACK
            for (int attempt = 0; attempt < 6; attempt++) {
                checkCancelled();
                writeSubpacket(chunk, 0, 0, ZCRCW);
                flushTx();
                Frame f;
                try {
                    f = readFrame();
                } catch (ReadTimeout t) {
                    continue;
                }
                if (f == null) {
                    throw new EOFException("connection closed");
                }
                switch (f.type) {
                    case ZACK -> {
                        return;
                    }
                    case ZRPOS -> {
                        streamFileData(file, f.pos, size);
                        return;
                    }
                    case ZCAN, ZABORT -> throw new IOException("transfer aborted by peer");
                    default -> { /* keep waiting */ }
                }
            }
            throw new IOException("no ZACK after data for " + file.getName());
        } finally {
            fis.close();
        }
    }

    /** Send ZEOF and wait until the receiver is ready for the next file (ZRINIT). */
    private void finishFileEof(File file, long size) throws IOException {
        for (int attempt = 0; attempt < 6; attempt++) {
            sendBinHeader(ZEOF, size);
            boolean resendTail = false;
            for (;;) {
                Frame f;
                try {
                    f = readFrame();
                } catch (ReadTimeout t) {
                    break; // resend ZEOF
                }
                if (f == null) {
                    throw new EOFException("connection closed");
                }
                switch (f.type) {
                    case ZRINIT -> {
                        return;
                    }
                    case ZSKIP -> {
                        return;
                    }
                    case ZRPOS -> {
                        streamFileData(file, f.pos, size);
                        resendTail = true;
                    }
                    case ZCAN, ZABORT, ZFERR -> throw new IOException("transfer aborted by peer");
                    default -> { /* noise: keep waiting */ }
                }
                if (resendTail) {
                    break;
                }
            }
        }
        throw new IOException("ZEOF not confirmed for " + file.getName());
    }

    /** All files sent: ZFIN, wait for ZFIN, answer "OO". */
    private void finishSession() throws IOException {
        for (int attempt = 0; attempt < 6; attempt++) {
            sendHexHeader(ZFIN, 0);
            for (;;) {
                Frame f;
                try {
                    f = readFrame();
                } catch (ReadTimeout t) {
                    break; // resend ZFIN
                }
                if (f == null) {
                    throw new EOFException("connection closed");
                }
                if (f.type == ZFIN) {
                    tx('O');
                    tx('O');
                    flushTx();
                    return;
                }
                if (f.type == ZCAN || f.type == ZABORT) {
                    throw new IOException("transfer aborted by peer");
                }
                // stale ZRINIT/ZRQINIT/ZACK: keep waiting
            }
        }
        throw new IOException("session finish not confirmed");
    }

    // ==================== frame layer ====================

    /** Wait for the peer's opening beacon: ZRQINIT from sz, ZRINIT from rz. */
    private void awaitBeacon(int type) throws IOException {
        for (int n = 0; n < 16; n++) {
            Frame f = readFrame();
            if (f == null) {
                throw new EOFException("connection closed");
            }
            if (f.type == type) {
                return;
            }
            if (f.type == ZCAN || f.type == ZABORT) {
                throw new IOException("transfer aborted by peer");
            }
        }
        throw new IOException("expected " + (type == ZRQINIT ? "ZRQINIT" : "ZRINIT"));
    }

    /** Read the next header frame; null on stream EOF. Consults the pushback slot first. */
    private Frame readFrame() throws IOException {
        if (pendingFrame != null) {
            Frame f = pendingFrame;
            pendingFrame = null;
            return f;
        }
        int canCount = 0;
        for (;;) {
            int c = in.read(IDLE_TIMEOUT_MS);
            if (c < 0) {
                return null;
            }
            if (c == XON || c == XOFF) {
                continue;
            }
            if (c == CAN) {
                if (++canCount >= 5) {
                    throw new IOException("transfer aborted by peer (CAN)");
                }
            } else {
                canCount = 0;
            }
            if (c != ZPAD) {
                continue;
            }
            int c2 = in.read(IDLE_TIMEOUT_MS);
            if (c2 == ZPAD) { // hex header: ZPAD ZPAD ZDLE 'B'
                int c3 = in.read(IDLE_TIMEOUT_MS);
                if (c3 != ZDLE) {
                    continue;
                }
                if (in.read(IDLE_TIMEOUT_MS) != ZHEX) {
                    continue;
                }
                return readHexHeader();
            }
            if (c2 == ZDLE) { // binary header: ZPAD ZDLE 'A'/'C'
                int fmt = in.read(IDLE_TIMEOUT_MS);
                if (fmt == ZBIN) {
                    return readBinHeader(false);
                }
                if (fmt == ZBIN32) {
                    return readBinHeader(true);
                }
            }
        }
    }

    /** 14 hex chars: type(2) F0..F3(8) crc16(4), then CR LF (and an optional XON, skipped by sync). */
    private Frame readHexHeader() throws IOException {
        int[] h = new int[7];
        for (int i = 0; i < 7; i++) {
            h[i] = readHexByte();
        }
        int crc = 0;
        for (int i = 0; i < 5; i++) {
            crc = ZModemCrc.update(crc, h[i]);
        }
        if (crc != ((h[5] << 8) | h[6])) {
            throw new IOException("ZModem hex header CRC error");
        }
        int t = in.read(IDLE_TIMEOUT_MS); // CR
        if (t == '\r') {
            int t2 = in.read(IDLE_TIMEOUT_MS); // LF
            if (t2 != '\n') {
                in.unread(t2); // not the expected trailer: keep the byte for the next frame
            }
        } else {
            in.unread(t);
        }
        Frame f = new Frame();
        f.type = h[0];
        f.pos = packPos(h[1], h[2], h[3], h[4]);
        log.debug("<< hex frame type={} pos={}", f.type, f.pos);
        return f;
    }

    private int readHexByte() throws IOException {
        int hi = in.read(IDLE_TIMEOUT_MS);
        int lo = in.read(IDLE_TIMEOUT_MS);
        if (hi < 0 || lo < 0) {
            throw new EOFException("connection closed");
        }
        return (hexVal(hi) << 4) | hexVal(lo);
    }

    private static int hexVal(int c) throws IOException {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw new IOException("bad hex digit in ZModem header: " + (char) c);
    }

    private Frame readBinHeader(boolean crc32) throws IOException {
        int[] b = new int[5];
        int c16 = 0;
        CRC32 c32 = new CRC32();
        for (int i = 0; i < 5; i++) {
            b[i] = zdlread();
            if (crc32) {
                c32.update(b[i]);
            } else {
                c16 = ZModemCrc.update(c16, b[i]);
            }
        }
        if (crc32) {
            long want = 0;
            for (int i = 0; i < 4; i++) {
                want |= ((long) zdlread()) << (8 * i);
            }
            if (c32.getValue() != want) {
                throw new IOException("ZModem bin32 header CRC error");
            }
        } else {
            int want = (zdlread() << 8) | zdlread();
            if (c16 != want) {
                throw new IOException("ZModem bin header CRC error");
            }
        }
        Frame f = new Frame();
        f.type = b[0];
        f.pos = packPos(b[1], b[2], b[3], b[4]);
        log.debug("<< bin{} frame type={} pos={}", crc32 ? 32 : 16, f.type, f.pos);
        return f;
    }

    /** Read one ZDLE-unescaped byte. */
    private int zdlread() throws IOException {
        int c = in.read(IDLE_TIMEOUT_MS);
        if (c < 0) {
            throw new EOFException("connection closed");
        }
        if (c != ZDLE) {
            return c;
        }
        int c2 = in.read(IDLE_TIMEOUT_MS);
        if (c2 < 0) {
            throw new EOFException("connection closed");
        }
        return c2 ^ 0x40;
    }

    /**
     * Read one data subpacket into sink, verifying its CRC. Data is unescaped into
     * a memory buffer first, then checksummed and written in bulk — per-byte file
     * writes were the download bottleneck.
     *
     * @return the end marker (ZCRCE/ZCRCG/ZCRCQ/ZCRCW); {@link #subpacketBytes} holds the data length
     */
    private int readSubpacket(OutputStream sink) throws IOException {
        boolean u32 = peerCanFcs32;
        int end = -1;
        subpacketBytes = 0;
        ByteArrayOutputStream raw = new ByteArrayOutputStream(SUBPACKET_LEN + 16);
        byte[] scratch = new byte[SUBPACKET_LEN + 16];
        boolean pendingZdle = false;
        outer:
        for (;;) {
            int n = in.readChunk(scratch, IDLE_TIMEOUT_MS);
            if (n < 0) {
                throw new EOFException("connection closed");
            }
            for (int i = 0; i < n; i++) {
                int c = scratch[i] & 0xFF;
                if (pendingZdle) {
                    pendingZdle = false;
                    if (c >= ZCRCE && c <= ZCRCW) {
                        end = c;
                        // readChunk grabs all buffered bytes; anything past the end
                        // marker (CRC bytes, next frame) must be readable again
                        in.pushback(n - i - 1);
                        break outer;
                    }
                    raw.write(c ^ 0x40);
                } else if (c == ZDLE) {
                    pendingZdle = true;
                } else {
                    raw.write(c);
                }
            }
        }
        byte[] data = raw.toByteArray();
        if (u32) {
            CRC32 c32 = new CRC32();
            c32.update(data, 0, data.length);
            c32.update(end);
            long want = 0;
            for (int i = 0; i < 4; i++) {
                want |= ((long) zdlread()) << (8 * i);
            }
            if (c32.getValue() != want) {
                throw new IOException("ZModem data CRC32 error");
            }
        } else {
            int c16 = ZModemCrc.crc16(data, 0, data.length);
            c16 = ZModemCrc.update(c16, end);
            int want = (zdlread() << 8) | zdlread();
            if (c16 != want) {
                throw new IOException("ZModem data CRC16 error");
            }
        }
        sink.write(data, 0, data.length);
        subpacketBytes = data.length;
        return end;
    }

    private void sendHexHeader(int type, long pos) throws IOException {
        int[] b = {(byte) type & 0xFF, (int) (pos & 0xFF), (int) ((pos >> 8) & 0xFF),
                (int) ((pos >> 16) & 0xFF), (int) ((pos >> 24) & 0xFF)};
        int crc = 0;
        for (int v : b) {
            crc = ZModemCrc.update(crc, v);
        }
        tx(ZPAD);
        tx(ZPAD);
        tx(ZDLE);
        tx(ZHEX);
        for (int v : b) {
            tx(hexChar(v >> 4));
            tx(hexChar(v));
        }
        tx(hexChar(crc >> 12));
        tx(hexChar(crc >> 8));
        tx(hexChar(crc >> 4));
        tx(hexChar(crc));
        tx('\r');
        tx('\n');
        if (type != ZFIN) {
            tx(XON);
        }
        flushTx(); // control frame: goes out immediately
        log.debug(">> hex frame type={} pos={}", type, pos);
    }

    private void tx(int b) {
        txBuf.write(b);
    }

    /** Push staged bytes to the wire and flush the underlying stream. */
    private void flushTx() throws IOException {
        int n = txBuf.size();
        long t0 = System.nanoTime();
        txBuf.writeTo(out);
        txBuf.reset();
        out.flush();
        // timing tells us whether the SSH stream's flush() itself is the throttle
        log.debug("flushTx {} bytes in {} ms", n, (System.nanoTime() - t0) / 1_000_000L);
    }

    private static int hexChar(int nibble) {
        int n = nibble & 0xF;
        return n < 10 ? '0' + n : 'a' + n - 10;
    }

    private void sendBinHeader(int type, long pos) throws IOException {
        boolean u32 = peerCanFcs32;
        tx(ZPAD);
        tx(ZDLE);
        tx(u32 ? ZBIN32 : ZBIN);
        int[] b = {type & 0xFF, (int) (pos & 0xFF), (int) ((pos >> 8) & 0xFF),
                (int) ((pos >> 16) & 0xFF), (int) ((pos >> 24) & 0xFF)};
        int c16 = 0;
        CRC32 c32 = new CRC32();
        for (int v : b) {
            if (u32) {
                c32.update(v);
            } else {
                c16 = ZModemCrc.update(c16, v);
            }
            zdleWrite(v);
        }
        if (u32) {
            long v = c32.getValue();
            for (int i = 0; i < 4; i++) {
                zdleWrite((int) ((v >> (8 * i)) & 0xFF));
            }
        } else {
            zdleWrite((c16 >> 8) & 0xFF);
            zdleWrite(c16 & 0xFF);
        }
        flushTx(); // control frame: goes out immediately
        log.debug(">> bin{} frame type={} pos={}", u32 ? 32 : 16, type, pos);
    }

    /**
     * Compose one data subpacket (data + ZDLE end-marker + CRC), all ZDLE-escaped,
     * into the staging buffer. Callers decide when to {@link #flushTx()} —
     * streaming batches many subpackets per flush.
     */
    private void writeSubpacket(byte[] data, int off, int len, int endType) throws IOException {
        boolean u32 = peerCanFcs32;
        CRC32 c32 = new CRC32();
        int c16 = 0;
        if (u32) {
            c32.update(data, off, len); // bulk: intrinsified, far faster than per-byte
        } else {
            c16 = ZModemCrc.crc16(data, off, len);
        }
        for (int i = off; i < off + len; i++) {
            zdleWrite(data[i] & 0xFF);
        }
        if (u32) {
            c32.update(endType);
        } else {
            c16 = ZModemCrc.update(c16, endType);
        }
        tx(ZDLE);
        tx(endType);
        lastWritten = endType;
        if (u32) {
            long v = c32.getValue();
            for (int i = 0; i < 4; i++) {
                zdleWrite((int) ((v >> (8 * i)) & 0xFF));
            }
        } else {
            zdleWrite((c16 >> 8) & 0xFF);
            zdleWrite(c16 & 0xFF);
        }
    }

    /** Write one byte, ZDLE-escaping per the ZModem rules and the negotiated ESCCTL/ESC8. */
    private void zdleWrite(int b) throws IOException {
        switch (b) {
            case ZDLE, 0x10, XON, XOFF, 0x90, 0x91, 0x93 -> writeEscaped(b);
            case '\r' -> {
                if (lastWritten == '@' || peerEscCtl) {
                    writeEscaped(b);
                } else {
                    tx(b);
                    lastWritten = b;
                }
            }
            default -> {
                if ((peerEscCtl && b < 0x20) || (peerEsc8 && (b & 0x80) != 0) || b == 0x8D && (lastWritten == '@' || peerEscCtl)) {
                    writeEscaped(b);
                } else {
                    tx(b);
                    lastWritten = b;
                }
            }
        }
    }

    private void writeEscaped(int b) throws IOException {
        tx(ZDLE);
        tx(b ^ 0x40);
        lastWritten = b;
    }

    // ==================== misc helpers ====================

    private void writeFileInfoSubpacket(File file, long size, int remaining) throws IOException {
        String meta = file.getName() + "\0" + size + " " + (file.lastModified() / 1000)
                + " 0 0 " + remaining + " 0\0";
        byte[] info = meta.getBytes(StandardCharsets.UTF_8);
        writeSubpacket(info, 0, info.length, ZCRCW);
        flushTx(); // the receiver answers with ZRPOS/ZSKIP: must go out now
    }

    private void readOverAndOut() throws IOException {
        // sz sends "OO" after our ZFIN; tolerate padding, stop right after the second 'O'
        int seen = 0;
        for (int i = 0; i < 64 && seen < 2; i++) {
            int c;
            try {
                c = in.read(5000);
            } catch (ReadTimeout t) {
                return;
            }
            if (c < 0) {
                return;
            }
            seen = c == 'O' ? seen + 1 : 0;
        }
    }

    private void checkCancelled() throws IOException {
        if (handler.isCancelled()) {
            abortPeer();
            throw new CancelledException();
        }
    }

    /** Best-effort abort so the remote sz/rz exits: CAN spam + ZABORT frame. */
    private void abortPeer() {
        try {
            byte[] cans = new byte[8];
            Arrays.fill(cans, (byte) CAN);
            txBuf.write(cans, 0, cans.length);
            flushTx();
        } catch (IOException ignored) {
            // best effort
        }
        try {
            sendHexHeader(ZABORT, 0);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static long packPos(int f0, int f1, int f2, int f3) {
        return ((long) f0 & 0xFF) | (((long) f1 & 0xFF) << 8)
                | (((long) f2 & 0xFF) << 16) | (((long) f3 & 0xFF) << 24);
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("file shorter than resume offset");
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }

    private static long fileCrc32(File file) throws IOException {
        CRC32 crc = new CRC32();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                crc.update(buf, 0, n);
            }
        }
        return crc.getValue();
    }

    private static long freeSpace() {
        try {
            return Math.min(new File(System.getProperty("user.home")).getUsableSpace(), 0xFFFFFFFFL);
        } catch (Exception e) {
            return 0xFFFFFFFFL;
        }
    }

    // ==================== buffered raw input with idle timeout ====================

    /** Buffered reader over the shell stream with per-byte idle timeout (no read timeout on the channel itself). */
    private static final class In {
        private final InputStream in;
        private byte[] buf;
        private int pos, len;

        In(InputStream in, byte[] prefix) {
            this.in = in;
            this.buf = prefix != null ? prefix : new byte[0];
            this.len = this.buf.length;
        }

        int available() throws IOException {
            return (len - pos) + in.available();
        }

        byte[] leftover() {
            return pos < len ? Arrays.copyOfRange(buf, pos, len) : new byte[0];
        }

        /** Push the last-read byte back (no-op for EOF or when the buffer was refilled). */
        void unread(int b) {
            if (b >= 0 && pos > 0) {
                pos--;
            }
        }

        /** Push back k bytes read by the last read/readChunk call. */
        void pushback(int k) {
            if (k > 0) {
                pos -= k;
            }
        }

        /** Copy buffered bytes out; returns the count (0 when the buffer is empty). */
        int drain(byte[] b, int off, int len) {
            int n = Math.min(len, this.len - pos);
            if (n > 0) {
                System.arraycopy(buf, pos, b, off, n);
                pos += n;
            }
            return n;
        }

        /**
         * Read as many buffered bytes as possible: enforces the idle timeout for the
         * first byte (refilling from the stream as needed), then grabs everything
         * already buffered. Bulk entry point so hot loops don't pay a call per byte.
         */
        int readChunk(byte[] b, long timeoutMs) throws IOException {
            int first = read(timeoutMs);
            if (first < 0) {
                return -1;
            }
            b[0] = (byte) first;
            return 1 + drain(b, 1, b.length - 1);
        }

        /** Next byte, waiting at most timeoutMs of idle time; -1 only on stream EOF. */
        int read(long timeoutMs) throws IOException {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            // Was Thread.sleep(20) per idle check: the OS timer granularity made each
            // nap ~15-30ms, capping throughput on fast links. Nap 1ms instead.
            // (A spin loop was tried and reverted: hammering available() starves the
            // writer thread on the stream's monitor.)
            for (;;) {
                if (pos < len) {
                    return buf[pos++] & 0xFF;
                }
                if (in.available() > 0) {
                    if (buf.length < 65536) {
                        buf = new byte[65536];
                    }
                    pos = 0;
                    len = in.read(buf, 0, buf.length);
                    if (len < 0) {
                        return -1;
                    }
                    if (len == 0) {
                        continue;
                    }
                } else if (System.nanoTime() - deadline >= 0) {
                    throw new ReadTimeout();
                } else {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", e);
                    }
                }
            }
        }
    }
}
