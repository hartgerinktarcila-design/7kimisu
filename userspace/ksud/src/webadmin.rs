//! 网页管理器 —— **跑在 ksud（root 守护进程）里**，不依赖管理器 App。
//!
//! ## 为什么要搬到这里（2026-09-16 的真实教训）
//!
//! 一开始这套网页服务写在管理器 App 里，结果用户把 App 从最近任务划掉、
//! 或者用系统"一键清理"，进程一死端口就关，浏览器直接"拒绝连接"。
//! 后来拆了另一个管理器（DickSU）的包：它的 `libksud.so` 里直接写着
//! `KernelSU Web UI listening on http://127.0.0.1:...`，还带一整套
//! `/api/modules`、`/api/apps/grant`、`/api/sulog` 之类的路由 ——
//! 也就是说 **服务跑在 ksud 里**。ksud 是内核拉起来的 root 守护进程，
//! 所以划掉 App、一键清理、重启手机它都照样在。
//!
//! 本模块就照这个架构写：ksud 自己监听 127.0.0.1，自己发网页和接口。
//!
//! ## 结构
//!   · [Backend]：数据来源抽象。Android 上由 `webadmin_ksud.rs` 实现
//!     （调 ksud 自己的模块管理 / 内核接口）；开发机上可以用假数据实现，
//!     于是这一段 HTTP 逻辑能在 Mac 上真跑起来测。
//!   · [serve]：监听 + HTTP 解析 + 路由，不依赖任何 Android API。
//!
//! ## 安全边界（2026-09-18 用户拍板：**保留功能、补上专属链接鉴权**）
//!
//! 旧方案（v2.12 及以前）只有"固定端口 + 固定路径前缀 `/x7k9f`"，
//! 而回环地址 `127.0.0.1` **只挡得住别的设备，挡不住同一台手机上的其它 App**：
//! 本机任意 App 只要知道端口和前缀，就能 `POST /x7k9f/api/app/root` **给自己授 root**、
//! 装卸模块、开关隐身、跑 `ksu.exec` —— 这是一条真实的本地提权链（2026-09-18 审计实证）。
//!
//! 现在的鉴权方案（**专属链接 / capability URL**）：
//!   · `webadmin.conf` 里存一个 **256 bit 高熵随机 token**（64 位十六进制，
//!     取自 `/dev/urandom`；**绝不**退回时间戳之类的弱随机源）。
//!   · 所有请求的路径必须是 `{PATH}/{token}[/...]`，例如
//!     `http://127.0.0.1:18427/x7k9f/3f2a…/api/status`。
//!     token 在**路径里**而不是 query（`?k=`）或请求头：
//!     ① 前端本来就用 `P = "__P__"` 前缀拼所有请求，注入一次就全覆盖（改动最小）；
//!     ② 静态资源（app.js/css）也自动带上 token，不需要额外加头；
//!     ③ 不会像 `?k=` 那样被日志/历史记录按"参数"单独记住。
//!   · token 缺失或不对 → **一律 401**，响应体固定为 `Unauthorized`：
//!     不说"token 错"、不说路径对错、不做跳转 —— 攻击者拿到的信息量为零。
//!   · 比较用**恒定时间**实现（`ct_eq`），没有短路返回，避免时序侧信道。
//!   · token **只写进 `webadmin.conf`（0600 root:root，原子写 + SELinux 标签）**，
//!     不进日志、不进命令行参数（`ps` 全机可读）、不进 `webadmin.status`。
//!   · 关闭再打开**沿用同一个 token**（用户的书签/收藏不失效）；
//!     要换只有显式动作：`ksud webadmin reset-token`（App 设置页也有入口）。
//!   · 只绑 `127.0.0.1`（局域网/公网连不上）；默认仍然**关闭**。
//!   · 页面零外部请求（资源全部编译进 ksud 二进制）。

// 在开发机（非安卓）上编译时，这个模块的很多接口只有测试在用，
// 别让"从没被构造过"之类的警告淹掉真正的问题。
#![cfg_attr(not(target_os = "android"), allow(dead_code))]
use std::collections::HashMap;
use std::io::{BufReader, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::sync::Arc;
use std::time::Duration;

use log::{debug, info, warn};

/// 首选端口：沿用 App 版的 18427（不跟 DickSU 的 10240 撞）
pub const PORT: u16 = 18427;

/// 首选被占时的备选（每台手机情况不同，自动顺延）
pub const CANDIDATE_PORTS: [u16; 4] = [18427, 18437, 18447, 18457];

/// 实际绑上的端口（由 [serve] 绑定成功后写入）
static BOUND_PORT: std::sync::atomic::AtomicU16 = std::sync::atomic::AtomicU16::new(PORT);

/// 🔴（v2.18）**并发连接上限**。服务跑在 **root 守护进程**里，而 `handle()` 每个连接
/// 会起一个线程、最长挂 [`IO_TIMEOUT`]（15s）。旧写法**无上限** ⇒ 本机任意 App
/// 只要连上一堆 socket 不发完请求，就能把 ksud 撑到线程/内存耗尽
/// （线程栈默认 8 MiB 虚拟内存 × 无上限 = 直接打爆）。
/// 超过上限的连接回一条 `503` 就关，不排队、不占资源。
const MAX_CONNS: usize = 16;
static ACTIVE_CONNS: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

/// 命令行/重启时显式指定的端口（0 = 没指定）。**比配置文件优先** ——
/// 因为 su 会话有独立 mount namespace，写配置文件可能落不到"真文件"上（2026-09-17 实测）。
static PORT_OVERRIDE: std::sync::atomic::AtomicU16 = std::sync::atomic::AtomicU16::new(0);

pub fn set_port_override(p: u16) {
    PORT_OVERRIDE.store(p, std::sync::atomic::Ordering::Relaxed);
}

fn port_override() -> u16 {
    PORT_OVERRIDE.load(std::sync::atomic::Ordering::Relaxed)
}

#[allow(dead_code)] // 生产代码走 effective_port()；这个给测试用
pub fn bound_port() -> u16 {
    BOUND_PORT.load(std::sync::atomic::Ordering::Relaxed)
}

/// 探测某个端口上是不是**我们自己的**服务在回话。
///
/// 只看"端口能连上"不够 —— 会被别的 App 占着端口骗过去（2026-09-17 真踩过：
/// 测试用的 busybox httpd 占了 18427，于是以为服务在跑、根本不去启动守护进程）。
/// 所以这里要发一个真请求，拿到我们自己的 `{"ok":true` 才算数。
pub fn probe_own(port: u16) -> bool {
    probe_ping(port).is_some()
}

/// 探活拿到的信息
pub struct PingInfo {
    pub pid: u32,
    /// 对方**正在执行的那个二进制**的 sha256（十六进制）。
    /// 用来判断"守护进程跑的是不是盘上这一份 ksud"（见 `sync_if_needed`）。
    pub bin_sha: String,
}

/// 探测端口上是不是我们自己的服务；是的话把 pid 和二进制指纹一起拿回来。
///
/// 为什么要 pid：只判断"是不是我们的服务"还不够 —— 换端口/重启时得确认
/// "这个端口上回话的**正是我刚起的那个子进程**"（2026-09-17 真机踩到）。
/// 为什么要二进制指纹：打开 App 时不该无谓重启服务，只有 ksud 真换过版本才重启。
pub fn probe_ping(port: u16) -> Option<PingInfo> {
    use std::io::{Read as _, Write as _};
    let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    let mut s = TcpStream::connect_timeout(&addr, Duration::from_millis(400)).ok()?;
    let _ = s.set_read_timeout(Some(Duration::from_millis(600)));
    // 判活请求也要带 token（见文件头"专属链接"）。token 从配置里读 ——
    // 探活的调用方（ksud 自己）本来就是 root，读得到 0600 的配置文件。
    let base = base_path();
    if base.len() <= PATH.len() {
        return None; // 还没生成 token → 不可能有任何服务在正确地回话
    }
    let req = format!("GET {base}/api/ping HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n");
    s.write_all(req.as_bytes()).ok()?;
    // ⚠️ 必须**读到连接关闭**：响应头里 Content-Security-Policy 那一长串就有 400 字节左右，
    //    只读一次（以前是 256 字节）只能读到头，body 里的标记永远看不到 —— 判活会永远为假。
    let mut buf: Vec<u8> = Vec::with_capacity(1024);
    let mut chunk = [0u8; 1024];
    loop {
        match s.read(&mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                buf.extend_from_slice(&chunk[..n]);
                if buf.len() > 8192 {
                    break;
                }
            }
        }
    }
    let text = String::from_utf8_lossy(&buf);
    let (head, body) = text.split_once("\r\n\r\n")?;
    if !head.starts_with("HTTP/1.1 200") {
        return None;
    }
    let v: serde_json::Value = serde_json::from_str(body.trim()).ok()?;
    if v.get("who")?.as_str()? != SERVICE_NAME {
        return None;
    }
    Some(PingInfo {
        pid: v.get("pid")?.as_u64()? as u32,
        bin_sha: v.get("bin_sha").and_then(|x| x.as_str()).unwrap_or_default().to_string(),
    })
}

/// 守护进程跑的是不是盘上这一份 ksud？
///
/// 两边指纹**都拿得到且相同**才算"一样"（任何一边为空都保守地认为"不一样"→ 重启）。
/// 抽成纯函数是为了能在 Mac 上单测这条判据（`sync_if_needed` 本身在 Android 侧）。
pub fn ksud_same(running_sha: &str, disk_sha: &str) -> bool {
    !running_sha.is_empty() && !disk_sha.is_empty() && running_sha == disk_sha
}

/// **本进程正在执行的那个二进制**的 sha256（只算一次，之后走缓存）。
///
/// 读 `/proc/self/exe` 而不是某个路径：APK 升级后 `/data/adb/ksud` 是**新文件**，
/// 而老进程还跑着被 unlink 的旧 inode —— 读 `/proc/self/exe` 拿到的才是"我到底在跑哪份代码"。
pub fn self_exe_sha() -> &'static str {
    static CACHE: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    CACHE.get_or_init(|| {
        // Android 上优先 /proc/self/exe（APK 升级后路径可能已被 unlink，读它才准）；
        // 开发机（macOS）没有 /proc，退回 current_exe()，这样这段逻辑在本机也能测。
        let bytes = std::fs::read("/proc/self/exe")
            .or_else(|_| std::env::current_exe().and_then(std::fs::read));
        match bytes {
            Ok(bytes) => sha256::digest(bytes),
            Err(e) => {
                warn!("读自己的可执行文件失败（{e}），二进制指纹留空");
                String::new()
            }
        }
    })
}



/// 服务自己的名字 —— `/api/ping` 用它表明"这个端口上是我"。
///
/// ⚠️ 这里踩过一个隐蔽的坑（2026-09-17，换端口修了三次都"失败"的真凶）：
/// 原来判活是搜响应里的 `{"ok":true`，但 `/api/status` 的 JSON **压根没有 ok 字段**，
/// 而且 serde 默认按字母序输出键、`{` 后面跟的是别的键 —— 于是判活**永远为假**，
/// 新起的服务明明绑上了端口、也被当成"没起来"杀掉。
pub const SERVICE_NAME: &str = "7kimisu";

/// 找出**真的在回话**的端口。配置里的 `bound_port` 可能是别的进程留下的过期值
/// （比如手工起过一个测试服务），所以这里逐个探一遍，别直接信配置。
pub fn effective_port() -> Option<u16> {
    let c = Config::load();
    let mut ports: Vec<u16> = Vec::new();
    if c.bound_port > 0 {
        ports.push(c.bound_port);
    }
    if c.port > 0 && !ports.contains(&c.port) {
        ports.push(c.port);
    }
    for p in CANDIDATE_PORTS {
        if !ports.contains(&p) {
            ports.push(p);
        }
    }
    ports.into_iter().find(|p| probe_own(*p))
}


/// 路径前缀：猜不到的一段（**注意：它本身不是秘密**，只是挡住乱扫；
/// 真正的钥匙是 [base_path] 里拼上去的 token）
pub const PATH: &str = "/x7k9f";
/// 配置（含 token）存放位置
pub const CONFIG_PATH: &str = "/data/adb/sevenk/webadmin.conf";

/// token 的字节数（32 字节 = 256 bit → 64 位十六进制）。
///
/// 为什么是 256 bit：这是**唯一**的访问凭据，且用户会把它复制到浏览器书签里长期用。
/// 128 bit 已经够，但 32 字节不增加任何使用成本（URL 里长一点而已），余量给足。
pub const TOKEN_BYTES: usize = 32;
/// token 的字符长度（十六进制）
pub const TOKEN_LEN: usize = TOKEN_BYTES * 2;

/// 实际用的配置文件路径（可用环境变量覆盖，方便在开发机上测试）
fn config_path() -> String {
    std::env::var("WEBADMIN_CONF").unwrap_or_else(|_| CONFIG_PATH.to_string())
}

/// 本机服务的**完整路径前缀** = `{PATH}/{token}`。所有请求都必须以它开头。
///
/// token 为空（还没生成）时退回 [PATH] —— 那种情况下 [authed_rel] 会拒绝一切请求
/// （空 token 永远不匹配），也就是"没配置好 = 谁也别进"。
pub fn base_path() -> String {
    let token = Config::load().token;
    if token.is_empty() {
        PATH.to_string()
    } else {
        format!("{PATH}/{token}")
    }
}

