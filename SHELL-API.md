# Arbore Shell v3 能力说明与调用方式

> 适用版本：**3.0.x**（`cn.arbore.shell`）  
> 业务 Web 与壳通过 **`window.AndroidBridge`**（JS Bridge）及 WebView 标准能力协作。

---

## 一、能力总览（SmartWebView 对照）

| 能力 | v3 壳是否支持 | 说明 |
|------|---------------|------|
| 上传 / 下载 | **部分** | `<input type="file">`、`<a download>` 由 WebView 处理；见下文 |
| 相机 / 扫码 | **是（原生扫码）** | HTTP 下必须用 `AndroidBridge.scan`，不能用网页摄像头扫码 |
| 定位 | **是** | `AndroidBridge.requestLocation` 或浏览器 `geolocation` |
| Push（极光 JPush） | **是** | 登录后自动或手动 `bindUser` |
| 返回键 | **是** | 系统返回 → WebView 历史后退 |
| 错误页 | **是** | 主框架加载失败显示原生错误面板 |
| 深色模式 | **否** | 未实现，跟随系统/Web 自身 CSS |
| Cookie | **是** | WebView 自动管理；设置页可「清除缓存」 |
| 下拉刷新 | **是** | 页面顶部下拉 |
| 服务器地址 | **是** | 设置页配置，不写死在 APK |

---

## 二、壳子菜单（用户操作）

1. 业务页 **右上角绿色圆形按钮** → 菜单  
2. **长按桌面图标** →「服务器设置」  
3. 菜单项 **「能力测试页」** → 打开内置测试页（逐项自测）

---

## 三、JS Bridge：`window.AndroidBridge`

仅在 **Arbore 壳内** 存在（UA 含 `ArboreShell/x.x.x`）。网页应先判断：

```javascript
var inShell = /ArboreShell\//i.test(navigator.userAgent);
var bridge = window.AndroidBridge;
if (!inShell || !bridge) {
  // 浏览器环境，走 H5 降级逻辑
}
```

### 3.1 推送与用户绑定

| 方法 | 说明 |
|------|------|
| `bindUser(userId, userName)` | 将 `userId` 设为极光 **alias**，用于按人推送 |
| `unbindUser()` | 登出时删除 alias |
| `getRegistrationId()` | 返回设备 RegistrationId（可上报后端） |
| `getBoundUserId()` / `getBoundUserName()` | 上次绑定记录（本地） |

**自动绑定（推荐）**  
壳在每次页面加载完成后会请求同源的 `GET /Account/Profile`（带 Cookie），若 `code===200` 则自动：

```javascript
AndroidBridge.bindUser(String(data.id), data.name || data.account);
```

对应后端：`DeviceMgmt.Web/Controllers/AccountController.cs` → `Profile()` 返回 `data.id`。

**手动绑定（登录成功回调里）**

```javascript
AndroidBridge.bindUser(String(user.id), user.name);
```

**后端推送（极光 REST）**

```json
{
  "platform": "android",
  "audience": { "alias": ["123"] },
  "notification": {
    "android": {
      "title": "新工单",
      "alert": "您有一条待处理工单",
      "extras": { "path": "/m/maintain/detail?id=1" }
    }
  }
}
```

- `alias` = 用户 `id`（与 `bindUser` 一致）  
- `Master Secret` 在极光控制台获取，配置在后端（尚未接入 device-mgmt 时可先用控制台「创建推送」测试）

**点击通知深链**

3.0.4+ 壳会自动从 `notification.android.extras.path` 取出业务路径并在 WebView 内打开，例如：

```json
"extras": { "path": "/m/maintain/detail?id=1" }
```

> 仅识别 `path` 字段；可写绝对 URL（`https://...`）或相对路径（`/m/...`）。
> 内部由 `cn.arbore.shell.push.ArboreJPushReceiver.onNotifyMessageOpened` 实现。

**通知权限（Android 13+ 必读）**

