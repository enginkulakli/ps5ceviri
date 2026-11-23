// SPDX-License-Identifier: AGPL-3.0-only
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <chiaki/regist.h>
#include <chiaki/session.h>
#include <openssl/crypto.h>

typedef struct {
    ChiakiSession session;
    ChiakiLog log;
    jobject receiver;
    jmethodID video;
    jmethodID event;
} RemoteSession;

typedef struct {
    bool success;
    uint8_t keys[32];
} RegistrationResult;

static JavaVM *vm;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *java_vm, void *reserved)
{
    (void)reserved;
    vm = java_vm;
    return chiaki_lib_init() == CHIAKI_ERR_SUCCESS ? JNI_VERSION_1_6 : JNI_ERR;
}

static void fail(JNIEnv *env, const char *message)
{
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
}

static void registration_event(ChiakiRegistEvent *event, void *user)
{
    RegistrationResult *result = user;
    if (event->type != CHIAKI_REGIST_EVENT_TYPE_FINISHED_SUCCESS) return;
    memcpy(result->keys, event->registered_host->rp_regist_key, 16);
    memcpy(result->keys + 16, event->registered_host->rp_key, 16);
    result->success = true;
}

JNIEXPORT jbyteArray JNICALL
Java_expo_modules_ps5translator_RemotePlayNative_register(
    JNIEnv *env, jobject self, jstring host, jbyteArray account, jint pin)
{
    (void)self;
    if ((*env)->GetArrayLength(env, account) != CHIAKI_PSN_ACCOUNT_ID_SIZE) return NULL;
    ChiakiRegistInfo info = { .target = CHIAKI_TARGET_PS5_1, .pin = (uint32_t)pin };
    (*env)->GetByteArrayRegion(env, account, 0, CHIAKI_PSN_ACCOUNT_ID_SIZE, (jbyte *)info.psn_account_id);
    info.host = (*env)->GetStringUTFChars(env, host, NULL);
    if (!info.host) return NULL;

    ChiakiLog log;
    chiaki_log_init(&log, 0, NULL, NULL);
    ChiakiRegist registration;
    RegistrationResult result = { 0 };
    ChiakiErrorCode error = chiaki_regist_start(&registration, &log, &info, registration_event, &result);
    (*env)->ReleaseStringUTFChars(env, host, info.host);
    OPENSSL_cleanse(info.psn_account_id, sizeof(info.psn_account_id));
    if (error != CHIAKI_ERR_SUCCESS) {
        fail(env, chiaki_error_string(error));
        return NULL;
    }
    chiaki_regist_fini(&registration);
    OPENSSL_cleanse(&registration, sizeof(registration));
    if (!result.success) return NULL;
    jbyteArray keys = (*env)->NewByteArray(env, sizeof(result.keys));
    if (keys) (*env)->SetByteArrayRegion(env, keys, 0, sizeof(result.keys), (jbyte *)result.keys);
    OPENSSL_cleanse(&result, sizeof(result));
    return keys;
}

static JNIEnv *callback_env(bool *attached)
{
    JNIEnv *env = NULL;
    jint state = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
    *attached = false;
    if (state == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return NULL;
        *attached = true;
    }
    return env;
}

static bool video_sample(uint8_t *data, size_t size, int32_t lost, bool recovered, void *user)
{
    (void)lost;
    (void)recovered;
    RemoteSession *remote = user;
    bool attached;
    JNIEnv *env = callback_env(&attached);
    if (!env) return false;
    jobject buffer = (*env)->NewDirectByteBuffer(env, data, (jlong)size);
    bool accepted = buffer &&
        (*env)->CallBooleanMethod(env, remote->receiver, remote->video, buffer);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        accepted = false;
    }
    if (buffer) (*env)->DeleteLocalRef(env, buffer);
    if (attached) (*vm)->DetachCurrentThread(vm);
    return accepted;
}

