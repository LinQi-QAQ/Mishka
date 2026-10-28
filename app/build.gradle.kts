import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.baselineProfile)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
    id("mishka.build-logic")
}

dependencies {
    baselineProfile(projects.baselineprofile)
    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)
    implementation(libs.bundles.androidx.lifecycle)
    implementation(libs.androidx.navigationevent)
    implementation(libs.bundles.room)
    implementation(libs.bundles.kotlinx)
    implementation(libs.bundles.ktor)
    implementation(libs.bundles.miuix)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.material.icons.extended)
    implementation(libs.hiddenapibypass)
    implementation(libs.quickie.bundled)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.kaml)
    implementation(libs.scripta.editor)
    ksp(libs.androidx.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}
val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val gitVersionCode = getGitVersionCode()

val properties = Properties()
runCatching { project.rootProject.file("local.properties").inputStream().use { properties.load(it) } }
val keystorePath: String? = (properties.getProperty("KEYSTORE_PATH") ?: System.getenv("KEYSTORE_PATH"))?.takeIf { it.isNotBlank() }
val keystorePwd: String? = properties.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
val alias: String? = properties.getProperty("KEY_ALIAS") ?: System.getenv("KEY_ALIAS")
val pwd: String? = properties.getProperty("KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")
val keystoreFile = keystorePath?.let { path ->
    file(path).takeIf { it.exists() } ?: project.rootProject.file(path).takeIf { it.exists() }
}

