/** JNI bridge — Kotlin ↔ _hcompress.c */
#include <jni.h>
#include <string.h>
#include "_hcompress.c"

// ── encode ──
JNIEXPORT jbyteArray JNICALL
Java_com_eric_hcompress_engine_HcompressJNI_encode(
    JNIEnv *env, jclass clz, jbyteArray data,
    jintArray codes, jintArray bitLengths) {

    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *raw = (*env)->GetByteArrayElements(env, data, NULL);

    jint *cCodes = (*env)->GetIntArrayElements(env, codes, NULL);
    jint *cBLens = (*env)->GetIntArrayElements(env, bitLengths, NULL);

    // Convert to C types
    uint32_t ccodes[256]; uint8_t cblens[256];
    for (int i = 0; i < 256; i++) { ccodes[i] = (uint32_t)cCodes[i]; cblens[i] = (uint8_t)cBLens[i]; }

    BitBuf *bb = encode_bulk((uint8_t*)raw, (size_t)len, ccodes, cblens);
    (*env)->ReleaseByteArrayElements(env, data, raw, 0);
    (*env)->ReleaseIntArrayElements(env, codes, cCodes, 0);
    (*env)->ReleaseIntArrayElements(env, bitLengths, cBLens, 0);

    if (!bb) return NULL;
    size_t outLen;
    const uint8_t *out = bitbuf_flush(bb, &outLen);
    if (!out) { bitbuf_free(bb); return NULL; }

    jbyteArray result = (*env)->NewByteArray(env, (jsize)outLen);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)outLen, (jbyte*)out);
    bitbuf_free(bb);
    return result;
}

// ── decode ──
JNIEXPORT jbyteArray JNICALL
Java_com_eric_hcompress_engine_HcompressJNI_decode(
    JNIEnv *env, jclass clz, jbyteArray compressed,
    jintArray baseCode, jintArray symbolOffset,
    jintArray symbolsFlat, jint maxLen, jint outCap) {

    jsize cLen = (*env)->GetArrayLength(env, compressed);
    jbyte *cData = (*env)->GetByteArrayElements(env, compressed, NULL);

    jint *bCode = (*env)->GetIntArrayElements(env, baseCode, NULL);
    jint *sOff  = (*env)->GetIntArrayElements(env, symbolOffset, NULL);
    jint *sFlat = (*env)->GetIntArrayElements(env, symbolsFlat, NULL);

    jbyte *outBuf = (jbyte*)malloc((size_t)outCap);

    size_t n = decode_bulk((uint8_t*)cData, (size_t)cLen,
        (uint8_t*)outBuf, (size_t)outCap, bCode, sOff, sFlat, maxLen);

    (*env)->ReleaseByteArrayElements(env, compressed, cData, 0);
    (*env)->ReleaseIntArrayElements(env, baseCode, bCode, 0);
    (*env)->ReleaseIntArrayElements(env, symbolOffset, sOff, 0);
    (*env)->ReleaseIntArrayElements(env, symbolsFlat, sFlat, 0);

    if (n == 0) { free(outBuf); return NULL; }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)n);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)n, outBuf);
    free(outBuf);
    return result;
}

// ── crc32 ──
JNIEXPORT jint JNICALL
Java_com_eric_hcompress_engine_HcompressJNI_crc32(
    JNIEnv *env, jclass clz, jbyteArray data) {
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *raw = (*env)->GetByteArrayElements(env, data, NULL);
    uint32_t crc = crc32_compute((uint8_t*)raw, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, data, raw, 0);
    return (jint)crc;
}