/// 模块自带 WebUI（`/modweb/<id>/…`）的 HTML 注入片段（🔴3，v2.17 修）。
///
/// `base` 必须是 [`base_path`]（`{PATH}/{token}`），**不能**是裸 [`PATH`]：
///   · `window.__7K_BASE__` 是 `modbridge.js` 里 `fetch(BASE + api)` / `XHR.open(BASE+api)`
///     的前缀 —— 少了 token，`ksu.exec/spawn/listPackages` 这些请求全部 401；
///   · `<script src>` 本身也要带 token，否则**这个脚本文件自己**就取不到
///     （`assets/webadmin/modbridge.js:31` 的 `window.__7K_BASE__ || ""` 没有回退），
///      于是 `window.ksu` 压根不存在 ⇒ 模块网页桥**整条死掉**。
///
/// 背景：v2.13（`5bc2086`）给网页管理器加专属密钥鉴权时，把服务端路由都改成了
/// `{PATH}/{token}/...`，却漏了这个注入点 —— 它还在用裸 `PATH`，于是模块自带的 WebUI
/// 从 v2.13 起一直必 401（功能回归，v2.16 审计发现）。
///
/// 为什么抽成纯函数放在这里：`webadmin_ksud.rs` 整个文件是 `#![cfg(target_os = "android")]`，
/// 在 Mac 上编不进去 ⇒ 注入字符串的形状只能在**这一侧**单测（见 `module_bridge_tag_*`）。
#[must_use]
pub fn module_bridge_tag(base: &str, module_info_json: &str) -> String {
    format!(
        "<script>window.__7K_BASE__=\"{base}\"</script>\
         <script>window.__7K_MODULE__={module_info_json}</script>\
         <script src=\"{base}/a/modbridge.js\"></script>"
    )
}

/// **恒定时间**比较：`a == b`，但**不因第一个不同的字节而提前返回**。
///
/// 为什么要它：普通的 `==` 在第一个不同字节就返回，比较耗时与"猜对了几个前缀字节"
/// 相关 —— 攻击者可以靠测量响应时间一个字节一个字节地把 token 试出来（时序侧信道）。
/// 这里对 `max(len)` 个位置全部做异或累加，长度差异也折进累加值里，没有短路分支。
///
/// 抽成 pub 是为了能在 Mac 上直接单测这条判据。
pub fn ct_eq(expected: &str, given: &str) -> bool {
    let (expected, given) = (expected.as_bytes(), given.as_bytes());
    // 长度不同 → 结果必然为假（长度本身不是秘密：我们发出的一直是 64 位十六进制）
    let mut diff: u8 = u8::from(expected.len() != given.len());
    let count = expected.len().max(given.len());
    for idx in 0..count {
        let left = expected.get(idx).copied().unwrap_or(0);
        let right = given.get(idx).copied().unwrap_or(0);
        diff |= left ^ right;
    }
    diff == 0
}

/// 从请求路径里剥出「鉴权后的相对路径」。
///
/// 通过 → `Some("/api/status")` 这种（**带前导斜杠**，与旧路由代码的形状一致）；
/// 不通过 → `None`。三种"不通过"（前缀不对 / 没带 token / token 错）**返回值完全一样**，
/// 调用方也只回同一个 401，所以攻击者无法区分"路径猜错了"还是"token 猜错了"。
pub fn authed_rel(path: &str, token: &str) -> Option<String> {
    if token.is_empty() {
        return None;
    }
    let rest = path.strip_prefix(PATH)?;
    let rest = rest.strip_prefix('/')?;
    let (given, tail) = match rest.split_once('/') {
        Some((given, tail)) => (given, tail),
        None => (rest, ""),
    };
    if !ct_eq(given, token) {
        return None;
    }
    Some(format!("/{tail}"))
}


const MAX_HEAD: usize = 16 * 1024;
const MAX_BODY: usize = 64 * 1024;
/// 上传装模块的体量上限（模块 zip 一般几 MB ~ 几十 MB；给足余量但不无限）
const MAX_UPLOAD: usize = 512 * 1024 * 1024;
const IO_TIMEOUT: Duration = Duration::from_secs(15);
/// 上传落盘的临时目录（可用环境变量覆盖，方便在开发机上测）
const UPLOAD_DIR: &str = "/data/adb/sevenk";

/// 网页资源（编译期嵌入。改完资源必须重新编译 ksud）
#[derive(rust_embed::RustEmbed)]
#[folder = "assets/webadmin"]
struct WebAsset;

/// 允许对外提供的静态文件白名单（写死，防目录穿越）
const ALLOWED_ASSETS: &[&str] = &[
    "modbridge.js",
    "themes.css",
    "app.css",
    "app.js",
    "icons.svg",
    "licenses/Lucide-ISC.txt",
    "licenses/Catppuccin-MIT.txt",
    "licenses/Nord-MIT.txt",
    "licenses/Dracula-MIT.txt",
];

/// 数据来源抽象：让 HTTP 这一段与 Android 解耦（好在本机测试）
pub trait Backend: Send + Sync + 'static {
    fn status(&self) -> serde_json::Value;
    fn modules(&self) -> serde_json::Value;
    fn toggle_module(&self, id: &str, enable: bool) -> Result<String, String>;
    fn uninstall_module(&self, id: &str) -> Result<String, String>;
    fn undo_uninstall_module(&self, id: &str) -> Result<String, String>;
    fn set_stealth(&self, enable: bool) -> Result<String, String>;
    fn superusers(&self) -> serde_json::Value;
    /// 改隐身拨号密令（退出隐身用）
    fn set_stealth_code(&self, code: &str) -> Result<String, String>;

    // ── 第二批：超级用户 / 超管日志 / 上传装模块 ──
    /// 全部已安装 App + 各自的授权状态（给超级用户页）
    fn apps(&self) -> serde_json::Value;
    /// 授权 / 撤销某个 App 的 root
    fn set_app_root(&self, pkg: &str, uid: u32, allow: bool) -> Result<String, String>;
    /// 读某个 App 的 root 配置
    fn app_profile(&self, pkg: &str, uid: u32) -> Result<serde_json::Value, String>;
    /// 写某个 App 的 root 配置（只应用 form 里出现的字段，其余保持原样）
    fn save_app_profile(
        &self,
        pkg: &str,
        uid: u32,
        form: &HashMap<String, String>,
    ) -> Result<String, String>;
    /// 超级用户日志（读 sulogd 落盘的文本）
    fn sulog(&self, limit: usize, query: &str) -> serde_json::Value;
    /// 清空超级用户日志
    fn clear_sulog(&self) -> Result<String, String>;
    /// 超级用户日志开关（内核 feature: sulog）
    fn set_sulog_enabled(&self, enable: bool) -> Result<String, String>;
    /// 装一个模块（zip 已由 HTTP 层落到 `path`）
    fn install_module_zip(&self, path: &str) -> Result<String, String>;

    // ── 第二批第 4 项：模块动作脚本输出 + 模块网页（WebUI）──
    /// 跑模块的 action.sh 并把输出带回来（返回 {code, output}）
    fn module_action(&self, id: &str) -> Result<serde_json::Value, String>;
    /// 模块网页里的 `ksu.exec`（返回 {errno, stdout, stderr}；options 支持 cwd/env）
    fn module_exec(&self, id: &str, cmd: &str, options_json: &str) -> Result<serde_json::Value, String>;
    /// 模块网页的静态文件；html 里由实现方注入桥脚本（返回 mime + 字节）
    fn module_web_file(&self, id: &str, rel: &str) -> Result<(String, Vec<u8>), String>;
    /// 模块网页的 `ksu.listPackages(type)`：返回 {packages:[…]}
    fn module_packages(&self, kind: &str) -> Result<serde_json::Value, String>;
    /// 模块网页的 `ksu.getPackagesInfo(names)`：返回 {list:[…]}
    fn module_packages_info(&self, names_json: &str) -> Result<serde_json::Value, String>;
    /// 模块网页的 `ksu.spawn(...)`：起子进程，返回 {sid}
    fn module_spawn_start(
        &self,
        id: &str,
        cmd: &str,
        args: &[String],
        options_json: &str,
    ) -> Result<serde_json::Value, String>;
    /// 轮询 spawn 的输出增量：返回 {stdout, stderr, done, code}
    fn module_spawn_poll(&self, sid: &str) -> Result<serde_json::Value, String>;
}

/// 运行时状态（口令 + 失败计数）
pub struct Server {
    backend: Arc<dyn Backend>,
}

impl Server {
    pub fn new(backend: Arc<dyn Backend>) -> Arc<Self> {
        Arc::new(Self { backend })
    }
}


/// 启动监听（阻塞在 accept 循环；调用方负责放进线程）
pub fn serve(server: &Arc<Server>) -> std::io::Result<()> {
    // 逐个试候选端口：某台手机上 18427 可能被别的 App 占了，自动顺延
    let cfg0 = Config::load();
    let mut tries: Vec<u16> = Vec::with_capacity(CANDIDATE_PORTS.len() + 1);
    // 优先级：命令行显式指定 > 配置文件里的首选 > 固定备选
    let want = if port_override() >= 1024 { port_override() } else { cfg0.port };
    if want >= 1024 {
        tries.push(want);
    }
    for p in CANDIDATE_PORTS {
        if !tries.contains(&p) {
            tries.push(p);
        }
    }
    let mut bound: Option<(TcpListener, u16)> = None;
    for p in &tries {
        match TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, *p))) {
            Ok(l) => {
                bound = Some((l, *p));
                break;
            }
            Err(e) => warn!("端口 {p} 绑不上（{e}），试下一个"),
        }
    }
    let (listener, port) = bound.ok_or_else(|| {
        std::io::Error::new(
            std::io::ErrorKind::AddrInUse,
            format!("候选端口全被占用：{tries:?}"),
        )
    })?;
    BOUND_PORT.store(port, std::sync::atomic::Ordering::Relaxed);
    // 把实际端口写回配置，命令行 `ksud webadmin url` 才能给出正确地址
    let mut cfg = Config::load();
    // 🔑 服务启动时**保证 token 存在**：老版本升级上来的配置文件里没有 token，
    //    这里补生成一次（之后就一直沿用，用户的书签不会失效）。
    //    注意 ensure_token() 必须**无条件调用**（不能写成 `a || ensure_token()` 被短路掉）。
    let generated = cfg.ensure_token();
    if generated || cfg.bound_port != port {
        cfg.bound_port = port;
        // 🟠（v2.19）旧写法 `let _ = cfg.save()` 把失败**吞掉**：ENOSPC / 权限异常时
        // 新 token（或端口）没落盘 ⇒ 之后所有请求恒定 401、服务反复重启，
        // 而日志里**零线索**（用户只看到"打不开"）。失败本身不致命（内存里那份已生效），
        // 但必须留一条痕，否则没法排查。
        if let Err(e) = cfg.save() {
            warn!("网页管理器配置写盘失败（内存里已生效；token/端口可能没落盘）: {e}");
        }
    }
    let listener = listener;
    let _ = listener.set_nonblocking(false);
    // ⚠️ 日志里**绝不写 token**（logcat 别的 App 也读得到）。
    info!("网页管理器已启动：http://127.0.0.1:{port}{PATH}/<专属密钥>（仅本机；密钥在 webadmin.conf）");
    for stream in listener.incoming() {
        match stream {
            Ok(mut s) => {
                // 🔴（v2.18）并发上限：先占名额再起线程，满了就回 503 关掉。
                // 关键点：**每个成功起线程的路径都要还名额**（用 RAII 守卫，
                // `handle()` panic/提前返回都不会漏），否则名额泄漏会让服务"越用越锁死"。
                let slot = ACTIVE_CONNS.fetch_add(1, std::sync::atomic::Ordering::AcqRel);
                if slot >= MAX_CONNS {
                    ACTIVE_CONNS.fetch_sub(1, std::sync::atomic::Ordering::AcqRel);
                    warn!("网页管理器并发连接超过 {MAX_CONNS}，拒绝一个连接");
                    let _ = respond(&mut s, 503, PLAIN, b"Busy");
                    continue;
                }
                let server = server.clone();
                std::thread::spawn(move || {
                    struct SlotGuard;
                    impl Drop for SlotGuard {
                        fn drop(&mut self) {
                            ACTIVE_CONNS.fetch_sub(1, std::sync::atomic::Ordering::AcqRel);
                        }
                    }
                    let _slot = SlotGuard;
                    if let Err(e) = handle(&server, s) {
                        debug!("网页管理器请求处理失败: {e}");
                    }
                });
            }
            Err(e) => warn!("网页管理器 accept 失败: {e}"),
        }
    }
    Ok(())
}

