# 红队靶场（Android）

一个装在手机上的对手模拟器，用来量**你自己的检测系统**。它在 Gen1–Gen5 五个能力等级上
复现对手行为，并对目标应用记录**真实注入事件的 trace**，可直接喂给 `detect-test` 的评分卡。

> 包名是中性占位（`com.milai.redteam`），与具体平台无关。
> 你要改的只有三处，见下面「改造成你的平台」。

---

## 它做什么

| 代 | 行为 | 需要的注入能力 | 单机能跑吗 |
|---|---|---|---|
| **gen1** | shell 级：固定端点滑动、有界均匀等待、固定浏览空等 | Shizuku shell `input` | ✅ |
| **gen2** | 端点抖动、注入式轨迹、长尾等待、任务顺序重采样 | InputManager 逐点注入 | ✅ |
| **gen3** | 生理级：最小 jerk 贝塞尔 + 8–12Hz 震颤 + Fitts 时长 + 压力/面积动态 | InputManager 逐点注入 | ✅ |
| **gen4** | 同一流程 × 6 实例顺序执行（农场/多开形态） | 真实多设备 | ⚠️ 只能出计划与 trace |
| **gen5** | 完全不碰 UI，只记录协议领奖标记 | 协议端 | ⚠️ 只能出标记 |

**每轮都导出 trace**（`sessions.jsonl`），格式与 `detect-test/trace_format.py` 一致：
`touch`（DOWN/MOVE/UP + x/y/pressure/size）、`idle`、`screen`，含 `reward_path_intervals`。
导出位置：`Android/data/<你的包名>/files/traces/sessions.jsonl`。

---

## 两道安全门（代码级，不是注释）

**门 1 —— 目标允许清单。** `SafetyGate.ALLOWED` 之外的包一律拒绝执行；
`FORBIDDEN`（淘宝/闲鱼/支付宝/微信）**命中即中止，没有覆盖开关**。
要驱动新包就显式加进 `ALLOWED` 并重新构建——刻意的摩擦，避免顺手把工具指到别处。

**门 2 —— 注入能力校验。** 每代带 `injectionClass`，每个注入器声明能力，对不上直接报错。
这道门是必需的：`input swipe` 内部是均匀插值，**它永远无法复现 Gen2/Gen3**。
不拦的话这两代会悄悄退化成 Gen1，你会得到一张「Gen3 也被我抓住了」的假评分卡。

---

## 改造成你的平台（只改这三处）

### 1. `SafetyGate.java` — 允许清单

```java
private static final Set<String> ALLOWED = new LinkedHashSet<>(Arrays.asList(
        "com.example.yourapp",     // ← 换成你的包名
        "com.example.yourlab"
));
```

### 2. `LabConfig.java` — 页面锚点

锚点按 **1080×2400 基准**写，运行时自动缩放到设备真实分辨率，换机型不用重算。
把 `aHome / aTaskRow0 / aTaskRowStep / aTaskDetailClaim / aBack / aScrollFrom / aScrollTo`
换成你的真实坐标。

> 最关键的是 `aScrollFrom/aScrollTo`（闲逛流滑动）——它是「无收益行为」的载体。

### 3. `ScenePlanner.java` + `LabConfig` — 奖励路径定义

```java
// ScenePlanner 里 screen(...) 的第二个参数
SceneStep.screen("task_list", true)      // true = 属于奖励路径
SceneStep.screen("browse_feed", false)   // false = 非奖励路径
```

**这是最要紧的一处**：奖励/非奖励的划分决定「无收益行为占比」的分母。分错了，
你那份方案第 7 节的核心指标就整个偏掉。

改完这三处，重新构建即可。

---

## 构建（本机没有 SDK，走 CI 最省事）

本机现状：无 Android SDK、无 Gradle、JDK 8。工程用 `compileSdk 34` + Java 17。

### 路线 A：GitHub Actions（推荐，零本地环境）

