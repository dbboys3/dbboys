package com.dbboys.infra.zmodem;

import java.io.File;
import java.util.List;

/**
 * Callbacks from a {@link ZModemSession} to the hosting terminal. All methods
 * are invoked on the session (reader) thread; implementations that need the
 * FX thread must marshal and wait themselves.
 */
public interface ZModemHandler {

    /**
     * Remote `sz` offered a file. Return the local target file, or null to
     * skip this file (ZSKIP is sent, the session continues with the next file).
     */
    File chooseSaveFile(String remoteName, long size);

    /**
     * Remote `rz` is ready to receive. Return the local files to upload;
     * null/empty cancels the session (peer is aborted so `rz` exits).
     */
    List<File> chooseUploadFiles();

    /** Progress callback. done == 0 marks the start of a new file; total may be -1 when unknown. */
    void onProgress(String name, long done, long total);

    /** Informational message to show in the terminal. */
    void onMessage(String message);

    /** True when the user asked to cancel the transfer (e.g. Ctrl+C). */
    boolean isCancelled();
}