fn handle(server: &Server, stream: TcpStream) -> std::io::Result<()> {
    stream.set_read_timeout(Some(IO_TIMEOUT))?;
    stream.set_write_timeout(Some(IO_TIMEOUT))?;
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut writer = stream;

    let head = match read_head(&mut reader)? {
        HeadRead::Head(h) => h,
        HeadRead::Empty => return respond(&mut writer, 404, PLAIN, b"Not Found"),
        // 🟠（v2.18）超长头**明确回 431**（旧写法继续解析半截头、什么都不回）
        HeadRead::TooLarge => {
            return respond(
                &mut writer,
                431,
                PLAIN,
                b"Request Header Fields Too Large",
            );
        }
    };

    let mut lines = head.split("\r\n");
    let req_line = lines.next().unwrap_or("");
    let mut parts = req_line.split(' ');
    let method = parts.next().unwrap_or("").to_uppercase();
    let target = parts.next().unwrap_or("").to_string();

    let mut content_length = 0usize;
    for line in lines {
        if let Some((k, v)) = line.split_once(':')
            && k.eq_ignore_ascii_case("content-length")
        {
            content_length = v.trim().parse::<usize>().unwrap_or(0);
        }
    }

    let (path, query) = match target.split_once('?') {
        Some((p, q)) => (p.to_string(), parse_query(q)),
        None => (target.clone(), HashMap::new()),
    };
    let mut params = query;

    // ── 鉴权（2026-09-18）：路径必须是 `{PATH}/{token}[/...]` ──
    //
    // 三种失败（没带 / 带错 / 前缀都不对）在这里被**折叠成同一个分支**：
    // 回一模一样的 401，响应体固定 `Unauthorized`。不回显任何东西 ——
    // 既不告诉对方"token 错"还是"路径错"，也不告诉对方正确的路径结构，
    // 更不做跳转（跳转本身就会泄露"你差一点猜对了"）。
    //
    // 为什么先 drain_body：与上传被拒时同一个坑 —— 不等客户端把 body 发完就关连接，
    // 对方收到的是 RST，表现成"网络错误"而不是我们的 401。
    let cfg_token = Config::load().token;
    let Some(rel) = authed_rel(&path, &cfg_token) else {
        drain_body(&mut reader, content_length);
        return respond(&mut writer, 401, PLAIN, b"Unauthorized");
    };
    let rel: &str = &rel;

    if rel.is_empty() || rel == "/" {
        let page = render_page();
        return respond(&mut writer, 200, HTML, page.as_bytes());
    }
    if let Some(name) = rel.strip_prefix("/a/") {
        return match asset_for(name) {
            Some((mime, data)) => respond(&mut writer, 200, mime, &data),
            None => respond(&mut writer, 404, PLAIN, b"Not Found"),
        };
    }

    // ── 模块网页（WebUI）：把模块 webroot 下的文件喂给页面里的 iframe ──
    //    路径形如 {PATH}/modweb/<模块id>/index.html（照 DickSU 的 /modweb/<id>/ 做法）
    if let Some(rest) = rel.strip_prefix("/modweb/") {
        if method != "GET" {
            return respond(&mut writer, 404, PLAIN, b"Not Found");
        }
        let (id, inner) = match rest.split_once('/') {
            Some((id, p)) => (id.to_string(), p.to_string()),
            None => (rest.to_string(), "index.html".to_string()),
        };
        if !valid_id(&id) {
            return respond(&mut writer, 404, PLAIN, b"Not Found");
        }
        return match server.backend.module_web_file(&id, &inner) {
            Ok((mime, data)) => respond_csp(&mut writer, 200, &mime, &data, CSP_MODWEB),
            Err(e) => {
                debug!("模块网页取文件失败（{id}/{inner}）: {e}");
                respond(&mut writer, 404, PLAIN, b"Not Found")
            }
        };
    }

    // 写操作一律要求 POST（防误触/防预取）
    macro_rules! require_post {
        () => {
            if method != "POST" {
                return respond(&mut writer, 404, PLAIN, b"Not Found");
            }
        };
    }

    // ── 上传装模块：请求体是**原始 zip 字节**（不是表单，也不是 multipart）──
    //    前端用 fetch/XHR 直接 POST 一个 File，所以这里只需按 Content-Length 搬字节。
    if rel == "/api/module/install" {
        require_post!();
        let name = params.get("name").cloned().unwrap_or_default();
        let is_zip_name = std::path::Path::new(&name)
            .extension()
            .is_some_and(|e| e.eq_ignore_ascii_case("zip"));
        if !is_zip_name {
            drain_body(&mut reader, content_length);
            return respond(
                &mut writer,
                200,
                JSON,
                result_json(Err("只接受 .zip 模块包".to_string())).as_bytes(),
            );
        }
        if content_length == 0 {
            return respond(
                &mut writer,
                200,
                JSON,
                result_json(Err("上传的内容是空的".to_string())).as_bytes(),
            );
        }
        if content_length > MAX_UPLOAD {
            return respond(
                &mut writer,
                200,
                JSON,
                result_json(Err(format!(
                    "文件太大（{} MB，上限 {} MB）",
                    content_length / 1024 / 1024,
                    MAX_UPLOAD / 1024 / 1024
                )))
                .as_bytes(),
            );
        }
        let tmp = upload_path();
        if let Some(dir) = std::path::Path::new(&tmp).parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let saved = save_upload(&mut reader, &tmp, content_length);
        let out = match saved {
            Ok(()) => {
                // 只认 ZIP 魔数（PK），挡住把别的文件改名成 .zip 的误操作。
                // ⚠️ 只读头 4 字节 —— 别把一个上百 MB 的包整个读进内存。
                let is_zip = std::fs::File::open(&tmp)
                    .and_then(|mut f| {
                        use std::io::Read as _;
                        let mut magic = [0u8; 4];
                        f.read_exact(&mut magic).map(|()| magic)
                    })
                    .is_ok_and(|magic| &magic[..2] == b"PK");
                if is_zip {
                    server.backend.install_module_zip(&tmp)
                } else {
                    Err("这不是一个 zip 包（开头不是 PK）".to_string())
                }
            }
            Err(e) => Err(format!("保存上传文件失败: {e}")),
        };
        let _ = std::fs::remove_file(&tmp);
        return respond(&mut writer, 200, JSON, result_json(out).as_bytes());
    }

    // 其余接口：请求体是表单（很小的 key=value），并进 params
    if content_length > MAX_BODY {
        // 🟠（v2.18）表单体有明确上限：超了直接回 413（旧写法只读前 64 KB 就当读完，
        // 剩下的字节留在连接里，既可能被当成下一条请求、也让对方等我们关连接）
        drain_body(&mut reader, content_length);
        return respond(&mut writer, 413, PLAIN, b"Payload Too Large");
    }
    if content_length > 0 {
        let mut buf = vec![0u8; content_length];
        // 🟠（v2.18）body 被截断时**必须回一条响应**。
        // 旧写法 `read_exact(...)?` 把错误直接冒出 `handle()` ⇒ 连接被静默关掉，
        // 对方看到的是"网络错误"而不是我们的提示（上传路径早就用 `drain_body` 绕过这个坑）。
        if let Err(e) = reader.read_exact(&mut buf) {
            return respond(
                &mut writer,
                400,
                JSON,
                result_json(Err(format!("请求体不完整（{e}）"))).as_bytes(),
            );
        }
        for (k, v) in parse_query(&String::from_utf8_lossy(&buf)) {
            params.insert(k, v);
        }
    }

    match rel {
        "/api/status" => respond(&mut writer, 200, JSON, server.backend.status().to_string().as_bytes()),
        // 专用探活接口：给"这个端口上是不是我们自己的服务"当判据（见 SERVICE_NAME 的注释）
        "/api/ping" => respond(
            &mut writer,
            200,
            JSON,
            serde_json::json!({
                "ok": true,
                "who": SERVICE_NAME,
                "pid": std::process::id(),
                "bin_sha": self_exe_sha(),
            })
                .to_string()
                .as_bytes(),
        ),
        "/api/modules" => respond(&mut writer, 200, JSON, server.backend.modules().to_string().as_bytes()),
        "/api/superusers" => {
            respond(&mut writer, 200, JSON, server.backend.superusers().to_string().as_bytes())
        }
        // 超级用户：全部 App + 授权状态
        "/api/apps" => respond(&mut writer, 200, JSON, server.backend.apps().to_string().as_bytes()),
        // 超级用户：读某个 App 的 root 配置
        "/api/app/profile" if method == "GET" => {
            let uid = params.get("uid").and_then(|v| v.trim().parse::<u32>().ok());
            let pkg = params.get("pkg").cloned().unwrap_or_default();
            let body = match uid {
                Some(uid) if valid_pkg(&pkg) => match server.backend.app_profile(&pkg, uid) {
                    Ok(v) => serde_json::json!({ "ok": true, "profile": v }).to_string(),
                    Err(e) => result_json(Err(e)),
                },
                _ => result_json(Err("pkg 或 uid 不合法".to_string())),
            };
            respond(&mut writer, 200, JSON, body.as_bytes())
        }
        // 超级用户：授权 / 撤销 root（列表上那个开关）
        "/api/app/root" => {
            require_post!();
            let uid = params.get("uid").and_then(|v| v.trim().parse::<u32>().ok());
            let pkg = params.get("pkg").cloned().unwrap_or_default();
            let allow = params.get("allow").map(String::as_str) == Some("1");
            let out = match uid {
                Some(uid) if valid_pkg(&pkg) => server.backend.set_app_root(&pkg, uid, allow),
                _ => Err("pkg 或 uid 不合法".to_string()),
            };
            respond(&mut writer, 200, JSON, result_json(out).as_bytes())
        }
        // 超级用户：保存 root 配置（表单字段见 webadmin_ksud::save_app_profile）
        "/api/app/profile" => {
            require_post!();
            let uid = params.get("uid").and_then(|v| v.trim().parse::<u32>().ok());
            let pkg = params.get("pkg").cloned().unwrap_or_default();
            let out = match uid {
                Some(uid) if valid_pkg(&pkg) => server.backend.save_app_profile(&pkg, uid, &params),
                _ => Err("pkg 或 uid 不合法".to_string()),
            };
            respond(&mut writer, 200, JSON, result_json(out).as_bytes())
        }
        // 超级用户日志
        "/api/sulog" => {
            let limit = params
                .get("limit")
                .and_then(|v| v.trim().parse::<usize>().ok())
                .unwrap_or(400)
                .clamp(1, 2000);
            let q = params.get("q").cloned().unwrap_or_default();
            respond(
                &mut writer,
                200,
                JSON,
                server.backend.sulog(limit, &q).to_string().as_bytes(),
            )
        }
        "/api/sulog/clear" => {
            require_post!();
            let out = server.backend.clear_sulog();
            respond(&mut writer, 200, JSON, result_json(out).as_bytes())
        }
        "/api/sulog/enable" => {
            require_post!();
            let enable = params.get("enable").map(String::as_str) == Some("1");
            let out = server.backend.set_sulog_enabled(enable);
            respond(&mut writer, 200, JSON, result_json(out).as_bytes())
        }
        "/api/module/toggle" => {
            require_post!();
            let id = params.get("id").cloned().unwrap_or_default();
            let enable = params.get("enable").map(String::as_str) == Some("1");
            let out = if valid_id(&id) {
                server.backend.toggle_module(&id, enable)
            } else {
                Err("模块 id 不合法".to_string())
            };
            respond(&mut writer, 200, JSON, result_json(out).as_bytes())
        }
        // 跑模块的动作脚本，把输出带回来给网页显示
        "/api/module/action" => {
            require_post!();
            let id = params.get("id").cloned().unwrap_or_default();
            if !valid_id(&id) {
                return respond(&mut writer, 200, JSON, result_json(Err("模块 id 不合法".into())).as_bytes());
            }
            let body = match server.backend.module_action(&id) {
                Ok(mut v) => {
                    if let Some(o) = v.as_object_mut() {
                        o.insert("ok".to_string(), serde_json::json!(true));
                    }
                    v.to_string()
                }
                Err(e) => result_json(Err(e)),
            };
            respond(&mut writer, 200, JSON, body.as_bytes())
        }
        // 模块网页里的 ksu.exec（桥脚本调它）
        "/api/module/exec" => {
            require_post!();
            let id = params.get("id").cloned().unwrap_or_default();
            let cmd = params.get("cmd").cloned().unwrap_or_default();
            let options = params.get("options").cloned().unwrap_or_default();
            if !valid_id(&id) || cmd.trim().is_empty() {
                return respond(&mut writer, 200, JSON, result_json(Err("参数不合法".into())).as_bytes());
            }
            let body = match server.backend.module_exec(&id, &cmd, &options) {
                Ok(mut v) => {
                    if let Some(o) = v.as_object_mut() {
                        o.insert("ok".to_string(), serde_json::json!(true));
                    }
                    v.to_string()
                }
                Err(e) => result_json(Err(e)),
            };
            respond(&mut writer, 200, JSON, body.as_bytes())
        }
        // 模块网页的 ksu.listPackages / getPackagesInfo（同步接口）
        "/api/module/packages" => {
            require_post!();
            let kind = params.get("type").cloned().unwrap_or_default();
            let body = match server.backend.module_packages(&kind) {
                Ok(mut v) => {
                    if let Some(o) = v.as_object_mut() {
                        o.insert("ok".to_string(), serde_json::json!(true));
                    }
                    v.to_string()
                }
                Err(e) => result_json(Err(e)),
            };
            respond(&mut writer, 200, JSON, body.as_bytes())
        }
        "/api/module/packages-info" => {
            require_post!();
            let names = params.get("names").cloned().unwrap_or_else(|| "[]".to_string());
            let body = match server.backend.module_packages_info(&names) {
                Ok(mut v) => {
                    if let Some(o) = v.as_object_mut() {
                        o.insert("ok".to_string(), serde_json::json!(true));
                    }
                    v.to_string()
                }
                Err(e) => result_json(Err(e)),
            };
            respond(&mut writer, 200, JSON, body.as_bytes())
        }
        // 模块网页的 ksu.spawn：起子进程 + 轮询输出
        "/api/module/spawn" => {
            require_post!();
            let id = params.get("id").cloned().unwrap_or_default();
            let cmd = params.get("cmd").cloned().unwrap_or_default();
            let args: Vec<String> = params
                .get("args")
                .and_then(|a| serde_json::from_str(a).ok())
                .unwrap_or_default();
            let options = params.get("options").cloned().unwrap_or_default();
            if !valid_id(&id) || cmd.trim().is_empty() {
                return respond(&mut writer, 200, JSON, result_json(Err("参数不合法".into())).as_bytes());
            }
            let body = match server.backend.module_spawn_start(&id, &cmd, &args, &options) {
                Ok(mut v) => {
                    if let Some(o) = v.as_object_mut() {
                        o.insert("ok".to_string(), serde_json::json!(true));
                    }
                    v.to_string()
                }
                Err(e) => result_json(Err(e)),
            };
            respond(&mut writer, 200, JSON, body.as_bytes())
        }
        "/api/module/spawn/poll" => {
            require_post!();
            let sid = params.get("sid").cloned().unwrap_or_default();
            let body = match server.backend.module_spawn_poll(&sid) {
                Ok(mut v) => {
                    if let Some(o) = v.as_object_mut() {
                        o.insert("ok".to_string(), serde_json::json!(true));
                    }
                    v.to_string()
                }
                Err(e) => result_json(Err(e)),
            };
            respond(&mut writer, 200, JSON, body.as_bytes())
        }
        "/api/module/uninstall" => {
            require_post!();
            let id = params.get("id").cloned().unwrap_or_default();
            let out = if valid_id(&id) {
                server.backend.uninstall_module(&id)
            } else {
                Err("模块 id 不合法".to_string())
            };
            respond(&mut writer, 200, JSON, result_json(out).as_bytes())
        }
        "/api/module/undo-uninstall" => {
            require_post!();
            let id = params.get("id").cloned().unwrap_or_default();
            let out = if valid_id(&id) {
                server.backend.undo_uninstall_module(&id)
            } else {
                Err("模块 id 不合法".to_string())
            };
            respond(&mut writer, 200, JSON, result_json(out).as_bytes())
        }
        "/api/stealth" => {
            require_post!();
            let enable = params.get("enable").map(String::as_str) == Some("1");
            let out = server.backend.set_stealth(enable);
            respond(&mut writer, 200, JSON, result_json(out).as_bytes())
        }
        "/api/stealth-code" => {
            require_post!();
            let code = params.get("code").cloned().unwrap_or_default();
            let out = if valid_code(&code) {
                server.backend.set_stealth_code(&code)
            } else {
                Err("密令只能是 4~12 位数字".to_string())
            };
            respond(&mut writer, 200, JSON, result_json(out).as_bytes())
        }
        // 其余（含模块自定义图标：前端 404 会退回首字母头像）一律 404
        _ => respond(&mut writer, 404, PLAIN, b"Not Found"),
    }
}

