#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "abotalal_php_adapter.h"

#define TAG "AbotalalPhpJNI"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef int (*fn_start_t)(const char *);
typedef int (*fn_exec_t)(const AbotalalPhpRequest *, AbotalalPhpResponse *);
typedef void (*fn_stop_t)(void);
typedef int (*fn_is_running_t)(void);
typedef const char *(*fn_get_version_t)(void);
typedef int (*fn_get_modules_t)(char *, size_t);
typedef void (*fn_free_resp_t)(AbotalalPhpResponse *);

typedef struct LoadedPhpSlot {
    char version_tag[32];
    void *handle;
    fn_start_t fn_start;
    fn_exec_t fn_exec;
    fn_stop_t fn_stop;
    fn_is_running_t fn_is_running;
    fn_get_version_t fn_get_version;
    fn_get_modules_t fn_get_modules;
    fn_free_resp_t fn_free_resp;
} LoadedPhpSlot;

#define MAX_PHP_SLOTS 8
static LoadedPhpSlot g_slots[MAX_PHP_SLOTS];
static pthread_mutex_t g_slots_mutex = PTHREAD_MUTEX_INITIALIZER;

static LoadedPhpSlot *load_php_slot(const char *native_lib_dir, const char *version_tag, char *err_buf, size_t err_cap) {
    if (!native_lib_dir || !version_tag || !native_lib_dir[0] || !version_tag[0]) {
        if (err_buf) snprintf(err_buf, err_cap, "Invalid nativeLibDir or versionTag");
        return NULL;
    }

    pthread_mutex_lock(&g_slots_mutex);
    for (int i = 0; i < MAX_PHP_SLOTS; i++) {
        if (g_slots[i].handle && strcmp(g_slots[i].version_tag, version_tag) == 0) {
            pthread_mutex_unlock(&g_slots_mutex);
            return &g_slots[i];
        }
    }

    int free_idx = -1;
    for (int i = 0; i < MAX_PHP_SLOTS; i++) {
        if (!g_slots[i].handle) {
            free_idx = i;
            break;
        }
    }
    if (free_idx < 0) {
        pthread_mutex_unlock(&g_slots_mutex);
        if (err_buf) snprintf(err_buf, err_cap, "Maximum PHP runtime slot limit reached");
        return NULL;
    }

    char adapter_path[1024];
    snprintf(adapter_path, sizeof(adapter_path), "%s/libabotalal_php_%s.so", native_lib_dir, version_tag);

    dlerror();
    void *handle = dlopen(adapter_path, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        const char *dlerr = dlerror();
        ALOGE("dlopen(%s) failed: %s", adapter_path, dlerr ? dlerr : "unknown");
        if (err_buf) snprintf(err_buf, err_cap, "dlopen(%s) failed: %s", adapter_path, dlerr ? dlerr : "unknown");
        pthread_mutex_unlock(&g_slots_mutex);
        return NULL;
    }

    LoadedPhpSlot slot;
    memset(&slot, 0, sizeof(slot));
    snprintf(slot.version_tag, sizeof(slot.version_tag), "%s", version_tag);
    slot.handle = handle;
    slot.fn_start = (fn_start_t)dlsym(handle, "abotalal_php_start");
    slot.fn_exec = (fn_exec_t)dlsym(handle, "abotalal_php_execute_request");
    slot.fn_stop = (fn_stop_t)dlsym(handle, "abotalal_php_stop");
    slot.fn_is_running = (fn_is_running_t)dlsym(handle, "abotalal_php_is_running");
    slot.fn_get_version = (fn_get_version_t)dlsym(handle, "abotalal_php_get_version");
    slot.fn_get_modules = (fn_get_modules_t)dlsym(handle, "abotalal_php_get_modules");
    slot.fn_free_resp = (fn_free_resp_t)dlsym(handle, "abotalal_php_free_response");

    if (!slot.fn_start || !slot.fn_exec || !slot.fn_stop || !slot.fn_is_running ||
        !slot.fn_get_version || !slot.fn_get_modules || !slot.fn_free_resp) {
        dlclose(handle);
        ALOGE("Missing adapter symbols in %s", adapter_path);
        if (err_buf) snprintf(err_buf, err_cap, "Missing required PHP adapter symbols in %s", adapter_path);
        pthread_mutex_unlock(&g_slots_mutex);
        return NULL;
    }

    g_slots[free_idx] = slot;
    ALOGI("Loaded PHP Embedded SAPI slot [%s] from %s", version_tag, adapter_path);
    pthread_mutex_unlock(&g_slots_mutex);
    return &g_slots[free_idx];
}

static const char *get_utf_or_null(JNIEnv *env, jstring s) {
    if (!s) return NULL;
    return (*env)->GetStringUTFChars(env, s, NULL);
}

static void rel_utf(JNIEnv *env, jstring s, const char *p) {
    if (s && p) (*env)->ReleaseStringUTFChars(env, s, p);
}

