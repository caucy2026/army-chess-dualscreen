# 陆战棋 1.2.0 商城发布报告

## 发布身份

| 项 | 值 |
| --- | --- |
| 应用 | **app_id 43**，`陆战棋`，`com.huanglong.pad`，分类「游戏」 |
| 本次版本 | `1.2.0` / `versionCode 3` |
| 上一版本 | `1.1.0` / `versionCode 2` |
| 操作类型 | 更新（未新建应用） |

## 产物

| 项 | 值 |
| --- | --- |
| 文件 | `build/publish/luzhanqi-1.2.0-3-store.apk` |
| 字节 | `10584504` |
| SHA-256 | `962e54606a281f497d218397c1265bf7f5554c5b7390f6559f05c6703436824c` |
| 签名证书 | `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`（KEMI K9，与线上旧版同证书，可直接覆盖升级） |

## 上传与更新

`apk-token → CDN 直传 → apk-complete` 三步均 200，服务端回读的 URL / 字节 / SHA-256 与本地一致：

```text
url    = https://cdn.newlink-sz.com/kemiAppStore/apk/2026/10/1792491898517_2c4f0d0f_luzhanqi-1_2_0-3-store.apk
size   = 10584504
sha256 = 962e54606a281f497d218397c1265bf7f5554c5b7390f6559f05c6703436824c
```

应用记录更新返回「已直接更新线上版本（免审）」。

## 四层发布后校验

1. **开发者记录**：唯一一条；版本 `1.2.0/3`；URL 为 HTTPS；`file_size` 为精确字节串、`file_size_bytes` 为整数；SHA-256 一致；`list_in_store` 为真。
2. **公共店面详情**（`/api/store/apps/43?os=android`）：可读；版本 `1.2.0/3`；`file_size` = `10584504`；URL 为 HTTPS；`apk_sha256` 一致。
3. **公共列表**（`/api/store/apps?page=1&pageSize=100&os=android`，不带 category）：收录 1 条，版本 `1.2.0/3`，分类「游戏」，`file_size` = `10584504`。
4. **CDN**：`HEAD` 返回 200，`Content-Length` = `10584504`；整包下载后字节数与 SHA-256 均一致。

**更新检查**

```text
version_code=2 → has_update=true，目标 1.2.0/3，URL/size/sha 均正确
version_code=3 → has_update=false
```

## 顺带修复

线上旧记录（1.1.0）的 `file_size` 被存成了 `10.1MB` 这种人可读文本、`file_size_bytes` 为整数。
客户端把 `file_size` 当数字解析会得到 0，表现为「缺少完整信息，禁止安装」。
本次已改为精确字节串 `10584504` + 整数 `10584504`，公共详情与列表两处都已核对为数字。

## 自升级

`AppUpdateManager` 走同一接口 `GET /kd-api/api/store/update/check`，参数为包名 + 本地 `versionCode`；
有更新时优先打开 `kemiappstore://` 商城详情，商城不可用时下载 APK、校验服务端 SHA-256，再经
`FileProvider` 交系统安装器覆盖安装。

## 清理

- 临时登录凭据（DPAPI 派生的密钥文件、加密口令副本、会话令牌临时文件、探测脚本）已全部删除并回读确认。
- 证据文件 `.kemi-luzhanqi-upload.json`（不含任何令牌）保留。
- 正式产物 `build/publish/luzhanqi-1.2.0-3-store.apk` 与 `.sha256` 保留。

## 剩余限制

- 商城页面的「点击下载/安装」按钮未做人工目视确认，本次以公共详情 + CDN 整包校验替代；
  客户端真机升级路径已在 192.168.1.209 上用同签名 release 包覆盖安装验证过。
- 副屏 180° 补偿已由人工在实体屏确认正确，保持按显示投影公式计算。
