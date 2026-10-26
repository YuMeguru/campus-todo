package dev.campus.todo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 链路帧：CRC 的正确性、编解码的严格性、分片与重组的边界。 */
public class BleFrameTest {

    @Test
    public void crc16MatchesTheStandardCheckValue() {
        // CRC-16/CCITT-FALSE 的标准校验值。写错多项式这里立刻会红。
        assertEquals(0x29B1, BleFrame.crc16("123456789"));
    }

    @Test
    public void encodeDecodeRoundTrip() {
        byte[] payload = "你好，campus-sync".getBytes(StandardCharsets.UTF_8);
        int crc = BleFrame.crc16(payload);
        byte[] raw = BleFrame.encode(BleFrame.TYPE_SNAPSHOT, 7, 2, 5, crc, payload);

        assertEquals(BleFrame.HEADER + payload.length, raw.length);
        BleFrame.Chunk chunk = BleFrame.decode(raw, raw.length);
        assertNotNull(chunk);
        assertEquals(BleFrame.TYPE_SNAPSHOT, chunk.type);
        assertEquals(7, chunk.transferId);
        assertEquals(2, chunk.index);
        assertEquals(5, chunk.total);
        assertEquals(crc, chunk.payloadCrc);
        assertArrayEquals(payload, chunk.payload);
    }

    @Test
    public void decodeRejectsForeignAndTruncatedFrames() {
        byte[] good = BleFrame.encode(BleFrame.TYPE_HELLO, 1, 0, 1, 0, new byte[]{1, 2, 3});

        assertNull(BleFrame.decode(null, 0));
        assertNull(BleFrame.decode(good, BleFrame.HEADER - 1));

        byte[] badMagic = good.clone();
        badMagic[0] = 0x00;
        assertNull(BleFrame.decode(badMagic, badMagic.length));

        byte[] badVersion = good.clone();
        badVersion[1] = 9;
        assertNull(BleFrame.decode(badVersion, badVersion.length));

        // 头里声明 3 字节载荷，但只给了 1 字节
        assertNull(BleFrame.decode(good, BleFrame.HEADER + 1));
    }