static jobject build_error_response(JNIEnv *env, int status, const char *err_msg) {
    jclass cls = (*env)->FindClass(env, "com/example/engine/php/PhpNativeHttpResponse");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(ZILjava/lang/String;[BLjava/lang/String;Ljava/lang/String;)V");
    jstring jHeaders = (*env)->NewStringUTF(env, "");
    jbyteArray jBody = (*env)->NewByteArray(env, 0);
    jstring jStderr = (*env)->NewStringUTF(env, "");
    jstring jErr = (*env)->NewStringUTF(env, err_msg ? err_msg : "Unknown PHP JNI error");
    return (*env)->NewObject(env, cls, ctor, JNI_FALSE, status, jHeaders, jBody, jStderr, jErr);
}

JNIEXPORT jboolean JNICALL
Java_com_example_engine_php_AbotalalPhpNativeBridge_nativeIsEngineLoadable(
    JNIEnv *env, jobject thiz, jstring jNativeDir, jstring jVersionTag
) {
    (void)thiz;
    const char *dir = get_utf_or_null(env, jNativeDir);
    const char *tag = get_utf_or_null(env, jVersionTag);
    char err[256] = {0};
    LoadedPhpSlot *slot = load_php_slot(dir, tag, err, sizeof(err));
    rel_utf(env, jNativeDir, dir);
    rel_utf(env, jVersionTag, tag);
    return slot != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_engine_php_AbotalalPhpNativeBridge_nativeStartEngine(
    JNIEnv *env, jobject thiz, jstring jNativeDir, jstring jVersionTag, jstring jIniPath
) {
    (void)thiz;
    const char *dir = get_utf_or_null(env, jNativeDir);
    const char *tag = get_utf_or_null(env, jVersionTag);
    const char *ini = get_utf_or_null(env, jIniPath);
    char err[256] = {0};
    LoadedPhpSlot *slot = load_php_slot(dir, tag, err, sizeof(err));
    int ok = 0;
    if (slot) {
        ok = slot->fn_start(ini);
    }
    rel_utf(env, jNativeDir, dir);
    rel_utf(env, jVersionTag, tag);
    rel_utf(env, jIniPath, ini);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_engine_php_AbotalalPhpNativeBridge_nativeStopEngine(
    JNIEnv *env, jobject thiz, jstring jNativeDir, jstring jVersionTag
) {
    (void)thiz;
    const char *dir = get_utf_or_null(env, jNativeDir);
    const char *tag = get_utf_or_null(env, jVersionTag);
    LoadedPhpSlot *slot = load_php_slot(dir, tag, NULL, 0);
    if (slot) {
        slot->fn_stop();
    }
    rel_utf(env, jNativeDir, dir);
    rel_utf(env, jVersionTag, tag);
}

JNIEXPORT jboolean JNICALL
Java_com_example_engine_php_AbotalalPhpNativeBridge_nativeIsEngineRunning(
    JNIEnv *env, jobject thiz, jstring jNativeDir, jstring jVersionTag
) {
    (void)thiz;
    const char *dir = get_utf_or_null(env, jNativeDir);
    const char *tag = get_utf_or_null(env, jVersionTag);
    LoadedPhpSlot *slot = load_php_slot(dir, tag, NULL, 0);
    int running = slot ? slot->fn_is_running() : 0;
    rel_utf(env, jNativeDir, dir);
    rel_utf(env, jVersionTag, tag);
    return running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_example_engine_php_AbotalalPhpNativeBridge_nativeGetPhpVersion(
    JNIEnv *env, jobject thiz, jstring jNativeDir, jstring jVersionTag
) {
    (void)thiz;
    const char *dir = get_utf_or_null(env, jNativeDir);
    const char *tag = get_utf_or_null(env, jVersionTag);
    LoadedPhpSlot *slot = load_php_slot(dir, tag, NULL, 0);
    const char *ver = slot ? slot->fn_get_version() : "";
    rel_utf(env, jNativeDir, dir);
    rel_utf(env, jVersionTag, tag);
    return (*env)->NewStringUTF(env, ver ? ver : "");
}

JNIEXPORT jstring JNICALL
Java_com_example_engine_php_AbotalalPhpNativeBridge_nativeGetLoadedModules(
    JNIEnv *env, jobject thiz, jstring jNativeDir, jstring jVersionTag
) {
    (void)thiz;
    const char *dir = get_utf_or_null(env, jNativeDir);
    const char *tag = get_utf_or_null(env, jVersionTag);
    LoadedPhpSlot *slot = load_php_slot(dir, tag, NULL, 0);
    char buf[4096] = {0};
    if (slot) {
        slot->fn_get_modules(buf, sizeof(buf));
    }
    rel_utf(env, jNativeDir, dir);
    rel_utf(env, jVersionTag, tag);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jobject JNICALL
Java_com_example_engine_php_AbotalalPhpNativeBridge_nativeExecuteRequest(
    JNIEnv *env, jobject thiz,
    jstring jNativeDir,
    jstring jVersionTag,
    jstring jScriptPath,
    jstring jDocumentRoot,
    jstring jPhpIniPath,
    jstring jSessionSavePath,
    jstring jUploadTmpDir,
    jstring jMethod,
    jstring jRequestUri,
    jstring jQueryString,
    jstring jScriptName,
    jstring jHttpHost,
    jint serverPort,
    jstring jRemoteAddr,
    jstring jContentType,
    jstring jCookieHeader,
    jstring jExtraHeaders,
    jbyteArray jPostBody
) {
    (void)thiz;
    const char *dir = get_utf_or_null(env, jNativeDir);
    const char *tag = get_utf_or_null(env, jVersionTag);
    char err[512] = {0};
    LoadedPhpSlot *slot = load_php_slot(dir, tag, err, sizeof(err));
    rel_utf(env, jNativeDir, dir);
    rel_utf(env, jVersionTag, tag);

    if (!slot) {
        return build_error_response(env, 503, err);
    }

    AbotalalPhpRequest req;
    memset(&req, 0, sizeof(req));
    const char *s_script = get_utf_or_null(env, jScriptPath);
    const char *s_doc = get_utf_or_null(env, jDocumentRoot);
    const char *s_ini = get_utf_or_null(env, jPhpIniPath);
    const char *s_sess = get_utf_or_null(env, jSessionSavePath);
    const char *s_tmp = get_utf_or_null(env, jUploadTmpDir);
    const char *s_method = get_utf_or_null(env, jMethod);
    const char *s_uri = get_utf_or_null(env, jRequestUri);
    const char *s_query = get_utf_or_null(env, jQueryString);
    const char *s_sname = get_utf_or_null(env, jScriptName);
    const char *s_host = get_utf_or_null(env, jHttpHost);
    const char *s_remote = get_utf_or_null(env, jRemoteAddr);
    const char *s_ctype = get_utf_or_null(env, jContentType);
    const char *s_cookie = get_utf_or_null(env, jCookieHeader);
    const char *s_extra = get_utf_or_null(env, jExtraHeaders);

    jbyte *post_bytes = NULL;
    jsize post_len = 0;
    if (jPostBody) {
        post_len = (*env)->GetArrayLength(env, jPostBody);
        if (post_len > 0) {
            post_bytes = (*env)->GetByteArrayElements(env, jPostBody, NULL);
        }
    }

    req.script_path = s_script;
    req.document_root = s_doc;
    req.php_ini_path = s_ini;
    req.session_save_path = s_sess;
    req.upload_tmp_dir = s_tmp;
    req.method = s_method;
    req.request_uri = s_uri;
    req.query_string = s_query;
    req.script_name = s_sname;
    req.http_host = s_host;
    req.server_port = (int)serverPort;
    req.remote_addr = s_remote;
    req.content_type = s_ctype;
    req.cookie_header = s_cookie;
    req.extra_headers = s_extra;
    req.post_body = (const char *)post_bytes;
    req.post_body_len = (size_t)post_len;

    AbotalalPhpResponse resp;
    int ok = slot->fn_exec(&req, &resp);

    if (post_bytes) {
        (*env)->ReleaseByteArrayElements(env, jPostBody, post_bytes, JNI_ABORT);
    }
    rel_utf(env, jScriptPath, s_script);
    rel_utf(env, jDocumentRoot, s_doc);
    rel_utf(env, jPhpIniPath, s_ini);
    rel_utf(env, jSessionSavePath, s_sess);
    rel_utf(env, jUploadTmpDir, s_tmp);
    rel_utf(env, jMethod, s_method);
    rel_utf(env, jRequestUri, s_uri);
    rel_utf(env, jQueryString, s_query);
    rel_utf(env, jScriptName, s_sname);
    rel_utf(env, jHttpHost, s_host);
    rel_utf(env, jRemoteAddr, s_remote);
    rel_utf(env, jContentType, s_ctype);
    rel_utf(env, jCookieHeader, s_cookie);
    rel_utf(env, jExtraHeaders, s_extra);

    jclass cls = (*env)->FindClass(env, "com/example/engine/php/PhpNativeHttpResponse");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(ZILjava/lang/String;[BLjava/lang/String;Ljava/lang/String;)V");

    jstring jHeaders = (*env)->NewStringUTF(env, resp.headers_text ? resp.headers_text : "");
    jbyteArray jBody = (*env)->NewByteArray(env, (jsize)resp.body_len);
    if (resp.body && resp.body_len > 0) {
        (*env)->SetByteArrayRegion(env, jBody, 0, (jsize)resp.body_len, (const jbyte *)resp.body);
    }
    jstring jStderr = (*env)->NewStringUTF(env, resp.stderr_log ? resp.stderr_log : "");
    jstring jErr = (*env)->NewStringUTF(env, resp.error_message ? resp.error_message : "");

    jobject jResult = (*env)->NewObject(
        env, cls, ctor,
        (ok && resp.success) ? JNI_TRUE : JNI_FALSE,
        resp.status_code > 0 ? resp.status_code : 200,
        jHeaders, jBody, jStderr, jErr
    );

    slot->fn_free_resp(&resp);
    return jResult;
}
