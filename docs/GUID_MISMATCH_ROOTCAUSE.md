# 找到「登录状态已失效」的根因：GUID 双路径不一致

> 这是本项目**第一次定位到「需要重新登录」的确切技术原因**。
> 之前几轮一直在设备指纹上找，方向偏了。

---

## 一、触发这次调查的关键信息

你指出 QQ 下线有两种，且它们机制不同：

1. **「登录状态已失效，请重新登录」**
2. **w21（服务端踢线）**

而今天的日志显示 `KICK_TO_LOGIN = 0` —— **不是 w21**。
所以今天遇到的是第一种，而第一种此前从未被真正解释过。

---

## 二、从错误码表反查

在 `classes11.dex` 找到完整的登录错误码表：

```
KLOGINERRORDIFFGUIDBETWEENQQHEADANDLOGINHEAD    ★ QQ头 vs 登录头的 GUID 不一致
KLOGINERRORDIFFGUIDBETWEENLOGINHEADANDA         ★ 登录头内部 GUID 不一致
KLOGINERROREXPIRETICKET / ILLAGETICKET
KLOGINERRORCOOKIEEXPIRED / COOKIEUSEDEXCEEDUPPERLIMIT
KLOGINERRORBLACKACCOUNT / FROZEN / ILLEGALACCOUNT
...
```

**`DIFFGUID` 这一条直指问题** —— 服务端会校验 QQ 头和登录头里的 GUID 是否一致。
不一致 → 登录被拒（`KICK=0`，因为这不是踢线，是**拒绝**）。

---

## 三、找到缺口：`getGuidFromFile` 从未被 hook

反汇编 `oicq.wlogin_sdk.tools.util.getGuidFromFile(Context)`：

```java
byte[] getGuidFromFile(Context ctx) {
    byte[] g0 = oicq.wlogin_sdk.request.w.g0;      // ① 客户自定义 GUID
    if (g0 != null && g0.length != 0) {
        LOGI("get guid from customer guid", "");
        return w.g0;
    }
    LOGI("get guid from file", "");                // ② 从文件读
    String path = ctx.getFilesDir().getAbsolutePath() + "/wlogin_device.dat";
    ...
}
```

**我们的 hook 只覆盖了 `generateGuid` / `generateGuidForUser` / `saveGuid`，
从未覆盖 `getGuidFromFile`。**

### 实测两个 GUID 不一致（A 级）

```
真机 GUID (wlogin_device.dat)  : 7a7b6091…（已脱敏）
伪造 GUID (FAKE_WLOGIN_GUID)   : be767c1b…（已脱敏）
一致: False
```

**这就是 `KLOGINERRORDIFFGUIDBETWEENQQHEADANDLOGINHEAD` 的直接成因：**
- 走 `generateGuid()` 的路径 → 返回伪造值
- 走 `getGuidFromFile()` 的路径 → 读到真机值
- **同一个会话里出现两个不同的 GUID，服务端判定不一致**

### 为什么账号 B 看起来稳定

**如果账号 B 的会话是长期在线的（不重新登录），它根本不触发 GUID 重新校验。**

而账号 A（主号）每次掉线后重新登录，就必然再撞一次 GUID 校验 —— 于是频繁掉。

**这与「账号 B 稳定」并不矛盾，恰恰是同一根因的另一面。**

---

## 四、修复：双保险

### ① hook 补齐（主防线）

```java
HookUtils.hookAllMethods(cls, "getGuidFromFile", new XC_MethodHook() {
    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        Object r = param.getResult();
        if (r instanceof byte[]) {
            param.setResult(fakeGuid.clone());
        }
    }
});
```

### ② 文件直改（第二道防线）

防止某处绕过 `getGuidFromFile` 直接读文件：

```
备份: /data/user/0/com.tencent.mobileqq/files/wlogin_device.dat.bak_orig
写入: be767c1b41b04915af284e6c5db89589
权限: u0_a369:u0_a369 600（必须恢复，否则 QQ 写不了）
```

---

## 五、验证（A 级）

### hook 生效

```
Hooked all methods: oicq.wlogin_sdk.tools.util.getGuidFromFile
[WLOGIN-UTIL] getGuidFromFile -> FAKED be767c1b...
[WLOGIN-UTIL] generateGuid  -> FAKED be767c1b...
[WLOGIN-UTIL] generateGuid() -> byte[16] be767c1b...      ← 实际调用
```

### 文件未被改回

```
重启后内容: vnZ8G0GwSRWvKE5sXbiViQ==  = be767c1b...
时间:       17:10（未被 QQ 重写）
权限:       -rw------- u0_a369 u0_a369  16 字节
```

**两条路径现在都返回同一个伪造值。**

---

## 六、待验证

**需要一次真实登录来确认修复有效。**

判定标准：
- 登录后 `rst1=0 rst2=0`（不是 1 / -40）
- 长时间锁屏后不再出现「登录状态已失效」
- 对比修复前：13:31 那次 `rst1=1 rst2=4294966296(-40)`、耗时 192 秒

---

## 七、方法论教训

**这次能查到，靠的是你给出的两条关键信息：**

1. **「掉线有两种」** → 逼我去区分「踢线」和「拒绝」，从而查到错误码表
2. **「另一个账号稳定」** → 排除了系统/设备因素，把范围收窄到账号登录流程

**在此之前我一直在设备指纹（QsecEst / fekit / Turing）上找原因，
方向是偏的 —— 那些工作对「设备被识别」有用，但对「需要重新登录」不是主因。**

**这类问题最有效的路径是：从现象文案/错误码反查代码，而不是从检测 SDK 正向排查。**