    @Test
    public void chunkSplitsAndAssemblerPutsItBack() {
        byte[] payload = new byte[500];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 7);
        }
        List<byte[]> pieces = BleFrame.chunk(BleFrame.TYPE_SNAPSHOT, 3, payload, 230);
        assertEquals(3, pieces.size());

        int total = BleFrame.decode(pieces.get(0), pieces.get(0).length).total;
        int crc = BleFrame.decode(pieces.get(0), pieces.get(0).length).payloadCrc;
        BleFrame.Assembler assembler = new BleFrame.Assembler(total, crc);

        for (int i = 0; i < pieces.size(); i++) {
            BleFrame.Chunk chunk = BleFrame.decode(pieces.get(i), pieces.get(i).length);
            BleFrame.Assembler.Result result = assembler.offer(chunk.index, chunk.payload);
            assertEquals(i == pieces.size() - 1
                            ? BleFrame.Assembler.Result.COMPLETE
                            : BleFrame.Assembler.Result.NEED_MORE,
                    result);
        }
        assertArrayEquals(payload, assembler.payload());
    }

    @Test
    public void assemblerAcceptsOutOfOrderAndIgnoresDuplicates() {
        byte[] payload = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        List<byte[]> pieces = BleFrame.chunk(BleFrame.TYPE_OP, 1, payload, 4);
        assertEquals(3, pieces.size());

        BleFrame.Chunk last = BleFrame.decode(pieces.get(2), pieces.get(2).length);
        BleFrame.Assembler assembler = new BleFrame.Assembler(last.total, last.payloadCrc);

        BleFrame.Chunk first = BleFrame.decode(pieces.get(0), pieces.get(0).length);
        assertEquals(BleFrame.Assembler.Result.NEED_MORE,
                assembler.offer(first.index, first.payload));
        // 重传第一片：不算新到，也不该报错
        assertEquals(BleFrame.Assembler.Result.NEED_MORE,
                assembler.offer(first.index, first.payload));
        assertEquals(1, assembler.received());

        BleFrame.Chunk second = BleFrame.decode(pieces.get(1), pieces.get(1).length);
        assertEquals(BleFrame.Assembler.Result.NEED_MORE,
                assembler.offer(second.index, second.payload));
        assertEquals(BleFrame.Assembler.Result.COMPLETE,
                assembler.offer(last.index, last.payload));
        assertArrayEquals(payload, assembler.payload());
    }

    @Test
    public void assemblerReportsCrcFailInsteadOfSilentlyReturningGarbage() {
        byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);
        List<byte[]> pieces = BleFrame.chunk(BleFrame.TYPE_SNAPSHOT, 1, payload, 4);
        BleFrame.Chunk first = BleFrame.decode(pieces.get(0), pieces.get(0).length);
        BleFrame.Assembler assembler = new BleFrame.Assembler(first.total, first.payloadCrc + 1);

        BleFrame.Assembler.Result result = BleFrame.Assembler.Result.NEED_MORE;
        for (byte[] piece : pieces) {
            BleFrame.Chunk chunk = BleFrame.decode(piece, piece.length);
            result = assembler.offer(chunk.index, chunk.payload);
        }
        assertEquals(BleFrame.Assembler.Result.CRC_FAIL, result);
    }

    @Test
    public void assemblerRejectsIndexOutOfRange() {
        BleFrame.Assembler assembler = new BleFrame.Assembler(2, 0);
        assertEquals(BleFrame.Assembler.Result.BAD_INDEX, assembler.offer(2, new byte[0]));
        assertEquals(BleFrame.Assembler.Result.BAD_INDEX, assembler.offer(-1, new byte[0]));
    }

    @Test
    public void emptyPayloadStillProducesOneChunk() {
        List<byte[]> pieces = BleFrame.chunk(BleFrame.TYPE_HELLO, 0, new byte[0], 230);
        assertEquals(1, pieces.size());
        BleFrame.Chunk chunk = BleFrame.decode(pieces.get(0), pieces.get(0).length);
        assertEquals(1, chunk.total);
        assertEquals(0, chunk.payload.length);
        assertEquals(BleFrame.crc16(new byte[0]), chunk.payloadCrc);
    }

    @Test
    public void chunkSizeFollowsNegotiatedMtu() {
        // ATT MTU 247 → 单次可写 244 → 减 14 字节头 = 230
        assertEquals(230, BleFrame.chunkSizeFor(247));
        // 协商前的默认 ATT MTU 23 → 23 - 3 - 14 = 6
        assertEquals(6, BleFrame.chunkSizeFor(23));
        // 荒谬的 MTU 也不能返回 0 或负数，否则分片会死循环
        assertEquals(1, BleFrame.chunkSizeFor(0));
    }

    @Test
    public void ackCarriesTransferIdAndStatus() {
        byte[] ack = BleFrame.encodeAck(1234, BleFrame.ACK_CRC_FAIL);
        assertEquals(1234, BleFrame.ackTransferId(ack));
        assertEquals(BleFrame.ACK_CRC_FAIL, BleFrame.ackStatus(ack));

        // 载荷太短时给 -1，而不是越界读
        assertEquals(-1, BleFrame.ackTransferId(new byte[]{1}));
        assertEquals(-1, BleFrame.ackStatus(new byte[]{1, 2}));
    }

    @Test
    public void goldenFrameVector() {
        // 与固件端 tests/test_campus_logic.c 的 test_golden_frame_vector 是同一串字节。
        // 两端各自单测都可能通过而实际上对不上话，这串字节是它们唯一的共同锚点。
        final byte[] expected = {
                (byte) 0xA7, 0x01, 0x02, 0x00, 0x34, 0x12, 0x00, 0x00, 0x01, 0x00,
                0x05, 0x00, 0x6E, (byte) 0xD2, 0x68, 0x65, 0x6C, 0x6C, 0x6F,
        };
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        int crc = BleFrame.crc16(payload);
        assertEquals(0xD26E, crc);

        byte[] actual = BleFrame.encode(BleFrame.TYPE_SNAPSHOT, 0x1234, 0, 1, crc, payload);
        assertArrayEquals(expected, actual);
    }

    @Test
    public void uuidsMatchTheNordicUartLayout() {
        // 这三条是固件端要照着实现的，改动等于换协议。
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", BleFrame.SERVICE);
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", BleFrame.RX);
        assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e", BleFrame.TX);
    }
}
