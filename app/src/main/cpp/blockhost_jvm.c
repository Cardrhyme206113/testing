#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TAG "BlockHostJVM"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

typedef jint JLI_Launch_func(
        int argc, char **argv,
        int jargc, const char **jargv,
        int appclassc, const char **appclassv,
        const char *fullversion,
        const char *dotversion,
        const char *pname,
        const char *lname,
        jboolean javaargs,
        jboolean cpwildcard,
        jboolean javaw,
        jint ergo);

typedef void (*android_update_LD_LIBRARY_PATH_t)(const char *);

static char **to_c_array(JNIEnv *env, jobjectArray values, int *length_out) {
    int length = values == NULL ? 0 : (*env)->GetArrayLength(env, values);
    char **result = calloc((size_t) length + 1, sizeof(char *));
    if (result == NULL) return NULL;
    for (int i = 0; i < length; i++) {
        jstring value = (jstring) (*env)->GetObjectArrayElement(env, values, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        result[i] = strdup(utf == NULL ? "" : utf);
        if (utf != NULL) (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }
    if (length_out != NULL) *length_out = length;
    return result;
}

static void free_c_array(char **values, int length) {
    if (values == NULL) return;
    for (int i = 0; i < length; i++) free(values[i]);
    free(values);
}

static char *copy_jstring(JNIEnv *env, jstring value) {
    if (value == NULL) return NULL;
    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    char *copy = utf == NULL ? NULL : strdup(utf);
    if (utf != NULL) (*env)->ReleaseStringUTFChars(env, value, utf);
    return copy;
}

static void update_library_path(const char *library_path) {
    if (library_path == NULL) return;
    setenv("LD_LIBRARY_PATH", library_path, 1);

    void *libdl = dlopen("libdl.so", RTLD_NOW | RTLD_LOCAL);
    if (libdl == NULL) return;
    android_update_LD_LIBRARY_PATH_t update =
            (android_update_LD_LIBRARY_PATH_t) dlsym(libdl, "android_update_LD_LIBRARY_PATH");
    if (update == NULL) {
        update = (android_update_LD_LIBRARY_PATH_t)
                dlsym(libdl, "__loader_android_update_LD_LIBRARY_PATH");
    }
    if (update != NULL) update(library_path);
    dlclose(libdl);
}

static void preload_libraries(JNIEnv *env, jobjectArray paths) {
    if (paths == NULL) return;
    int length = (*env)->GetArrayLength(env, paths);
    for (int i = 0; i < length; i++) {
        jstring path_string = (jstring) (*env)->GetObjectArrayElement(env, paths, i);
        char *path = copy_jstring(env, path_string);
        (*env)->DeleteLocalRef(env, path_string);
        if (path == NULL || path[0] == '\0') {
            free(path);
            continue;
        }
        void *handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (handle == NULL) {
            LOGE("Preload failed for %s: %s", path, dlerror());
        }
        free(path);
    }
}

JNIEXPORT jint JNICALL
Java_com_example_blockhost_NativeJvmRunner_nativeLaunch(
        JNIEnv *env,
        jclass clazz,
        jstring libjli_path_string,
        jstring library_path_string,
        jstring java_home_string,
        jstring work_dir_string,
        jobjectArray preload_paths,
        jobjectArray argument_array,
        jint stdin_fd,
        jint stdout_fd) {
    (void) clazz;

    char *libjli_path = copy_jstring(env, libjli_path_string);
    char *library_path = copy_jstring(env, library_path_string);
    char *java_home = copy_jstring(env, java_home_string);
    char *work_dir = copy_jstring(env, work_dir_string);
    if (libjli_path == NULL || java_home == NULL || work_dir == NULL) {
        free(libjli_path);
        free(library_path);
        free(java_home);
        free(work_dir);
        return -100;
    }

    int saved_stdin = dup(STDIN_FILENO);
    int saved_stdout = dup(STDOUT_FILENO);
    int saved_stderr = dup(STDERR_FILENO);
    if (stdin_fd >= 0) dup2(stdin_fd, STDIN_FILENO);
    if (stdout_fd >= 0) {
        dup2(stdout_fd, STDOUT_FILENO);
        dup2(stdout_fd, STDERR_FILENO);
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    chdir(work_dir);
    setenv("JAVA_HOME", java_home, 1);
    setenv("HOME", work_dir, 1);
    setenv("TMPDIR", work_dir, 1);
    setenv("PWD", work_dir, 1);
    setenv("USER", "root", 1);
    setenv("LOGNAME", "root", 1);
    setenv("LANG", "C.UTF-8", 1);
    setenv("LC_ALL", "C.UTF-8", 1);

    size_t path_size = strlen(java_home) + 32;
    char *path_value = calloc(path_size, 1);
    if (path_value != NULL) {
        snprintf(path_value, path_size, "%s/bin:/system/bin", java_home);
        setenv("PATH", path_value, 1);
        free(path_value);
    }

    update_library_path(library_path);
    preload_libraries(env, preload_paths);

    void *libjli = dlopen(libjli_path, RTLD_NOW | RTLD_GLOBAL);
    if (libjli == NULL) {
        fprintf(stderr, "[BlockHost/JVM] dlopen libjli failed: %s\n", dlerror());
        if (saved_stdin >= 0) { dup2(saved_stdin, STDIN_FILENO); close(saved_stdin); }
        if (saved_stdout >= 0) { dup2(saved_stdout, STDOUT_FILENO); close(saved_stdout); }
        if (saved_stderr >= 0) { dup2(saved_stderr, STDERR_FILENO); close(saved_stderr); }
        free(libjli_path);
        free(library_path);
        free(java_home);
        free(work_dir);
        return -101;
    }

    JLI_Launch_func *launch = (JLI_Launch_func *) dlsym(libjli, "JLI_Launch");
    if (launch == NULL) {
        fprintf(stderr, "[BlockHost/JVM] JLI_Launch not found: %s\n", dlerror());
        if (saved_stdin >= 0) { dup2(saved_stdin, STDIN_FILENO); close(saved_stdin); }
        if (saved_stdout >= 0) { dup2(saved_stdout, STDOUT_FILENO); close(saved_stdout); }
        if (saved_stderr >= 0) { dup2(saved_stderr, STDERR_FILENO); close(saved_stderr); }
        free(libjli_path);
        free(library_path);
        free(java_home);
        free(work_dir);
        return -102;
    }

    int argc = 0;
    char **argv = to_c_array(env, argument_array, &argc);
    if (argv == NULL || argc == 0) {
        free_c_array(argv, argc);
        if (saved_stdin >= 0) { dup2(saved_stdin, STDIN_FILENO); close(saved_stdin); }
        if (saved_stdout >= 0) { dup2(saved_stdout, STDOUT_FILENO); close(saved_stdout); }
        if (saved_stderr >= 0) { dup2(saved_stderr, STDERR_FILENO); close(saved_stderr); }
        free(libjli_path);
        free(library_path);
        free(java_home);
        free(work_dir);
        return -103;
    }

    LOGI("Launching mobile JVM from %s", java_home);
    jint result = launch(
            argc, argv,
            0, NULL,
            0, NULL,
            "21", "21",
            argv[0], argv[0],
            JNI_FALSE, JNI_TRUE, JNI_FALSE, 0);

    free_c_array(argv, argc);
    if (saved_stdin >= 0) { dup2(saved_stdin, STDIN_FILENO); close(saved_stdin); }
    if (saved_stdout >= 0) { dup2(saved_stdout, STDOUT_FILENO); close(saved_stdout); }
    if (saved_stderr >= 0) { dup2(saved_stderr, STDERR_FILENO); close(saved_stderr); }
    free(libjli_path);
    free(library_path);
    free(java_home);
    free(work_dir);
    return result;
}