- App 首次启动会请求 `POST_NOTIFICATIONS`，**点拒绝**后系统会直接屏蔽通知（极光仍会送达，只是不显示在状态栏）  
- 拒绝后想恢复：**系统设置 → 应用 → Arbore TPM → 通知 → 开启**  
- 应用未在前台时也能收到通知，前提是「进程未被系统强杀」+「自启动 / 后台运行」未被厂商电源管理拦截  

**测试步骤**

1. 装 / 启动壳，首次启动会弹通知权限对话框 → 允许  
2. 打开壳 → 右上角菜单 → **能力测试页** → 「读取 RegistrationId」，复制 ID 或先登录 TPM 让壳自动 `bindUser`  
3. 测试通知有两种发法：  
   - **按 RegistrationId**：极光控制台 → 推送 → 创建推送 → 选「Registration ID」→ 粘贴第 2 步的 ID  
   - **按 alias（推荐生产）**：登录 TPM 后壳自动调 `bindUser(userId)`，控制台改为「别名」→ 填用户 id  
4. 通知标题随意，可选「自定义额外字段」加 `path = /m/maintain/detail?id=<某个真实工单 id>`  
5. 点击发送 → 手机状态栏出现通知 → 点击通知 → WebView 直接进保养工单详情页  

**后台 / 息屏行为对照表**

| 应用状态 | 是否能弹通知 | 说明 |
|----------|--------------|------|
| 前台 | 是 | 直接弹横幅 |
| 后台运行（进程存活、桌面/最近任务可见） | 是 | 长连保活；息屏唤醒响铃震动 |
| 息屏 | 是 | 等同后台，进程未被杀即可 |
| 被系统电源管理冻结 / 用户从「最近任务」上滑杀 | 否 | 自家通道断开；要解决须接入厂商通道 (Xiaomi / HMS / OPPO / vivo / FCM)，目前 v3 未集成 |
| 整机重启后第一次开机 | 看是否申请了自启动 | RECEIVE_BOOT_COMPLETED 已声明，但小米 / 华为 / OPPO 等仍要用户在「自启动管理」放行 |


---

### 3.2 原生扫码（重要）

HTTP 业务地址下，Chromium **不提供** `navigator.mediaDevices`（不安全上下文），**网页内摄像头扫码不可用**。

| 方式 | HTTP | HTTPS |
|------|------|-------|
| `AndroidBridge.scan(token)` | **可用** | 可用 |
| `navigator.mediaDevices.getUserMedia` | 通常 **不可用** | 可能可用（**仅视频预览，不能扫码**，需 JS 解码库） |
| `cordova.plugins.barcodeScanner` | **v3 未集成** | 未集成 |

**调用示例（与 `Maintain.cshtml` 一致）**

```javascript
var __scanResolvers = {};
window.__arboreScanResult = function (token, code, err) {
  var r = __scanResolvers[token];
  if (!r) return;
  delete __scanResolvers[token];
  if (err) r.reject(new Error(err));
  else r.resolve(code || '');
};

function nativeScan() {
  return new Promise(function (resolve, reject) {
    var token = 's_' + Date.now();
    __scanResolvers[token] = { resolve: resolve, reject: reject };
    AndroidBridge.scan(token);
  });
}

nativeScan().then(function (code) {
  console.log('扫码结果', code);
});
```

**错误码 `err`**

| err | 含义 |
|-----|------|
| `CANCELLED` | 用户取消 |
| `PERMISSION_REQUESTED` | 已申请相机权限，请授权后 **再点一次** |
| `NOT_READY` | 壳未就绪 |

**旧版 Cordova 页面**  
`wwwroot/js/app/routes.js` 等仍调用 `cordova.plugins.barcodeScanner` → **在 v3 壳中无效**，需改为上表 `AndroidBridge.scan` 或统一封装。

---

### 3.3 定位

```javascript
var locResolvers = {};
window.__arboreLocationResult = function (token, json, err) {
  var r = locResolvers[token];
  if (!r) return;
  delete locResolvers[token];
  if (err) r.reject(new Error(err));
  else r.resolve(json ? JSON.parse(json) : null);
};

function nativeLocation() {
  return new Promise(function (resolve, reject) {
    var token = 'loc_' + Date.now();
    locResolvers[token] = { resolve: resolve, reject: reject };
    AndroidBridge.requestLocation(token);
  });
}
```

