package com.eric.hcompress.engine

/** JNI bridge — gracefully falls back to null if native lib not available. */
object HcompressJNI {
    private val available: Boolean = try {
        System.loadLibrary("hcompress")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    fun encode(data: ByteArray, codes: IntArray, bitLengths: IntArray): ByteArray? {
        if (!available) return null
        return nativeEncode(data, codes, bitLengths)
    }

    fun decode(compressed: ByteArray, baseCode: IntArray, symOffset: IntArray,
               symsFlat: IntArray, maxLen: Int, outCap: Int): ByteArray? {
        if (!available) return null
        return nativeDecode(compressed, baseCode, symOffset, symsFlat, maxLen, outCap)
    }

    fun crc32(data: ByteArray): Int {
        if (!available) return -1
        return nativeCrc32(data)
    }

    private external fun nativeEncode(data: ByteArray, codes: IntArray, bitLengths: IntArray): ByteArray?
    private external fun nativeDecode(compressed: ByteArray, baseCode: IntArray, symOffset: IntArray,
                                      symsFlat: IntArray, maxLen: Int, outCap: Int): ByteArray?
    private external fun nativeCrc32(data: ByteArray): Int
}
