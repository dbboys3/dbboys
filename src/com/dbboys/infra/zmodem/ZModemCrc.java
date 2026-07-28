package com.dbboys.infra.zmodem;

/**
 * CRC16-CCITT (polynomial 0x1021, initial value 0) as used by ZModem for
 * HEX headers, CRC16 binary headers and CRC16 data subpackets.
 * CRC32 (for the negotiated 32-bit frame check) is java.util.zip.CRC32.
 */
public final class ZModemCrc {

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
            }
            TABLE[i] = crc & 0xFFFF;
        }
    }

    private ZModemCrc() {
    }

    /** Update a running CRC16 with one byte. */
    public static int update(int crc, int b) {
        return ((crc << 8) ^ TABLE[((crc >> 8) ^ b) & 0xFF]) & 0xFFFF;
    }

    /** CRC16 over a byte range. */
    public static int crc16(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc = update(crc, data[i] & 0xFF);
        }
        return crc;
    }
}
