#include "campus_frame.h"

#include <string.h>

uint16_t campus_crc16(const uint8_t *data, size_t len) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= (uint16_t)((uint16_t)data[i] << 8);
        for (int bit = 0; bit < 8; bit++) {
            crc = (crc & 0x8000u) ? (uint16_t)((crc << 1) ^ 0x1021u) : (uint16_t)(crc << 1);
        }
    }
    return crc;
}

static uint16_t read_u16(const uint8_t *in) {
    return (uint16_t)((uint16_t)in[0] | ((uint16_t)in[1] << 8));
}

static void write_u16(uint8_t *out, uint16_t value) {
    out[0] = (uint8_t)(value & 0xFF);
    out[1] = (uint8_t)((value >> 8) & 0xFF);
}

bool campus_frame_decode(const uint8_t *raw, size_t len, campus_frame_t *out) {
    if (raw == NULL || out == NULL || len < CAMPUS_FRAME_HEADER) {
        return false;
    }
    if (raw[0] != CAMPUS_FRAME_MAGIC || raw[1] != CAMPUS_FRAME_VERSION) {
        return false;
    }
    uint16_t payload_len = read_u16(raw + 10);
    if ((size_t)CAMPUS_FRAME_HEADER + payload_len > len) {
        return false;
    }
    out->type = raw[2];
    out->transfer_id = read_u16(raw + 4);
    out->index = read_u16(raw + 6);
    out->total = read_u16(raw + 8);
    out->payload_len = payload_len;
    out->payload_crc = read_u16(raw + 12);
    out->payload = raw + CAMPUS_FRAME_HEADER;
    return true;
}

size_t campus_frame_encode(uint8_t type, uint16_t transfer_id, uint16_t index, uint16_t total,
                           uint16_t payload_crc, const uint8_t *payload, uint16_t payload_len,
                           uint8_t *out, size_t out_capacity) {
    size_t needed = (size_t)CAMPUS_FRAME_HEADER + payload_len;
    if (out == NULL || out_capacity < needed) {
        return 0;
    }
    out[0] = CAMPUS_FRAME_MAGIC;
    out[1] = CAMPUS_FRAME_VERSION;
    out[2] = type;
    out[3] = 0;
    write_u16(out + 4, transfer_id);
    write_u16(out + 6, index);
    write_u16(out + 8, total);
    write_u16(out + 10, payload_len);
    write_u16(out + 12, payload_crc);
    if (payload_len > 0 && payload != NULL) {
        memcpy(out + CAMPUS_FRAME_HEADER, payload, payload_len);
    }
    return needed;
}

void campus_assembler_reset(campus_assembler_t *assembler) {
    if (assembler == NULL) {
        return;
    }
    assembler->transfer_id = 0;
    assembler->type = 0;
    assembler->total = 0;
    assembler->expected_crc = 0;
    assembler->next_index = 0;
    assembler->len = 0;
}

campus_asm_result_t campus_assembler_offer(campus_assembler_t *assembler, const campus_frame_t *frame) {
    if (assembler == NULL || frame == NULL || frame->total == 0) {
        return CAMPUS_ASM_OUT_OF_ORDER;
    }

    bool fresh = assembler->total == 0
            || assembler->transfer_id != frame->transfer_id
            || assembler->type != frame->type
            || assembler->total != frame->total;
    if (fresh) {
        campus_assembler_reset(assembler);
        assembler->transfer_id = frame->transfer_id;
        assembler->type = frame->type;
        assembler->total = frame->total;
        assembler->expected_crc = frame->payload_crc;
    }

    if (frame->index < assembler->next_index) {
        return CAMPUS_ASM_DUPLICATE;
    }
    if (frame->index > assembler->next_index) {
        return CAMPUS_ASM_OUT_OF_ORDER;
    }
    if ((uint32_t)assembler->len + frame->payload_len > CAMPUS_MAX_PAYLOAD) {
        return CAMPUS_ASM_TOO_LARGE;
    }
    if (frame->payload_len > 0 && frame->payload != NULL) {
        memcpy(assembler->data + assembler->len, frame->payload, frame->payload_len);
    }
    assembler->len = (uint16_t)(assembler->len + frame->payload_len);
    assembler->next_index++;

    if (assembler->next_index < assembler->total) {
        return CAMPUS_ASM_NEED_MORE;
    }
    return campus_crc16(assembler->data, assembler->len) == assembler->expected_crc
            ? CAMPUS_ASM_COMPLETE
            : CAMPUS_ASM_CRC_FAIL;
}