返回 JSON：`{ lat, lng, accuracy }`。

---

### 3.4 其它 Bridge 方法

| 方法 | 说明 |
|------|------|
| `getBuildLabel()` | 如 `3.0.2 (30002)` |
| `getServerUrl()` | 当前配置的服务器根地址 |
| `openSettings()` | 打开服务器设置 Activity |
| `openCapabilityTest()` | 打开能力测试页 |
| `toast(msg)` | 原生 Toast |

---

## 四、无需 Bridge 的 Web 标准能力

### 4.1 文件上传

```html
<input type="file" accept="image/*" />
<!-- 拍照：capture="environment"（部分机型走系统相机） -->
<input type="file" accept="image/*" capture="environment" />
```

壳已实现 `WebChromeClient.onShowFileChooser`，会弹出系统文件选择器。

### 4.2 文件下载

```html
<a href="/api/export/xxx" download="report.xlsx">下载</a>
```

或 JS 创建 Blob + `<a download>`（能力测试页有示例）。复杂下载若被拦截，可改为后端直链或 `window.open(url)`。

### 4.3 浏览器 geolocation

网页调用 `navigator.geolocation.getCurrentPosition` 时，壳会在 `WebChromeClient.onGeolocationPermissionsShowPrompt` 中代为申请定位权限并授权给 WebView（3.0.3+）。若仍提示 `user denied`，请检查系统「位置信息」权限，或改用 `AndroidBridge.requestLocation`。

### 4.4 Cookie / 登录

- 登录接口：`POST /Account/DoLogin` → 设置 Cookie `Token`  
- WebView 自动携带 Cookie  
- **清除登录**：设置 →「清除缓存（含登录状态）」

### 4.4 返回键

- WebView 有历史 → 后退  
- 无历史 → 退出 App  

### 4.5 错误页

主 URL 加载失败时显示原生面板（错误信息 + 重新加载 + 打开设置），不会整屏黑屏。

### 4.6 深色模式

**当前未实现**。若业务需要，请在 TPM 网页 CSS 用 `prefers-color-scheme` 自行适配。

---

## 五、服务器地址配置

- **不写死在 APK**  
- 首次启动 → 设置页输入，如 `http://192.168.1.100:8080`  
- 保存后壳自动打开 **`{baseUrl}/m`** 移动端入口  
- 修改：右上角菜单 → 服务器设置  

---

## 六、扫码失败排查清单

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| 点击无反应 | 网页仍用 Cordova API | 改为 `AndroidBridge.scan` |
| 提示 PERMISSION_REQUESTED | 未授权相机 | 系统设置开启相机权限后重试 |
| getUserMedia 失败 | HTTP 地址 | 改用原生扫码 |
| 能力测试页原生扫码 OK、业务页不行 | 业务 JS 未接 Bridge | 对照 `Maintain.cshtml` 改 |

---

## 七、工程路径

| 文件 | 作用 |
|------|------|
| `app/src/main/java/cn/arbore/shell/MainActivity.java` | WebView、扫码、文件选择、定位 |
| `app/src/main/java/cn/arbore/shell/AndroidBridge.java` | JS Bridge |
| `app/src/main/assets/shell_test/index.html` | 能力测试页 |
| `app/build.gradle` | JPush AppKey、`zxing-android-embedded` |

---

## 八、版本记录

| 版本 | build | 说明 |
|------|-------|------|
| 3.0.4 | 30004 | 通知深链 (ArboreJPushReceiver)、运行时申请 POST_NOTIFICATIONS、`/m` 默认入口、原生 ZXing 扫码 + cordova 兼容垫片 |
| 3.0.3 | 30003 | WebView geolocation 授权回调 |
| 3.0.2 | 30002 | 能力测试页、文件上传、定位 Bridge、文档 |
| 3.0.1 | 30001 | 设置菜单、清缓存、快捷方式 |
| 3.0.0 | 30000 | 首版 WebView 壳 + JPush |
