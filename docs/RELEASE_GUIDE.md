# 陆战棋 正式发布指南

## 固定发布身份

- 包名：`com.huanglong.pad`
- KEMI 商城已有线上应用「陆战棋」，后续必须使用“提交新版本”，不能重复新建应用。
- 每次发布必须提高整数 `versionCode`，`versionName` 用于展示。

## 签名

本项目**只有一套证书**，线上包与本地包一致，直接覆盖升级，不需要签名轮换：

- 证书 SHA-256：`c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`
  （KEMI 供应商签名 K9，alias `androiddebugkey`）

`signing/` 必须安全保管以下文件，且永远不能提交 Git（`.gitignore` 已忽略 `signing/`）：

- `k9.release.signing.properties`
- `debug.keystore`（证书库，文件名是供应包原名）

发布前请核对该 keystore 的证书指纹与上面一致：

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore signing\debug.keystore `
    -storepass <密码> -alias androiddebugkey | Select-String SHA256
```

## 构建商城包

正式发布必须运行脚本，不要直接上传 `app-release.apk`（脚本会硬校验签名指纹、包名、版本，
并生成同名 `.sha256` 校验清单）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build_store_release.ps1
```

产物：

```text
build/publish/luzhanqi-{versionName}-{versionCode}-store.apk
build/publish/luzhanqi-{versionName}-{versionCode}-store.apk.sha256
```

> 脚本含中文注释，必须保存为 **UTF-8 with BOM**，否则 Windows PowerShell 5.1 会按 GBK 解码
> 导致解析失败（本项目踩过）。

## KEMI 自升级

客户端（`AppUpdateManager`）按官方文档调用：

`GET https://kemi.newlinksz.com/kd-api/api/store/update/check`

请求参数为包名与本地 `versionCode`。有更新时优先打开商城 DeepLink（`kemiappstore://`）；
商城不可用时下载 APK，校验服务端 SHA-256，并通过 `FileProvider` 交给系统安装器覆盖安装。

官方文档：<https://kemi.newlinksz.com/kd/docs/app-self-update/android>

## 商城发布检查表

1. 提高 `app/build.gradle` 里的 `versionCode` 和 `versionName`。
2. 运行 `scripts/build_store_release.ps1`，记录脚本输出的 SHA-256。
3. 打开 KEMI 开发者平台 → 应用管理 → 陆战棋 → 提交新版本。
4. 上传 `build/publish/luzhanqi-{versionName}-{versionCode}-store.apk`，等待服务端解析包名、版本和哈希。
5. 填写更新说明（可用 `CHANGELOG.md` 对应段落），确认“双屏”“在商城展示”和强制更新策略。
6. 保存发布后，用 `version_code=旧版本号` 调检查更新接口验证返回数据。
7. 双屏回归：`docs/DUAL_SCREEN_SYNC.md` 第 8 章的验收项，重点覆盖未交换/交换、切后台、退出。
