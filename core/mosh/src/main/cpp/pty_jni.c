#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <signal.h>

/**
 * Forks a child process with a PTY.
 *
 * @param path   Executable path (mosh-client binary)
 * @param argv   Command-line arguments
 * @param env    Environment variables (KEY=VALUE)
 * @param rows   Initial terminal rows
 * @param cols   Initial terminal columns
 * @return int[2] = {masterFd, childPid}, or null on failure
 */
JNIEXPORT jintArray JNICALL
Java_sh_haven_core_mosh_PtyHelper_nativeForkPty(
    JNIEnv *env, jobject thiz,
    jstring path, jobjectArray argv, jobjectArray jenv,
    jint rows, jint cols)
{
    int master;
    struct winsize ws = { .ws_row = rows, .ws_col = cols };
    pid_t pid = forkpty(&master, NULL, NULL, &ws);

    if (pid < 0) return NULL;

    if (pid == 0) {
        /* Child — build argv and env, then exec */
        int argc = (*env)->GetArrayLength(env, argv);
        char **c_argv = calloc(argc + 1, sizeof(char *));
        for (int i = 0; i < argc; i++) {
            jstring s = (*env)->GetObjectArrayElement(env, argv, i);
            const char *u = (*env)->GetStringUTFChars(env, s, NULL);
            c_argv[i] = strdup(u);
            (*env)->ReleaseStringUTFChars(env, s, u);
        }
        c_argv[argc] = NULL;

        int envc = (*env)->GetArrayLength(env, jenv);
        for (int i = 0; i < envc; i++) {
            jstring s = (*env)->GetObjectArrayElement(env, jenv, i);
            const char *u = (*env)->GetStringUTFChars(env, s, NULL);
            putenv(strdup(u));
            (*env)->ReleaseStringUTFChars(env, s, u);
        }

        const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
        execv(c_path, c_argv);
        _exit(127); /* exec failed */
    }

    /* Parent — return [masterFd, childPid] */
    jintArray result = (*env)->NewIntArray(env, 2);
    jint buf[2] = { master, pid };
    (*env)->SetIntArrayRegion(env, result, 0, 2, buf);
    return result;
}

/**
 * Send TIOCSWINSZ to resize the PTY.
 */
JNIEXPORT void JNICALL
Java_sh_haven_core_mosh_PtyHelper_nativeResize(
    JNIEnv *env, jobject thiz,
    jint masterFd, jint rows, jint cols)
{
    struct winsize ws = { .ws_row = rows, .ws_col = cols };
    ioctl(masterFd, TIOCSWINSZ, &ws);
}
