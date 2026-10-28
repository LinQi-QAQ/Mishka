# 构建 Sakura Clash（定制版）

当前定制源码在 `custom-rules-ui` 分支，拉下来就能直接构建，不用再拼分享包。

## 拉取

```sh
git clone -b custom-rules-ui --recursive https://github.com/LinQi-QAQ/Mishka.git
```

必须带 `--recursive`：`mihomo/` 是子模块，缺了它编不出核心。已拉过的旧 clone 补一句：

```sh
git submodule update --init --recursive
```

## 环境

JDK 21、Go 1.25.3、Android SDK（platform 37）+ NDK 28.2.13676358。
`local.properties` 里写 `sdk.dir` 指向 SDK 即可，别的构建配置都在仓库里。

## beta 包签名（每台机器做一次）

自建一个 keystore，在仓库根目录新建 `beta.properties`（不进仓库）：

```properties
beta.keystore.path=<你的 keystore 路径>
beta.keystore.storePassword=<store 密码>
beta.keystore.keyAlias=<alias>
beta.keystore.keyPassword=<key 密码>
```

新签名的 beta 包不能覆盖旧签名的 beta 包，装前先卸载旧的。

## 构建

```sh
# Windows
gradlew.bat :app:assembleBetaRelease
# Linux/macOS
./gradlew :app:assembleBetaRelease
```

产物在 `app/build/outputs/apk/beta/release/`，包名 `com.linqi.sakuraclash.beta`。
稳定版改用 `:app:assembleStableRelease`。

## 两点说明

- TLS 分片 patch 不在 mihomo 上游里，以文件形式放在 `patches/`，构建前由
  `applyMihomoPatches` 任务自动打上（已打过则跳过），不用手动处理。
- 内核版本见 `gradle.properties` 的 `mihomo.version`；升级内核时先把
  `patches/` 里的 patch 存出来，升完再套回并跑通测试。
