/*
 * KeepAgent Tier-2 runtime bridge (M0 spike, ADR-0001).
 *
 * Embeds quickjs-ng behind a small JNI surface. An add-on runs in its own
 * JS context; the only host surface it can touch is the global `native`
 * object:
 *
 *   native.log(msg)                    -> Callbacks.onLog(String)
 *   native.onRegisterTool(specJson)    -> Callbacks.onRegisterTool(String)
 *   native.workspacePath()             -> Callbacks.workspacePath(): String
 *   native.settingsGet(ns)             -> Callbacks.settingsGet(String): String?
 *
 * The Kotlin prelude wraps this in a friendlier `ka` API (see
 * JsAddonRuntime in core/host).
 *
 * M0: the engine runs in-process. M1: this whole .so moves to a helper
 * process and the `native` calls cross the IPC boundary instead of JNI —
 * the prelude and the add-on code stay untouched.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "quickjs.h"

#define MAX_HOSTS 8

typedef struct {
    JSRuntime *rt;
    JSContext *ctx;
    JavaVM *vm;
    jobject callbacks; /* global ref */
} Host;

static Host hosts[MAX_HOSTS];
static int host_count = 0;

static Host *find_host(JSRuntime *rt) {
    for (int i = 0; i < host_count; i++) {
        if (hosts[i].rt == rt) return &hosts[i];
    }
    return NULL;
}

/* Call a Java method: void f(String). */
static void call_java_string(Host *h, JNIEnv *env, const char *method, const char *arg) {
    if (!h || !h->vm || !h->callbacks) return;
    JNIEnv *local_env = env;
    if (!local_env && (*h->vm)->GetEnv(h->vm, (void **)&local_env, JNI_VERSION_1_6) != JNI_OK) return;
    jclass cls = (*local_env)->GetObjectClass(local_env, h->callbacks);
    if (!cls) return;
    jmethodID mid = (*local_env)->GetMethodID(local_env, cls, method, "(Ljava/lang/String;)V");
    if (!mid) { (*local_env)->ExceptionClear(local_env); (*local_env)->DeleteLocalRef(local_env, cls); return; }
    jstring jarg = (*local_env)->NewStringUTF(local_env, arg ? arg : "");
    if (jarg) {
        (*local_env)->CallVoidMethod(local_env, h->callbacks, mid, jarg);
        (*local_env)->DeleteLocalRef(local_env, jarg);
    }
    (*local_env)->DeleteLocalRef(local_env, cls);
}

/* Call a Java method: String f(). Returns NULL on error/absent. */
static jstring call_java_noarg_ret(Host *h, JNIEnv *env, const char *method) {
    if (!h || !h->callbacks) return NULL;
    jclass cls = (*env)->GetObjectClass(env, h->callbacks);
    if (!cls) return NULL;
    jmethodID mid = (*env)->GetMethodID(env, cls, method, "()Ljava/lang/String;");
    if (!mid) { (*env)->ExceptionClear(env); (*env)->DeleteLocalRef(env, cls); return NULL; }
    jstring ret = (jstring)(*env)->CallObjectMethod(env, h->callbacks, mid);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); ret = NULL; }
    (*env)->DeleteLocalRef(env, cls);
    return ret;
}

/* Call a Java method: String f(String). Returns NULL on error/absent. */
static jstring call_java_string_ret(Host *h, JNIEnv *env, const char *method, const char *arg) {
    if (!h || !h->callbacks) return NULL;
    jclass cls = (*env)->GetObjectClass(env, h->callbacks);
    if (!cls) return NULL;
    jmethodID mid = (*env)->GetMethodID(env, cls, method, "(Ljava/lang/String;)Ljava/lang/String;");
    if (!mid) { (*env)->ExceptionClear(env); (*env)->DeleteLocalRef(env, cls); return NULL; }
    jstring jarg = (*env)->NewStringUTF(env, arg ? arg : "");
    if (!jarg) { (*env)->DeleteLocalRef(env, cls); return NULL; }
    jstring ret = (jstring)(*env)->CallObjectMethod(env, h->callbacks, mid, jarg);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); ret = NULL; }
    (*env)->DeleteLocalRef(env, jarg);
    (*env)->DeleteLocalRef(env, cls);
    return ret;
}

/* ---- JS-side host functions (the `native` object) ---- */

