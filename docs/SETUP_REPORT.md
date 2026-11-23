# 项目搭建完成记录

**日期**：2026-11-23
**状态**：✅ 编译通过、Vector 加载成功、真机运行无崩溃

---

## 1. 项目信息

| 项 | 值 |
|---|---|
| 项目名 | QQ Native Guard |
| 包名 | `io.github.kida.qqnativeguard` |
| APK 大小 | 1,393,683 字节 |
| 目标 | `com.tencent.mobileqq` |
| 许可证 | GPL-3.0（继承上游） |

## 2. 来源与合规

- **基础**：[Xalsace/QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass)（GPL-3.0）
- **更早来源**：[jhl337/QQNTHookBypass](https://github.com/jhl337/QQNTHookBypass)
- **第三方**：[ByteHook](https://github.com/bytedance/android-inline-hook)（Apache-2.0）

**GPL-3.0 合规**：
- ✅ `LICENSE` 包含 GPL-3.0 全文（553 行）
- ✅ `NOTICE` 详述版权归属与所有修改
- ✅ 源码完整公开
- ✅ 同等许可发布

详见 [NOTICE](NOTICE)。

## 3. 相对上游的实际增量

### Native 层

| 文件 | 上游 | 本项目 | 增量 |
|---|---|---|---|
| `hook_libc.cpp` | 110 行 | 1,932 行 | **17.6 倍** |
| `hook_libfekit.cpp` | 57 行 | 123 行 | 2.2 倍 |
| `hook_libturingxq.cpp` | 47 行 | 227 行 | 4.8 倍 |
| `native-bypass.cpp` | 基础 | 249 行 | 扩展 |

**新增能力**：
1. ByteHook inline hook 框架（上游无 native hook）
2. `__system_property_get` / `read_callback` / `find` 三入口覆盖
3. `FakeKind` 内容级文件伪造框架（9 种文件）
4. SoC 平台标识伪造（`ro.board.platform` + `/proc/cpuinfo`）
5. Turing deflate 层明文注入

### Java 层

| 文件 | 增量 |
|---|---|
| `DeviceInfoHook.java` | 691 行，含 **`http.agent` 运行时重算** |
| `NetworkReportHook.java` | 572 行，**观测/拦截分离** |

**新增能力**：
6. `http.agent` 重建（修复 zygote 静态缓存导致的 UA 泄露）
7. `NETWORK_OBSERVE_ONLY` 开关

## 4. 搭建过程中遇到的问题

| # | 问题 | 解决 |
|---|---|---|
| 1 | Gradle 8.0 不支持 JDK 21 | 升级到 8.5 |
| 2 | 无 `ndkVersion`，AGP 默认 25.1 未安装 | 指定 27.3.13750724 |
| 3 | `compileSdk 34` → Build Tools 30.0.3 未装 | 指定 36.0.0 |
| 4 | 缺 `local.properties` | 创建并填 `sdk.dir` |
| 5 | 缺 `native-bypass.cpp` / `utils/` | 从提取目录补全 |
| 6 | **14 个 Java 文件带 UTF-8 BOM** | 脚本批量移除 |
| 7 | **缺 `assets/xposed_init`** | 创建，内容 `io.github.kida.qqnativeguard.XposedEntry` |
| 8 | **JNI 函数名仍是旧包名**（9 处） | 批量替换 `qqenhanced_bypass` → `kida_qqnativeguard` |
| 9 | native 黑名单含旧模块名 | 改为 `qqnativeguard` |
| 10 | `strings.xml` 中文被 PowerShell 写坏 | 改用英文，避免编码问题 |

**重要教训**：
- **PowerShell 的 `Set-Content -Encoding UTF8` 会加 BOM**，导致 Java 编译报“需要 class/interface/enum”
- 批量文件操作必须用 Python，且显式 `newline='\n'`

## 5. 验证结果

### Vector 加载日志
```
VectorLegacyBridge: Loading legacy module io.github.kida.qqnativeguard
VectorLegacyBridge:   Loading class io.github.kida.qqnativeguard.XposedEntry
nativeloader: Load .../libbytehook.so: ok
nativeloader: Load .../libnative-bypass.so: ok
QQNativeBypass: === Native Bypass Library Loaded ===
QQNativeBypass: JNI_OnLoad complete
QQNativeBypass: initNativeHooks called
QQNativeBypass: ByteHook initialized (AUTOMATIC mode)
QQNativeBypass: === All Native Hooks Installed Successfully ===
```

### 自检输出
```
Self-test ro.boot.verifiedbootstate='green'
Self-test ro.build.host='ReVork'
Self-test ro.build.display.id='<MODEL>_15.0.1.200(CN01)'
Self-test ro.build.fingerprint='OPPO/<MODEL>/OPPO_<MODEL>:15/<BUILD_ID>/1710000000:user/release-keys'
Self-test ro.serialno='<serial>'
Self-test soc_serial='<soc>'
Self-test env release='15' sdk='35'
```

### 稳定性
```
QQ 进程 pid=21673 存活
死亡次数 = 0
```

## 6. Vector 配置确认

从 `/data/adb/lspd/config/modules_config.db` 读出：

```
modules 表:
  mid=285  io.github.kida.qqnativeguard
  apk_path: /data/app/~~zkje7V-NP3i_2Q0Bzz6diQ==/.../base.apk
  enabled=1

scope 表:
  mid=285 -> com.tencent.mobileqq (user 0)  ✓
```

**配置正确**。Vector 有模块缓存延迟，重启设备后新模块才被识别。

## 7. 待办

- [ ] 提交到 GitHub（`KIDA-Nya/QQNativeGuard`）
- [ ] 补充 `qqbypass.conf.example` 完整示例
- [ ] 移植 `docs/` 中的技术文档（脱敏后）
- [ ] 添加 `config` 目录的热加载说明
- [ ] 编写 `CONTRIBUTING.md`
- [ ] 验证 `http.agent` 修复在实际抓包中生效

## 8. 注意事项

1. **`fp=(real)`**：native 启动早期读到的 fingerprint 是真机值，Java 层随后覆盖。这是时序问题，不影响最终上报。
2. **MMKV hook 默认禁用**：会导致崩溃循环（详见 README 已知限制）。
3. **`prctl` hook 必须关闭**：QQ 自检会导致自杀退出。
4. **Vector 缓存**：安装/更新模块后需要重启设备才能生效。