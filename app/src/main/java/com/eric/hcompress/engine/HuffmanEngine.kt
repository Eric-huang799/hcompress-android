package com.eric.hcompress.engine

import kotlin.math.max
import kotlin.math.min

/** Pure-Kotlin Canonical Huffman encoder/decoder (fallback when JNI unavailable). */
object HuffmanEngine {

    fun freqTable(data: ByteArray): IntArray {
        val f = IntArray(256)
        for (b in data) f[b.toInt() and 0xFF]++
        return f
    }

    fun buildCanonical(freq: IntArray): Pair<IntArray, IntArray> {
        // Min-heap tree builder
        data class Node(val w: Int, val sym: Int, val left: Node?, val right: Node?)
        val heap = mutableListOf<Node>()
        for (i in 0..255) if (freq[i] > 0) heap.add(Node(freq[i], i, null, null))
        if (heap.isEmpty()) return Pair(IntArray(256), IntArray(256))
        if (heap.size == 1) {
            val bl = IntArray(256); bl[heap[0].sym] = 1
            return Pair(IntArray(256).also { it[heap[0].sym] = 0 }, bl)
        }
        while (heap.size > 1) {
            heap.sortByDescending { it.w }
            val a = heap.removeAt(heap.lastIndex)
            val b = heap.removeAt(heap.lastIndex)
            heap.add(Node(a.w + b.w, -1, a, b))
        }
        val bitLens = IntArray(256)
        fun walk(n: Node, d: Int) {
            if (n.sym >= 0) bitLens[n.sym] = max(d, 1)
            else { n.left?.let { walk(it, d + 1) }; n.right?.let { walk(it, d + 1) } }
        }
        walk(heap[0], if (heap[0].sym >= 0) 1 else 0)

        // Canonical codes
        val byLen = (0..255).filter { bitLens[it] > 0 }.groupBy { bitLens[it] }
            .mapValues { it.value.sorted() }
        val codes = IntArray(256)
        var code = 0; var prev = 0
        for (len in byLen.keys.sorted()) {
            code = code shl (len - prev)
            for (sym in byLen[len]!!) { codes[sym] = code; code++ }
            prev = len
        }
        return Pair(codes, bitLens)
    }

    fun encode(data: ByteArray, codes: IntArray, blens: IntArray): ByteArray? {
        // Try JNI first
        HcompressJNI.encode(data, codes, blens)?.let { return it }
        // Pure Kotlin fallback
        val buf = mutableListOf<Byte>()
        var cur = 0; var bits = 0
        for (b in data) {
            val c = codes[b.toInt() and 0xFF]
            val n = blens[b.toInt() and 0xFF]
            for (i in n - 1 downTo 0) {
                cur = cur or (((c shr i) and 1) shl bits)
                if (++bits == 8) { buf.add(cur.toByte()); cur = 0; bits = 0 }
            }
        }
        if (bits > 0) buf.add(cur.toByte())
        return buf.toByteArray()
    }

    fun decode(compressed: ByteArray, baseCode: IntArray, symOff: IntArray,
               symsFlat: IntArray, maxLen: Int, outCap: Int): ByteArray? {
        HcompressJNI.decode(compressed, baseCode, symOff, symsFlat, maxLen, outCap)?.let { return it }
        // Pure Kotlin fallback
        val out = ByteArray(outCap)
        var pos = 0; var value = 0; var bits = 0; var byteIdx = 0; var bitIdx = 0
        while (pos < outCap && byteIdx < compressed.size) {
            val bit = (compressed[byteIdx].toInt() shr bitIdx) and 1
            if (++bitIdx == 8) { byteIdx++; bitIdx = 0 }
            value = (value shl 1) or bit; bits++
            if (bits <= maxLen) {
                val count = symOff[bits + 1] - symOff[bits]
                if (count > 0) {
                    val off = value - baseCode[bits]
                    if (off in 0 until count) { out[pos++] = symsFlat[symOff[bits] + off].toByte(); value = 0; bits = 0 }
                }
            }
        }
        return if (pos == outCap) out else out.copyOf(pos)
    }
}