static JSValue js_native_log(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    const char *msg = JS_ToCString(ctx, argc > 0 ? argv[0] : JS_UNDEFINED);
    Host *h = find_host(JS_GetRuntime(ctx));
    if (h && h->vm) {
        JNIEnv *env = NULL;
        if ((*h->vm)->GetEnv(h->vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
            call_java_string(h, env, "onLog", msg);
        }
    }
    if (msg) JS_FreeCString(ctx, msg);
    return JS_NewBool(ctx, 1);
}

static JSValue js_native_on_register_tool(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    const char *spec = JS_ToCString(ctx, argc > 0 ? argv[0] : JS_UNDEFINED);
    Host *h = find_host(JS_GetRuntime(ctx));
    if (h && h->vm) {
        JNIEnv *env = NULL;
        if ((*h->vm)->GetEnv(h->vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
            call_java_string(h, env, "onRegisterTool", spec);
        }
    }
    if (spec) JS_FreeCString(ctx, spec);
    return JS_NewBool(ctx, 1);
}

static JSValue js_native_workspace_path(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Host *h = find_host(JS_GetRuntime(ctx));
    if (h && h->vm && h->callbacks) {
        JNIEnv *env = NULL;
        if ((*h->vm)->GetEnv(h->vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
            jstring ret = call_java_noarg_ret(h, env, "workspacePath");
            if (ret) {
                const char *s = (*env)->GetStringUTFChars(env, ret, NULL);
                JSValue v = JS_NewString(ctx, s ? s : "");
                if (s) (*env)->ReleaseStringUTFChars(env, ret, s);
                (*env)->DeleteLocalRef(env, ret);
                return v;
            }
        }
    }
    return JS_NewString(ctx, "");
}

static JSValue js_native_settings_get(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Host *h = find_host(JS_GetRuntime(ctx));
    const char *ns = (h && argc > 0) ? JS_ToCString(ctx, argv[0]) : NULL;
    if (h && h->vm && h->callbacks && ns) {
        JNIEnv *env = NULL;
        if ((*h->vm)->GetEnv(h->vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
            jstring ret = call_java_string_ret(h, env, "settingsGet", ns);
            if (ret) {
                const char *s = (*env)->GetStringUTFChars(env, ret, NULL);
                JSValue v = JS_NewString(ctx, s ? s : "");
                if (s) (*env)->ReleaseStringUTFChars(env, ret, s);
                (*env)->DeleteLocalRef(env, ret);
                JS_FreeCString(ctx, ns);
                return v;
            }
        }
    }
    if (ns) JS_FreeCString(ctx, ns);
    return JS_NULL;
}

static JSValue make_native_object(JSContext *ctx) {
    JSValue obj = JS_NewObject(ctx);
    if (JS_IsException(obj)) return obj;
    JS_SetPropertyStr(ctx, obj, "log", JS_NewCFunction(ctx, js_native_log, "log", 1));
    JS_SetPropertyStr(ctx, obj, "onRegisterTool", JS_NewCFunction(ctx, js_native_on_register_tool, "onRegisterTool", 1));
    JS_SetPropertyStr(ctx, obj, "workspacePath", JS_NewCFunction(ctx, js_native_workspace_path, "workspacePath", 0));
    JS_SetPropertyStr(ctx, obj, "settingsGet", JS_NewCFunction(ctx, js_native_settings_get, "settingsGet", 1));
    return obj;
}

/* Throw a RuntimeException carrying the JS exception message. */
static void report_exception(JNIEnv *env, JSContext *ctx) {
    JSValue ex = JS_GetException(ctx);
    const char *msg = JS_ToCString(ctx, ex);
    jclass exc_cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (exc_cls) {
        if (msg) {
            (*env)->ThrowNew(env, exc_cls, msg);
        } else {
            (*env)->ThrowNew(env, exc_cls, "JS exception (no message)");
        }
        (*env)->DeleteLocalRef(env, exc_cls);
    }
    if (msg) JS_FreeCString(ctx, msg);
    JS_FreeValue(ctx, ex);
}

/* ---- JNI exports (io.keepagent.runtime.js.JsHost) ---- */

JNIEXPORT jlong JNICALL
Java_io_keepagent_runtime_js_JsHost_nativeCreate(JNIEnv *env, jobject thiz, jobject callbacks) {
    /* NOTE: `thiz` is the JsHost instance — the Java-side callback surface
     * is the separate `callbacks` argument (JsHost.Callbacks). Storing
     * thiz here (the M0 bug) made every JS->host call silently fail: the
     * bridge looks up onLog/onRegisterTool/... by name on the stored
     * object's class, and JsHost has none of those methods. */
    if (host_count >= MAX_HOSTS) return 0;

    JSRuntime *rt = JS_NewRuntime();
    if (!rt) return 0;
    JSContext *ctx = JS_NewContext(rt);
    if (!ctx) { JS_FreeRuntime(rt); return 0; }
    JS_SetMaxStackSize(rt, 1024 * 1024);

    JSValue native_obj = make_native_object(ctx);
    JSValue global = JS_GetGlobalObject(ctx);
    if (!JS_IsException(native_obj) && !JS_IsException(global)) {
        JS_SetPropertyStr(ctx, global, "native", native_obj);
    } else {
        JS_FreeValue(ctx, native_obj);
    }
    JS_FreeValue(ctx, global);

    Host *h = &hosts[host_count++];
    h->rt = rt;
    h->ctx = ctx;
    h->vm = NULL;
    h->callbacks = NULL;
    if ((*env)->GetJavaVM(env, &h->vm) != JNI_OK) h->vm = NULL;
    h->callbacks = (*env)->NewGlobalRef(env, callbacks);
    return (jlong)(intptr_t)h;
}

JNIEXPORT void JNICALL
Java_io_keepagent_runtime_js_JsHost_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    Host *h = (Host *)(intptr_t)handle;
    if (!h) return;
    for (int i = 0; i < host_count; i++) {
        if (&hosts[i] == h) {
            hosts[i] = hosts[--host_count];
            break;
        }
    }
    if (h->callbacks) (*env)->DeleteGlobalRef(env, h->callbacks);
    if (h->ctx) JS_FreeContext(h->ctx);
    if (h->rt) JS_FreeRuntime(h->rt);
    h->rt = NULL;
    h->ctx = NULL;
    h->vm = NULL;
    h->callbacks = NULL;
}

JNIEXPORT jstring JNICALL
Java_io_keepagent_runtime_js_JsHost_nativeEval(JNIEnv *env, jobject thiz, jlong handle, jstring source, jstring filename) {
    Host *h = (Host *)(intptr_t)handle;
    if (!h || !h->ctx) return NULL;

    const char *src = (*env)->GetStringUTFChars(env, source, NULL);
    const char *fn = filename ? (*env)->GetStringUTFChars(env, filename, NULL) : NULL;
    size_t len = src ? strlen(src) : 0;

    JSValue result = JS_Eval(h->ctx, src ? src : "", len, fn ? fn : "eval.js", JS_EVAL_TYPE_GLOBAL);

    jstring ret = NULL;
    if (JS_IsException(result)) {
        report_exception(env, h->ctx);
    } else if (JS_IsString(result)) {
        const char *s = JS_ToCString(h->ctx, result);
        ret = (*env)->NewStringUTF(env, s ? s : "");
        if (s) JS_FreeCString(h->ctx, s);
    }
    JS_FreeValue(h->ctx, result);

    if (src) (*env)->ReleaseStringUTFChars(env, source, src);
    if (fn && filename) (*env)->ReleaseStringUTFChars(env, filename, fn);
    return ret;
}

JNIEXPORT jstring JNICALL
Java_io_keepagent_runtime_js_JsHost_nativeCallTwo(JNIEnv *env, jobject thiz, jlong handle, jstring func_name, jstring arg1, jstring arg2) {
    Host *h = (Host *)(intptr_t)handle;
    if (!h || !h->ctx) return NULL;

    const char *fn = (*env)->GetStringUTFChars(env, func_name, NULL);
    const char *a1 = (*env)->GetStringUTFChars(env, arg1, NULL);
    const char *a2 = arg2 ? (*env)->GetStringUTFChars(env, arg2, NULL) : NULL;

    JSValue global = JS_GetGlobalObject(h->ctx);
    JSValue f = JS_GetPropertyStr(h->ctx, global, fn ? fn : "");
    JS_FreeValue(h->ctx, global);

    jstring ret = NULL;
    if (JS_IsFunction(h->ctx, f)) {
        JSValue argv[2];
        argv[0] = JS_NewString(h->ctx, a1 ? a1 : "");
        argv[1] = JS_NewString(h->ctx, a2 ? a2 : "");
        JSValue result = JS_Call(h->ctx, f, JS_UNDEFINED, 2, argv);
        JS_FreeValue(h->ctx, argv[0]);
        JS_FreeValue(h->ctx, argv[1]);
        if (JS_IsException(result)) {
            report_exception(env, h->ctx);
        } else if (JS_IsString(result)) {
            const char *s = JS_ToCString(h->ctx, result);
            ret = (*env)->NewStringUTF(env, s ? s : "");
            if (s) JS_FreeCString(h->ctx, s);
        }
        JS_FreeValue(h->ctx, result);
    } else {
        jclass exc_cls = (*env)->FindClass(env, "java/lang/RuntimeException");
        if (exc_cls) {
            (*env)->ThrowNew(env, exc_cls, "no such global function");
            (*env)->DeleteLocalRef(env, exc_cls);
        }
    }
    JS_FreeValue(h->ctx, f);

    if (fn) (*env)->ReleaseStringUTFChars(env, func_name, fn);
    if (a1) (*env)->ReleaseStringUTFChars(env, arg1, a1);
    if (a2 && arg2) (*env)->ReleaseStringUTFChars(env, arg2, a2);
    return ret;
}