`.github/workflows/build-apk.yml` 已就绪（`workflow_dispatch` 手动触发）。
推上去 → Actions → 手动运行 → 下载 `redteam-debug-apk` 产物。

### 路线 B：本地

装 Android Studio（自带 JDK 17 + SDK），打开本目录，`Build > Build APK(s)`。
或用命令行：

```bash
gradle :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 用法

1. 确保 Shizuku 在运行并已授权本 App（界面里有「授权 Shizuku」按钮）。
2. 目标包名填你自己的包；选代数；轮数建议 1 起步。
3. **先用 `dry-run` 跑一轮**：只出 trace、不注入，用来验证锚点和流程顺序。
4. 确认无误后取消 `dry-run`，从 `gen1` 开始真跑。
5. 把 `sessions.jsonl` 拉到电脑，配一份真人基线，跑评分卡：

```bash
adb pull /sdcard/Android/data/<你的包名>/files/traces/sessions.jsonl ./data/gen1.jsonl
python adversary-lab/scorer.py --input ./data
python adversary-lab/validity.py --input ./data
```

---

## 未验证的部分（重要）

这份代码**没有编译过、没有真机运行过**（本机无 SDK、无 Android Studio）。
`static_check.py` 只验证了括号平衡、类名唯一、接口方法齐全、无未定义方法调用——
**它不能替代编译**。首次编译和真机调试请按下面的顺序排查：

1. **最容易出问题：`InputManagerInjector.java`**。它反射调用四个 hidden API
   （`ServiceManager.getService` / `IInputManager$Stub.asInterface` /
   `injectInputEvent` / `MotionEvent.obtain` 的 13 参数重载）。
   Android 9 起 hidden API 对非系统应用受限，`targetSdk ≥ 30` 更严。
   如果注入被拦，工程里加 `org.lsposed.hiddenapibypass:hiddenapibypass:4.3`，
   并在 `Application.onCreate()` 里放开豁免（文件顶部注释写了具体调用）。
   如果 `MotionEvent.obtain` 反射失败，调用 `describeObtainOverloads()` 打印全部重载手工匹配。

2. **`ShellUserService` 的 transaction code** 必须与 `ShizukuShell.TRANSACTION_EXEC` 一致
   （当前都是 `FIRST_CALL_TRANSACTION + 1`）。gen1 跑不通先查这里。

3. **各机型 `input tap` 的实际行为**。`ShellInjector.tap` 在 duration>0 时用同点 swipe 模拟，
   这会让 DOWN/UP 出现真实间隔——这是有意的，但不保证所有机型一致。

排查顺序建议：`dry-run` → `gen1` → `gen2` → `gen3`。

---

## 文件一览

| 文件 | 职责 |
|---|---|
| `SafetyGate.java` | 门 1：目标允许/禁用清单（**改这里**） |
| `LabConfig.java` | 分辨率与锚点，基准坐标自动缩放（**改这里**） |
| `ScenePlanner.java` | 场景计划：Gen1–Gen5 的动作序列（**改这里**） |
| `Generation.java` | 各代参数：抖动、等待分布、滑动时长规则 |
| `SceneRunner.java` | 执行 + trace 记录（记录注入器实际产出的样本） |
| `StrokeSampler.java` | 轨迹几何：直线均匀插值 / 生理级贝塞尔+震颤 |
| `InputInjector.java` | 注入接口 + 能力声明 |
| `ShellInjector.java` | gen1：Shizuku shell `input` |
| `InputManagerInjector.java` | gen2/gen3：InputManager 逐点注入（**最脆弱**） |
| `DryRunInjector.java` | 不注入，只出 trace |
| `ShizukuShell.java` / `ShellUserService.java` | Shizuku UserService 特权 shell |
| `TraceWriter.java` | 导出 trace（与评分卡格式对齐） |
| `ScenarioService.java` | 前台服务，跑整套 |
| `MainActivity.java` | 操作台：选目标/代数/轮数，看日志 |
| `static_check.py` | 静态自检（非编译） |
