import org.gradle.api.Project

object ProjectConfig {
    const val APP_NAME = "SakuraClash"
    const val PACKAGE_NAME = "top.yukonga.mishka"
    const val APPLICATION_ID = "com.linqi.sakuraclash"

    /**
     * 发布版本。**两个值都手写，不跟 git 提交数联动**：
     * versionCode 必须只增不减，而提交数会在 rebase / squash 后回退，
     * 一旦回退到低于已发布版本，系统会拒绝覆盖安装。
     *
     * 发版规则：构建成功后才把这里 +1 并提交；试运行、失败构建不占用版本号。
     */
    const val VERSION_NAME = "1.1.1"
    const val VERSION_CODE = 267

    object Android {
        const val TARGET_SDK = 37
        const val MIN_SDK = 31
        const val COMPILE_SDK = 37
        const val COMPILE_SDK_MINOR = 2
    }
}

/**
 * 兼容旧调用点。已改为返回手写常量，不再读 `git rev-list --count`——
 * 提交数会随 rebase 变化，无法作为单调递增的 versionCode。
 */
@Suppress("UNUSED_PARAMETER")
fun Project.getGitVersionCode(): Int = ProjectConfig.VERSION_CODE
