<div align="center">

# Sakura Clash

**基於 [Mishka](https://github.com/YuKongA/Mishka) 定製的 Android 代理用戶端**

基於 [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) 和 [mihomo](https://github.com/MetaCubeX/mihomo) 建置

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![minSdk](https://img.shields.io/badge/minSdk-31-3DDC84?logo=android&logoColor=white)](#)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)

[简体中文](README.md) | 繁體中文 | [English](README.en.md)

</div>

---

## 簡介

採用 [mihomo](https://github.com/MetaCubeX/mihomo)（Clash.Meta）核心的 Android 代理用戶端，UI 使用 [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) + Jetpack Compose 建置，資料層 Room + Ktor + Koin。核心以子行程 + JNI 雙通道整合，支援 **VPN**、**ROOT TUN**、**ROOT TPROXY** 三種隧道模式

## 定製版說明

這是我在 Mishka 基礎上改的個人定製版，原始碼和原版一樣按 GPL-3.0 開源。原版的功能都在（見下方「特色」），另外加了這些：

**定製功能**

- 獨立的「規則設定」頁：規則可增、刪、清空，存為專用覆寫，訂閱更新不會沖掉 ✔️
- 訂閱可自訂排序：列表裡上下移動調整順序，會記住排好的次序 ✔️
- 分享連結解析：貼上機場的分享連結或整包訂閱內容可直接匯入✔️
- 應用內切換語言，不用再跟隨系統的語言設定 ✔️
- 加入 TLS 分片：把 TLS 握手的第一段拆開發送，繞開針對網域的連線重置（設定→Meta 設定裡可開，可調每段大小與間隔）✔️
- Geo 資料打包進安裝包，也能在應用內單獨更新 ✔️
- 卡片液態玻璃 + 自訂壁紙：主頁和各頁卡片改成液態玻璃，主題設定裡可調模糊半徑與著色濃度；可自選一張圖當整屏靜態壁紙✔️

**下載**：到 [Releases](../../releases) 頁下載安裝包。本倉庫是個人自用定製，功能優先按自己的需求來，不保證適合所有人。

---

## 特色

**代理與核心**

- 內建 [mihomo](https://github.com/MetaCubeX/mihomo) 核心（Mishka fork），統一為單個 `libmihomo.so`，同時作為 runtime 入口與訂閱匯入的 JNI 介面
- 三種隧道模式，隨時切換：
  - **VPN** —— 無需 Root，基於系統 `VpnService`
  - **ROOT TUN** —— Root 自建 TUN，`auto-route` + 大包 GSO 聚合
  - **ROOT TPROXY** —— 核心態透明代理（iptables + fwmark），效能接近直連
- **分應用代理** —— 白/黑名單，三模式各自走 VpnService / `include-package` / iptables uid-owner
- **熱點流量處理**（ROOT）—— 支援繞過代理或透明代理（TPROXY）兩種模式

**訂閱管理**

- 支援 URL / 本機檔案 / 掃描 QR Code 匯入訂閱
- 三階段沙箱式匯入流程（Pending → Processing → Imported），可取消、可回滾
- **age 加密訂閱** —— per-profile 金鑰，加密原樣保存、執行時解密；內建 X25519 / 抗量子金鑰對產生
- **per-profile User-Agent** 覆寫，訂閱下載可選走本機代理
- 自動更新（AlarmManager 排程）+ 手動全部更新

**介面與體驗**

- 平滑圓角 + 進階材質底欄/頂欄（或液態玻璃底欄）
- 深淺色模式 + 跟隨系統 + Monet 動態取色
- 寬螢幕適配（NavigationRail 側邊欄）
- 內建 YAML 設定編輯器（[scripta](https://github.com/YuKongA/scripta)）
- 即時流量、連線、日誌、DNS 查詢、Provider 管理
- 動態流量通知（VPN 模式）+ Quick Settings 磁貼一鍵啟停
- **Wi-Fi 自動切換** —— 符合指定 SSID 時自動停止代理或切換 Direct 模式
- 開機自啟、隱藏最近任務卡片等實用開關

## 建置

### 環境需求

- JDK 21
- Android SDK + NDK（含 clang），SDK 版本見 `build-logic/src/main/kotlin/ProjectConfig.kt`
- Go（版本見 `mihomo/go.mod`）
- Git（用於 submodule 與版本號產生）

### 步驟

```bash
# 1. 複製倉庫
git clone https://github.com/YuKongA/Mishka.git
cd Mishka

# 2. 初始化子模組（mihomo fork + scripta 編輯器）
git submodule update --init --recursive

# 3. 首次建置前下載 GeoIP 資料（預置進 assets）
./gradlew :app:downloadGeoFiles

# 4. 建置 APK（assemble 自動觸發 mihomo cgo 交叉編譯 + CMake）
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

# 5. 只改 Kotlin 時可跳過 Go 建置（秒級驗證）
./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a
```

> `mihomo` 透過 git submodule 引入 [YuKongA/mihomo](https://github.com/YuKongA/mihomo) 的 `Mishka` 分支（含 5 個針對 Android fd/TUN 的 patch）。Gradle 會自動驅動 Go 交叉編譯，產物位於 `app/src/main/jniLibs/<ABI>/`

## 致謝

- [mihomo](https://github.com/MetaCubeX/mihomo) —— 代理核心
- [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) —— UI 元件庫
- [sparkle](https://github.com/xishang0128/sparkle) / [Clash Meta for Android](https://github.com/MetaCubeX/ClashMetaForAndroid) —— 實作參考
- [scripta](https://github.com/YuKongA/scripta) —— 程式碼編輯器

## 授權

本專案以 [GPL-3.0](LICENSE) 授權開源；與核心 [mihomo](https://github.com/MetaCubeX/mihomo) 一致