static void session_event(ChiakiEvent *event, void *user)
{
    const char *message = NULL;
    jint code;
    switch (event->type) {
        case CHIAKI_EVENT_CONNECTED:
            code = 0;
            break;
        case CHIAKI_EVENT_LOGIN_PIN_REQUEST:
            code = 1;
            break;
        case CHIAKI_EVENT_QUIT:
            code = chiaki_quit_reason_is_error(event->quit.reason) ? 2 : 3;
            message = chiaki_quit_reason_string(event->quit.reason);
            break;
        default:
            return;
    }
    RemoteSession *remote = user;
    bool attached;
    JNIEnv *env = callback_env(&attached);
    if (!env) return;
    jstring details = message ? (*env)->NewStringUTF(env, message) : NULL;
    if (!(*env)->ExceptionCheck(env)) {
        (*env)->CallVoidMethod(env, remote->receiver, remote->event, code, details);
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (details) (*env)->DeleteLocalRef(env, details);
    if (attached) (*vm)->DetachCurrentThread(vm);
}

static void free_session(JNIEnv *env, RemoteSession *remote)
{
    if (remote->receiver) (*env)->DeleteGlobalRef(env, remote->receiver);
    OPENSSL_cleanse(remote, sizeof(*remote));
    free(remote);
}

JNIEXPORT jlong JNICALL
Java_expo_modules_ps5translator_RemotePlayNative_start(
    JNIEnv *env, jobject self, jstring host, jbyteArray keys, jobject receiver)
{
    (void)self;
    if ((*env)->GetArrayLength(env, keys) != 32) {
        fail(env, "Invalid Remote Play credentials");
        return 0;
    }
    RemoteSession *remote = calloc(1, sizeof(*remote));
    if (!remote) {
        fail(env, "Unable to allocate Remote Play session");
        return 0;
    }
    jclass klass = (*env)->GetObjectClass(env, receiver);
    remote->video = (*env)->GetMethodID(env, klass, "onVideo", "(Ljava/nio/ByteBuffer;)Z");
    if (!remote->video) { free_session(env, remote); return 0; }
    remote->event = (*env)->GetMethodID(env, klass, "onEvent", "(ILjava/lang/String;)V");
    if (!remote->event) { free_session(env, remote); return 0; }
    remote->receiver = (*env)->NewGlobalRef(env, receiver);
    if (!remote->receiver) { free_session(env, remote); return 0; }

    ChiakiConnectInfo info = { .ps5 = true, .audio_video_disabled = CHIAKI_AUDIO_DISABLED,
                              .enable_idr_on_fec_failure = true, .packet_loss_max = 0.1 };
    uint8_t material[32];
    (*env)->GetByteArrayRegion(env, keys, 0, sizeof(material), (jbyte *)material);
    memcpy(info.regist_key, material, sizeof(info.regist_key));
    memcpy(info.morning, material + 16, sizeof(info.morning));
    OPENSSL_cleanse(material, sizeof(material));
    chiaki_connect_video_profile_preset(&info.video_profile, CHIAKI_VIDEO_RESOLUTION_PRESET_720p,
                                       CHIAKI_VIDEO_FPS_PRESET_30);
    info.video_profile.codec = CHIAKI_CODEC_H264;
    chiaki_log_init(&remote->log, 0, NULL, NULL);
    info.host = (*env)->GetStringUTFChars(env, host, NULL);
    if (!info.host) {
        OPENSSL_cleanse(&info, sizeof(info));
        free_session(env, remote);
        return 0;
    }
    ChiakiErrorCode error = chiaki_session_init(&remote->session, &info, &remote->log);
    (*env)->ReleaseStringUTFChars(env, host, info.host);
    OPENSSL_cleanse(&info, sizeof(info));
    if (error == CHIAKI_ERR_SUCCESS) {
        chiaki_session_set_video_sample_cb(&remote->session, video_sample, remote);
        chiaki_session_set_event_cb(&remote->session, session_event, remote);
        error = chiaki_session_start(&remote->session);
        if (error == CHIAKI_ERR_SUCCESS) return (jlong)(intptr_t)remote;
        chiaki_session_fini(&remote->session);
    }
    free_session(env, remote);
    fail(env, chiaki_error_string(error));
    return 0;
}

JNIEXPORT void JNICALL
Java_expo_modules_ps5translator_RemotePlayNative_stop(JNIEnv *env, jobject self, jlong handle)
{
    (void)self;
    if (!handle) return;
    RemoteSession *remote = (RemoteSession *)(intptr_t)handle;
    chiaki_session_stop(&remote->session);
    chiaki_session_join(&remote->session);
    chiaki_session_fini(&remote->session);
    free_session(env, remote);
}