const PLAIN: &str = "text/plain; charset=utf-8";
const HTML: &str = "text/html; charset=utf-8";
const JSON: &str = "application/json; charset=utf-8";

fn result_json(out: Result<String, String>) -> String {
    match out {
        Ok(msg) => serde_json::json!({ "ok": true, "message": msg }).to_string(),
        Err(err) => serde_json::json!({ "ok": false, "error": err }).to_string(),
    }
}

/// 拨号密令规则与 App 里一致：4~12 位纯数字
fn valid_code(code: &str) -> bool {
    (4..=12).contains(&code.len()) && code.bytes().all(|b| b.is_ascii_digit())
}

fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && !id
            .chars()
            .any(|c| c.is_control() || c == '/' || c == '\\')
}

/// 包名规则（比 valid_id 更严：Android 包名只允许字母数字下划线和点）
pub fn valid_pkg(pkg: &str) -> bool {
    !pkg.is_empty()
        && pkg.len() <= 200
        && pkg
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_')
}

/// 上传临时文件名（进程号 + 随机后缀，避免并发请求互相踩）
///
/// 🔴（v2.18）旧写法**只有** `std::process::id()`，而服务是**每个连接起一个线程** ——
/// 同一个进程里两个并发上传会算出**同一个路径**，`File::create` 互相截断 ⇒
/// 装出来的模块 zip 是坏的（还可能被误判成"这不是一个 zip 包"）。
/// 现在加一段随机后缀；`random_hex` 在拿不到 `/dev/urandom` 时退回时间戳 ——
/// 这里只需要"别撞车"，不是安全用途，正好用它的弱兜底。
fn upload_path() -> String {
    let dir = std::env::var("WEBADMIN_UPLOAD_DIR").unwrap_or_else(|_| UPLOAD_DIR.to_string());
    let uniq = random_hex(8).unwrap_or_else(|| {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_nanos())
            .to_string()
    });
    format!("{dir}/webadmin-upload-{}-{uniq}.zip", std::process::id())
}

/// 回错误**之前**把请求体读掉。
///
/// 为什么要读：如果我们不等客户端把 body 发完就关连接，对方会收到 **RST** ——
/// 真机上的表现是"上传被拒时浏览器报网络错误，而不是我们写的提示"。
/// （这个坑是从测试偶发 `ConnectionReset` 里挖出来的。）
fn drain_body(reader: &mut BufReader<TcpStream>, len: usize) {
    let mut left = len.min(8 * 1024 * 1024);
    let mut buf = vec![0u8; 64 * 1024];
    while left > 0 {
        let want = left.min(buf.len());
        match reader.read(&mut buf[..want]) {
            Ok(0) | Err(_) => break,
            Ok(n) => left -= n,
        }
    }
}

/// 把请求体按 Content-Length 原样搬进文件（流式，不在内存里堆整个 zip）
fn save_upload(reader: &mut BufReader<TcpStream>, path: &str, len: usize) -> std::io::Result<()> {
    use std::io::Write as _;
    let mut file = std::fs::File::create(path)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
    }
    let mut left = len;
    let mut buf = vec![0u8; 64 * 1024];
    while left > 0 {
        let want = left.min(buf.len());
        let n = reader.read(&mut buf[..want])?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "请求体提前结束",
            ));
        }
        file.write_all(&buf[..n])?;
        left -= n;
    }
    file.flush()
}

/// 静态资源：白名单（路径前缀是编译期常量，运行时替换进去）
///
/// 注入的是 [base_path]（`{PATH}/{token}`）而不是裸 [PATH] —— 前端所有请求都靠这个
/// 前缀拼 URL，注入一次就自动带上 token（见文件头"专属链接"）。
fn asset_for(name: &str) -> Option<(&'static str, Vec<u8>)> {
    if !ALLOWED_ASSETS.contains(&name) || name.contains("..") {
        return None;
    }
    let file = WebAsset::get(name)?;
    let mime = mime_of(name);
    if name == "app.js" {
        let text = String::from_utf8_lossy(&file.data).replace("__P__", &base_path());
        return Some((mime, text.into_bytes()));
    }
    Some((mime, file.data.into_owned()))
}

fn render_page() -> String {
    match WebAsset::get("index.html") {
        Some(f) => String::from_utf8_lossy(&f.data).replace("__P__", &base_path()),
        None => "<!doctype html><meta charset=utf-8><h1>页面资源缺失</h1>".to_string(),
    }
}

fn mime_of(name: &str) -> &'static str {
    // ⚠️ 用 eq_ignore_ascii_case：`ends_with(".css")` 是大小写敏感的，
    //    遇到 `.CSS` 会当成纯文本（clippy::case_sensitive_file_extension_comparison 也盯这个）
    let ext = name.rsplit_once('.').map_or("", |(_, e)| e);
    if ext.eq_ignore_ascii_case("css") {
        "text/css; charset=utf-8"
    } else if ext.eq_ignore_ascii_case("js") {
        "application/javascript; charset=utf-8"
    } else if ext.eq_ignore_ascii_case("svg") {
        "image/svg+xml; charset=utf-8"
    } else {
        PLAIN
    }
}

fn read_head(reader: &mut BufReader<TcpStream>) -> std::io::Result<HeadRead> {
    let mut buf = Vec::with_capacity(1024);
    let mut byte = [0u8; 1];
    while buf.len() < MAX_HEAD {
        match reader.read(&mut byte) {
            Ok(0) => break,
            Ok(_) => {
                buf.push(byte[0]);
                if buf.ends_with(b"\r\n\r\n") {
                    break;
                }
            }
            Err(e) => return Err(e),
        }
    }
    if buf.is_empty() {
        return Ok(HeadRead::Empty);
    }
    // 旧写法到这里**不管有没有读到结尾**都把它当完整请求头去解析：
    // 头部超过 `MAX_HEAD` 时既不回 431，还会拿半截头继续走路由（白占一个线程）。
    if !buf.ends_with(b"\r\n\r\n") {
        return Ok(HeadRead::TooLarge);
    }
    Ok(HeadRead::Head(String::from_utf8_lossy(&buf).to_string()))
}

/// [`read_head`] 的三种结局（🟠 v2.18：把"头太长"和"客户端没发完就断了"分开）
enum HeadRead {
    /// 读到一个以 `\r\n\r\n` 结束的完整请求头
    Head(String),
    /// 连接上什么都没有
    Empty,
    /// 超过 [`MAX_HEAD`] 还没结束（或没读到结尾）⇒ 回 431
    TooLarge,
}

fn parse_query(s: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    for kv in s.split('&') {
        if kv.is_empty() {
            continue;
        }
        let (k, v) = kv.split_once('=').unwrap_or((kv, ""));
        map.insert(url_decode(k), url_decode(v));
    }
    map
}

fn url_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("");
                if let Ok(b) = u8::from_str_radix(hex, 16) {
                    out.push(b);
                    i += 3;
                } else {
                    out.push(bytes[i]);
                    i += 1;
                }
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).to_string()
}

/// 网页管理器自己的页面用这条：**一个外部请求都不许发**
const CSP_MAIN: &str =
    "default-src 'none'; style-src 'self' 'unsafe-inline'; script-src 'self'; \
     img-src 'self' data:; connect-src 'self'; frame-src 'self'; \
     base-uri 'none'; form-action 'none'";

/// 模块自带的网页用这条。相比我们自己的页面多放行两样：
///   · `img-src` 加 `https:` —— 很多模块页面拿图床当横幅（真机上这个模块就是），挡掉会缺图；
///   · `script-src` 加 `'unsafe-inline'` —— **模块页面的逻辑基本都是内联 <script>**
///     （真机踩到：只给 'self' 会把模块自己的脚本整段拦掉 → 页面上按钮全点不动）。
/// 这两条只对**模块自己的页面**生效；网页管理器自己的页面仍然是严格版（零外部请求）。
const CSP_MODWEB: &str =
    "default-src 'none'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; \
     img-src 'self' data: https:; connect-src 'self'; \
     base-uri 'none'; form-action 'none'";

fn respond(stream: &mut TcpStream, code: u16, mime: &str, body: &[u8]) -> std::io::Result<()> {
    respond_csp(stream, code, mime, body, CSP_MAIN)
}

fn respond_csp(
    stream: &mut TcpStream,
    code: u16,
    mime: &str,
    body: &[u8],
    csp: &str,
) -> std::io::Result<()> {
    let reason = match code {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        404 => "Not Found",
        413 => "Payload Too Large",
        431 => "Request Header Fields Too Large",
        503 => "Service Unavailable",
        _ => "Error",
    };
    let head = format!(
        "HTTP/1.1 {code} {reason}\r\n\
         Content-Type: {mime}\r\n\
         Content-Length: {}\r\n\
         Cache-Control: no-store\r\n\
         X-Content-Type-Options: nosniff\r\n\
         Referrer-Policy: no-referrer\r\n\
         Content-Security-Policy: {csp}\r\n\
         Connection: close\r\n\r\n",
        body.len(),
        csp = csp
    );
    stream.write_all(head.as_bytes())?;
    stream.write_all(body)?;
    stream.flush()
}

/// 配置（`/data/adb/sevenk/webadmin.conf`，简单 key=value）
#[derive(Debug, Clone, Default)]
pub struct Config {
    pub enabled: bool,
    /// **用户想要的首选端口**（默认 18427；可在网页里自定义）
    pub port: u16,
    /// **实际绑上的端口**（守护进程绑成功后写回；首选被占时会顺延，所以单独记）
    pub bound_port: u16,
    /// **访问密钥**（256 bit，64 位十六进制）。缺失/形状不对时由 [Config::ensure_token] 补生成。
    ///
    /// 注意：它只在进程内存和 0600 的配置文件里出现 —— 不进日志、不进 `ps` 可见的命令行、
    /// 不进 `webadmin.status`（见文件头"安全边界"）。
    pub token: String,
}