@Suppress("UnstableApiUsage")
android {
    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePwd
                keyAlias = alias
                keyPassword = pwd
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            optimization.enable = true
            vcsInfo.include = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileSdk {
        version = release(ProjectConfig.Android.COMPILE_SDK) {
            minorApiLevel = ProjectConfig.Android.COMPILE_SDK_MINOR
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    defaultConfig {
        applicationId = ProjectConfig.APPLICATION_ID
        minSdk = ProjectConfig.Android.MIN_SDK
        targetSdk = ProjectConfig.Android.TARGET_SDK
        versionName = ProjectConfig.VERSION_NAME
        versionCode = gitVersionCode
    }
    // beta 签名：密钥信息放在仓库外的 beta.properties（不进仓库），
    // 每台机器自建 keystore 后填 beta.keystore.* 四项即可；同签名 beta 包可互相覆盖更新
    val betaProps = java.util.Properties().apply {
        val propsFile = rootProject.file("beta.properties")
        if (propsFile.exists()) propsFile.inputStream().use { load(it) }
    }
    signingConfigs {
        create("betaLocal") {
            storeFile = file(betaProps.getProperty("beta.keystore.path", "beta.keystore"))
            storePassword = betaProps.getProperty("beta.keystore.storePassword", "")
            keyAlias = betaProps.getProperty("beta.keystore.keyAlias", "beta")
            keyPassword = betaProps.getProperty("beta.keystore.keyPassword", "")
        }
    }
    // 测试渠道：beta 改包名与稳定版共存，手机双开测试用
    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            dimension = "channel"
        }
        create("beta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            // 没有 beta.properties（例如 CI）时不绑 betaLocal 签名，debug/release 回退到默认签名配置，
            // 避免缺 keystore 导致整包构建失败；本地有 beta.properties 时仍用 beta 签名
            if (rootProject.file("beta.properties").exists()) {
                signingConfig = signingConfigs.getByName("betaLocal")
            }
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    namespace = ProjectConfig.PACKAGE_NAME
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
    }
    splits {
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            include(*supportedAbis.toTypedArray())
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("app/compose_compiler_config.conf")
    )
}

baselineProfile {
    automaticGenerationDuringBuild = false
}

androidComponents {
    finalizeDsl { ext ->
        // AGP 9: 关闭 nonMinifiedRelease 继承自 release 的 optimization.enable
        ext.buildTypes.findByName("nonMinifiedRelease")?.optimization?.enable = false
        if (keystoreFile == null) {
            val debugSigning = ext.signingConfigs.getByName("debug")
            listOf("nonMinifiedRelease", "benchmarkRelease").forEach { name ->
                ext.buildTypes.findByName(name)?.signingConfig = debugSigning
            }
        }
    }
}

abstract class DownloadGeoFilesTask : DefaultTask() {
    @get:Input
    abstract val sources: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        sources.get().forEach { (downloadUrl, fileName) ->
            val target = File(dir, fileName)
            val partial = File(dir, "$fileName.part")
            val connection = (URI(downloadUrl).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
            }
            try {
                val status = connection.responseCode
                check(status == HttpURLConnection.HTTP_OK) { "$downloadUrl responded HTTP $status" }
                connection.inputStream.use {
                    Files.copy(it, partial.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                connection.disconnect()
            }
            // 校验文件体积下限，防止 404/限流 HTML 伪装成数据文件落盘
            val size = partial.length()
            if (size < MIN_FILE_BYTES) {
                partial.delete()
                error("$fileName is only $size bytes, expected a data file (bad URL or rate limited?)")
            }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logger.lifecycle("$fileName downloaded to $target ($size bytes)")
        }
    }

    private companion object {
        const val HTTP_TIMEOUT_MS = 30_000
        const val MIN_FILE_BYTES = 512 * 1024L
    }
}

val downloadGeoFiles = tasks.register<DownloadGeoFilesTask>("downloadGeoFiles") {
    description = "Download the prebuilt GeoIP/GeoSite data files into the assets source set"
    sources.set(
        mapOf(
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb" to "geoip.metadb",
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat" to "geosite.dat",
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb" to "ASN.mmdb",
        )
    )
    outputDir.set(layout.projectDirectory.dir("src/main/assets"))
    // 上游 latest tag 原地更新时 URL 不变，强制每次显式调用均重新拉取
    outputs.upToDateWhen { false }
}

// 避免 outputDir 与 mergeAssets 输入重叠导致未声明任务依赖冲突
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    mustRunAfter(downloadGeoFiles)
}

val mihomoSubmoduleDir = rootProject.layout.projectDirectory.dir("mihomo")
val mishkaCoreSourceDir = rootProject.layout.projectDirectory.dir("app/src/main/native/mishka_core")
val mihomoBuildTags = listOf("cmfa", "mishka", "with_gvisor")
val mihomoVersion = providers.gradleProperty("mihomo.version").orElse("dev").get()
val mihomoVersionPath = "github.com/metacubex/mihomo/constant.Version"
val ndkDirectoryProvider = androidComponents.sdkComponents.ndkDirectory

// 编译 libmihomo.so（JNI + runtime wrapper 共用）
val buildMihomoTasks = supportedAbis.map { abi ->
    val suffix = abi.replace("-", "_")
    tasks.register<GoBuildTask>("buildMihomo_$suffix") {
        group = "mihomo"
        description = "Build unified mihomo + JNI c-shared library (libmihomo.so) for $abi"
        goSourceDir.set(mishkaCoreSourceDir)
        // 追踪 replace 指向的 submodule 源码；反向过滤以覆盖 go:embed 内嵌资源
        replacedModuleSources.from(
            mihomoSubmoduleDir.asFileTree.matching {
                exclude(".git", ".github/**", "docs/**", "test/**", "**/*_test.go")
            }
        )
        this.abi.set(abi)
        versionName.set(mihomoVersion)
        buildTags.set(mihomoBuildTags)
        cgoEnabled.set(true)
        buildMode.set(GoBuildTask.BuildMode.CShared)
        ndkDirectory.set(ndkDirectoryProvider)
        minSdk.set(ProjectConfig.Android.MIN_SDK)
        moduleVersionPath.set(mihomoVersionPath)
        outputFile.set(layout.projectDirectory.file("src/main/jniLibs/$abi/libmihomo.so"))
        headerFile.set(layout.projectDirectory.file("src/main/jniLibs/$abi/libmihomo.h"))
    }
}

val buildMihomo = tasks.register("buildMihomo") {
    group = "mihomo"
    description = "Build unified mihomo library for all supported ABIs"
    dependsOn(buildMihomoTasks)
}

// 打 mihomo 补丁：TLS 分片 patch 不在子模块上游里，以文件形式放在 patches/，
// 构建前自动打上（已打过则跳过），保证 clone 下来就能直接编出带分片功能的定制版
val applyMihomoPatches = tasks.register("applyMihomoPatches") {
    group = "mihomo"
    description = "Apply vendored mihomo patches if not already applied"
    doLast {
        val patchFile = rootProject.layout.projectDirectory.file("patches/mihomo-tls-fragment.patch").asFile
        val mihomoDir = mihomoSubmoduleDir.asFile
        fun gitApplyCheck(reverse: Boolean): Boolean {
            val args = mutableListOf("apply", "--check")
            if (reverse) args.add("--reverse")
            args.add(patchFile.absolutePath)
            val result = project.exec {
                commandLine("git", *args.toTypedArray())
                workingDir(mihomoDir)
                isIgnoreExitValue = true
            }
            return result.exitValue == 0
        }
        if (gitApplyCheck(true)) {
            logger.lifecycle("mihomo patch already applied, skipping")
        } else {
            if (!gitApplyCheck(false)) {
                throw GradleException("mihomo patch does not apply cleanly; check patches/mihomo-tls-fragment.patch against the mihomo submodule")
            }
            project.exec {
                commandLine("git", "apply", patchFile.absolutePath)
                workingDir(mihomoDir)
            }
            logger.lifecycle("mihomo patch applied")
        }
    }
}
buildMihomoTasks.forEach { it.configure { dependsOn(applyMihomoPatches) } }

tasks.named("preBuild") {
    dependsOn(buildMihomo)
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(buildMihomo)
    }
}

androidComponents {
    // 剥除 release 冗余打包资源（不影响 services/**）
    onVariants(selector().withBuildType("release")) {
        it.packaging.resources.excludes.add("**")
    }
}

base {
    archivesName.set(
        "${ProjectConfig.APP_NAME}-v${ProjectConfig.VERSION_NAME}($gitVersionCode)",
    )
}
