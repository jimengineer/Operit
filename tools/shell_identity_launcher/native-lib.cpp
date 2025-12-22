// 通用 shell 身份 launcher：
// 以 root 身份运行本程序，尝试切换到 shell 的 SELinux 域，
// 然后切换到 shell 用户 (uid/gid 2000)，最后直接 execvp 目标命令。

#include <unistd.h>
#include <sys/types.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// 从 Shizuku 源码简化移植的 SELinux helper
typedef int getcon_t(char **context);
typedef int setcon_t(const char *ctx);
typedef int setfilecon_t(const char *path, const char *ctx);
typedef int selinux_check_access_t(const char *scon, const char *tcon,
                                   const char *tclass, const char *perm,
                                   void *auditdata);
typedef void freecon_t(char *con);

namespace se {

    static int __getcon(char **context) {
        int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
        if (fd < 0)
            return fd;

        char *buf;
        size_t size;
        int errno_hold;
        ssize_t ret;

        size = (size_t)sysconf(_SC_PAGE_SIZE);
        buf = (char *)malloc(size);
        if (!buf) {
            ret = -1;
            goto out;
        }
        memset(buf, 0, size);

        do {
            ret = read(fd, buf, size - 1);
        } while (ret < 0 && errno == EINTR);
        if (ret < 0)
            goto out2;

        if (ret == 0) {
            *context = nullptr;
            goto out2;
        }

        *context = strdup(buf);
        if (!(*context)) {
            ret = -1;
            goto out2;
        }
        ret = 0;
    out2:
        free(buf);
    out:
        errno_hold = errno;
        close(fd);
        errno = errno_hold;
        return (int)ret;
    }

    static int __setcon(const char *ctx) {
        int fd = open("/proc/self/attr/current", O_WRONLY | O_CLOEXEC);
        if (fd < 0)
            return fd;
        size_t len = strlen(ctx) + 1;
        ssize_t rc = write(fd, ctx, len);
        close(fd);
        return rc != (ssize_t)len;
    }

    static int __setfilecon(const char *path, const char *ctx) {
        int rc = (int)syscall(__NR_setxattr, path,
                              "security.selinux", ctx,
                              strlen(ctx) + 1, 0);
        if (rc) {
            errno = -rc;
            return -1;
        }
        return 0;
    }

    static int __selinux_check_access(const char *scon, const char *tcon,
                                      const char *tclass, const char *perm,
                                      void *auditdata) {
        (void)scon;
        (void)tcon;
        (void)tclass;
        (void)perm;
        (void)auditdata;
        return 0;
    }

    static void __freecon(char *con) {
        free(con);
    }

    getcon_t *getcon = __getcon;
    setcon_t *setcon = __setcon;
    setfilecon_t *setfilecon = __setfilecon;
    selinux_check_access_t *selinux_check_access = __selinux_check_access;
    freecon_t *freecon = __freecon;

    void init() {
        if (access("/system/lib/libselinux.so", F_OK) != 0 &&
            access("/system/lib64/libselinux.so", F_OK) != 0)
            return;

        void *handle = dlopen("libselinux.so", RTLD_LAZY | RTLD_LOCAL);
        if (handle == nullptr)
            return;

        getcon = (getcon_t *)dlsym(handle, "getcon");
        setcon = (setcon_t *)dlsym(handle, "setcon");
        setfilecon = (setfilecon_t *)dlsym(handle, "setfilecon");
        selinux_check_access = (selinux_check_access_t *)dlsym(handle, "selinux_check_access");
        freecon = (freecon_t *)dlsym(handle, "freecon");
    }
}

int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <command ...>\n", argv[0]);
        return 1;
    }

    uid_t uid = getuid();
    if (uid != 0 && uid != 2000) {
        // 与 Shizuku starter 一致：要求以 root/su 身份运行，由 su/Magisk 自己决定 SELinux 域。
        fprintf(stderr, "[operit_shell_exec] must run as root (uid 0) or shell (uid 2000), current uid=%d\n", (int)uid);
        return 1;
    }

    // 对齐 Shizuku 的思路：在 root(0) 进程中初始化 SELinux helper，打印当前上下文用于调试，
    // 然后将 uid/gid 降级为 shell 用户 (2000)，让后续通过 FakeContext(PACKAGE_NAME=com.android.shell)
    // 调用系统服务时，DisplayManagerService 中的 "packageName must match the calling uid" 校验能够通过。
    se::init();
    char *cur_ctx = nullptr;
    se::getcon(&cur_ctx);
    if (cur_ctx) {
        fprintf(stderr, "[operit_shell_exec] current selinux context (before drop): %s\n", cur_ctx);
        se::freecon(cur_ctx);
    }

    // 将进程身份从 root 降级为 shell 用户/组 (uid/gid 2000)。
    // 这与 Shower 端 FakeContext.PACKAGE_NAME = "com.android.shell" 相匹配，
    // 使得 createVirtualDisplay 这类需要 "packageName 属于 callingUid" 的检查能够通过。
    if (setgid(2000) != 0) {
        perror("setgid(2000) failed");
        // 即使降级失败，也继续尝试后续步骤，让错误信息在日志中可见。
    }
    if (setuid(2000) != 0) {
        perror("setuid(2000) failed");
    }

    uid_t final_uid = getuid();
    gid_t final_gid = getgid();
    if (final_uid != 2000 || final_gid != 2000) {
        fprintf(stderr, "[operit_shell_exec] failed to switch to shell identity (uid=2000,gid=2000); final uid=%d gid=%d\n",
                (int)final_uid, (int)final_gid);
        return 1;
    }

    fprintf(stderr, "[operit_shell_exec] running as uid=%d gid=%d\n", (int)final_uid, (int)final_gid);

    // 解析前缀形式的环境变量赋值（例如 CLASSPATH=...），然后直接 execvp 目标程序
    int cmd_index = 1;
    for (; cmd_index < argc; ++cmd_index) {
        char *eq = strchr(argv[cmd_index], '=');
        if (eq == NULL) {
            // 第一个不包含 '=' 的参数视为要执行的程序名
            break;
        }

        // 拆分 KEY=VALUE 并设置环境变量
        *eq = '\0';
        const char *key = argv[cmd_index];
        const char *value = eq + 1;
        if (setenv(key, value, 1) != 0) {
            perror("setenv");
            return 1;
        }
    }

    if (cmd_index >= argc) {
        fprintf(stderr, "[operit_shell_exec] no command to exec after env vars\n");
        return 1;
    }

    // 直接执行 argv[cmd_index]，其后参数保持不变
    execvp(argv[cmd_index], &argv[cmd_index]);

    // execvp 返回说明失败
    perror("execvp");
    return 1;
}