impl Config {
    pub fn load() -> Self {
        let text = std::fs::read_to_string(config_path()).unwrap_or_default();
        let mut cfg = Self {
            port: PORT,
            bound_port: 0,
            ..Self::default()
        };
        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            if let Some((k, v)) = line.split_once('=') {
                match k.trim() {
                    "enabled" => cfg.enabled = matches!(v.trim(), "1" | "true" | "yes"),
                    "port" => cfg.port = v.trim().parse().unwrap_or(PORT),
                    "bound_port" => cfg.bound_port = v.trim().parse().unwrap_or(0),
                    // 只接受"形状对"的 token：长度必须正好 64 且全是十六进制。
                    // 形状不对的（老版本没有这个键、被人手改坏）一律当成"没有"，
                    // 由 ensure_token() 重新生成 —— 绝不用一个弱值当密钥。
                    "token" => {
                        let v = v.trim();
                        if valid_token(v) {
                            cfg.token = v.to_string();
                        }
                    }
                    _ => {}
                }
            }
        }
        cfg
    }

    /// 配置文件里没有可用 token 时生成一个（返回是否**新生成**了）。
    /// 已有合法 token → 原样沿用（**这是"关掉再打开书签不失效"的护栏**）。
    pub fn ensure_token(&mut self) -> bool {
        if valid_token(&self.token) {
            return false;
        }
        if let Some(t) = new_token() {
            self.token = t;
            true
        } else {
            // 拿不到强随机数时**宁可没有密钥**（= 谁也别进），也不能退回弱随机源。
            warn!("取不到 /dev/urandom，无法生成访问密钥（网页管理器会拒绝一切请求）");
            false
        }
    }

    /// 读配置；没有可用 token 就生成并落盘。
    ///
    /// 给 `ksud webadmin url` 用：命令行/App 要地址时必须拿到**带密钥的完整链接**。
    pub fn load_or_create() -> Self {
        let mut cfg = Self::load();
        if cfg.ensure_token() {
            // 🟠（v2.19）同 `serve()` 里那一处：新生成的密钥**必须**落盘，
            // 落不进去就要留痕（否则下次读到的还是没 token 的配置
            // ⇒ `ksud webadmin url` 给出的链接永远 401，而日志零线索）。
            if let Err(e) = cfg.save() {
                warn!("网页管理器配置写盘失败（新密钥/端口可能没落盘）: {e}");
            }
        }
        cfg
    }

    pub fn save(&self) -> std::io::Result<()> {
        let path = config_path();
        if let Some(dir) = std::path::Path::new(&path).parent() {
            std::fs::create_dir_all(dir)?;
        }
        let text = format!(
            "# 7kimisu 网页管理器（ksud 读取；改完执行 `ksud webadmin restart` 或重启设备）\n\
             # token = 访问密钥：**别外传**，拿到它的人能在本机拿到 root。\n\
             #         要换一把：`ksud webadmin reset-token`（App 设置页也有入口）\n\
             enabled={}\nport={}\nbound_port={}\ntoken={}\n",
            u8::from(self.enabled),
            self.port,
            self.bound_port,
            self.token
        );
        // 原子写：`std::fs::write` 会**先把目标文件截断**，写到一半掉电/被杀
        // 就会留下一份半截配置（token 可能变成空 → 服务拒绝一切请求，用户以为"坏了"）。
        // 写法照抄本仓库已验证过的 `profile.rs::atomic_write_text` / `feature.rs::save_binary_config`
        // （temp + sync_all + rename：对外要么是完整旧文件、要么是完整新文件）。
        //
        // 🟠（v2.18）临时名加**进程号 + 随机后缀**。旧写法固定 `{path}.tmp`：
        // `ksud webadmin url` / App 同步 / 守护进程自己写回端口是**不同进程**，
        // 同时写会用同一个临时名互相截断 ⇒ 可能 rename 出一份半截配置（token 丢失 =
        // 服务拒绝一切请求，用户表现为"网页打不开了"）。
        let uniq = random_hex(8).unwrap_or_else(|| std::process::id().to_string());
        let tmp = format!("{path}.tmp-{}-{uniq}", std::process::id());
        {
            use std::io::Write as _;
            let mut f = std::fs::File::create(&tmp)?;
            f.write_all(text.as_bytes())?;
            f.sync_all()?;
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            // 先给临时文件就位权限，再 rename —— 避免"文件已经是最终名字但还没收紧权限"的窗口。
            // 🟠（v2.18）**失败不再静默**：配置里有 token，权限没收紧比"没写成"更危险。
            if let Err(e) = std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600)) {
                let _ = std::fs::remove_file(&tmp);
                return Err(e);
            }
        }
        if let Err(e) = std::fs::rename(&tmp, &path) {
            let _ = std::fs::remove_file(&tmp);
            return Err(e);
        }
        // SELinux 标签：ksud 自己写的数据文件用 `ksu_file`（与 `restorecon::KSU_CON` 一致，
        // 照 `utils::install` 给 /data/adb/ksud 打标签的既有做法）。失败不致命（只记一条）。
        #[cfg(target_os = "android")]
        {
            if let Err(e) = crate::restorecon::lsetfilecon(&path, crate::restorecon::KSU_CON) {
                warn!("给 {path} 打 SELinux 标签失败: {e:#}");
            }
        }
        Ok(())
    }
}

/// token 的形状检查：正好 64 位、全是十六进制小写（[new_token] 的输出形状）。
///
/// 为什么要检查而不是"非空就算"：配置文件可能被人手改、被半截写入，
/// 一个 `token=1` 这种弱值如果被当成密钥接受，等于整个鉴权层白做。
pub fn valid_token(t: &str) -> bool {
    t.len() == TOKEN_LEN && t.bytes().all(|b| b.is_ascii_hexdigit())
}

/// 生成一把新密钥；**只用 `/dev/urandom`**，读不到就返回 `None`。
///
/// ⚠️ 这里刻意**不用** [random_hex] 的"退回时间戳"兜底：时间戳是可预测的，
/// 拿它当访问密钥等于没有密钥。安全用途必须"要么强随机、要么没有"。
pub fn new_token() -> Option<String> {
    random_hex_secure(TOKEN_BYTES)
}


/// 取一段随机十六进制（会话 id 之类）。读不到 /dev/urandom 就退回时间戳，
/// **只用于"别撞车"的标识**，不用于安全用途。
pub fn random_hex(bytes: usize) -> Option<String> {
    if let Some(hex) = random_hex_secure(bytes) {
        return Some(hex);
    }
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .ok()?
        .as_nanos();
    Some(format!("{now:x}"))
}

/// 取一段**只来自 `/dev/urandom`** 的随机十六进制；读不到就 `None`（**没有弱兜底**）。
///
/// 与 [random_hex] 的区别就是这条：安全用途（访问密钥）必须能区分"强随机"与
/// "退化了"，所以单独一个函数，绝不复用那个会退回时间戳的实现。
pub fn random_hex_secure(bytes: usize) -> Option<String> {
    use std::fmt::Write as _;
    use std::io::Read as _;
    let mut buf = vec![0u8; bytes];
    let mut f = std::fs::File::open("/dev/urandom").ok()?;
    f.read_exact(&mut buf).ok()?;
    let mut hex = String::with_capacity(bytes * 2);
    for b in &buf {
        let _ = write!(hex, "{b:02x}");
    }
    Some(hex)
}

/// 换一把新密钥（`ksud webadmin reset-token` / App 设置页的「重置密钥」）。
///
/// 返回新 token；调用方负责把它拼进 URL 给用户（**不要写进日志**）。
/// 老的书签会在下一次请求时得到 401 —— 这正是"重置"的语义。
pub fn reset_token() -> std::io::Result<String> {
    let mut cfg = Config::load();
    let token = new_token()
        .ok_or_else(|| std::io::Error::other("取不到 /dev/urandom，没法生成新密钥"))?;
    cfg.token.clone_from(&token);
    cfg.save()?;
    info!("网页管理器访问密钥已重置（新密钥只在返回值和配置文件里，未写日志）");
    Ok(token)
}

/// 拼地址（指定端口 + 指定密钥）。地址里**带密钥**：
/// `http://127.0.0.1:18427/x7k9f/<token>`。
pub fn url_with(port: u16, token: &str) -> String {
    if token.is_empty() {
        format!("http://127.0.0.1:{port}{PATH}")
    } else {
        format!("http://127.0.0.1:{port}{PATH}/{token}")
    }
}

