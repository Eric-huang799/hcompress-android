package com.eric.hcompress.engine

/** JNI bridge to libhcompress.so (C-accelerated Huffman codec). */
object HcompressJNI {
    init { System.loadLibrary("hcompress") }

    /** Encode raw bytes → compressed bytes. Returns null on failure. */
    external fun encode(data: ByteArray, codes: IntArray, bitLengths: IntArray): ByteArray?

    /** Decode compressed bytes → raw bytes. Returns null on failure. */
    external fun decode(
        compressed: ByteArray, baseCode: IntArray,
        symbolOffset: IntArray, symbolsFlat: IntArray,
        maxLen: Int, outCap: Int
    ): ByteArray?

    /** CRC-32 checksum */
    external fun crc32(data: ByteArray): Int
}
