package com.ai.assistance.operit.core.tools.system

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
import com.ai.assistance.operit.core.tools.system.shell.RootShellExecutor
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences

/** 向后兼容的Shell命令执行工具类 通过权限级别委托到相应的Shell执行器 */
class AndroidShellExecutor {
    companion object {
        private const val TAG = "AndroidShellExecutor"
        private var context: Context? = null

        /**
         * 设置全局上下文引用
         * @param appContext 应用上下文
         */
        fun setContext(appContext: Context) {
            context = appContext.applicationContext
        }
        /**
         * 封装执行命令的函数
         * @param command 要执行的命令
         * @return 命令执行结果
         */
        suspend fun executeShellCommand(command: String): CommandResult {
            return executeShellCommand(command, null)
        }

        suspend fun executeShellCommand(command: String, identityOverride: ShellIdentity?): CommandResult {
            val ctx = context ?: return CommandResult(false, "", "Context not initialized")

            if (identityOverride == ShellIdentity.APP) {
                val executor = ShellExecutorFactory.getExecutor(ctx, AndroidPermissionLevel.STANDARD)
                val status = executor.hasPermission()
                if (!executor.isAvailable() || !status.granted) {
                    return CommandResult(false, "", "STANDARD shell executor not available: ${status.reason}", -1)
                }
                val result = executor.executeCommand(command)
                return CommandResult(result.success, result.stdout, result.stderr, result.exitCode)
            }

            if (identityOverride == ShellIdentity.ROOT) {
                val executor = ShellExecutorFactory.getExecutor(ctx, AndroidPermissionLevel.ROOT)
                val status = executor.hasPermission()
                if (!executor.isAvailable() || !status.granted) {
                    return CommandResult(false, "", "ROOT shell executor not available: ${status.reason}", -1)
                }
                val result = executor.executeCommand(command)
                return CommandResult(result.success, result.stdout, result.stderr, result.exitCode)
            }

            if (identityOverride == ShellIdentity.SHELL) {
                val debuggerExecutor = ShellExecutorFactory.getExecutor(ctx, AndroidPermissionLevel.DEBUGGER)
                val debuggerStatus = debuggerExecutor.hasPermission()
                if (debuggerExecutor.isAvailable() && debuggerStatus.granted) {
                    val result = debuggerExecutor.executeCommand(command)
                    return CommandResult(result.success, result.stdout, result.stderr, result.exitCode)
                }

                val rootExecutor = ShellExecutorFactory.getExecutor(ctx, AndroidPermissionLevel.ROOT)
                if (rootExecutor is RootShellExecutor) {
                    val rootStatus = rootExecutor.hasPermission()
                    if (rootExecutor.isAvailable() && rootStatus.granted) {
                        val result = rootExecutor.executeCommandAsShellUser(command)
                        return CommandResult(result.success, result.stdout, result.stderr, result.exitCode)
                    }
                }

                return CommandResult(
                    false,
                    "",
                    "No suitable shell identity executor available (DEBUGGER/ROOT shell)",
                    -1
                )
            }

            val preferredLevel = androidPermissionPreferences.getPreferredPermissionLevel()
            AppLogger.d(TAG, "Using preferred permission level: $preferredLevel")

            val actualLevel = preferredLevel ?: AndroidPermissionLevel.STANDARD

            val preferredExecutor = ShellExecutorFactory.getExecutor(ctx, actualLevel)
            val permStatus = preferredExecutor.hasPermission()

            if (preferredExecutor.isAvailable() && permStatus.granted) {
                val result = preferredExecutor.executeCommand(command)
                return CommandResult(result.success, result.stdout, result.stderr, result.exitCode)
            }

            AppLogger.d(
                TAG,
                "Preferred executor not available (${permStatus.reason}), trying highest available executor"
            )

            val (executor, executorStatus) = ShellExecutorFactory.getHighestAvailableExecutor(ctx)

            if (!executorStatus.granted) {
                return CommandResult(
                    false,
                    "",
                    "No suitable shell executor available: ${executorStatus.reason}",
                    -1
                )
            }

            AppLogger.d(TAG, "Using executor with permission level: ${executor.getPermissionLevel()}")

            val result = executor.executeCommand(command)
            return CommandResult(result.success, result.stdout, result.stderr, result.exitCode)
        }
    }

    /** 命令执行结果数据类 */
    data class CommandResult(
            val success: Boolean,
            val stdout: String,
            val stderr: String = "",
            val exitCode: Int = -1
    )
}

enum class ShellIdentity {
    DEFAULT,
    APP,
    ROOT,
    SHELL
}
