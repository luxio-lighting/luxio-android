#include <jni.h>
#include <stdint.h>
#include "AnimationRuntime.h"
JNIEXPORT jlong JNICALL Java_lighting_luxio_AnimationRuntime_create(JNIEnv *env, jobject self, jint count, jint strip) { return (jlong)(intptr_t)luxio_animation_create(count, strip); }
JNIEXPORT void JNICALL Java_lighting_luxio_AnimationRuntime_destroy(JNIEnv *env, jobject self, jlong handle) { luxio_animation_destroy((LuxioAnimation *)(intptr_t)handle); }
JNIEXPORT jboolean JNICALL Java_lighting_luxio_AnimationRuntime_parameter(JNIEnv *env, jobject self, jlong handle, jstring name, jboolean boolean, jdouble value) {
 const char *text = (*env)->GetStringUTFChars(env, name, NULL);
 if (!text) return JNI_FALSE;
 bool result = luxio_animation_parameter((LuxioAnimation *)(intptr_t)handle, text, boolean, value);
 (*env)->ReleaseStringUTFChars(env, name, text); return result;
}
JNIEXPORT jboolean JNICALL Java_lighting_luxio_AnimationRuntime_start(JNIEnv *env, jobject self, jlong handle, jbyteArray source) {
 jsize length = (*env)->GetArrayLength(env, source);
 if (length < 1 || length > 16384) return JNI_FALSE;
 char text[16385]; (*env)->GetByteArrayRegion(env, source, 0, length, (jbyte *)text); text[length] = 0;
 return luxio_animation_start((LuxioAnimation *)(intptr_t)handle, text);
}
JNIEXPORT jbyteArray JNICALL Java_lighting_luxio_AnimationRuntime_frame(JNIEnv *env, jobject self, jlong handle, jint milliseconds, jint count) {
 if (count < 1 || count > 1024) return NULL;
 const uint8_t *pixels = luxio_animation_frame((LuxioAnimation *)(intptr_t)handle, (uint32_t)milliseconds);
 if (!pixels) return NULL;
 jbyteArray result = (*env)->NewByteArray(env, count * 6);
 if (result) (*env)->SetByteArrayRegion(env, result, 0, count * 6, (const jbyte *)pixels);
 return result;
}
JNIEXPORT jstring JNICALL Java_lighting_luxio_AnimationRuntime_error(JNIEnv *env, jobject self, jlong handle) { return (*env)->NewStringUTF(env, luxio_animation_error((LuxioAnimation *)(intptr_t)handle)); }
