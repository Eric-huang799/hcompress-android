/**
 * _hcompress.c — C-accelerated hot paths for hcompress.
 *
 * Compile (GCC on Windows):
 *   gcc -shared -O3 -march=native -o _hcompress.dll _hcompress.c
 *
 * Compile (GCC on Linux/macOS):
 *   gcc -shared -O3 -march=native -fPIC -o _hcompress.so _hcompress.c
 *
 * Provides:
 *   1. Bulk Huffman encoder (write codes to bit buffer)
 *   2. Bulk Huffman decoder (read bits → symbols via canonical tables)
 *   3. CRC-32 with precomputed table
 */

#include <stdint.h>
#include <string.h>
#include <stdlib.h>

#ifdef _MSC_VER
#define EXPORT __declspec(dllexport)
#else
#define EXPORT
#endif

/* ── Bit buffer (writer) ─────────────────────────────────────────────── */

typedef struct {
    uint8_t *buf;
    size_t   cap;
    size_t   pos;       /* byte position */
    int      bits;      /* bits in current partial byte (0..7) */
    uint8_t  cur;       /* current partial byte */
} BitBuf;

EXPORT BitBuf* bitbuf_new(size_t capacity) {
    BitBuf *bb = (BitBuf*)malloc(sizeof(BitBuf));
    if (!bb) return NULL;
    bb->buf = (uint8_t*)malloc(capacity);
    bb->cap = capacity;
    bb->pos = 0;
    bb->bits = 0;
    bb->cur = 0;
    return bb;
}

EXPORT void bitbuf_free(BitBuf *bb) {
    if (bb) { free(bb->buf); free(bb); }
}

/* Write a single code (value, nbits) MSB-first into the buffer.
   Returns 0 on success, -1 if buffer would overflow. */
EXPORT int bitbuf_write(BitBuf *bb, uint32_t value, int nbits) {
    for (int i = nbits - 1; i >= 0; i--) {
        int bit = (value >> i) & 1;
        bb->cur |= (uint8_t)(bit << bb->bits);
        bb->bits++;
        if (bb->bits == 8) {
            if (bb->pos >= bb->cap) {
                /* grow buffer */
                size_t new_cap = bb->cap * 2;
                uint8_t *new_buf = (uint8_t*)realloc(bb->buf, new_cap);
                if (!new_buf) return -1;
                bb->buf = new_buf;
                bb->cap = new_cap;
            }
            bb->buf[bb->pos++] = bb->cur;
            bb->cur = 0;
            bb->bits = 0;
        }
    }
    return 0;
}

/* Flush remaining bits, return final byte count via *out_len. */
EXPORT const uint8_t* bitbuf_flush(BitBuf *bb, size_t *out_len) {
    if (bb->bits > 0) {
        if (bb->pos >= bb->cap) {
            size_t new_cap = bb->cap + 1;
            uint8_t *new_buf = (uint8_t*)realloc(bb->buf, new_cap);
            if (!new_buf) { *out_len = 0; return NULL; }
            bb->buf = new_buf;
            bb->cap = new_cap;
        }
        bb->buf[bb->pos++] = bb->cur;
        bb->cur = 0;
        bb->bits = 0;
    }
    *out_len = bb->pos;
    return bb->buf;
}

/* ── Bulk encoder ────────────────────────────────────────────────────── */

/**
 * Encode *data_len* bytes from *data* using *codes* and *bit_lengths*
 * tables (both 256 entries).  Returns a BitBuf with the encoded stream.
 */
EXPORT BitBuf* encode_bulk(const uint8_t *data, size_t data_len,
                            const uint32_t *codes, const uint8_t *bit_lengths) {
    /* Estimate output size: worst case = data_len bytes (all 8-bit codes) */
    BitBuf *bb = bitbuf_new(data_len > 256 ? data_len : 256);
    if (!bb) return NULL;
    for (size_t i = 0; i < data_len; i++) {
        uint8_t sym = data[i];
        uint32_t code = codes[sym];
        int nbits = bit_lengths[sym];
        if (nbits == 0) {
            bitbuf_free(bb);
            return NULL;  /* symbol not in table */
        }
        if (bitbuf_write(bb, code, nbits) != 0) {
            bitbuf_free(bb);
            return NULL;
        }
    }
    return bb;
}

/* ── Bit reader ──────────────────────────────────────────────────────── */

typedef struct {
    const uint8_t *data;
    size_t  len;
    size_t  byte_pos;
    int     bit_pos;   /* 0 = LSB of current byte */
} BitReader;

static inline int br_read_bit(BitReader *br) {
    if (br->byte_pos >= br->len) return -1;
    int bit = (br->data[br->byte_pos] >> br->bit_pos) & 1;
    br->bit_pos++;
    if (br->bit_pos == 8) { br->byte_pos++; br->bit_pos = 0; }
    return bit;
}

/* ── Bulk decoder ────────────────────────────────────────────────────── */

/**
 * Decode *compressed_len* bytes from *compressed* using canonical tables.
 * Writes up to *out_cap* bytes into *output*.  Returns actual decoded
 * byte count, or 0 on error.
 *
 * Tables (all (max_len+1)-sized):
 *   base_code[len]    = first canonical code for this bit-length
 *   symbol_offset[len]= cumulative index into symbols_by_len flat list
 *   symbols_by_len    = flat list of symbols grouped by length, ordered
 *   max_len           = maximum code length
 *   symbols_total     = total number of entries in symbols_by_len
 */
EXPORT size_t decode_bulk(const uint8_t *compressed, size_t compressed_len,
                           uint8_t *output, size_t out_cap,
                           const int *base_code,
                           const int *symbol_offset,
                           const int *symbols_by_len,
                           int max_len) {
    BitReader br;
    br.data = compressed;
    br.len = compressed_len;
    br.byte_pos = 0;
    br.bit_pos = 0;

    size_t out_pos = 0;
    int value = 0;
    int bits_read = 0;

    while (out_pos < out_cap) {
        int bit = br_read_bit(&br);
        if (bit < 0) break;
        value = (value << 1) | bit;
        bits_read++;

        /* Only check the length matching the number of bits read so far */
        int len = bits_read;
        if (len <= max_len) {
            int count = symbol_offset[len + 1] - symbol_offset[len];
            if (count > 0) {
                int offset = value - base_code[len];
                if (offset >= 0 && offset < count) {
                    int sym = symbols_by_len[symbol_offset[len] + offset];
                    output[out_pos++] = (uint8_t)sym;
                    value = 0;
                    bits_read = 0;
                }
            }
        }
    }
    return out_pos;
}

/* ── CRC-32 ──────────────────────────────────────────────────────────── */

static uint32_t crc32_table[256];
static int crc_table_ready = 0;

static void crc32_init_table(void) {
    for (int i = 0; i < 256; i++) {
        uint32_t crc = (uint32_t)i;
        for (int j = 0; j < 8; j++) {
            if (crc & 1) crc = (crc >> 1) ^ 0xEDB88320UL;
            else crc >>= 1;
        }
        crc32_table[i] = crc;
    }
    crc_table_ready = 1;
}

EXPORT uint32_t crc32_compute(const uint8_t *data, size_t len) {
    if (!crc_table_ready) crc32_init_table();
    uint32_t crc = 0xFFFFFFFFUL;
    for (size_t i = 0; i < len; i++) {
        crc = crc32_table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    }
    return crc ^ 0xFFFFFFFFUL;
}
