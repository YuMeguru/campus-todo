package dev.campus.todo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * campus-sync/1 的链路帧。纯逻辑：不碰 Android API，可在 JVM 上单测（见 BleFrameTest）。
 *
 * 帧头 14 字节，小端：
 * <pre>
 *   0   magic          0xA7
 *   1   ver            1
 *   2   type
 *   3   flags          预留
 *   4   transfer_id    u16
 *   6   index          u16，从 0 起
 *   8   total          u16
 *   10  payload_len    u16
 *   12  payload_crc16  u16   —— **整个 payload** 的 CRC，不是本片的
 *   14  payload
 * </pre>
 *
 * 为什么要每片都带 total 与整包 CRC：手机→设备走 Write With Response，ATT 层本来就逐片确认，
 * 但那只保证「写成功」，不保证设备把它按序拼对了。整包 CRC 才是最终判据。
 */
public final class BleFrame {

    private BleFrame() {
    }

    // 沿用 Nordic UART 布局。选它不是为了好看：ai-passport 仓库里这套 UUID 已经在两块真板上
    // 验证过（docs/reference/shinku-chen/two-device-ble-link.md），手机端和现成 BLE 工具都能直接认。
    public static final String SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
    /** 手机写入（设备接收）。 */
    public static final String RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
    /** 设备通知（手机接收）。 */
    public static final String TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";
    /** CCCD，用来打开 TX 的 notify。 */
    public static final String CLIENT_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";

    public static final int MAGIC = 0xA7;
    public static final int VERSION = 1;
    public static final int HEADER = 14;

    public static final int TYPE_HELLO = 0x01;
    public static final int TYPE_SNAPSHOT = 0x02;
    /** 增量同步，本版未实现，先把号占住。 */
    public static final int TYPE_DELTA = 0x03;
    public static final int TYPE_OP = 0x04;
    public static final int TYPE_ACK = 0x05;

    public static final int ACK_OK = 0;
    public static final int ACK_CRC_FAIL = 1;
    public static final int ACK_PARSE_FAIL = 2;

    /** ATT 写操作自身占 3 字节，再减去帧头。 */
    public static int chunkSizeFor(int attMtu) {
        return Math.max(1, attMtu - 3 - HEADER);
    }

    public static byte[] encode(int type, int transferId, int index, int total,
                                int payloadCrc, byte[] payload) {
        int length = payload == null ? 0 : payload.length;
        byte[] out = new byte[HEADER + length];
        out[0] = (byte) MAGIC;
        out[1] = (byte) VERSION;
        out[2] = (byte) type;
        out[3] = 0;
        putShort(out, 4, transferId);
        putShort(out, 6, index);
        putShort(out, 8, total);
        putShort(out, 10, length);
        putShort(out, 12, payloadCrc);
        if (length > 0) {
            System.arraycopy(payload, 0, out, HEADER, length);
        }
        return out;
    }

    public static final class Chunk {
        public final int type;
        public final int transferId;
        public final int index;
        public final int total;
        public final int payloadCrc;
        public final byte[] payload;

        Chunk(int type, int transferId, int index, int total, int payloadCrc, byte[] payload) {
            this.type = type;
            this.transferId = transferId;
            this.index = index;
            this.total = total;
            this.payloadCrc = payloadCrc;
            this.payload = payload;
        }
    }

    /** 解析一片。返回 null 表示这不是本协议的帧（magic / 版本 / 长度不对）—— 直接忽略，不当异常。 */
    public static Chunk decode(byte[] raw, int length) {
        if (raw == null || length < HEADER || length > raw.length) {
            return null;
        }
        if ((raw[0] & 0xFF) != MAGIC || (raw[1] & 0xFF) != VERSION) {
            return null;
        }
        int payloadLen = getShort(raw, 10);
        if (payloadLen < 0 || HEADER + payloadLen > length) {
            return null;
        }
        byte[] payload = new byte[payloadLen];
        System.arraycopy(raw, HEADER, payload, 0, payloadLen);
        return new Chunk(raw[2] & 0xFF, getShort(raw, 4), getShort(raw, 6),
                getShort(raw, 8), getShort(raw, 12), payload);
    }

    /** ACK 的载荷：transfer_id(u16) + status(u8)。 */
    public static byte[] encodeAck(int transferId, int status) {
        return new byte[]{(byte) (transferId & 0xFF), (byte) ((transferId >> 8) & 0xFF),
                (byte) (status & 0xFF)};
    }

    public static int ackTransferId(byte[] payload) {
        return payload == null || payload.length < 2 ? -1 : getShort(payload, 0);
    }

    public static int ackStatus(byte[] payload) {
        return payload == null || payload.length < 3 ? -1 : (payload[2] & 0xFF);
    }

    public static List<byte[]> chunk(int type, int transferId, byte[] payload, int maxChunk) {
        int size = Math.max(1, maxChunk);
        int length = payload == null ? 0 : payload.length;
        int total = Math.max(1, (length + size - 1) / size);
        int crc = crc16(payload == null ? new byte[0] : payload);

        List<byte[]> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int from = i * size;
            int pieceLen = Math.min(size, length - from);
            byte[] piece = new byte[Math.max(0, pieceLen)];
            if (pieceLen > 0) {
                System.arraycopy(payload, from, piece, 0, pieceLen);
            }
            out.add(encode(type, transferId, i, total, crc, piece));
        }
        return out;
    }

    /** 收到分片后往里丢，齐了自动校验整包 CRC。 */
    public static final class Assembler {

        public enum Result {
            NEED_MORE,
            COMPLETE,
            CRC_FAIL,
            BAD_INDEX
        }

        private final int total;
        private final int expectedCrc;
        private final byte[][] parts;
        private int received;
        private byte[] assembled;

        public Assembler(int total, int expectedCrc) {
            this.total = total;
            this.expectedCrc = expectedCrc;
            this.parts = new byte[Math.max(0, total)][];
        }

        /** 重复的分片会被忽略，不重复计数 —— 重传是正常现象，不是错误。 */
        public Result offer(int index, byte[] payload) {
            if (total <= 0 || index < 0 || index >= total) {
                return Result.BAD_INDEX;
            }
            if (parts[index] == null) {
                parts[index] = payload == null ? new byte[0] : payload;
                received++;
            }
            if (received < total) {
                return Result.NEED_MORE;
            }

            int length = 0;
            for (byte[] part : parts) {
                length += part.length;
            }
            byte[] all = new byte[length];
            int at = 0;
            for (byte[] part : parts) {
                System.arraycopy(part, 0, all, at, part.length);
                at += part.length;
            }
            assembled = all;
            return crc16(all) == expectedCrc ? Result.COMPLETE : Result.CRC_FAIL;
        }

        public byte[] payload() {
            return assembled;
        }

        public int received() {
            return received;
        }

        public int total() {
            return total;
        }
    }

    /** CRC16-CCITT（init 0xFFFF，poly 0x1021）。 */
    public static int crc16(byte[] data, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    public static int crc16(byte[] data) {
        return crc16(data, data == null ? 0 : data.length);
    }

    public static int crc16(String text) {
        return crc16(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void putShort(byte[] out, int at, int value) {
        out[at] = (byte) (value & 0xFF);
        out[at + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static int getShort(byte[] in, int at) {
        return (in[at] & 0xFF) | ((in[at + 1] & 0xFF) << 8);
    }
}
