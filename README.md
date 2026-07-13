# 白云截屏

白云截屏是一个 Android 长截屏工具，支持自动滚屏、手动滚动、多图拼接、网页长图和结果管理。

## 开源范围

本仓库只发布不含白云云端接口的客户端源码：

- 不包含云端版本检查、更新日志、强制更新或远程配置；
- 不包含云端反馈配置和诊断上传；
- 截图、拼接、预览、保存、历史记录和本地诊断导出保持可用；
- 网页长图仍可访问用户主动输入的网页地址，因此保留 Android `INTERNET` 权限；
- 不提交签名密钥、本地设备数据或构建产物。

官方成品 APK 使用独立的带云端接口构建链路，仍通过 [白云云端](https://apps.whiteyun.com) 发布和更新；本仓库源码不会替代或覆盖官方 APK 发布物。

## 工程信息

- 包名：`com.whiteyun.screenshot`
- 模块：单 `app` 模块
- 语言：Java
- UI：Android 原生 View
- 依赖：仅 Android Gradle Plugin 和 Apache-2.0 `pngj`
- 最低 SDK：29

## 本地构建

需要 Android SDK 36 和 Java/Gradle 环境：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的公开版 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

公开源码默认不带正式签名。若需要本地 release 签名，请自行提供未提交的 `keystore.properties`；仓库已忽略签名文件和密码。

## 功能检查

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\c2_smoke_check.ps1
powershell -ExecutionPolicy Bypass -File .\tools\c3_smoke_check.ps1
powershell -ExecutionPolicy Bypass -File .\tools\c4_smoke_check.ps1
powershell -ExecutionPolicy Bypass -File .\tools\c5_smoke_check.ps1
powershell -ExecutionPolicy Bypass -File .\tools\c6_smoke_check.ps1
```

## 许可证

本项目采用 MIT License，见仓库根目录的 [LICENSE](LICENSE)。第三方依赖按其各自许可证使用。
