// tests/print_vector.c —— 打印协议固定向量，用来把两端钉在同一份字节序列上。
//
// 为什么要它：安卓端和固件端各自有单测，但两边从来没真正对过话。字节序或 CRC 只要
// 有一边写错，各自的测试都可能照样通过，直到接上硬件才发现。把这些向量同时写进两边
// 的测试里，任何一端偏移一个比特都会在主机测试阶段炸掉，不需要无线电。
//
// 只用于生成向量，不是测试：
//   zig cc -std=c11 -I main tests/print_vector.c main/campus_frame.c -o vector.exe && vector.exe
#include <stdio.h>
#include <string.h>

#include "campus_frame.h"

static void dump(const char *label, const uint8_t *data, size_t len) {
    printf("%s (%u bytes) =", label, (unsigned)len);
    for (size_t i = 0; i < len; i++) {
        printf(" 0x%02X,", data[i]);
    }
    printf("\n");
}

int main(void) {
    printf("crc16(\"123456789\") = 0x%04X\n", campus_crc16((const uint8_t *)"123456789", 9));

    // 固定帧：type=SNAPSHOT, transfer_id=0x1234, index=0, total=1, payload="hello"
    const char *payload = "hello";
    uint16_t crc = campus_crc16((const uint8_t *)payload, 5);
    printf("crc16(\"hello\") = 0x%04X\n", crc);

    uint8_t frame[CAMPUS_FRAME_HEADER + 16];
    size_t n = campus_frame_encode(CAMPUS_TYPE_SNAPSHOT, 0x1234, 0, 1, crc,
                                   (const uint8_t *)payload, 5, frame, sizeof(frame));
    dump("frame", frame, n);

    // 固定快照文本：两端的文本编码器要产出同一份字节（含 UTF-8 中文）
    // source=微信  convo=班级群  title=作业
    const char *snapshot =
            "campus-sync/1\n"
            "T\t42\t1760000000000\t0\tactive\t\xE5\xBE\xAE\xE4\xBF\xA1\t"
            "\xE7\x8F\xAD\xE7\xBA\xA7\xE7\xBE\xA4\t\xE4\xBD\x9C\xE4\xB8\x9A\n";
    printf("snapshot len = %u\n", (unsigned)strlen(snapshot));
    printf("snapshot crc16 = 0x%04X\n", campus_crc16((const uint8_t *)snapshot, strlen(snapshot)));
    dump("snapshot", (const uint8_t *)snapshot, strlen(snapshot));

    // ACK 载荷：transfer_id=0x1234(LE) + status=1
    uint8_t ack[3] = {0x34, 0x12, 0x01};
    dump("ack", ack, sizeof(ack));
    return 0;
}