/// 给用户/命令行看的完整地址（**含密钥**，用户复制到浏览器就能用）。
/// 端口优先取"真的在回话"的那个 —— 配置里的 bound_port 可能是过期值。
pub fn url() -> String {
    // 用 load_or_create：命令行/App 问地址时，配置文件里必须有密钥，
    // 否则打印出来的链接是"没钥匙的门"，用户点了就 401。
    let c = Config::load_or_create();
    let fallback = if c.bound_port > 0 { c.bound_port } else { c.port };
    url_with(effective_port().unwrap_or(fallback), &c.token)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 随机口令的质量：形状对（12 位、只在口令字符表里）且**不重复**。
    /// 取 2000 个样本 —— 空间是 31^12 ≈ 7.9e17，出现重复的概率约 2.5e-12，
    /// 真出现重复就说明随机源坏了（正常绝不该发生）。
    /// 用户自定义的口令**必须原样保留** —— 这是"隐身一开一关口令被重置"的护栏。
    /// 端到端：写进配置文件 → 重新加载（模拟守护进程重启）→ 口令必须还在。
    /// 护栏：app.js 里 `ic("名字")` 用到的图标，sprite 里必须都有
    /// （踩过：用了 dialpad 但 sprite 里没有 → 那个位置一直是个空白图标，没人发现）
    #[test]
    fn every_used_icon_exists_in_sprite() {
        let js = WebAsset::get("app.js").expect("app.js 应该在资源里");
        let js = String::from_utf8_lossy(&js.data);
        let sprite = WebAsset::get("icons.svg").expect("icons.svg 应该在资源里");
        let sprite = String::from_utf8_lossy(&sprite.data);
        let mut missing: Vec<String> = Vec::new();
        let mut used: Vec<String> = Vec::new();
        let bytes = js.as_bytes();
        let needle = b"ic(\"";
        let mut i = 0;
        while let Some(pos) = js[i..].find("ic(\"") .map(|p| p + i) {
            let start = pos + needle.len();
            let rest = &js[start..];
            if let Some(end) = rest.find('"') {
                let name = &rest[..end];
                if !name.is_empty()
                    && name.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
                    && !used.contains(&name.to_string())
                {
                    used.push(name.to_string());
                }
                i = start + end;
            } else {
                break;
            }
        }
        for name in &used {
            if !sprite.contains(&format!("id=\"i-{name}\"") ) {
                missing.push(name.clone());
            }
        }
        assert!(used.len() > 10, "没扫到图标用法（扫描逻辑写错了？）");
        assert!(
            missing.is_empty(),
            "这些图标页面在用、但 icons.svg 里没有：{missing:?}（那个位置会显示成空白）"
        );
        let _ = bytes;
    }

    /// 地址契约：**端口 + 路径前缀 + 专属密钥**，而且密钥在**路径**里
    /// （不是 `?k=` 查询参数 —— 查询参数会被日志/历史记录单独记住）
    #[test]
    fn url_carries_token_in_path() {
        let t = "a".repeat(TOKEN_LEN);
        assert_eq!(
            url_with(18427, &t),
            format!("http://127.0.0.1:18427{}/{t}", PATH)
        );
        assert_eq!(
            url_with(18437, &t),
            format!("http://127.0.0.1:18437{}/{t}", PATH)
        );
        assert!(!url_with(18427, &t).contains('?'), "地址里不该带 ?k= 之类的查询参数");
    }

    /// 🔐 新密钥：形状必须是 64 位十六进制、每次都不同、且**只来自 /dev/urandom**。
    ///
    /// 取 500 个样本：空间是 16^64 = 2^256，重复概率约等于 0；
    /// 真出现重复就说明随机源坏了（比如悄悄退回了时间戳）。
    #[test]
    fn new_token_is_strong_and_unique() {
        let mut seen = std::collections::HashSet::new();
        for _ in 0..500 {
            let t = new_token().expect("应该有 /dev/urandom");
            assert_eq!(t.len(), TOKEN_LEN, "{t}");
            assert!(valid_token(&t), "{t}");
            assert!(seen.insert(t), "出现重复密钥 —— 随机源坏了");
        }
    }

    /// 🔐 token 形状校验：弱值一律不认（手改配置 / 半截写入都可能留下这种值）
    #[test]
    fn token_shape_validation() {
        assert!(valid_token(&"f".repeat(TOKEN_LEN)));
        assert!(!valid_token(""), "空值不算");
        assert!(!valid_token("1"), "太短不算（等于没有密钥）");
        assert!(!valid_token(&"f".repeat(TOKEN_LEN - 1)), "少一位不算");
        assert!(!valid_token(&"f".repeat(TOKEN_LEN + 1)), "多一位不算");
        assert!(!valid_token(&"z".repeat(TOKEN_LEN)), "非十六进制不算");
    }

    /// 🔐 恒定时间比较：结果必须与普通 `==` 一致（包括长度不同、空串、前后缀相同）
    #[test]
    fn ct_eq_matches_normal_equality() {
        let t = "0123456789abcdef".repeat(4);
        assert!(ct_eq(&t, &t));
        // 前缀对、最后一位错 —— 普通 `==` 会在第一位就返回，这里不许短路，但**结果必须一样**
        let mut wrong_tail = t.clone();
        wrong_tail.pop();
        wrong_tail.push('0');
        assert_eq!(ct_eq(&t, &wrong_tail), t == wrong_tail);
        assert!(!ct_eq(&t, &wrong_tail));
        // 只差第一位
        let mut wrong_head = t.clone();
        wrong_head.replace_range(0..1, if t.starts_with('0') { "1" } else { "0" });
        assert!(!ct_eq(&t, &wrong_head));
        // 长度不同（短的 / 长的 / 空的）都必须是"不等"，且不 panic
        assert!(!ct_eq(&t, &t[..t.len() - 1]));
        assert!(!ct_eq(&t, &format!("{t}0")));
        assert!(!ct_eq(&t, ""));
        assert!(!ct_eq("", &t));
        assert!(ct_eq("", ""));
    }

    /// 🔐 鉴权判据：只有 `{PATH}/{正确 token}[/...]` 才放行；其余（含各种近似猜法）一律 None。
    /// 关键：**三种失败返回同一个 None**，调用方回同一个 401 → 不泄露路径结构。
    #[test]
    fn authed_rel_requires_exact_token() {
        let t = "0123456789abcdef".repeat(4);
        assert_eq!(authed_rel(&format!("{PATH}/{t}"), &t).as_deref(), Some("/"));
        assert_eq!(
            authed_rel(&format!("{PATH}/{t}/"), &t).as_deref(),
            Some("/")
        );
        assert_eq!(
            authed_rel(&format!("{PATH}/{t}/api/status"), &t).as_deref(),
            Some("/api/status")
        );
        assert_eq!(
            authed_rel(&format!("{PATH}/{t}/modweb/demo/index.html"), &t).as_deref(),
            Some("/modweb/demo/index.html")
        );
        // 没带 token（老地址）
        assert!(authed_rel(PATH, &t).is_none());
        assert!(authed_rel(&format!("{PATH}/"), &t).is_none());
        // token 错（少一位 / 多一位 / 改一位）
        assert!(authed_rel(&format!("{PATH}/{}", &t[..TOKEN_LEN - 1]), &t).is_none());
        assert!(authed_rel(&format!("{PATH}/{t}0"), &t).is_none());
        let mut bad = t.clone();
        bad.replace_range(TOKEN_LEN - 1..TOKEN_LEN, "0");
        assert!(authed_rel(&format!("{PATH}/{bad}/api/status"), &t).is_none());
        // 前缀都不对
        assert!(authed_rel("/api/status", &t).is_none());
        assert!(authed_rel(&format!("/wrong/{t}/api/status"), &t).is_none());
        // 配置里还没有 token 时 → 谁也别进
        assert!(authed_rel(&format!("{PATH}/{t}/api/status"), "").is_none());
        assert!(authed_rel(PATH, "").is_none());
    }

    /// 🔴3（v2.17）：模块网页桥的注入片段**必须带 token**。
    ///
    /// 修前 `webadmin_ksud.rs` 用的是裸 `webadmin::PATH` ⇒ 两个注入点
    /// （`window.__7K_BASE__` 与 `<script src>`）都缺 token ⇒ 必 401 ⇒
    /// `modbridge.js` 加载不到 / 请求发不到 ⇒ 模块自带 WebUI 的 `ksu.exec/spawn/...` 全死。
    #[test]
    fn module_bridge_tag_carries_the_token() {
        let t = "0123456789abcdef".repeat(4); // 64 位十六进制 = 合法 token 形状
        let base = format!("{PATH}/{t}");
        let tag = module_bridge_tag(&base, r#"{"id":"demo"}"#);

        // ① `window.__7K_BASE__` 带 token（modbridge.js 拿它拼所有 API 请求）
        assert!(
            tag.contains(&format!("window.__7K_BASE__=\"{base}\"")),
            "注入的前缀必须带 token：{tag}"
        );
        // ② `<script src>` 也带 token（否则脚本自己就 401，window.ksu 根本不存在）
        assert!(
            tag.contains(&format!("src=\"{base}/a/modbridge.js\"")),
            "桥脚本的 src 必须带 token：{tag}"
        );
        // ③ 模块信息照旧注入
        assert!(
            tag.contains(r#"window.__7K_MODULE__={"id":"demo"}"#),
            "模块信息必须照旧注入：{tag}"
        );
        // ④ 顺序与"无多余空白"也和旧实现一致（`\` 续行不该带缩进）
        assert!(
            tag.starts_with("<script>window.__7K_BASE__=") && !tag.contains("\n"),
            "注入片段必须是一行、且以 BASE 开头：{tag:?}"
        );

        // ⑤ 把"修前"的裸 PATH 拿出来对比：同一个鉴权函数下必被拒
        let tag_before_fix = module_bridge_tag(PATH, r#"{"id":"demo"}"#);
        assert!(
            tag_before_fix.contains(&format!("window.__7K_BASE__=\"{PATH}\"")),
            "{tag_before_fix}"
        );
        //   带 token ⇒ 通过鉴权，落到 `/a/modbridge.js` 这个真实路由
        assert_eq!(
            authed_rel(&format!("{base}/a/modbridge.js"), &t).as_deref(),
            Some("/a/modbridge.js")
        );
        //   裸 PATH（修前的样子）⇒ 401（None），模块网页桥整条死
        assert!(
            authed_rel(&format!("{PATH}/a/modbridge.js"), &t).is_none(),
            "修前的裸 PATH 必然 401 —— 这就是那个 bug 的证据"
        );
    }

    /// 🔐 配置文件里已有合法 token → `ensure_token()` 必须**原样保留**。
    /// 这条对应"关掉再打开，用户书签不失效"。
    #[test]
    fn existing_token_is_reused() {
        let t = "abcdef0123456789".repeat(4);
        let mut cfg = Config {
            token: t.clone(),
            ..Config::default()
        };
        assert!(!cfg.ensure_token(), "已有合法 token 就不该重新生成");
        assert_eq!(cfg.token, t);
        // 没有 / 形状不对 → 生成新的，且形状必须合法
        for bad in [String::new(), "1".to_string(), "z".repeat(TOKEN_LEN)] {
            let mut cfg = Config {
                token: bad,
                ..Config::default()
            };
            assert!(cfg.ensure_token());
            assert!(valid_token(&cfg.token), "{}", cfg.token);
        }
    }

    /// 🔐 落盘端到端：写进配置 → **重新加载（模拟守护进程重启）** → 密钥必须一模一样；
    /// 同时确认文件权限是 0600（别的 App 读不到，只有 root 读得到）。
    #[test]
    fn token_survives_reload_and_file_is_0600() {
        let _guard = ENV_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let tmp = std::env::temp_dir().join("7k-webadmin-token");
        let _ = std::fs::create_dir_all(&tmp);
        let conf = tmp.join("webadmin.conf");
        unsafe {
            std::env::set_var("WEBADMIN_CONF", conf.display().to_string());
        }
        let mut cfg = Config {
            enabled: true,
            port: 18427,
            bound_port: 18437,
            ..Config::default()
        };
        assert!(cfg.ensure_token());
        cfg.save().expect("写配置");
        let first = cfg.token.clone();
        // 重新加载 = 守护进程重启/关掉再打开
        let again = Config::load();
        assert_eq!(again.token, first, "重开必须沿用同一个密钥（否则书签全失效）");
        assert!(again.enabled && again.bound_port == 18437, "别的字段也要原样回来");
        // 再存一次（模拟 off → on）也不许换密钥
        let mut c2 = Config::load();
        assert!(!c2.ensure_token());
        c2.save().expect("再写一次");
        assert_eq!(Config::load().token, first, "off→on 不许换密钥");
        // 权限
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&conf).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o600, "配置文件必须是 0600（实际 {mode:o}）");
        }
        // 显式重置 → 换一把、且还是合法的
        let new = reset_token().expect("重置密钥");
        assert_ne!(new, first, "重置后必须换了一把");
        assert!(valid_token(&new));
        assert_eq!(Config::load().token, new);
        unsafe {
            std::env::remove_var("WEBADMIN_CONF");
        }
    }

    #[test]
    fn port_rule() {
        assert!((1024..=65535).contains(&18427));
        assert!(!(1024..=65535).contains(&80));     // 系统保留段
        assert!(!(1024..=65535).contains(&70000));  // 超出范围（u16 已挡住更大值）
    }

    #[test]
    fn stealth_code_validation() {
        assert!(valid_code("70707"));
        assert!(valid_code("1234"));
        assert!(valid_code("123456789012"));
        assert!(!valid_code("123"));
        assert!(!valid_code("1234567890123"));
        assert!(!valid_code("12a4"));
        assert!(!valid_code(""));
    }

    #[test]
    fn id_validation() {
        assert!(valid_id("meta-overlayfs"));
        assert!(!valid_id(""));
        assert!(!valid_id("../etc/passwd"));
        assert!(!valid_id("a/b"));
    }

    #[test]
    fn whitelist_blocks_traversal() {
        assert!(asset_for("../Cargo.toml").is_none());
        assert!(asset_for("index.html").is_none()); // 页面走 render_page，不在静态白名单
        assert!(asset_for("app.css").is_some());
        assert!(asset_for("icons.svg").is_some());
        assert!(asset_for("licenses/Lucide-ISC.txt").is_some());
    }

    #[test]
    fn query_parsing() {
        let q = parse_query("k=abc&id=meta%2Doverlayfs&enable=1");
        assert_eq!(q.get("k").unwrap(), "abc");
        assert_eq!(q.get("id").unwrap(), "meta-overlayfs");
        assert_eq!(q.get("enable").unwrap(), "1");
    }

    /// "要不要为换了 ksud 而重启"的判据护栏
    #[test]
    fn ksud_same_rule() {
        let a = "a".repeat(64);
        let b = "b".repeat(64);
        assert!(ksud_same(&a, &a), "指纹相同 → 不该重启");
        assert!(!ksud_same(&a, &b), "指纹不同 → 要重启");
        assert!(!ksud_same("", &a), "拿到不指纹就保守重启");
        assert!(!ksud_same(&a, ""), "盘上算不出指纹就保守重启");
        assert!(!ksud_same("", ""), "两边都空更不能当成一样");
    }


    #[test]
    fn pkg_rule() {
        assert!(valid_pkg("com.tencent.mm"));
        assert!(valid_pkg("com.android.chrome_1"));
        assert!(!valid_pkg(""));
        assert!(!valid_pkg("com/../etc/passwd"));
        assert!(!valid_pkg("com foo"));
        assert!(!valid_pkg("a".repeat(201).as_str()));
    }

    #[test]
    fn upload_path_shape() {
        let p = upload_path();
        assert!(
            std::path::Path::new(&p)
                .extension()
                .is_some_and(|e| e.eq_ignore_ascii_case("zip")),
            "{p}"
        );
        assert!(p.contains("webadmin-upload-"), "{p}");
    }

    /// 🔴（v2.18）并发上传**不能撞路径** 的证据：
    /// 旧写法（只有 `std::process::id()`）在同一进程里连续两次调用返回**同一个**字符串 ⇒
    /// 两个上传线程会 `File::create` 同一个文件、互相截断。
    /// 新写法带随机后缀 ⇒ 连续 64 次必须**两两不同**。
    #[test]
    fn upload_path_is_unique_per_call() {
        let paths: Vec<String> = (0..64).map(|_| upload_path()).collect();
        let uniq: std::collections::HashSet<&String> = paths.iter().collect();
        assert_eq!(uniq.len(), paths.len(), "上传临时路径出现重复：{paths:?}");
    }

    // ══════════════════════════════════════════════════════════════
    // 端到端：真的起一个服务，用真的 HTTP 请求打进来
    // （webadmin.rs 是跨平台的，所以这一段在 Mac 上就能跑）
    // ══════════════════════════════════════════════════════════════

    /// 两个端到端测试都会改 WEBADMIN_CONF（进程级环境变量）→ 必须串行，
    /// 否则一个把变量清掉后，另一个就去写 /data/adb/...（macOS 上直接 EPERM）。
    static ENV_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    struct Fake;

    impl Backend for Fake {
        fn status(&self) -> serde_json::Value {
            serde_json::json!({ "ok": true, "root": true })
        }
        fn modules(&self) -> serde_json::Value {
            // ⚠️ 故意用**真机那种形状**（字符串 "true"/"false"）——
            //    前端必须自己归一化，否则标签/开关全都不对（踩过）
            serde_json::json!([
                {
                    "id": "demo", "name": "演示模块", "version": "1.0", "author": "测试",
                    "description": "用来本机冒烟：有动作脚本也有网页",
                    "enabled": "true", "action": "true", "web": "true",
                    "update": "false", "remove": "false", "mount": "true"
                },
                {
                    "id": "plain", "name": "普通模块", "version": "2.0", "author": "测试",
                    "description": "没有动作脚本也没有网页",
                    "enabled": "false", "action": "false", "web": "false",
                    "update": "false", "remove": "false", "mount": "false"
                }
            ])
        }
        fn toggle_module(&self, _id: &str, _e: bool) -> Result<String, String> {
            Ok("toggled".into())
        }
        fn uninstall_module(&self, _id: &str) -> Result<String, String> {
            Ok("uninstalled".into())
        }
        fn undo_uninstall_module(&self, _id: &str) -> Result<String, String> {
            Ok("undone".into())
        }
        fn set_stealth(&self, _e: bool) -> Result<String, String> {
            Ok("stealth".into())
        }
        fn superusers(&self) -> serde_json::Value {
            serde_json::json!({ "ok": true, "list": [] })
        }
        fn set_stealth_code(&self, _c: &str) -> Result<String, String> {
            Ok("code".into())
        }
        fn apps(&self) -> serde_json::Value {
            serde_json::json!({ "ok": true, "list": [{ "pkg": "com.demo", "uid": 10123 }] })
        }
        fn set_app_root(&self, pkg: &str, uid: u32, allow: bool) -> Result<String, String> {
            Ok(format!("{pkg}|{uid}|{allow}"))
        }
        fn app_profile(&self, pkg: &str, uid: u32) -> Result<serde_json::Value, String> {
            Ok(serde_json::json!({ "pkg": pkg, "uid": uid, "exists": false }))
        }
        fn save_app_profile(
            &self,
            _pkg: &str,
            _uid: u32,
            form: &HashMap<String, String>,
        ) -> Result<String, String> {
            Ok(format!(
                "saved:allowSu={}",
                form.get("allowSu").cloned().unwrap_or_default()
            ))
        }
        fn sulog(&self, limit: usize, query: &str) -> serde_json::Value {
            serde_json::json!({ "ok": true, "limit": limit, "q": query, "lines": [] })
        }
        fn clear_sulog(&self) -> Result<String, String> {
            Ok("cleared".into())
        }
        fn set_sulog_enabled(&self, enable: bool) -> Result<String, String> {
            Ok(format!("sulog={enable}"))
        }
        fn module_action(&self, id: &str) -> Result<serde_json::Value, String> {
            if id == "bad" {
                return Err("没有动作脚本".to_string());
            }
            Ok(serde_json::json!({ "id": id, "code": 0, "output": "hello from action.sh" }))
        }
        fn module_exec(&self, id: &str, cmd: &str, options_json: &str) -> Result<serde_json::Value, String> {
            Ok(serde_json::json!({
                "errno": 0,
                "stdout": format!("{id}:{cmd}"),
                "stderr": "",
                // 让测试能看出 options 有没有传下来
                "optionsSeen": options_json,
            }))
        }
        fn module_web_file(&self, id: &str, rel: &str) -> Result<(String, Vec<u8>), String> {
            if id != "demo" || rel.contains("..") {
                return Err("没有这个文件".to_string());
            }
            Ok((
                "text/html; charset=utf-8".to_string(),
                b"<html><body>demo webui</body></html>".to_vec(),
            ))
        }
        fn module_packages(&self, kind: &str) -> Result<serde_json::Value, String> {
            Ok(serde_json::json!({ "packages": [format!("com.demo.{kind}")] }))
        }
        fn module_packages_info(&self, names_json: &str) -> Result<serde_json::Value, String> {
            let names: Vec<String> = serde_json::from_str(names_json).unwrap_or_default();
            let list: Vec<serde_json::Value> = names
                .iter()
                .map(|n| {
                    serde_json::json!({
                        "packageName": n, "appLabel": n, "versionName": "1.0",
                        "versionCode": 1, "isSystem": false, "uid": 10123
                    })
                })
                .collect();
            Ok(serde_json::json!({ "list": list }))
        }
        fn module_spawn_start(
            &self,
            _id: &str,
            cmd: &str,
            args: &[String],
            options_json: &str,
        ) -> Result<serde_json::Value, String> {
            Ok(serde_json::json!({
                "sid": format!("sid-{cmd}-{}", args.len()),
                "optionsSeen": options_json,
            }))
        }
        fn module_spawn_poll(&self, sid: &str) -> Result<serde_json::Value, String> {
            Ok(serde_json::json!({
                "stdout": format!("{sid}: line1\n"),
                "stderr": "",
                "done": true,
                "code": 0
            }))
        }
        fn install_module_zip(&self, path: &str) -> Result<String, String> {
            // 这里顺便验证 HTTP 层真的把 zip 完整落盘了
            let data = std::fs::read(path).map_err(|e| e.to_string())?;
            Ok(format!(
                "got {} bytes magic={}",
                data.len(),
                String::from_utf8_lossy(&data[..2])
            ))
        }
    }

    /// 等"**我们自己的**服务"起来，返回它真正绑上的端口（0 = 超时没起来）。
    ///
    /// ⚠️ 只能用 [`probe_own`]（带 token 的完整判活）来确认——**不能**只看
    /// `TcpStream::connect` 成功：同一个测试进程里，**先跑的那个端到端测试起的服务线程
    /// 永远不会退出**（`serve()` 阻塞在 accept 循环里，测试结束也不停），它的端口照样连得上，
    /// 而它用的是**另一份 token**（每个测试一份临时配置）。
    /// 旧写法"连得上就算起来了"于是会把上一轮那个服务的端口当成自己的 →
    /// 紧接着的 `probe_own` 断言偶发失败（`http_routes_end_to_end` 长期偶发的根因）。
    fn wait_own_port() -> u16 {
        for _ in 0..200 {
            let p = bound_port();
            if p >= 1024 && probe_own(p) {
                return p;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        0
    }

    /// 构造一把"错一位"的密钥：把**末位**换成与它不同的那个十六进制字符。
    ///
    /// ⚠️ 为什么不写成 `replace_range(.., "0")`（旧写法）：密钥是随机 64 位十六进制，
    /// 末位本来就是 `'0'` 的概率是 **1/16** —— 那一次"错密钥"其实**等于真密钥**，
    /// 请求会被正常鉴权（200），于是"未授权必须 401"这条断言偶发失败。
    /// 这是 `http_routes_end_to_end` 长期偶发的**真正根因**（2026-09-22 定位）。
    fn wrong_token(token: &str) -> String {
        let last = token.as_bytes()[TOKEN_LEN - 1] as char;
        let flipped = if last == '0' { '1' } else { '0' };
        let mut bad = token.to_string();
        bad.replace_range(TOKEN_LEN - 1..TOKEN_LEN, &flipped.to_string());
        assert_ne!(bad, token, "构造的'错密钥'必须与真密钥不同");
        bad
    }

    /// 🐞 上面那条偶发失败的**直接**回归：16 种末位都必须真的改掉，且都过不了鉴权。
    #[test]
    fn wrong_token_always_differs() {
        // ① 旧写法的反例：末位 '0' 时 `replace_range(.., "0")` 等于没改 ——
        //    于是"未授权"的探针拿到了 200（这就是那个 bug 的机制）。
        let real = format!("{}0", &"0123456789abcdef".repeat(4)[..TOKEN_LEN - 1]);
        assert_eq!(real.len(), TOKEN_LEN);
        assert!(valid_token(&real));
        let mut old_style = real.clone();
        old_style.replace_range(TOKEN_LEN - 1..TOKEN_LEN, "0");
        assert_eq!(
            old_style, real,
            "旧写法在末位是 '0' 时没有改掉密钥 —— 偶发失败就是它造成的"
        );
        assert_eq!(
            authed_rel(&format!("{PATH}/{old_style}/api/status"), &real).as_deref(),
            Some("/api/status"),
            "所以那条'未授权'请求其实被鉴权通过了（200）"
        );

        // ② 新写法：16 种末位全部真的不同、且全部被拒
        for c in "0123456789abcdef".chars() {
            let real = format!("{}{}", &"fedcba9876543210".repeat(4)[..TOKEN_LEN - 1], c);
            assert!(valid_token(&real), "{real}");
            let bad = wrong_token(&real);
            assert_ne!(bad, real, "末位 {c}: 必须真的改掉");
            assert!(
                authed_rel(&format!("{PATH}/{bad}/api/status"), &real).is_none(),
                "末位 {c}: 错密钥必须 401"
            );
        }
    }

    fn http(port: u16, method: &str, target: &str, body: &[u8]) -> (u16, String) {
        use std::io::{Read as _, Write as _};
        let mut s = TcpStream::connect(("127.0.0.1", port)).expect("连不上测试服务");
        let head = format!(
            "{method} {target} HTTP/1.1\r\nHost: 127.0.0.1\r\n\
             Content-Length: {}\r\nContent-Type: application/octet-stream\r\n\
             Connection: close\r\n\r\n",
            body.len()
        );
        s.write_all(head.as_bytes()).unwrap();
        s.write_all(body).unwrap();
        s.flush().unwrap();
        s.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
        let mut buf = Vec::new();
        s.read_to_end(&mut buf).unwrap();
        let text = String::from_utf8_lossy(&buf).to_string();
        let code = text
            .split_whitespace()
            .nth(1)
            .and_then(|c| c.parse::<u16>().ok())
            .unwrap_or(0);
        let body = text.split("\r\n\r\n").nth(1).unwrap_or("").to_string();
        (code, body)
    }

    /// 本机 UI 冒烟：真起一个服务（假模块数据 + **真前端资源**）并挂住，
    /// 方便用浏览器（playwright）把页面点一遍。默认忽略，需要时：
    ///   cargo test --release --offline -- --ignored --nocapture webadmin_ui_harness
    #[test]
    #[ignore = "本机 UI 冒烟用（要配 Playwright 手动跑），日常 CI 不需要"]
    fn webadmin_ui_harness() {
        let tmp = std::env::temp_dir().join("7k-webadmin-ui");
        let _ = std::fs::create_dir_all(&tmp);
        unsafe {
            std::env::set_var("WEBADMIN_CONF", tmp.join("webadmin.conf").display().to_string());
            std::env::set_var("WEBADMIN_UPLOAD_DIR", tmp.display().to_string());
        }
        let server = Server::new(Arc::new(Fake));
        std::thread::spawn(move || {
            let _ = serve(&server);
        });
        println!("UI harness ready: {}  （挂 300 秒）", url_with(wait_own_port(), &Config::load().token));
        std::thread::sleep(Duration::from_secs(300));
    }

    #[test]
    fn http_routes_end_to_end() {
        let _guard = ENV_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let tmp = std::env::temp_dir().join("7k-webadmin-test");
        let _ = std::fs::create_dir_all(&tmp);
        // 清掉上一轮留下的配置：否则里面已经有密钥，"首次生成"的断言会假失败
        let _ = std::fs::remove_file(tmp.join("webadmin.conf"));
        unsafe {
            std::env::set_var("WEBADMIN_CONF", tmp.join("webadmin.conf").display().to_string());
            std::env::set_var("WEBADMIN_UPLOAD_DIR", tmp.display().to_string());
        }

        // 先自己把密钥写进配置（不等 serve 里那次生成），下面好直接拼带密钥的路径
        let token = {
            let mut c = Config::load();
            assert!(c.ensure_token(), "测试里应该是首次生成密钥");
            c.save().expect("写配置");
            c.token
        };
        let base = format!("{PATH}/{token}");

        // 🔧 端口硬化（v2.17）：先让内核分配一个**空闲端口**再交给 `serve()`。
        //    为什么不能靠固定候选端口（18427/18437/18447/18457）：开发机上任何东西
        //    （或上一次跑测试留下的服务）把 4 个都占了，`serve()` 就直接返回 `Err`、
        //    测试线程**悄悄结束** → 断言变成"测试服务没起来"，成了与本次改动无关的假失败。
        let want_port = {
            use std::net::TcpListener;
            let l = TcpListener::bind(("127.0.0.1", 0)).expect("拿不到空闲端口");
            let p = l.local_addr().expect("读出端口").port();
            drop(l);
            p
        };
        set_port_override(want_port);

        let server = Server::new(Arc::new(Fake));
        std::thread::spawn(move || {
            let _ = serve(&server);
        });

        // 等服务把端口绑上。
        // ⚠️ 必须用 `wait_own_port()`（带 token 的判活），不能只 connect 成功 —— 见它的注释。
        let port = wait_own_port();
        assert!(port != 0, "测试服务没起来（期望端口 {want_port}）");
        assert_eq!(port, want_port, "服务绑到了别的端口？");

        // 0) 判活必须认得自己（踩过：判据串写错 → 永远为假）
        assert!(probe_own(port), "probe_own 认不出自己的服务");
        // 0.1) 探活还要回报"我在跑哪个二进制"（App 靠它决定要不要重启服务）
        let info = probe_ping(port).expect("探活应该成功");
        assert_eq!(info.bin_sha.len(), 64, "指纹应该是 64 位十六进制");
        assert!(info.bin_sha.bytes().all(|b| b.is_ascii_hexdigit()));
        assert!(info.pid > 0);

        // 0.2) CSP 必须允许同源 iframe —— 否则模块网页（iframe）会一片空白
        //      （踩过：default-src 'none' 会让 frame-src 也变成 none，浏览器直接不加载 iframe）
        {
            use std::io::{Read as _, Write as _};
            let mut sock = TcpStream::connect(("127.0.0.1", port)).unwrap();
            sock.write_all(format!("GET {base}/x HTTP/1.0\r\n\r\n").as_bytes()).unwrap();
            let mut raw = Vec::new();
            let _ = sock.read_to_end(&mut raw);
            let head = String::from_utf8_lossy(&raw);
            assert!(
                head.contains("frame-src 'self'"),
                "响应头里必须放行同源 iframe，否则模块网页打不开"
            );
        }

        // 1) 🔐 鉴权：**不带密钥一律 401**。
        //    这几种"猜法"必须得到**完全一样**的响应（状态码 + 响应体）——
        //    否则攻击者能靠响应差异判断"路径猜对了没有"。
        let mut unauthorized: Vec<String> = Vec::new();
        for probe in [
            PATH.to_string(),                                   // 老地址（只有前缀）
            format!("{PATH}/"),                                 // 前缀 + 斜杠
            format!("{PATH}/api/status"),                       // 老式 API 地址
            format!("{PATH}/{}", &token[..TOKEN_LEN - 1]),      // 密钥少一位
            format!("{PATH}/{token}0"),                         // 密钥多一位
            {
                // ⚠️🐞 「密钥错一位」这条例子的**真正偶发根因**（2026-09-22 定死）：
                //    旧写法把末位**无条件换成 `'0'`** —— 密钥是随机生成的 64 位十六进制，
                //    末位本来就是 `'0'` 的概率 = 1/16 ⇒ 那次"错密钥"其实**等于真密钥**，
                //    请求真的通过了鉴权（200）⇒ 断言失败。
                //    这就是 `http_routes_end_to_end` 长期"偶发失败"的来源（约 6%）。
                //    修法见 `wrong_token()`：换成**与末位不同**的字符（回归见
                //    `wrong_token_always_differs`）。
                let bad = wrong_token(&token);
                format!("{PATH}/{bad}/api/status")              // 密钥错一位
            },
            "/api/status".to_string(),                          // 连前缀都不对
            "/".to_string(),
            "/index.html".to_string(),                          // 有人会去摸根路径
        ] {
            let (code, body) = http(port, "GET", &probe, b"");
            assert_eq!(code, 401, "{probe} 必须 401（实际 {code}）");
            assert_eq!(body.trim(), "Unauthorized", "{probe} 的 401 响应体必须固定: {body:?}");
            assert!(!body.contains(&token), "{probe} 的响应里泄露了密钥！");
            unauthorized.push(format!("{code}|{body}"));
        }
        // 所有失败响应对攻击者来说必须**一模一样**（不泄露"哪一步错了"）
        assert!(
            unauthorized.windows(2).all(|w| w[0] == w[1]),
            "不同的猜测方式得到了不同的 401 —— 会泄露路径结构: {unauthorized:?}"
        );
        // 写接口也一样：没密钥就别想动（这条是本漏洞的核心 —— 自授 root）
        let (code, body) = http(
            port,
            "POST",
            &format!("{PATH}/api/app/root"),
            b"pkg=com.demo&uid=10123&allow=1",
        );
        assert_eq!(code, 401, "不带密钥的 /api/app/root 必须 401（防本机 App 自授 root）");
        assert_eq!(body.trim(), "Unauthorized");
        let (code, _) = http(port, "GET", &format!("{PATH}/api/apps"), b"");
        assert_eq!(code, 401, "应用列表也不许裸奔");

        // 1.1) 带上正确密钥：**一切照旧能用**（这条是"别把功能改坏"的护栏）
        let (code, body) = http(port, "GET", &format!("{base}/api/status"), b"");
        assert_eq!(code, 200, "带正确密钥应该能访问");
        assert!(body.contains("\"root\":true"), "{body}");
        // 1.2) 密钥对了、路径不对 → 仍然是 404（不是 401，也不能是 200）
        let (code, _) = http(port, "GET", &format!("{base}/api/nope"), b"");
        assert_eq!(code, 404);

        // 2) 页面 + 状态
        let (code, body) = http(port, "GET", &base, b"");
        assert_eq!(code, 200);
        assert!(body.contains("网页管理器"), "页面渲染失败");
        let (code, body) = http(port, "GET", &format!("{base}/api/status"), b"");
        assert_eq!(code, 200);
        assert!(body.contains("\"root\":true"), "{body}");

        // 3) 超级用户：列表
        let (code, body) = http(port, "GET", &format!("{base}/api/apps"), b"");
        assert_eq!(code, 200);
        assert!(body.contains("com.demo"), "{body}");

        // 4) 超级用户：读配置（GET）
        let (code, body) = http(
            port,
            "GET",
            &format!("{base}/api/app/profile?pkg=com.demo&uid=10123"),
            b"",
        );
        assert_eq!(code, 200);
        assert!(body.contains("\"exists\":false"), "{body}");

        // 5) 超级用户：授权（POST，表单在 body 里）
        let (code, body) = http(
            port,
            "POST",
            &format!("{base}/api/app/root"),
            b"pkg=com.demo&uid=10123&allow=1",
        );
        assert_eq!(code, 200);
        assert!(body.contains("com.demo|10123|true"), "{body}");

        // 6) 超级用户：保存配置（表单）
        let (code, body) = http(
            port,
            "POST",
            &format!("{base}/api/app/profile"),
            b"pkg=com.demo&uid=10123&allowSu=1&umountModules=0",
        );
        assert_eq!(code, 200);
        assert!(body.contains("saved:allowSu=1"), "{body}");

        // 7) 日志：limit / 关键字要透传
        let (code, body) = http(port, "GET", &format!("{base}/api/sulog?limit=7&q=abc"), b"");
        assert_eq!(code, 200);
        assert!(body.contains("\"limit\":7") && body.contains("\"q\":\"abc\""), "{body}");
        let (code, _) = http(port, "POST", &format!("{base}/api/sulog/clear"), b"");
        assert_eq!(code, 200);
        let (code, body) = http(port, "POST", &format!("{base}/api/sulog/enable"), b"enable=1");
        assert_eq!(code, 200);
        assert!(body.contains("sulog=true"), "{body}");

        // 8) 上传装模块：真的把二进制搬过去
        let zip: Vec<u8> = {
            let mut v = b"PK\x03\x04".to_vec();
            v.extend_from_slice(&[0x41u8; 5000]);
            v
        };
        let (code, body) = http(
            port,
            "POST",
            &format!("{base}/api/module/install?name=demo.zip"),
            &zip,
        );
        assert_eq!(code, 200);
        assert!(
            body.contains("got 5004 bytes") && body.contains("magic=PK"),
            "{body}"
        );

        // 9) 上传的三种拒绝：扩展名、空、不是 zip
        let (code, body) = http(
            port,
            "POST",
            &format!("{base}/api/module/install?name=demo.txt"),
            &zip,
        );
        assert_eq!(code, 200);
        assert!(body.contains("\"ok\":false") && body.contains("zip"), "{body}");

        let (code, body) = http(
            port,
            "POST",
            &format!("{base}/api/module/install?name=demo.zip"),
            b"",
        );
        assert_eq!(code, 200);
        assert!(body.contains("\"ok\":false"), "{body}");

        let (code, body) = http(
            port,
            "POST",
            &format!("{base}/api/module/install?name=demo.zip"),
            b"NOTAZIP....",
        );
        assert_eq!(code, 200);
        assert!(body.contains("不是"), "{body}");

        // 9.5) 模块动作脚本输出
        let (code, body) = http(port, "POST", &format!("{base}/api/module/action"), b"id=demo");
        assert_eq!(code, 200);
        assert!(body.contains("hello from action.sh") && body.contains("\"ok\":true"), "{body}");
        let (_, body) = http(port, "POST", &format!("{base}/api/module/action"), b"id=bad");
        assert!(body.contains("\"ok\":false") && body.contains("没有动作脚本"), "{body}");

        // 9.6) 模块网页的 ksu.exec 桥（**options 必须透传**：cwd/env）
        let (code, body) = http(
            port,
            "POST",
            &format!("{base}/api/module/exec"),
            b"id=demo&cmd=echo+hi",
        );
        assert_eq!(code, 200);
        assert!(body.contains("demo:echo hi"), "{body}");
        let (_, body) = http(
            port,
            "POST",
            &format!("{base}/api/module/exec"),
            b"id=demo&cmd=pwd&options=%7B%22cwd%22%3A%22%2Fdata%22%7D",
        );
        let v: serde_json::Value = serde_json::from_str(&body).expect("应该是 JSON");
        assert_eq!(
            v["optionsSeen"],
            serde_json::json!("{\"cwd\":\"/data\"}"),
            "options 必须原样透传到后端：{body}"
        );

        // 9.65) 包列表（同步接口，桥直接返回 JSON 字符串）
        let (code, body) = http(port, "POST", &format!("{base}/api/module/packages"), b"type=user");
        assert_eq!(code, 200);
        assert!(body.contains("com.demo.user"), "{body}");
        let (_, body) = http(
            port,
            "POST",
            &format!("{base}/api/module/packages-info"),
            b"names=%5B%22com.a%22%2C%22com.b%22%5D",
        );
        assert!(body.contains("\"packageName\":\"com.b\"") && body.contains("\"uid\":10123"), "{body}");

        // 9.66) spawn：起进程 + 轮询（桥按行发 data 事件 + exit）
        let (code, body) = http(
            port,
            "POST",
            &format!("{base}/api/module/spawn"),
            b"id=demo&cmd=echo&args=%5B%22hi%22%5D",
        );
        assert_eq!(code, 200);
        assert!(body.contains("\"sid\":\"sid-echo-1\""), "{body}");
        let (code, body) = http(
            port,
            "POST",
            &format!("{base}/api/module/spawn/poll"),
            b"sid=sid-echo-1",
        );
        assert_eq!(code, 200);
        assert!(body.contains("line1") && body.contains("\"done\":true") && body.contains("\"code\":0"), "{body}");

        // 9.7) 模块网页静态资源：正常能取、越界/非 GET 一律 404
        let (code, body) = http(port, "GET", &format!("{base}/modweb/demo/index.html"), b"");
        assert_eq!(code, 200);
        assert!(body.contains("demo webui"), "{body}");
        let (code, _) = http(port, "GET", &format!("{base}/modweb/demo/../../etc/passwd"), b"");
        assert_eq!(code, 404, "目录穿越必须挡住");
        let (code, _) = http(port, "GET", &format!("{base}/modweb/nope/index.html"), b"");
        assert_eq!(code, 404);
        let (code, _) = http(port, "POST", &format!("{base}/modweb/demo/index.html"), b"");
        assert_eq!(code, 404, "模块网页只收 GET");

        // 10) 写接口只收 POST；未知路径 404
        let (code, _) = http(port, "GET", &format!("{base}/api/module/install?name=a.zip"), b"");
        assert_eq!(code, 404, "写接口 GET 必须 404");
        let (code, _) = http(port, "GET", &format!("{base}/api/nope"), b"");
        assert_eq!(code, 404);

        // 11) 静态资源白名单 + 目录穿越
        let (code, _) = http(port, "GET", &format!("{base}/a/app.js"), b"");
        assert_eq!(code, 200);
        let (code, _) = http(port, "GET", &format!("{base}/a/../Cargo.toml"), b"");
        assert_eq!(code, 404, "目录穿越必须挡住");

        // 12) 前端资源真的带上了这一批新功能。
        //     release 构建下 rust-embed 会把资源**压缩**嵌入，所以这条同时验证了解压路径
        //     —— 跑通它 = 发出去的 libksud.so 里那份前端确实是新版。
        let (_, js) = http(port, "GET", &format!("{base}/a/app.js"), b"");
        for marker in ["超级用户", "上传安装", "超级用户日志", "/api/app/root", "/api/sulog"] {
            assert!(js.contains(marker), "app.js 里缺 {marker}");
        }

        // 12.1) 回归护栏：get()/post() 的 **api 路径里不许自带 ?**
        //       带了就会和末尾拼上去的 `?k=口令` 打架（v0.13.99 真机踩到：
        //       「配置」按钮点了没反应、日志页打不开 —— 就是 URL 拼错、uid 解析失败）。
        let mut bad: Vec<String> = Vec::new();
        for line in js.lines() {
            if let Some(pos) = line.find("(\"/api/") {
                let after = &line[pos + 2..];
                let path = &after[..after.find('"').unwrap_or(0)];
                if path.contains('?') {
                    bad.push(path.to_string());
                }
            }
        }
        assert!(bad.is_empty(), "api 路径里不许带 ?（要改用 params 参数）: {bad:?}");
        assert!(js.contains("function qs(params)"), "app.js 里缺少 qs() 参数拼接函数");
        let (_, css) = http(port, "GET", &format!("{base}/a/app.css"), b"");
        let (_, js2) = http(port, "GET", &format!("{base}/a/app.js"), b"");
        assert!(!js2.contains("__K__"), "前端里不该再有口令占位符");
        assert!(!js2.contains("\"?k=\""), "前端代码里不该再拼口令参数");
        assert!(css.contains(".logitem") && css.contains(".prog"), "app.css 里缺新样式");
        let (_, page) = http(port, "GET", &base, b"");
        assert!(!page.contains("__K__"), "页面里不该再有口令占位符");
        assert!(
            page.contains("data-tab=\"users\"") && page.contains("data-tab=\"sulog\""),
            "页面缺新标签"
        );

        // 12.2) 🔐 前端拿到的前缀必须**含密钥**：否则页面自己发的请求全变 401
        //       （踩过的同类坑：注入占位符写错 → 点哪个按钮都没反应）
        assert!(
            js2.contains(&base),
            "app.js 注入的路径前缀里必须有密钥：期望 {base}"
        );
        assert!(
            page.contains(&base),
            "index.html 注入的路径前缀里必须有密钥：期望 {base}"
        );
        // 页面/JS 里不许出现**裸前缀**形式的 URL（那会请求到需要密钥的地址上）
        assert!(
            !js2.contains(&format!("\"{PATH}\"")),
            "app.js 里还留着不带密钥的裸前缀"
        );
        assert!(
            !page.contains(&format!("{PATH}\"")),
            "index.html 里还留着不带密钥的裸前缀"
        );
        // 前端自己也不许再写死"没有口令"这类误导文案
        assert!(!page.contains("__P__"), "占位符必须被替换掉");

        unsafe {
            std::env::remove_var("WEBADMIN_CONF");
            std::env::remove_var("WEBADMIN_UPLOAD_DIR");
        }
    }

}

