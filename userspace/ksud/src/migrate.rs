//! 数据目录改名：`/data/adb/ksu` → `/data/adb/sevenk`（2026-09-20「彻底切割」）
//!
//! 用户要求（原文，**2026-09-20 那一版**）：
//!   · 新 App/ksud 首次运行时，把旧目录的内容**搬到**新目录（先复制 → 校验 → 再删旧的）
//!   · 失败要**安全**：宁可留旧的、不要删一半
//!   · **不要**在新旧路径之间留符号链接
//!
//! ⚠️ **2026-09-21（v2.9）用户把最后那一条改了**（这是一次真实回归的补救）：
//!   改名之后**大量第三方模块把自己写死的 `/data/adb/ksu/...` 当成 KernelSU 的数据目录**
//!   （典型：Zygisk Next —— 装上去没效果，它自己的界面报 "Module files corrupted"）。
//!   它们找不到自己的文件 ⇒ 整类功能全废。用户要求"这类问题**全部**修复"，
//!   所以现在**必须**在新旧路径之间留一条**兼容软链**
//!   （`/data/adb/ksu` → `/data/adb/sevenk`，见 [`ensure_compat_link_at`]）。
//!
//! 🔑 **当初"留软链 = 没切干净"这个顾虑，在 Android 上根本不成立**（关键认识）：
//!   · `/data/adb` 整个目录**只有 root 能读**（`ls /data/adb` 在 adb shell 上是
//!     `Permission denied`，实测设备 HA247EY4；非 root 进程连 `stat` 都 EACCES）；
//!   · 所以**检测类 App（没有 root 的那些）根本看不到这条软链** ⇒ **不影响隐身** ✓；
//!   · 反过来，**需要旧路径的恰恰是有 root 的那批人**（模块安装脚本 / `su` 会话 /
//!     Zygisk Next 的 daemon）—— 他们本来就读得到 `/data/adb`。
//!   ⇒ 软链是**纯收益**：对外兼容第三方，对隐身零影响。
//!
//! ⚠️ **2026-09-21（v2.10）审计又抓出软链本身的两个致命缺口**（都在 v2.9 的实现里）：
//!   · 🔴1 **悬空软链会被永久跳过**：v2.9 的 `LinkedToNew` 早返回只认"软链指向 new"，
//!     不认"new 还在不在"。`ksud uninstall` 删掉 `/data/adb/sevenk` 之后，
//!     `/data/adb/ksu` 就成了**指向不存在目标的死链**，而 `ensure_compat_link_at`
//!     永远报"已经是软链（幂等跳过）"⇒ 第三方拿到的路径全 ENOENT。
//!     修法：① 建链前先确认目标是**真实可用的目录**，否则删死链 + 补空目录 + 重建
//!     （[`CompatLink::DeadLinkRepaired`]）；② `uninstall()` 顺手删掉软链本身。
//!   · 🔴2 **旧管理器会把软链"换成真实目录"**：v0.13.18~v0.13.151 的老管理器包
//!     （`applicationId=com.sevenkimimasy.love`）里的 ksud 用**旧路径常量**，
//!     它的 `install()` 会 `rm -rf /data/adb/ksu` 再建目录 ⇒ 一次性报废迁移与兼容。
//!     修法：`auto_migrate_with()` **末尾无条件**做一次廉价 `lstat` 复检
//!     （见 [`reassert_compat_link`]），发现不再是软链就按下面第 4 条的安全规则重处理。
//!
//! ── 2026-09-20 独立审计后的三条红线（本文件的核心）────────────────────────
//!
//! 🔴A **只有"新内核在跑"才可以搬。** 数据目录是**编译期写死在内核里**的：
//!     老内核读 `/data/adb/ksu`（见 `kernel/policy/allowlist.c:71,521`、
//!     `kernel/manager/stealth.c:29`），新内核读 `/data/adb/sevenk`。
//!     「先装新 APK、还没刷新内核」时一旦把旧目录搬走：
//!       · 隐身标志读不到 → **默认关闭 = 暴露** ✗
//!       · 授权名单读不到 → 重启后已授权应用要重新授权 ✗
//!
//!     ⚠️ **只认"新命令号 27/28 有没有"是不够的**：v0.13.151 的内核**已经带了**
//!        `KSU_IOCTL_STEALTH_GET_G/SET_G`，但它读的还是 `/data/adb/ksu`。
//!        （实测证据：从 `v0.13.151/7kimisu-v0.13.151.apk` 里把内嵌 `.ko` 挖出来，
//!          同一份二进制里既有 `STEALTH_GET_G` 又有 `/data/adb/ksu/.allowlist`。）
//!        所以门控用**行为探测**（[`probe_datadir_with`]）：
//!        让内核把隐身标志写一遍（**同值写回，语义上等于空操作**），再看**哪个**路径的文件
//!        真的变了 —— 只有确实写进新目录才算"新内核"。命令不支持 / 权限不足 / 两边都没动
//!        ⇒ **拿不准 ⇒ 不迁移** ✓（宁可晚点迁，也绝不把数据搬走）。
//!
//! 🔴B **关键文件按"内容优劣"取舍，而且永远能自动修回。**
//!     `.allowlist` / `stealth` / `stealth_code` 是"丢了就掉授权 / 退不出隐身"的文件。
//!     新目录里若是一份**空的/截断的**（例如内核刚建目录时写下的空名单），
//!     **绝不能**按"新目录那份优先"保留 ✗。规则见 [`decide_key_file`]：
//!       · 空 / 截断 / 明显偏小 ⇒ **不算数**，以**完整的那份**为准；
//!       · 两边都完整但不同 ⇒ 保留新目录那份，但**不删旧目录**、写冲突标记；
//!       · 冲突标记存在时**也要**做一遍关键文件体检（否则自动流程永远修不回 ✗）。
//!     🟠 新目录里**整个关键文件缺失**（例如上次复制时 ENOSPC/EIO 丢掉 `.allowlist`）
//!        也要在体检里补回：旧目录那份有效就从旧目录复制过去，绝不指望"全量迁移下次会跑"
//!        —— 标记存在时全量迁移**根本不会跑** ✗。
//!
//! 🟠 复制时**尽力**恢复属主（uid/gid）与 SELinux 标签，失败就写日志并记进 report
//!     （[`restore_meta`]）。原子 `rename()` 路径本来就是原样搬，没这个问题。
//!
//! 安全设计（照着用户那三条来）：
//!   1. 旧目录不存在 → 立刻返回（正常情况，只有一次 `stat`）。
//!   2. 旧目录在、但没确认"新内核在跑" → **只探测，一个字节都不动**。
//!   3. 新目录不存在 → 首选 **原子 `rename()`**：同一文件系统上的目录改名是原子的，
//!      根本不存在"删一半"的窗口。rename 失败（跨文件系统等）才退回复制。
//!   4. 复制路径：逐条复制 → **逐条回读校验**（长度 + 分块逐字节比对）→ 补属主/SELinux
//!      → 全部通过才 `remove_dir_all(旧目录)`。
//!      任何一条冲突/失败 ⇒ **一条都不删**，只在新目录写/更新冲突标记。
//!   5. ~~全程**不建任何符号链接**~~ → **v2.9 起反过来**：旧路径上**必须**留一条
//!      `/data/adb/ksu` → `/data/adb/sevenk` 的软链（理由见文件开头）。但有一条铁律不变：
//!      **有内容的老目录一个字节都不删**——需要腾出路径时是把老目录**改名留存**
//!      （`/data/adb/ksu.legacy_backup_<ts>`），不是删除。
//!   6. 幂等：搬完旧目录就没了，再跑返回 `legacy_absent`（并顺手保证软链就位）。
//!
//! ⚠️ 本文件里的 [`LEGACY_DIR`] 是**全仓仅有的两处故意保留的旧路径字符串**之一
//!    （另一处是 App 侧 `LegacyDataMigration.kt`）：不知道旧目录叫什么就搬不动它 ——
//!    而且 v2.9 起它还多了一个身份：**兼容软链要落在的那个路径**。

// 本模块在 Android 上由 cli.rs 调用；在开发机（宿主 target）上只有单测用得到它，
// 于是会报一堆 dead_code/never used —— 这里显式允许，免得污染宿主上的 clippy 结果。
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use anyhow::{Context, Result};
use std::collections::{HashMap, HashSet};
use std::fmt::Write as _;
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

/// 旧数据目录（改名前的名字）。**故意保留**：迁移必须知道旧目录叫什么，
/// 而且 v2.9 起它就是**兼容软链所在的路径**（[`ensure_compat_link_at`]）。
pub const LEGACY_DIR: &str = "/data/adb/ksu";
/// 新数据目录（改名后）。
pub const NEW_DIR: &str = "/data/adb/sevenk";
/// 迁移冲突标记文件（落在新目录里）。存在 = 上次迁移有冲突/修过关键文件、
/// **故意没删旧目录**。见到它就不再全量重扫（避免每次 `su` 都重扫一遍），
/// 但**仍然**做一遍关键文件体检（🔴B）。要重来一次用 `ksud migrate --force`。
pub const CONFLICT_MARKER: &str = ".legacy_migrate_conflict";
/// 迁移成功标记（落在新目录里，纯排查用；成功路径上旧目录已经没了，不靠它做判断）。
pub const DONE_MARKER: &str = ".migrated_from_ksu";
/// 授权名单（内核持久化格式，丢了 = 全员掉授权）。
pub const ALLOWLIST_FILE: &str = ".allowlist";
/// 隐身标志（内容 `'0'`/`'1'`，丢了 = 隐身默认关闭 = 暴露）。
pub const STEALTH_FILE: &str = "stealth";
/// 隐身密令（丢了 = 拨号盘退不出隐身 = 有可能被锁死）。
pub const STEALTH_CODE_FILE: &str = "stealth_code";
/// 需要做"内容优劣判定"的关键文件（🔴B）。
pub const KEY_FILES: [&str; 3] = [ALLOWLIST_FILE, STEALTH_FILE, STEALTH_CODE_FILE];

// ── 🔴1（v2.11）`.allowlist` 的"按记录并集合并"用的字段偏移 ─────────────────
//
// 记录布局 = 内核 `struct app_profile`（`uapi/app_profile.h`，与 ksud `ksu_uapi.rs` 同一份）：
//   u32 version | char key[256] | s32 curr_uid | bool allow_su | union{...}
// 所以 key 在 [4, 260)、curr_uid 在 [260, 264)（都是小端）。
// 合并只读这两个字段，**记录其余字节原样搬运** —— 绝不重新解释/重写 profile 内容。

/// `key` 字段在记录内的范围（C 字符串，`\0` 结尾）
const ALLOWLIST_KEY_RANGE: std::ops::Range<usize> = 4..(4 + 256);
/// `curr_uid` 在记录内的偏移（小端 `i32`）
const ALLOWLIST_UID_OFFSET: usize = 4 + 256;
/// 内核 `KSU_APP_PROFILE_PRESERVE_UID`（9999 = NOBODY_UID）：默认 profile 用的假 uid。
/// 这条记录的唯一身份是它的 `key`（内核要求它必须是 `"$"`），合并时按 key 判重。
const ALLOWLIST_PRESERVE_UID: i32 = 9999;

/// 🟠7（v2.11）冲突标记文件的**上限**：超过就只保留最近的这一段（旧记录从前面裁掉）。
/// 理由：标记是"只追加"的排查日志，而它在每次 `su` 的隐式迁移路径上都可能被追加；
/// 真机 HA247EY4 上曾经一夜涨到 24 KB+。规范化（不再重复记同样的行）之后正常不会长，
/// 但**上限**是最后一道保险（防"每次都是新行"的病态情况无限写闪存）。
const MARKER_MAX_BYTES: usize = 64 * 1024;

/// 🟡（v2.11）迁移段的进程间锁文件名（放在旧目录的**父目录**里，即真机上的 `/data/adb/`）。
const MIGRATE_LOCK_FILE: &str = ".migrate.lock";
/// 锁多久没被刷新就算**陈旧**（进程被 kill 掉时留下的锁；直接抢过来）。
const MIGRATE_LOCK_STALE_SECS: u64 = 120;
/// 🟠6（v2.11）`ksu.legacy_backup_*` 最多保留几份（按时间戳，新的留下）。
const LEGACY_BACKUP_KEEP: usize = 3;

// ══════════════════════════════════════════════════════════════════════
// 门控结论缓存（"一次性标记"）—— 2026-09-21 省电优化
// ══════════════════════════════════════════════════════════════════════
//
// 背景：`auto_migrate()` 在**每一次** `ksud` 调用（含每一次 `su`）的开头都会跑。
// 旧目录还在的机器上，它要么让内核**同值重写一遍** `stealth`（走 🔴A 门控探测，
// 内核 `ksu_stealth_set()` 是 `O_TRUNC` + `kernel_write` = 一次**真实的闪存写入**），
// 要么把冲突标记**再追加一段**（走 🔴B 关键文件体检）。实测（HA247EY4）：
// 每跑一次 `su` ⇒ `.legacy_migrate_conflict` 一次 read + 一次 write，文件 +150 字节，
// 且**永远不会收敛**（新/旧 `stealth` 值不同 ⇒ 每次都判定"冲突"）。
//
// 但这两件事的结论在**同一次开机 + 同一个内核**里根本不会变 ⇒ 白干的活。
// 所以把结论落到这个小文件里，命中就**不探测、不体检、一个字节都不写**。

/// 门控结论缓存文件（落在新目录里；纯缓存，删掉/损坏只会退化成"每次重新探测"）。
pub const GATE_STAMP_FILE: &str = ".gate_stamp";
/// 非"旧内核"结论的缓存有效期（秒）。迁移与关键文件体检不会分钟级变化，
/// 但也不能永久跳过：万一新目录那份关键文件后来被写坏，最多 1 小时内会被体检修回。
pub const STAMP_TTL_SECS: u64 = 3600;
/// 内核 release 字符串（`uname -r`；不需要 root、不需要 ioctl）
const OSRELEASE_PATH: &str = "/proc/sys/kernel/osrelease";
/// 本次开机 id（每次重启都变；不需要 root）
const BOOT_ID_PATH: &str = "/proc/sys/kernel/random/boot_id";

/// 门控缓存的"当前内核身份"。三者任一变化 ⇒ 缓存作废、重新探测。
///
/// * `kernel`：内核报的 KSU 版本（`ksucalls::get_version()`，一次 ioctl，不落盘）。
///   `None`/`0` = 拿不到（很老的内核 / 命令不支持）—— 这时**不敢缓存旧内核结论**
///   （见 [`GateStamp::is_fresh`]），因为 LKM 热加载能换内核而**不重启**。
/// * `osrelease`：`uname -r`。换内核包一定会变（同一 KMI 不同编译也会带不同后缀）。
/// * `boot`：本次开机 id。重启必然作废。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GateKey {
    /// 内核 KSU 版本；`None` = 拿不到
    pub kernel: Option<i32>,
    /// `uname -r`
    pub osrelease: String,
    /// `/proc/sys/kernel/random/boot_id`
    pub boot: String,
}

impl GateKey {
    /// 组装一个身份（**只在宿主单测里用**；Android 上走 [`Self::current`]）
    #[cfg_attr(target_os = "android", allow(dead_code))]
    #[must_use]
    pub fn new(kernel: Option<i32>, osrelease: impl Into<String>, boot: impl Into<String>) -> Self {
        Self {
            kernel,
            osrelease: osrelease.into(),
            boot: boot.into(),
        }
    }

    /// 读**当前**内核身份：两个 proc 文件 + 一次内核版本 ioctl（都不落盘）
    #[cfg(target_os = "android")]
    #[must_use]
    pub fn current() -> Self {
        let read_trim = |p: &str| {
            fs::read_to_string(p)
                .map(|s| s.trim().to_string())
                .unwrap_or_default()
        };
        let v = crate::ksucalls::get_version();
        Self {
            kernel: (v > 0).then_some(v),
            osrelease: read_trim(OSRELEASE_PATH),
            boot: read_trim(BOOT_ID_PATH),
        }
    }
}

/// 缓存里的结论（就是"上次 `auto_migrate()` 干完活之后的定型状态"）
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StampVerdict {
    /// 确认新内核在跑（迁移或关键文件体检已经跑过）
    New,
    /// 确认旧内核在跑（本次不迁移；旧目录一个字节都没动）
    Legacy,
    /// 冲突标记路径：关键文件体检已经跑过（本次连探测都没做）
    Checked,
}

impl StampVerdict {
    const fn as_str(self) -> &'static str {
        match self {
            Self::New => "new_datadir",
            Self::Legacy => "legacy_datadir",
            Self::Checked => "checked",
        }
    }

    fn parse(s: &str) -> Option<Self> {
        match s {
            "new_datadir" => Some(Self::New),
            "legacy_datadir" => Some(Self::Legacy),
            "checked" => Some(Self::Checked),
            _ => None,
        }
    }
}

/// 门控缓存的一条记录（纯数据；`parse`/`render` 都是纯函数 ⇒ 宿主上可单测）
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GateStamp {
    /// 上次的结论
    pub verdict: StampVerdict,
    /// 上次的内核 KSU 版本（`None` = 当时也拿不到）
    pub kernel: Option<i32>,
    /// 上次的 `uname -r`
    pub osrelease: String,
    /// 上次的开机 id
    pub boot: String,
    /// 写入时刻（unix 秒）
    pub at: u64,
}

impl GateStamp {
    /// 写进文件的格式（一行一项，纯文本，方便人工排查）
    fn render(&self) -> String {
        format!(
            "verdict={}\nkernel={}\nosrelease={}\nboot={}\nat={}\n",
            self.verdict.as_str(),
            self.kernel
                .map_or_else(|| "-".to_string(), |v| v.to_string()),
            self.osrelease,
            self.boot,
            self.at,
        )
    }

    /// 解析（缺项/垃圾内容一律返回 `None` ⇒ 当作没有缓存 ⇒ 重新探测，安全方向）
    fn parse(s: &str) -> Option<Self> {
        let mut verdict = None;
        let mut kernel = None;
        let mut osrelease = String::new();
        let mut boot = String::new();
        let mut at = None;
        for line in s.lines() {
            let (k, v) = line.split_once('=')?;
            match k {
                "verdict" => verdict = StampVerdict::parse(v),
                "kernel" => kernel = v.parse::<i32>().ok(),
                "osrelease" => osrelease = v.to_string(),
                "boot" => boot = v.to_string(),
                "at" => at = v.parse::<u64>().ok(),
                _ => {}
            }
        }
        Some(Self {
            verdict: verdict?,
            kernel,
            osrelease,
            boot,
            at: at?,
        })
    }

    /// 这条缓存现在还作数吗？
    ///
    /// * `boot` 必须非空且相同（重启即作废）；
    /// * `osrelease` 必须相同（换内核包即作废）；
    /// * `Legacy` 结论额外要求**双方都能拿到内核版本号**且相同
    ///   —— LKM 热加载换内核**不重启**，只有版本号能识破；拿不到版本号就
    ///   **不复用**（宁可每次多探测一次，也绝不把迁移拖到下次重启）；
    /// * 其余结论有 [`STAMP_TTL_SECS`] 上限。
    #[must_use]
    pub fn is_fresh(&self, key: &GateKey, now: u64) -> bool {
        if key.boot.is_empty() || self.boot != key.boot || self.osrelease != key.osrelease {
            return false;
        }
        match self.verdict {
            StampVerdict::Legacy => match (self.kernel, key.kernel) {
                (Some(a), Some(b)) if a > 0 && b > 0 => a == b,
                _ => false,
            },
            StampVerdict::New | StampVerdict::Checked => {
                // 🟠（v2.18）**内核换过就不能复用缓存**。LKM 热加载（"直接安装"后不重启）
                // 会在 boot/osrelease 都不变的情况下把内核换掉 —— 只比 TTL 的话，
                // 上一轮"新内核在跑"的结论会被原样复用，而实际在跑的可能已经是老内核。
                // 两个版本号都拿得到且不同 ⇒ 直接判"缓存不新鲜"（下次重新探测，保守方向）。
                if let (Some(a), Some(b)) = (self.kernel, key.kernel)
                    && a > 0
                    && b > 0
                    && a != b
                {
                    return false;
                }
                now.saturating_sub(self.at) <= STAMP_TTL_SECS
            }
        }
    }
}

/// 读缓存文件（没有/坏了都返回 `None`）
fn read_stamp(new: &Path) -> Option<GateStamp> {
    let raw = fs::read_to_string(new.join(GATE_STAMP_FILE)).ok()?;
    GateStamp::parse(&raw)
}

/// 把结论落盘（**只在结论"定型"时才调**，见 [`verdict_of`]）。
///
/// 不 fsync：这是纯缓存，掉电丢了只会退化成"下次重新探测"，不值得多一次(sync)闪存写。
fn write_stamp(new: &Path, stamp: &GateStamp) {
    if !path_exists(new) && fs::create_dir_all(new).is_err() {
        return;
    }
    let _ = fs::write(new.join(GATE_STAMP_FILE), stamp.render());
}

/// 这份 report 的结论"定型"了没有？定型才写缓存。
///
/// * 旧目录不存在 ⇒ 不需要缓存（下次一个 `stat` 就返回了）；
/// * 门控**拿不准**（`unknown`）⇒ **绝不缓存**：保留"下次继续探测"的原语义
///   （老内核不认命令、ioctl 偶发失败这类情况会一直重试，直到有结论为止）；
/// * 其余（确认新内核 / 确认旧内核 / 体检跑完）⇒ 定型。
fn verdict_of(r: &Report) -> Option<StampVerdict> {
    if r.legacy_absent {
        return None;
    }
    if r.skipped_not_new_kernel {
        return match r.kernel_probe {
            Some("legacy_datadir") => Some(StampVerdict::Legacy),
            _ => None,
        };
    }
    // 🟢 v2.9：**确认新内核在跑**本身就是一个定型结论（`New`）。
    //     旧代码在这里一律返回 `Checked`（"只做过体检"），而 v2.9 的兼容软链需要
    //     知道"能不能接管有内容的老目录"—— 那个判断靠的就是这个 `New`。
    //     （`New` 与 `Checked` 一样有 1 小时 TTL、重启/换内核即作废，语义没变宽。）
    if r.kernel_probe == Some(DatadirProbe::New.as_str()) {
        return Some(StampVerdict::New);
    }
    Some(StampVerdict::Checked)
}

/// 把缓存命中包装成一份 report（调用方只用来打日志/打印，不做任何动作）
fn cached_report(v: StampVerdict) -> Report {
    let mut r = Report {
        cached: true,
        ..Report::default()
    };
    match v {
        StampVerdict::Legacy => {
            r.skipped_not_new_kernel = true;
            r.kernel_probe = Some("legacy_datadir");
            r.notes.push(
                "门控结论缓存命中（同内核 + 同一次开机）⇒ 本次不探测、不体检、不写任何文件"
                    .to_string(),
            );
        }
        StampVerdict::New => {
            r.kernel_probe = Some("new_datadir");
            r.notes
                .push("门控结论缓存命中（新内核 + 同一次开机）⇒ 本次无动作".to_string());
        }
        StampVerdict::Checked => {
            r.notes
                .push("关键文件体检缓存命中（同一次开机 + 1 小时内）⇒ 本次无动作".to_string());
        }
    }
    r
}

/// 当前 unix 秒（取不到就返回 0 ⇒ 缓存判过期，安全方向）
fn unix_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}

/// `.allowlist` 二进制格式（与 `kernel/policy/allowlist.c` 对齐）：
/// `u32 magic` + `u32 version` + N × `sizeof(struct app_profile)`。
/// 记录大小：version 4 = 784（同一份头文件在 bindgen 里的 `size_of` 断言 = 784，
/// 单测 [`tests::allowlist_record_size_matches_kernel_header`] 会去核对它；
/// Android 侧这里还有一条**编译期**断言兜底）；
/// 更老的 version 2/3 = 776（内核里的 `kAppProfileSizePreV4`）。
const ALLOWLIST_MAGIC: u32 = 0x7f4b_5355; // ' KSU'
const ALLOWLIST_RECORD_V4: usize = 784;
const ALLOWLIST_RECORD_PRE_V4: usize = 776;
const ALLOWLIST_MIN_VERSION: u32 = 2;
const ALLOWLIST_MAX_VERSION: u32 = 4;

// 编译期核对：常量必须等于内核那份 `struct app_profile` 的真实大小（同一份 uapi 头文件）。
// `cargo ndk -t arm64-v8a check` 会真的把这条断言编一遍 —— 常量写错就编不过。
#[cfg(target_os = "android")]
const _: () = assert!(
    std::mem::size_of::<crate::ksu_uapi::app_profile>() == ALLOWLIST_RECORD_V4,
    "ALLOWLIST_RECORD_V4 与内核 struct app_profile 大小不一致（见 uapi/app_profile.h）"
);

/// 一次迁移的结果。`is_clean()` 为真 = 没冲突没失败没修过关键文件 = 旧目录已被安全删除。
///
/// 这一堆 bool 是**报告字段**（不是一个状态机的状态位），而且每一个都直接对应
/// "旧目录到底动没动"的一条结论，拆开反而更难读 —— 所以显式关掉这条 lint。
#[allow(clippy::struct_excessive_bools)]
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Report {
    /// 旧目录本来就不存在（无事可做）
    pub legacy_absent: bool,
    /// 走的是原子 rename（最理想）
    pub renamed: bool,
    /// 复制成功的条目数
    pub copied: usize,
    /// 新目录里已有一份**内容相同**的条目（跳过）
    pub already: usize,
    /// 双方都有但**内容不同**的条目（一条都不删）
    pub conflicts: Vec<String>,
    /// 复制/校验失败的条目（一条都不删）
    pub failed: Vec<String>,
    /// 关键文件：用**旧目录的完整版本**覆盖了新目录里的空/截断版本（🔴B 的自动修复）
    pub repaired: Vec<String>,
    /// 关键文件：旧目录那份空/截断 → **不算数**，以新目录那份为准（🔴B）
    pub ignored_broken_legacy: Vec<String>,
    /// 🔴A 门控：没确认"新内核在跑"，本次**不迁移**（旧目录一个字节都没动）
    pub skipped_not_new_kernel: bool,
    /// 门控探测的结论（`new_datadir` / `legacy_datadir` / `unknown`；诊断用）
    pub kernel_probe: Option<&'static str>,
    /// 🟢（v2.9）兼容软链 `/data/adb/ksu` → `/data/adb/sevenk` 这一次的处理结果
    /// （见 [`ensure_compat_link_at`]；诊断 + App 提示用）
    pub compat_link: Option<CompatLink>,
    /// 旧目录是否已被删除
    pub deleted_legacy: bool,
    /// 本次是**门控结论缓存命中**（没探测、没体检、没写任何文件；见 [`GateStamp`]）
    pub cached: bool,
    /// 🔴1（v2.11）`.allowlist`：旧目录（含改名留存的 `ksu.legacy_backup_*`）里
    /// **新目录还没有**的授权记录条数。> 0 = "旧目录还有 N 条授权未被采用"
    /// （App 首页那张卡就是靠它出数字；命令行会打印 `ALLOWLIST_PENDING=N`）。
    pub allowlist_legacy_only: usize,
    /// 🔴1（v2.11）`.allowlist`：**本次真的采纳**（并集合并写盘）的记录条数。
    /// 只看它 > 0 才提示"请重启"（命令行会打印 `ALLOWLIST_ADOPTED=N`）。
    pub allowlist_adopted: usize,
    /// 过程说明（rename 失败退回复制、属主/SELinux 恢复情况之类）
    pub notes: Vec<String>,
}

impl Report {
    /// 没冲突、没失败、也没修过关键文件
    #[must_use]
    pub const fn is_clean(&self) -> bool {
        self.conflicts.is_empty()
            && self.failed.is_empty()
            && self.repaired.is_empty()
            // 🟠（v2.18）**显式**把"旧目录还有未采纳的授权记录"也算进"不干净"。
            // 这个方法决定的是"能不能 `remove_dir_all(legacy)`"（见 `auto_migrate_inner`）——
            // 旧写法靠"凡是设置这个字段的路径都恰好会 push conflicts/failed"这条
            // **隐式耦合**兜底；哪天有人加一条"只记数、不记冲突"的路径，
            // 就会在"授权还没采纳"时把旧目录连根删掉（数据丢失）。
            // 宁可不删（旧目录改名留存本来就是安全方向），也不赌隐式约定。
            && self.allowlist_legacy_only == 0
    }

    /// 一行摘要（给命令行/日志看）
    ///
    /// 🟢（v2.9）：兼容软链的结果**挂在每一行分支后面**——`legacy_absent` / 缓存命中 /
    /// 门控拦下这三种"看起来什么都没干"的路径，恰恰是老用户升级后补软链的主路径，
    /// 不报出来就没法在真机上排查（App 侧也是靠这一行做判断）。
    #[must_use]
    pub fn summary(&self) -> String {
        let mut body = self.summary_body();
        // 🔴1（v2.11）授权名单的"未采纳 / 已采纳"必须在**每一条分支**上都看得见：
        // App 首页那张「旧目录还有 N 条授权未被采用」的卡、以及"采纳并重启"的结果，
        // 都是靠这两行（加上 `ALLOWLIST_PENDING=` / `ALLOWLIST_ADOPTED=`）判断的。
        if self.allowlist_legacy_only > 0 {
            let _ = write!(
                body,
                "\n  · 🔴1 旧目录还有 {} 条授权未被采用（App 首页点「采纳并重启」，或跑 `ksud migrate --adopt-legacy-allowlist`）",
                self.allowlist_legacy_only
            );
        }
        if self.allowlist_adopted > 0 {
            let _ = write!(
                body,
                "\n  ! 🔴1 已采纳旧目录独有的 {} 条授权（并集合并：同 UID 以新目录那份为准）⇒ **重启一次**内核才会读到",
                self.allowlist_adopted
            );
        }
        match &self.compat_link {
            Some(l) => format!(
                "{body}\n  · 兼容软链 {LEGACY_DIR} -> {NEW_DIR}：{}",
                l.describe()
            ),
            None => body,
        }
    }

    fn summary_body(&self) -> String {
        if self.legacy_absent {
            let mut s = "旧目录不存在，无需迁移".to_string();
            for n in &self.notes {
                s.push_str("\n  · ");
                s.push_str(n);
            }
            // `.allowlist` 的采纳可能发生在"旧路径已经是软链"这条路上
            // （老目录被改名留存在 `ksu.legacy_backup_*`）⇒ 结论也要打出来。
            for x in &self.repaired {
                s.push_str("\n  ! 已修复/采纳: ");
                s.push_str(x);
            }
            return s;
        }
        // 缓存命中：**什么都没做**，一行说清楚就够（别让日志看起来像刚探测过）
        if self.cached {
            let what = match self.kernel_probe {
                Some("legacy_datadir") => "上次判定=旧内核在跑",
                Some("new_datadir") => "上次判定=新内核在跑",
                _ => "上次已完成关键文件体检",
            };
            return format!("门控结论缓存命中（{what}）⇒ 本次不探测/不体检/不写任何文件");
        }
        if self.skipped_not_new_kernel {
            let why = match self.kernel_probe {
                Some("legacy_datadir") => "探测到**旧内核**（它读的还是 /data/adb/ksu）",
                Some("new_datadir") => "探测结论自相矛盾",
                _ => "**拿不准**新内核在不在跑（探测无结论）",
            };
            let mut s = format!("本次不迁移：{why}；旧目录保持原样（绝不搬走）");
            for n in &self.notes {
                s.push_str("\n  · ");
                s.push_str(n);
            }
            return s;
        }
        if self.renamed {
            return format!("原子改名成功：{LEGACY_DIR} -> {NEW_DIR}");
        }
        let mut s = format!(
            "复制 {} 条、已存在且一致 {} 条、冲突 {} 条、失败 {} 条、关键文件修复 {} 条；旧目录{}",
            self.copied,
            self.already,
            self.conflicts.len(),
            self.failed.len(),
            self.repaired.len(),
            if self.deleted_legacy {
                "已删除"
            } else {
                "**保留**（安全起见）"
            }
        );
        for n in &self.notes {
            s.push_str("\n  · ");
            s.push_str(n);
        }
        for c in &self.conflicts {
            s.push_str("\n  ! 冲突（保留新目录那份，不动旧目录）: ");
            s.push_str(c);
        }
        for f in &self.failed {
            s.push_str("\n  ! 失败: ");
            s.push_str(f);
        }
        for x in &self.repaired {
            s.push_str("\n  ! 已修复（用旧目录的完整版本覆盖新目录里空/截断的那份）: ");
            s.push_str(x);
        }
        for x in &self.ignored_broken_legacy {
            s.push_str("\n  · 忽略（旧目录那份空/截断，以新目录为准）: ");
            s.push_str(x);
        }
        s
    }
}

fn path_exists(p: &Path) -> bool {
    fs::symlink_metadata(p).is_ok()
}

/// `lstat`，但把 `NotFound` 当作"它已经不在了"（`Ok(None)`）而**不是**错误。
///
/// 🔴（v2.18）为什么需要它：本文件到处是"`path_exists(p)` 先判、随后
/// `symlink_metadata(p)?` 再用"的写法 —— 中间那段时间 `p` 可能被并发删掉
/// （另一次迁移 / 用户手动清 / `prune`）。旧写法把这条**良性竞态**变成 `Err`
/// 一路冒出 `auto_migrate()`，于是**每次 `su` 都刷一条硬错误**（报告/日志污染，
/// 用户以为"迁移坏了"）。照本仓 `utils.rs::ensure_clean_dir` 的正确样例：
/// `NotFound` 走"不存在"分支；只有真读不动（EACCES/EIO）才继续当错误。
///
/// 抽成独立函数是为了能在宿主上把 `None` / `Some` / `Err` 三种结局**逐条钉进单测**。
fn lstat_or_gone(p: &Path) -> std::io::Result<Option<fs::Metadata>> {
    match fs::symlink_metadata(p) {
        Ok(m) => Ok(Some(m)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e),
    }
}

fn show(rel: &Path) -> String {
    if rel.as_os_str().is_empty() {
        "<根>".to_string()
    } else {
        rel.display().to_string()
    }
}

fn dir_is_empty(p: &Path) -> bool {
    fs::read_dir(p).is_ok_and(|mut it| it.next().is_none())
}

// ══════════════════════════════════════════════════════════════════════
// 🔴A 门控：确认"新内核在跑"（它读的是 /data/adb/sevenk）才允许迁移
// ══════════════════════════════════════════════════════════════════════

/// 门控探测的结论
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DatadirProbe {
    /// 内核把数据写进了**新**目录 ⇒ 新内核在跑 ⇒ 可以迁移
    New,
    /// 内核把数据写进了**旧**目录 ⇒ 旧内核在跑 ⇒ 绝不能搬走
    Legacy,
    /// 探测不到（命令不支持 / 权限不足 / 两边都没动）⇒ **保守起见当老内核处理**
    Unknown,
}

impl DatadirProbe {
    /// 日志/命令行用的稳定短名
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::New => "new_datadir",
            Self::Legacy => "legacy_datadir",
            Self::Unknown => "unknown",
        }
    }
}

/// 门控判定（**纯函数**，宿主上可单测）：只有"确认新内核在跑"才放行。
#[must_use]
pub const fn gate_allows(probe: DatadirProbe) -> bool {
    matches!(probe, DatadirProbe::New)
}

/// 探测要用的"内核隐身读写"原语。
///
/// 抽成 trait 是为了**宿主上可单测**：测试里注入一个"假内核"，
/// 让它把文件写到新路径或旧路径，就能逐条验证门控判定（不需要真机、不需要内核）。
pub trait StealthIo {
    /// 读内核当前隐身开关（**只读，不落盘**）。
    ///
    /// # Errors
    /// 拿不到内核句柄 / 命令不被支持 / 权限不足时返回错误（调用方按"拿不准"处理）。
    fn get(&self) -> Result<bool>;
    /// 写内核隐身开关（**会写它自己那个数据目录里的 stealth 文件**）。
    ///
    /// 探测时用**同值写回**：语义上等于空操作，只是"让内核留下笔迹"。
    ///
    /// # Errors
    /// 命令不被支持 / 权限不足 / 写盘失败时返回错误。
    fn set(&self, enabled: bool) -> Result<()>;
}

/// 读一份 stealth 文件里的**合法**值（只有 1 字节的 `'0'`/`'1'` 才算数）
fn stealth_value_of(p: &Path) -> Option<bool> {
    match fs::read(p) {
        Ok(d) if d.as_slice() == b"1" => Some(true),
        Ok(d) if d.as_slice() == b"0" => Some(false),
        _ => None,
    }
}

/// 文件"笔迹"快照（存在性 + 长度 + mtime(ns) + 内容哈希）。
///
/// 为什么要 mtime：探测写的是**同值**，内容可能完全不变（比如两边都已经是 `'1'`），
/// 这时候只有 mtime 能证明"内核确实写了这个文件"。
#[derive(Debug, Clone, PartialEq, Eq)]
struct Snap {
    exists: bool,
    len: u64,
    mtime_ns: Option<i128>,
    content: Option<u64>,
}

fn fnv1a(data: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in data {
        h ^= u64::from(*b);
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h
}

fn snap(p: &Path) -> Snap {
    const ABSENT: Snap = Snap {
        exists: false,
        len: 0,
        mtime_ns: None,
        content: None,
    };
    fs::metadata(p).map_or(ABSENT, |m| Snap {
        exists: true,
        len: m.len(),
        mtime_ns: m
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_nanos() as i128),
        content: fs::read(p).ok().map(|d| fnv1a(&d)),
    })
}

/// 探测"当前跑的内核读的是哪个数据目录"（🔴A 的门控本体）。
///
/// 做法（无害、保守）：
///   1. 先读两边的 `stealth` 文件；⚠️ **两边都有效、值却不同时直接判 Unknown**（见下），
///      而且这一条排在**任何写之前** ⇒ 拿不准时一个字节都不写；
///   2. 记下新旧两个 `stealth` 文件的笔迹；
///   3. 让内核把它**自己刚报出来的当前值**写一遍（`SET_G`；**同值写回 = 语义空操作**，
///      这是 v2.17 修 🔴2 起改的，见下）；
///   4. 哪个文件的笔迹变了，就说明内核读写的是哪个目录；
///   5. **只有结论是"新内核在跑"**，才把文件里那份"权威值"补写一次（见下）。
///
/// 任何一步出错/两边都没动 ⇒ [`DatadirProbe::Unknown`]（调用方会**不迁移**）。
///
/// 🔴2（v2.17）**探测写回的值必须是内核自己的当前值**：这条命令写进的是"当前正在跑的
/// 那个内核"的数据目录，而探测阶段**还不知道跑的是哪个内核**。老写法优先拿"新目录那份
/// 有效值"（其次旧目录那份）去写 ⇒ 老内核在跑时会把**新侧的值写进老内核读的旧目录** ⇒
/// 可能把运行中老内核的隐身开关改掉（新侧是 `'0'` = 当场关掉隐身 = 暴露）✗。
/// 现在：探测只写 `io.get()`（同值写回，语义零变化），文件里那份权威值**推迟到确认
/// 新内核在跑之后**才写 —— 老内核/拿不准的结论下，**不再写第二个值**。
///
/// 🟠 **两边都有效但不能选**：新目录那份有效、旧目录那份也有效、**而两者不同**时，
/// 无论写哪个值，都会把"另一个内核"的隐身语义改掉。最危险的组合是
/// **老内核在跑**（它读 `/data/adb/ksu`）+ 新目录里恰好留着一份 `'0'`：
/// 按"新目录优先"把 `'0'` 写进旧目录 ⇒ **把运行中老内核的隐身关掉 = 暴露** ✗。
/// 所以这种"拿不准"的情况**直接判 Unknown**：不迁移、也**一个字节都不写**。
///
/// 为什么确认新内核之后写"权威值"不会把目录搞坏：写进去的值就是"权威值"——
/// 新目录那份有效就用新目录那份，否则用旧目录那份，
/// 所以这一步只会**减轻**新旧不一致，不会制造新的冲突。
#[must_use]
pub fn probe_datadir_with<IO: StealthIo>(io: &IO, legacy: &Path, new: &Path) -> DatadirProbe {
    let new_stealth = new.join(STEALTH_FILE);
    let legacy_stealth = legacy.join(STEALTH_FILE);

    let new_value = stealth_value_of(&new_stealth);
    let legacy_value = stealth_value_of(&legacy_stealth);

    // 🟠 两边都有效但不同 ⇒ 无法判断哪个才是"当前内核的真值" ⇒ 拿不准，什么都不做。
    //    ⚠️ 这一条必须排在**任何写之前**：拿不准时一个字节都不能写。
    if let (Some(nv), Some(lv)) = (new_value, legacy_value)
        && nv != lv
    {
        return DatadirProbe::Unknown;
    }

    // 🔴2（v2.17）：探测这一次写，值**只能用"内核自己刚报出来的那个"**（同值写回 = 语义空操作）。
    //
    //   旧写法 `new_value.or(legacy_value).or_else(|| io.get().ok())` 在
    //   「**新目录那份有效、旧目录还没有 stealth 文件**」时会拿**新侧的值**去写 ——
    //   而这条命令写进的是**当前正在跑的那个内核**的数据目录。如果跑着的是**老内核**
    //   （它读 `/data/adb/ksu`），这就等于**拿新侧的值去改老内核正在读的隐身开关** ✗：
    //   新侧是 `'0'` 就当场把老内核的隐身**关掉 = 暴露**。
    //
    //   现在：`io.get()`（内核自己的当前值）—— 与 `ksucalls::stealth_probe_set` 文档里
    //   「同值写回，语义上等于空操作」的约定一致；`Snap` 笔迹照样能证明"写进了哪个目录"，
    //   探测能力一点没变。文件里那份"权威值"推迟到**确认新内核在跑**之后才写（见下）。
    let Some(current) = io.get().ok() else {
        return DatadirProbe::Unknown;
    };

    let before_new = snap(&new_stealth);
    let before_legacy = snap(&legacy_stealth);

    if io.set(current).is_err() {
        return DatadirProbe::Unknown;
    }

    let after_new = snap(&new_stealth);
    let after_legacy = snap(&legacy_stealth);

    let probe = if after_new != before_new {
        DatadirProbe::New
    } else if after_legacy != before_legacy {
        DatadirProbe::Legacy
    } else {
        DatadirProbe::Unknown
    };

    // 🔴2（v2.17）：**只有确认新内核在跑**，才允许把"文件里那份权威值"补写下去
    //   （新目录那份 > 旧目录那份；两边都有效但不同已在上面判 Unknown）。
    //   典型必要性：新内核在跑、新目录还没有 `stealth` 文件、旧目录那份是 `'1'` ——
    //   探测那一次写已经把新目录写成了内核的默认值 `'0'`，此时必须把用户的 `'1'` 补回去，
    //   否则紧接着的迁移会拿这个 `'0'` 当"新侧权威值"（见 `decide_key_file`）→ 隐身被关掉。
    //   老内核 / 拿不准 ⇒ **到此为止，绝不写第二个值**：老内核的隐身开关零变化 ✓。
    //   写失败只记日志、不改结论（它是"补写"，不是判据；下次 `su` 还会再来一遍）。
    if probe == DatadirProbe::New
        && let Some(auth) = new_value.or(legacy_value)
        && auth != current
        && let Err(e) = io.set(auth)
    {
        log::warn!("探测：权威隐身值 {auth} 补写失败（{e}），本次不改判据");
    }

    probe
}

/// 门控不通过时的 report（**一个字节都不动**）
fn skipped_report(probe: DatadirProbe) -> Report {
    let mut r = Report {
        skipped_not_new_kernel: true,
        kernel_probe: Some(probe.as_str()),
        ..Report::default()
    };
    r.notes.push(match probe {
        DatadirProbe::Legacy => {
            "旧内核在跑（它的数据目录是 /data/adb/ksu）→ 搬走会让它读不到隐身标志与授权名单"
                .to_string()
        }
        DatadirProbe::New => "探测结论自相矛盾，按拿不准处理".to_string(),
        DatadirProbe::Unknown => {
            "探测拿不准（命令不被支持 / 权限不足 / 两边都没动静 / 新旧隐身值冲突）→ 按老内核处理"
                .to_string()
        }
    });
    r
}

// ══════════════════════════════════════════════════════════════════════
// 🟢（v2.9）兼容软链：/data/adb/ksu -> /data/adb/sevenk
// ══════════════════════════════════════════════════════════════════════
//
// 为什么必须有它（**一次真实回归的补救**，2026-09-21 用户实测）：
//   改名（v0.13.152 起）之后，**大量第三方模块把自己写死的 `/data/adb/ksu/...`
//   当成 KernelSU 的数据目录**（`/data/adb/ksu/bin/ksud`、`/data/adb/ksu/bin/busybox`、
//   `/data/adb/ksu/lib/…`…）。典型就是 Zygisk Next：装上没效果，它自己的界面报
//   "Module files corrupted"。它们找不到自己的文件 ⇒ 整类功能全废。
//   修法 = 用一条**软链**把旧路径接回新目录：一条软链兜住**所有**写死旧路径的第三方，
//   不需要去改任何第三方代码。
//
// 🔑 **为什么留软链不会"露馅"**（当初的顾虑是错的）：
//   `/data/adb` 整个目录**只有 root 能读**（实测设备 HA247EY4：adb shell 里
//   `ls /data/adb` = Permission denied）。检测类 App（没有 root）**连看都看不到**
//   这条软链 ⇒ 对隐身**零影响**。而真正需要旧路径的（模块脚本 / `su` 会话 /
//   Zygisk Next daemon）本来就是有 root 的那批人。
//
// 安全规则（按"旧路径上现在是什么"分六种情形，见 [`ensure_compat_link_at`]）：
//   1. 不存在            ⇒ 建软链（**v2.8 及以后老用户升级的主路径**）；
//   2. 已是指向新目录的软链 ⇒ 幂等跳过（一次 `lstat` + 一次 `stat`，零写入 ⇒ 每次 `su` 都便宜）；
//   2b. 指向新目录但**目标已被删**（悬空软链，🔴1/v2.10）⇒ 删死链 + 补回**空**的新目录
//       + 重建软链（只碰软链与空目录壳，真实数据一个字节不动）；
//   3. 真实目录但是空的    ⇒ 没数据可丢：删空壳、建软链；
//   4. 真实目录且有内容    ⇒ ⚠️ **一个字节都不删**：
//        · 只在**确认新内核在跑**（`probe_datadir_with` = `new_datadir`）时才
//          **改名留存**（`/data/adb/ksu.legacy_backup_<ts>`，内容原样）腾出路径、再建软链；
//        · 没确认新内核（旧内核 / 拿不准）⇒ **不动**（老内核正读它，动了 = 掉授权/暴露）；
//   5. 指向别处的软链 / 别的类型 ⇒ **不猜、不删、不覆盖**，只记日志。
//
// 🔴2（v2.10）**每次调用末尾还要复检一遍**：`auto_migrate_with()` 在唯一出口调
// [`reassert_compat_link`]，用一次 `lstat` 确认旧路径**仍然**是软链（防旧管理器
// 把它顶成真实目录）；健康时零写入，异常时按上面第 3/4/5 条重处理。

/// 兼容软链这一次的处理结果（给 report / 日志 / App 判断用）
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CompatLink {
    /// 早就已经是 `/data/adb/ksu -> /data/adb/sevenk`（幂等跳过）
    AlreadyLinked,
    /// 🔴1（v2.10）旧路径是**悬空软链**（软链还指着新目录，但新目录已经被删掉了 ——
    /// 典型是 `ksud uninstall` 删了 `/data/adb/sevenk`、v2.9 却把软链留在原地）
    /// ⇒ 已删掉这条死链、补回新目录空壳、再把软链建好。
    /// 只删/建**软链与空目录**本身，绝不碰任何真实数据。
    DeadLinkRepaired,
    /// 旧路径上什么都没有 → 这次新建了软链
    Created,
    /// 旧路径上是个**空**的真目录 → 换成软链（没有数据可丢）
    ReplacedEmptyDir,
    /// 旧路径上是**有内容**的真目录 → 已**改名留存**到这里的路径（内容一个字节没删），
    /// 然后建了软链
    TookOverLegacy(String),
    /// 旧路径上是**有内容**的真目录，但**没确认新内核在跑** ⇒ 不动它
    BlockedNotNewKernel,
    /// 旧路径上是**指向别处的软链 / 别的类型** ⇒ 不认识，不碰（附带原因）
    Foreign(String),
    /// 这次没建（附带原因：建不了 / 新目录不存在 / 系统调用失败…）
    Skipped(String),
}

impl CompatLink {
    /// 这一次**真的改动了磁盘**吗？（日志只在这种时候刷屏，免得每次 `su` 都写一条）
    #[must_use]
    pub const fn changed(&self) -> bool {
        matches!(
            self,
            Self::Created
                | Self::DeadLinkRepaired
                | Self::ReplacedEmptyDir
                | Self::TookOverLegacy(_)
        )
    }

    /// 人话描述（进 `summary()` / 日志）
    #[must_use]
    pub fn describe(&self) -> String {
        match self {
            Self::AlreadyLinked => "已经是软链（幂等跳过）".to_string(),
            Self::DeadLinkRepaired => {
                "旧路径是**悬空软链**（新目录被删过、链接却留在原地）→ 已删掉死链 + \
                 补回新目录空壳 + 重建软链"
                    .to_string()
            }
            Self::Created => "已新建".to_string(),
            Self::ReplacedEmptyDir => "旧路径是个空目录（没数据可丢），已换成软链".to_string(),
            Self::TookOverLegacy(p) => format!(
                "旧路径是**有内容的真实目录** → 已改名留存到 {p}（一个字节都没删），路径已换成软链"
            ),
            Self::BlockedNotNewKernel => {
                "旧路径是**有内容的真实目录**，但没确认新内核在跑 ⇒ 不动它（老内核可能正读它）"
                    .to_string()
            }
            Self::Foreign(why) => format!("旧路径不是我们的旧数据目录（{why}）⇒ 不碰"),
            Self::Skipped(why) => format!("本次没建（{why}）"),
        }
    }
}

/// 旧路径现在的形态（`ensure_compat_link_at` 的分派依据）
#[derive(Debug, Clone, PartialEq, Eq)]
enum LegacyShape {
    /// 什么都没有
    Absent,
    /// 已经是指向新目录的软链
    LinkedToNew,
    /// 软链，但指向别处（或读不出来）
    LinkedElsewhere(String),
    /// 真实目录、空的
    EmptyDir,
    /// 真实目录、有内容
    Dir,
    /// **看不了**（`stat` 报 EACCES/EPERM）：说明这个进程**不是 root**
    /// （`/data/adb` 只有 root 能进）。这种进程什么都做不了、也不该做 ⇒ 静默跳过。
    Unreadable,
    /// 别的类型（普通文件…）或别的 `stat` 错误
    Other,
}

fn legacy_shape(legacy: &Path, new: &Path) -> LegacyShape {
    let meta = match fs::symlink_metadata(legacy) {
        Ok(m) => m,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return LegacyShape::Absent,
        // ⚠️ 必须是单独一支：内核直接 `exec` 出来的 `su` 进程**不是 root**
        // （uid 还是调用方那个），它连 `stat /data/adb/ksu` 都会 EACCES。
        // 那种进程要**静默跳过**（v2.8 就是静默的），绝不能每次都刷一条
        // "旧路径不是我们的旧数据目录" 的警告 —— 那是误导，也是日志垃圾。
        // （std 把 Linux/Android 的 EACCES **和** EPERM 都映射成 `PermissionDenied`，
        //   所以这一支就够，不引 `libc` —— 那个 crate 只在 android 目标下才有。）
        Err(e) if e.kind() == std::io::ErrorKind::PermissionDenied => {
            return LegacyShape::Unreadable;
        }
        Err(_) => return LegacyShape::Other,
    };
    if meta.file_type().is_symlink() {
        return match fs::read_link(legacy) {
            // 🟡（v2.11）：比较前抹掉**尾斜杠** —— `ln -s /data/adb/sevenk/ /data/adb/ksu`
            // 是同样一条正确的软链，老写法 `t == new` 会把它判成"指向别处"（Foreign）
            // ⇒ 每次 `su` 都刷一条"不是我们的软链"的误导日志。
            Ok(t) if same_dir_target(&t, new) => LegacyShape::LinkedToNew,
            Ok(t) => LegacyShape::LinkedElsewhere(format!("软链指向 {}", t.display())),
            Err(e) => LegacyShape::LinkedElsewhere(format!("软链读不出来（{e}）")),
        };
    }
    if meta.is_dir() {
        return if dir_is_empty(legacy) {
            LegacyShape::EmptyDir
        } else {
            LegacyShape::Dir
        };
    }
    LegacyShape::Other
}

/// 🟡（v2.11）软链目标是不是"就是 `new`"（**忽略尾斜杠**差异：`/a/b` 与 `/a/b/` 等价）
fn same_dir_target(link_target: &Path, new: &Path) -> bool {
    fn norm(p: &Path) -> String {
        let s = p.to_string_lossy();
        let t = s.trim_end_matches('/');
        if t.is_empty() {
            "/".to_string()
        } else {
            t.to_string()
        }
    }
    norm(link_target) == norm(new)
}

/// 把 `legacy` 建成指向 `new` 的软链（**不碰 `new` 本身**）
fn link_new_dir(legacy: &Path, new: &Path) -> CompatLink {
    if !new.is_dir() {
        return CompatLink::Skipped(format!("新目录不是真实目录（{}）", new.display()));
    }
    match std::os::unix::fs::symlink(new, legacy) {
        Ok(()) => CompatLink::Created,
        Err(e) => CompatLink::Skipped(format!("建软链失败：{e}")),
    }
}

/// 给"有内容的老目录"找一个不撞车的留存路径：`<老目录名>.legacy_backup_<stamp>`。
///
/// ⚠️ **刻意不改名成 `.bak` 之类的隐蔽名**：这是给用户/排查的人看的，
/// 名字里带 `ksu` 前缀一眼就知道是老数据目录，带时间戳就知道是哪一次留的。
fn free_backup_path(legacy: &Path, stamp: u64) -> PathBuf {
    let base = legacy
        .file_name()
        .map_or_else(|| "ksu".to_string(), |n| n.to_string_lossy().into_owned());
    let dir = legacy
        .parent()
        .map_or_else(|| PathBuf::from("/data/adb"), Path::to_path_buf);
    let mut candidate = dir.join(format!("{base}.legacy_backup_{stamp}"));
    let mut i = 2u32;
    while path_exists(&candidate) {
        candidate = dir.join(format!("{base}.legacy_backup_{stamp}_{i}"));
        i += 1;
    }
    candidate
}

/// 🟠6（v2.11）"有内容的老目录"该怎么留存：复用一份内容相同的旧备份，还是新建一份。
#[derive(Debug, Clone, PartialEq, Eq)]
enum LegacyBackupPlan {
    /// 改成这个名字留存（新备份）
    Rename(PathBuf),
    /// 已经有一份**逐字节相同**的备份 ⇒ 不必再留第二份（内容在 `existing` 里，不算丢）
    ReuseExisting(PathBuf),
}

/// 🟠6（v2.11）决定留存方案：**内容指纹相同就复用**（真实数据在两边都在，删的是重复副本）。
///
/// 背景（真机）：旧管理器（`com.sevenkimimasy.love` v0.13.18~v0.13.151）会把
/// `/data/adb/ksu` 重新建成真实目录 ⇒ 每次 `auto_migrate` 都"改名留存"一份
/// `ksu.legacy_backup_*`。旧代码只按时间戳换名字 ⇒ 备份目录**无限增长**（全是同一坨内容）。
/// 现在：指纹相同 + 逐字节复核通过 ⇒ 复用已有那份，不再叠加。
fn plan_legacy_backup(legacy: &Path, stamp: u64) -> LegacyBackupPlan {
    if let (Some(parent), Some(base)) = (legacy.parent(), legacy.file_name())
        && let Some(fp) = tree_fingerprint(legacy)
    {
        for cand in list_legacy_backups(parent, &base.to_string_lossy()) {
            if tree_fingerprint(&cand) == Some(fp) && trees_equal(legacy, &cand) {
                return LegacyBackupPlan::ReuseExisting(cand);
            }
        }
    }
    LegacyBackupPlan::Rename(free_backup_path(legacy, stamp))
}

/// 🟠6（v2.11）列出 `<dir>` 下**真实目录**形态的 `<base>.legacy_backup_<数字>[_<数字>]`
/// （新 → 旧排序）。软链 / 普通文件 / 名字不匹配的一律**不碰**（用 `symlink_metadata` 分流）。
fn list_legacy_backups(dir: &Path, base: &str) -> Vec<PathBuf> {
    let prefix = format!("{base}.legacy_backup_");
    let mut out: Vec<(u64, u32, PathBuf)> = Vec::new();
    let Ok(entries) = fs::read_dir(dir) else {
        return Vec::new();
    };
    for ent in entries.flatten() {
        let name = ent.file_name().to_string_lossy().into_owned();
        let Some(suffix) = name.strip_prefix(&prefix) else {
            continue;
        };
        let Some(sort_key) = backup_suffix_key(suffix) else {
            continue;
        };
        let path = ent.path();
        // ⚠️ `symlink_metadata`：软链**不跟随**（别人放的软链可能指向 /，跟随就完蛋）
        let Ok(meta) = fs::symlink_metadata(&path) else {
            continue;
        };
        if meta.file_type().is_symlink() || !meta.is_dir() {
            continue;
        }
        out.push((sort_key.0, sort_key.1, path));
    }
    out.sort_by_key(|a| (a.0, a.1));
    out.into_iter().map(|(_, _, p)| p).collect()
}

/// 备份目录名后缀必须是 `<数字>` 或 `<数字>_<数字>`（严格形状，避免误删别人的目录）
fn backup_suffix_key(suffix: &str) -> Option<(u64, u32)> {
    match suffix.split_once('_') {
        None => Some((suffix.parse::<u64>().ok()?, 0)),
        Some((a, b)) => Some((a.parse::<u64>().ok()?, b.parse::<u32>().ok()?)),
    }
}

/// 🟠6（v2.11）**只保留最近 `keep` 份**改名留存的备份（删之前每一份都复核：
/// 名字严格匹配、`symlink_metadata` 确认是**真实目录**、且不是刚建成的那一份）。
/// 返回被删掉的路径（调用方记进 report，App/日志看得见）。
fn prune_legacy_backups(dir: &Path, base: &str, keep: usize) -> Vec<PathBuf> {
    let all = list_legacy_backups(dir, base);
    if all.len() <= keep {
        return Vec::new();
    }
    let mut removed = Vec::new();
    for cand in &all[..all.len() - keep] {
        // 二次确认（`remove_dir_all` 之前再 lstat 一次，防 TOCTOU / 防跟随软链）
        let Ok(meta) = fs::symlink_metadata(cand) else {
            continue;
        };
        if meta.file_type().is_symlink() || !meta.is_dir() {
            continue;
        }
        if fs::remove_dir_all(cand).is_ok() {
            removed.push(cand.clone());
        }
    }
    removed
}

/// 🟠6 内容指纹：目录树里"类型 + 相对路径 + 大小 + 内容前缀"的 FNV-1a。
///
/// 只用来**缩短候选**（真正决定复用与否的是逐字节的 [`trees_equal`]）。
/// 单文件最多读 1 MiB、整树最多读 32 MiB；超了返回 `None`（⇒ 不复用，老老实实新建一份），
/// 免得在一次 `su` 里长时间读盘。
fn tree_fingerprint(root: &Path) -> Option<u64> {
    /// 单文件最多参与指纹的字节数
    const PER_FILE: u64 = 1024 * 1024;
    /// 整棵树最多读的字节数
    const TOTAL: u64 = 32 * 1024 * 1024;

    fn walk(root: &Path, rel: &Path, desc: &mut Vec<u8>, budget: &mut u64) -> bool {
        let full = root.join(rel);
        let Ok(meta) = fs::symlink_metadata(&full) else {
            return false;
        };
        desc.extend_from_slice(rel.to_string_lossy().as_bytes());
        desc.push(0);
        if meta.is_dir() {
            desc.push(b'd');
            let Ok(rd) = fs::read_dir(&full) else {
                return false;
            };
            let mut names: Vec<std::ffi::OsString> = rd.flatten().map(|e| e.file_name()).collect();
            names.sort();
            for n in names {
                if !walk(root, &rel.join(n), desc, budget) {
                    return false;
                }
            }
        } else if meta.file_type().is_symlink() {
            desc.push(b'l');
            let Ok(t) = fs::read_link(&full) else {
                return false;
            };
            desc.extend_from_slice(t.to_string_lossy().as_bytes());
        } else if meta.is_file() {
            desc.push(b'f');
            let size = meta.len();
            desc.extend_from_slice(&size.to_le_bytes());
            let take = size.min(PER_FILE);
            if *budget < take {
                return false;
            }
            *budget -= take;
            let Ok(mut f) = fs::File::open(&full) else {
                return false;
            };
            let mut buf = vec![0u8; usize::try_from(take).unwrap_or(0)];
            if f.read_exact(&mut buf).is_err() {
                return false;
            }
            desc.extend_from_slice(&fnv1a(&buf).to_le_bytes());
        } else {
            desc.push(b'?');
        }
        true
    }

    let mut desc = Vec::new();
    let mut budget = TOTAL;
    if !walk(root, Path::new(""), &mut desc, &mut budget) {
        return None;
    }
    Some(fnv1a(&desc))
}

/// 🔴1（v2.17）软链「目标相同」的判据 —— **只有两次读取都成功、且目标相等**才算相同。
///
/// 旧写法是 `read_link(&pa).ok() == read_link(&pb).ok()`：两边**都读失败**时
/// `None == None` 会被判成"相同" ⇒ 假阳性 ⇒ `trees_equal` 返回 `true` ⇒
/// `plan_legacy_backup` 认为"已经有一份逐字节相同的备份" ⇒ 调用方走到
/// `fs::remove_dir_all(legacy)`（见本文件 `ensure_compat_link_at` 的
/// `LegacyBackupPlan::ReuseExisting` 分支）。**这是本文件唯一可能误删真实老目录的路径** ✗。
///
/// `read_link` 失败的原因很多（EACCES / ELOOP / 条目在两次调用之间消失 / IO 错误），
/// 任何一次读不出来都只能算"**不确定**"⇒ `false` ⇒ 走 `Rename`（改名留存，绝不删除）
/// 或"重新建一份备份"的老路 —— 保守方向：宁可不复用、绝不误删。
///
/// 为什么抽成独立函数：真实的 `read_link` 在"软链存在"时几乎不会失败
/// （`lstat` 成功而 `readlink` 失败的组合在本机没法自然构造出来），
/// 抽出来才能把 `(Err, Err)` / `(Ok, Err)` / `(Err, Ok)` 三种组合**逐条钉进单测**。
fn symlink_targets_equal(a: std::io::Result<PathBuf>, b: std::io::Result<PathBuf>) -> bool {
    match (a, b) {
        (Ok(x), Ok(y)) => x == y,
        _ => false,
    }
}

/// 🟠6 **逐字节**复核两棵树是否完全相同（决定"能不能复用/能不能删重复那份"）。
///
/// 保守到什么程度：任何一项读不出来 / 类型不同 / 大小不同 / 字节不同 / 单文件超过
/// 4 MiB（不做长时间 IO）⇒ `false`（⇒ 走"新建一份备份"的老路，绝不删任何东西）。
fn trees_equal(a: &Path, b: &Path) -> bool {
    /// 逐字节复核时单文件的上限
    const MAX_FILE: u64 = 4 * 1024 * 1024;

    fn walk(a: &Path, b: &Path, rel: &Path) -> bool {
        let (pa, pb) = (a.join(rel), b.join(rel));
        let (Ok(ma), Ok(mb)) = (fs::symlink_metadata(&pa), fs::symlink_metadata(&pb)) else {
            return false;
        };
        if ma.file_type().is_dir() != mb.file_type().is_dir()
            || ma.file_type().is_symlink() != mb.file_type().is_symlink()
            || ma.is_file() != mb.is_file()
        {
            return false;
        }
        if ma.file_type().is_symlink() {
            return symlink_targets_equal(fs::read_link(&pa), fs::read_link(&pb));
        }
        if ma.is_dir() {
            let (Ok(ra), Ok(rb)) = (fs::read_dir(&pa), fs::read_dir(&pb)) else {
                return false;
            };
            let mut na: Vec<std::ffi::OsString> = ra.flatten().map(|e| e.file_name()).collect();
            let mut nb: Vec<std::ffi::OsString> = rb.flatten().map(|e| e.file_name()).collect();
            na.sort();
            nb.sort();
            if na != nb {
                return false;
            }
            return na.iter().all(|n| walk(a, b, &rel.join(n)));
        }
        if ma.is_file() {
            if ma.len() != mb.len() || ma.len() > MAX_FILE {
                return false;
            }
            return files_equal(&pa, &pb).unwrap_or(false);
        }
        false
    }

    if !a.is_dir() || !b.is_dir() {
        return false;
    }
    walk(a, b, Path::new(""))
}

/// 兼容软链本体（**纯 std ⇒ 宿主上可逐条单测**）：保证 `legacy` 指向 `new`。
///
/// * `kernel_is_new` = **确认当前内核读的是新目录**（`probe_datadir_with` = `New`）。
///   只有它为真时，才敢动"有内容的真实老目录"（把它改名留存、腾出路径）。
/// * `stamp` = 留存目录名里的时间戳（调用方给 `unix_now()`；单测里传固定值 ⇒ 可断言）。
///
/// 任何一步失败都**只返回结论、不 panic、不删任何东西**：调用方只把它记进 report/日志。
///
/// 🔴1（v2.10）：`legacy` 若是**悬空软链**（指向 `new`，但 `new` 已经被删）⇒ 删掉死链、
/// 必要时补一个**空**的新目录、重建软链（见 [`CompatLink::DeadLinkRepaired`]）。
#[must_use]
pub fn ensure_compat_link_at(
    legacy: &Path,
    new: &Path,
    kernel_is_new: bool,
    stamp: u64,
) -> CompatLink {
    match legacy_shape(legacy, new) {
        LegacyShape::LinkedToNew => {
            // ── 🔴1（v2.10）**悬空软链自愈** ─────────────────────────────────
            // 旧写法在这里**无条件**返回 `AlreadyLinked` ✗：软链可能已经悬空
            // （`ksud uninstall` 把 `/data/adb/sevenk` 删了，v2.9 却把
            //  `/data/adb/ksu` 这条软链留在原地）—— 于是它**永远**被判成
            // "已经是软链（幂等跳过）"，第三方模块拿到的旧路径全是 ENOENT ✗。
            // 现在：只要目标不是**真实可用的目录**就当场修 ——
            //   删死链 → 补回空的新目录 → 重走建链流程。
            // 只碰**软链本身和一个空目录壳**，任何真实数据一个字节都不动 ✓。
            if new.is_dir() {
                return CompatLink::AlreadyLinked; // 健康路径：一次 lstat + 一次 stat，零写入
            }
            if let Err(e) = fs::remove_file(legacy) {
                return CompatLink::Skipped(format!("悬空软链删不掉（{e}）⇒ 保持原样"));
            }
            // 新目录不在的话，刚建的链同样是悬空的 ⇒ 先补一个**空目录壳**
            // （迁移流程本来也要建它，见 `auto_migrate_with`；这里**只建、不删**）。
            if !new.is_dir()
                && let Err(e) = fs::create_dir_all(new)
            {
                return CompatLink::Skipped(format!(
                    "死链已删，但新目录建不回来（{}）：{e}",
                    new.display()
                ));
            }
            match link_new_dir(legacy, new) {
                CompatLink::Created => CompatLink::DeadLinkRepaired,
                other => other,
            }
        }
        LegacyShape::Absent => link_new_dir(legacy, new),
        LegacyShape::EmptyDir => match fs::remove_dir(legacy) {
            Ok(()) => match link_new_dir(legacy, new) {
                CompatLink::Created => CompatLink::ReplacedEmptyDir,
                other => other,
            },
            Err(e) => CompatLink::Skipped(format!("空目录删不掉（{e}）⇒ 保持原样")),
        },
        LegacyShape::Dir => {
            if !kernel_is_new {
                return CompatLink::BlockedNotNewKernel;
            }
            // 🟠4（v2.11）：**改名之前先保证新目录真实存在**。
            // 老写法的顺序是"先 rename 老目录 → 再 link_new_dir"，而 `link_new_dir` 只在
            // `new` 是真实目录时才建链 ⇒ 万一 `new` 不在（被 uninstall 删了 / 被普通文件占了），
            // 结果就是"老目录已经改名腾走了、软链却没建起来"—— 旧路径直接消失 ✗。
            // 现在：建不出来就**一个字节都不动**（老目录保持原样，下次再试）。
            if !new.is_dir()
                && let Err(e) = fs::create_dir_all(new)
            {
                return CompatLink::Skipped(format!(
                    "新目录 {} 建不出来（{e}）⇒ 老目录保持原样（绝不先改名）",
                    new.display()
                ));
            }
            let base = legacy
                .file_name()
                .map_or_else(|| "ksu".to_string(), |n| n.to_string_lossy().into_owned());
            match plan_legacy_backup(legacy, stamp) {
                // 🟠6：已经有一份**逐字节相同**的存活备份 ⇒ 不再叠加第二份。
                // 删掉的只是"同一份内容的重复副本"（复用前已 `trees_equal` 复核过），
                // 任何一份不同的内容都不会走到这里。
                LegacyBackupPlan::ReuseExisting(existing) => match fs::remove_dir_all(legacy) {
                    Ok(()) => match link_new_dir(legacy, new) {
                        CompatLink::Created => {
                            CompatLink::TookOverLegacy(existing.display().to_string())
                        }
                        other => CompatLink::Skipped(format!(
                            "老目录与已有备份 {} 内容相同（已去重留存），但软链没建成：{}",
                            existing.display(),
                            other.describe()
                        )),
                    },
                    Err(e) => CompatLink::Skipped(format!(
                        "老目录与已有备份 {} 内容相同，但去重删除失败（{e}）⇒ 老目录保持原样",
                        existing.display()
                    )),
                },
                // 有内容的老目录：**只改名、绝不删除**（同一文件系统内的 rename 是原子的，
                // 内容一个字节都不会丢；失败了就什么都不动）。
                LegacyBackupPlan::Rename(backup) => match fs::rename(legacy, &backup) {
                    Ok(()) => {
                        // 🟠6：顺手把超过 3 份的旧备份清掉（严格限定名字 + 必须是真实目录）
                        let removed = prune_legacy_backups(
                            backup.parent().unwrap_or_else(|| Path::new("/data/adb")),
                            &base,
                            LEGACY_BACKUP_KEEP,
                        );
                        if !removed.is_empty() {
                            log::warn!(
                                "compat link: 旧备份超过 {LEGACY_BACKUP_KEEP} 份，已删最旧的 {} 份：{}",
                                removed.len(),
                                removed
                                    .iter()
                                    .map(|p| p.display().to_string())
                                    .collect::<Vec<_>>()
                                    .join("、")
                            );
                        }
                        match link_new_dir(legacy, new) {
                            CompatLink::Created => {
                                CompatLink::TookOverLegacy(backup.display().to_string())
                            }
                            other => CompatLink::Skipped(format!(
                                "老目录已改名留存到 {}（数据安全），但软链没建成：{}",
                                backup.display(),
                                other.describe()
                            )),
                        }
                    }
                    Err(e) => CompatLink::Skipped(format!(
                        "有内容的老目录改名失败（{e}）⇒ 保持原样（绝不删）"
                    )),
                },
            }
        }
        LegacyShape::LinkedElsewhere(why) => CompatLink::Foreign(why),
        // 看不了（非 root 调用）：什么都不做 —— 用 `Skipped` 而不是 `Foreign`，
        // 因为"你是别人的目录"和"你没权限看"是两回事，不该混在一起报。
        LegacyShape::Unreadable => {
            CompatLink::Skipped("没有权限查看旧路径（非 root 调用）".to_string())
        }
        LegacyShape::Other => CompatLink::Foreign("既不是目录也不是软链".to_string()),
    }
}

/// 🔴1（v2.10）**卸载**时删兼容软链的结果（给 `uninstall()` 打日志用，不参与迁移 report）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CompatLinkRemoval {
    /// 确认是我们的那条软链（`legacy -> new`）⇒ 删掉了
    Removed,
    /// 旧路径上本来就什么都没有（最正常的情况）
    Absent,
    /// 不是我们的软链 / 根本不是软链 ⇒ **一个字节都没碰**（附带原因）
    Kept(String),
    /// 是我们的软链，但删不掉（附带原因）
    Failed(String),
}

/// 🔴1（v2.10）**卸载专用**：只删**我们那条**兼容软链本身，绝不碰任何真实数据。
///
/// 为什么要有它：`ksud uninstall` 会把新目录 `remove_dir_all` 掉；如果不顺手删掉
/// `/data/adb/ksu` 这条软链，它就变成**指向不存在目标的死链** —— 之后第三方模块
/// （Zygisk Next 等）拿到的旧路径全是 ENOENT ✗（v2.9 的 `uninstall()` 漏了这一步）。
///
/// 保守到什么程度（纯 std ⇒ 宿主上可逐条单测）：
///   · `symlink_metadata` 确认它**是软链**，且 `readlink` **正是** `new` 才删；
///   · 真实目录 / 普通文件 / 指向**别处**的软链 / 压根不存在 ⇒ 什么都不做；
///   · 删除失败 ⇒ 只返回结论，调用方记日志（v2.10 的 `auto_migrate` 下次会自愈）。
///
/// 🔴（v2.18）删除这一步走 [`remove_our_symlink_checked`]（**TOCTOU 安全**）：
/// 旧写法"检查完直接 `remove_file(legacy)`"之间，`legacy` 这个**名字**可能被别人
/// 换成普通文件 ⇒ 删掉的是别人的真实文件。
#[must_use]
pub fn remove_compat_link_at(legacy: &Path, new: &Path) -> CompatLinkRemoval {
    match fs::symlink_metadata(legacy) {
        Ok(meta) if meta.file_type().is_symlink() => {
            if !fs::read_link(legacy).is_ok_and(|t| same_dir_target(&t, new)) {
                return CompatLinkRemoval::Kept(
                    "它是软链但指向别处（不是我们的兼容软链）".to_string(),
                );
            }
            remove_our_symlink_checked(legacy, new)
        }
        Ok(_) => CompatLinkRemoval::Kept("它不是软链（真实目录/别的类型）".to_string()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => CompatLinkRemoval::Absent,
        Err(e) => CompatLinkRemoval::Kept(format!("看不了它（{e}）")),
    }
}

/// 🔴（v2.18）**TOCTOU 安全**地删掉"我们那条软链"。
///
/// 旧写法是 `symlink_metadata`（是软链）→ `read_link`（指向 `new`）→ `remove_file(legacy)`：
/// 三步之间 `legacy` 这个**名字**可能被并发换成普通文件 —— 那一瞬间 `remove_file`
/// 删掉的就是**别人的真实文件**（换成目录则报错，算走运）。
///
/// 修法（照本仓 `utils.rs::ensure_clean_dir` 的"先分流再动手"思想）：
///   ① 先用 `rename` 把它**原子地**挪到一个只有我们知道的名字上
///      （同目录 ⇒ 同文件系统，`rename` 要么整个挪走、要么什么都不动）；
///   ② 在**独占名字**上复核"是软链 + 指向 `new`"，确认是我们的才 `unlink`；
///   ③ 复核不通过（说明刚才是并发替换）⇒ 原样挪回去，**一个字节都不删**。
/// 这样"检查"与"删除"之间再也没有别人能插进来的窗口。
fn remove_our_symlink_checked(legacy: &Path, new: &Path) -> CompatLinkRemoval {
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_nanos());
    let quarantine = legacy.with_file_name(format!(".7k-unlink-{}-{stamp:x}", std::process::id()));
    if let Err(e) = fs::rename(legacy, &quarantine) {
        // `rename` 失败是原子的：原路径一个字节都没动
        return CompatLinkRemoval::Failed(format!("挪到隔离名失败（未删任何东西）：{e}"));
    }
    let still_ours = fs::symlink_metadata(&quarantine)
        .is_ok_and(|m| m.file_type().is_symlink())
        && fs::read_link(&quarantine).is_ok_and(|t| same_dir_target(&t, new));
    if !still_ours {
        return match fs::rename(&quarantine, legacy) {
            Ok(()) => CompatLinkRemoval::Kept(
                "挪走后复核发现它不是我们的软链（并发替换）⇒ 已原样放回".to_string(),
            ),
            Err(e) => CompatLinkRemoval::Kept(format!(
                "并发替换：它不是我们的软链，且放回失败（{e}）⇒ 留在 {}",
                quarantine.display()
            )),
        };
    }
    match fs::remove_file(&quarantine) {
        Ok(()) => CompatLinkRemoval::Removed,
        Err(e) => {
            // 删不掉就放回原位，别留一个我们造出来的隐藏名
            let _ = fs::rename(&quarantine, legacy);
            CompatLinkRemoval::Failed(format!("删不掉：{e}"))
        }
    }
}

/// 兼容软链的日志（**只在"改动了磁盘"或"没能建"时刷**；
/// 幂等命中的 `AlreadyLinked` 走 `debug`，省得每次 `su` 都往 logcat 写一条）
fn log_compat_link(link: &CompatLink) {
    if link.changed() {
        log::info!("compat link {LEGACY_DIR} -> {NEW_DIR}: {}", link.describe());
    } else if matches!(link, CompatLink::AlreadyLinked) {
        log::debug!("compat link {LEGACY_DIR} -> {NEW_DIR}: {}", link.describe());
    } else {
        log::warn!("compat link {LEGACY_DIR} -> {NEW_DIR}: {}", link.describe());
    }
}

// ══════════════════════════════════════════════════════════════════════
// 设备上的入口（带门控）
// ══════════════════════════════════════════════════════════════════════

#[cfg(target_os = "android")]
struct RealStealthIo;

#[cfg(target_os = "android")]
impl StealthIo for RealStealthIo {
    fn get(&self) -> Result<bool> {
        crate::ksucalls::stealth_probe_get()
    }

    fn set(&self, enabled: bool) -> Result<()> {
        crate::ksucalls::stealth_probe_set(enabled)
    }
}

/// 🟡（v2.11）迁移段的**进程间锁**结果。
enum MigrateLockOutcome {
    /// 拿到锁了（离开作用域时自动删掉锁文件）
    Acquired(MigrateLock),
    /// 另一个进程正拿着这把锁 ⇒ 本次**跳过**迁移（下次 `su`/下次开机再来）
    HeldByOther,
    /// 锁本身用不了（建不了文件）⇒ **fail-open**，照旧迁移（锁只是加固，不是功能）
    Unavailable,
}

/// 🟡（v2.11）`.migrate.lock` 的 RAII 句柄。
struct MigrateLock {
    path: PathBuf,
}

impl MigrateLock {
    /// 用 `O_CREAT|O_EXCL` 抢锁；发现是**陈旧锁**（> [`MIGRATE_LOCK_STALE_SECS`]）就抢过来。
    ///
    /// ⚠️ 锁文件放在旧目录的**父目录**（真机 `/data/adb/`）而不是新目录里：
    /// 新目录里多一个文件会让"建了又删的空目录"清理逻辑误判（见 `auto_migrate_inner`）。
    fn try_acquire(legacy: &Path, now: u64) -> MigrateLockOutcome {
        let Some(dir) = legacy.parent() else {
            return MigrateLockOutcome::Unavailable;
        };
        if !dir.is_dir() {
            return MigrateLockOutcome::Unavailable;
        }
        let path = dir.join(MIGRATE_LOCK_FILE);
        for attempt in 0..2 {
            match fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&path)
            {
                Ok(mut f) => {
                    let _ = write!(f, "{now}");
                    return MigrateLockOutcome::Acquired(Self { path });
                }
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {
                    // 只给一次"抢陈旧锁"的机会（抢完还撞车 ⇒ 认输，不空转）
                    if attempt == 0 && migrate_lock_is_stale(&path, now) {
                        let _ = fs::remove_file(&path);
                        continue;
                    }
                    return MigrateLockOutcome::HeldByOther;
                }
                // 目录只读 / 文件系统不支持…：**不能**因为锁而挡住迁移
                Err(_) => return MigrateLockOutcome::Unavailable,
            }
        }
        MigrateLockOutcome::HeldByOther
    }
}

impl Drop for MigrateLock {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}

/// 锁文件里的时间戳太老（进程被 kill 掉了）⇒ 当作陈旧锁
fn migrate_lock_is_stale(path: &Path, now: u64) -> bool {
    if let Ok(s) = fs::read_to_string(path)
        && let Ok(at) = s.trim().parse::<u64>()
    {
        return now.saturating_sub(at) > MIGRATE_LOCK_STALE_SECS;
    }
    fs::metadata(path)
        .and_then(|m| m.modified())
        .map_or(true, |t| {
            t.elapsed()
                .map_or(true, |d| d.as_secs() > MIGRATE_LOCK_STALE_SECS)
        })
}

/// 迁移主流程（**不含门控缓存**；宿主上可整条跑，见单测）。`key = None` ⇒ 不读也不写缓存。
///
/// ⚠️ v2.10 起**别直接调它**：对外的入口是末尾带 🔴2 复检的 [`auto_migrate_with`]
/// （它才是 `auto_migrate()` / `auto_migrate_full()` / 单测真正走的那条）。
/// 这里保持 `pub` 只为让既有单测能单独驱动"不含复检"的原始流程。
///
/// 这是 [`auto_migrate`] 与 `ksud migrate` 共用的本体：
/// * 旧路径不在（或只是个空壳 / 已经是指向新目录的软链）⇒ 一个 `stat` 就返回，
///   顺带保证**兼容软链**就位（v2.9）；
/// * `key = Some(k)`（隐式调用，每次 `ksud`/`su` 都跑的路径）⇒ 先看门控缓存，
///   命中就**什么都不做**（见 [`GateStamp`]），否则照老流程探测/体检，最后把结论落盘；
/// * `key = None`（用户显式 `ksud migrate`）⇒ **永远走真流程**，绝不被缓存挡住。
///
/// # Errors
/// 同 [`migrate`]：只有建目录这类硬错误才 `Err`。
pub fn auto_migrate_inner<IO: StealthIo>(
    io: &IO,
    legacy: &Path,
    new: &Path,
    key: Option<&GateKey>,
    now: u64,
    adopt_allowlist: bool,
) -> Result<Report> {
    // ── 先按"旧路径现在是什么"分派（v2.9）────────────────────────────────
    match legacy_shape(legacy, new) {
        // 迁移早就完成过、软链也就位了（`LinkedToNew`）⇒ 每次 `su` 走到这里本来只有
        // 一次 `lstat`。🔴1（v2.10）：**不能**再无条件返回 `AlreadyLinked` —— 软链可能
        // 已经**悬空**（uninstall 删了 `/data/adb/sevenk`、v2.9 却把链接留在原地）。
        // 所以这三种"没有真实数据要搬"的形态统一交给 [`ensure_compat_link_at`]：
        // 健康时它只多花一次 `stat`（仍零写入），悬空时当场自愈（删死链 + 补空目录 + 重建）。
        //
        // 旧路径上什么都没有 / 只有一个空壳目录 ⇒ **没有数据要搬**，
        // 直接保证兼容软链就位。⚠️ 这是 **v2.8 及以后老用户升级的主路径**：
        // 他们早就迁完了，但第三方模块正等着 `/data/adb/ksu` 出现。
        // 这两种形态**没有数据可丢**，用不到门控（`kernel_is_new` 传 false 即可：
        // 空目录的分支本来就不会碰任何数据）。
        LegacyShape::LinkedToNew | LegacyShape::Absent | LegacyShape::EmptyDir => {
            let link = ensure_compat_link_at(legacy, new, false, now);
            log_compat_link(&link);
            let mut r = Report {
                legacy_absent: true,
                compat_link: Some(link),
                ..Report::default()
            };
            // 🔴1（v2.11）：旧路径上确实没有"真实的老目录"要搬，但**老名单可能还在
            // "改名留存"的 `ksu.legacy_backup_*` 里** —— v2.9/v2.10 在确认新内核后会
            // 把有内容的老目录改名留存、再在原地建兼容软链；真机上十有八九就是这个形态。
            // 只扫备份目录里的 `.allowlist`（几个小文件，只读）：
            //   · `adopt_allowlist=false`（每次 `su` 的隐式路径）⇒ **一个字节都不写**，
            //     只把"还有 N 条没被采纳"记进 report（App 首页据此提示）；
            //   · `true`（`ksud migrate --adopt-legacy-allowlist`）⇒ 并集合并 + 备份 + 校验。
            repair_allowlist(legacy, new, adopt_allowlist, &mut r)?;
            return Ok(r);
        }
        // 指向别处的软链 / 别的类型 ⇒ **不猜、不删、不覆盖**，只记日志。
        LegacyShape::LinkedElsewhere(_) | LegacyShape::Other => {
            let link = ensure_compat_link_at(legacy, new, false, now);
            log_compat_link(&link);
            let mut r = Report {
                legacy_absent: true,
                compat_link: Some(link),
                ..Report::default()
            };
            r.notes.push(format!(
                "{LEGACY_DIR} 的形态不是我们的旧数据目录 ⇒ 一个字节都没动"
            ));
            return Ok(r);
        }
        // **看不了旧路径** = 这个进程不是 root（内核 exec 出来的 `su` 就是这种情况）。
        // 什么都做不了、也不该做 ⇒ **静默**返回（debug 级，不刷 logcat、不报"不认识"）。
        LegacyShape::Unreadable => {
            log::debug!("compat link: 旧路径没有读权限（非 root 调用）⇒ 静默跳过");
            return Ok(Report {
                legacy_absent: true,
                ..Report::default()
            });
        }
        // ⚠️ 旧路径是**有内容的真实目录**：下面走原来的门控迁移流程，
        // 搬完（或确认冲突）以后再决定兼容软链能不能接管它。
        LegacyShape::Dir => {}
    }

    // 🟡（v2.11）进程间锁：`/data/adb/ksu`（旧路径）上还有真实数据时，App 的迁移、
    // 开机脚本的迁移、用户手敲的 `ksud migrate` 可能**同时**在跑；两边都在
    // "读 → 改名/复制 → 删旧的"，撞在一起会把对方的新目录当成自己的中间状态 ✗。
    // 拿到锁才继续；别人正拿着就本次跳过（**不阻塞**，下次 `su` 自然重来）。
    // ⚠️ 只在"有真实老目录要处理"这条路上加锁：这是冷路径（迁完就变软链），
    //    不会给每次 `su` 的健康路径加一次建文件/删文件。
    let _migrate_lock = match MigrateLock::try_acquire(legacy, now) {
        MigrateLockOutcome::Acquired(l) => Some(l),
        MigrateLockOutcome::HeldByOther => {
            log::info!("migrate: 另一个迁移进程正拿着 {MIGRATE_LOCK_FILE} ⇒ 本次跳过");
            return Ok(Report {
                notes: vec![format!(
                    "另一个迁移进程正在跑（{MIGRATE_LOCK_FILE} 存在）⇒ 本次跳过，不碰任何数据"
                )],
                ..Report::default()
            });
        }
        MigrateLockOutcome::Unavailable => None,
    };

    if let Some(k) = key
        && let Some(stamp) = read_stamp(new)
        && stamp.is_fresh(k, now)
    {
        // 🟢 v2.9：`Checked`（"上次只做了关键文件体检"）**不足以判断兼容软链能不能
        //    接管这个有内容的老目录**——那要先确认"新内核在跑"。这种情况**绕过缓存**
        //    走一次真流程（真流程会探测；拿到 `New`/`Legacy` 结论后落盘，之后由缓存挡住）。
        //    代价：老目录还占着路径的那些机器上**最多多探测一次**；
        //    软链一旦就位，上面的 `LinkedToNew` 早返回就接管了，不会再有第二次。
        match stamp.verdict {
            StampVerdict::New | StampVerdict::Legacy => {
                let mut r = cached_report(stamp.verdict);
                let link =
                    ensure_compat_link_at(legacy, new, stamp.verdict == StampVerdict::New, now);
                log_compat_link(&link);
                r.compat_link = Some(link);
                return Ok(r);
            }
            StampVerdict::Checked => {}
        }
    }

    // 上次留下了冲突标记 ⇒ 本次只做"关键文件体检"，**不会搬旧目录**。
    // 要全量重来用 `ksud migrate --force`（它清掉标记后再走下面的门控）。
    //
    // 🟢 v2.9：为了判断兼容软链**能不能安全接管**这个"有内容的真实老目录"，
    //    这里仍然做**一次**门控探测（老代码为了省一次闪存写刻意跳过了它）。
    //    探到的结论用**明确的** `New`/`Legacy` 落盘，所以"最多探一次"：
    //    · `New`     ⇒ 老目录只剩存量数据、内核读的是新目录 ⇒ 可以改名留存 + 建软链；
    //    · `Legacy`/`Unknown` ⇒ **按老内核处理、不接管**（拿不准就不动，安全方向）。
    if path_exists(&new.join(CONFLICT_MARKER)) {
        let mut r = migrate_with(legacy, new, adopt_allowlist)?;
        r.notes.push(
            "冲突标记已存在：本次只做关键文件体检 +（v2.9 起）一次门控探测（用于判断兼容软链能否接管老目录）"
                .to_string(),
        );
        let probe = probe_datadir_with(io, legacy, new);
        r.kernel_probe = Some(probe.as_str());
        // 结论落盘：**不用** `stamp_if_decisive` —— 它的 `verdict_of()` 在这里只会得到
        // "无信息"的 `Checked`，那样每次 `su` 都会再绕开缓存重探一遍。
        if let Some(k) = key {
            write_stamp(
                new,
                &GateStamp {
                    verdict: match probe {
                        DatadirProbe::New => StampVerdict::New,
                        // `Legacy` / `Unknown` 都按老内核处理：本开机 + 本内核不再重试。
                        // （`Unknown` 也落成 `Legacy` 是**故意**的：见上文"最多探一次"。）
                        DatadirProbe::Legacy | DatadirProbe::Unknown => StampVerdict::Legacy,
                    },
                    kernel: k.kernel,
                    osrelease: k.osrelease.clone(),
                    boot: k.boot.clone(),
                    at: now,
                },
            );
        }
        let link = ensure_compat_link_at(legacy, new, probe == DatadirProbe::New, now);
        log_compat_link(&link);
        r.compat_link = Some(link);
        return Ok(r);
    }

    // 探测要求"新目录存在"才能让（可能的）新内核把笔迹落在那里；
    // 建目录本身无害（迁移本来也要建），判明"不是新内核"且目录是空的就删回去。
    let created = !path_exists(new);
    if created {
        fs::create_dir_all(new).with_context(|| format!("建不了新目录 {}", new.display()))?;
    }

    let mut out = migrate_gated_with(io, legacy, new, adopt_allowlist)?;

    // 结论定型就落盘（下次同内核同开机直接命中）。
    // ⚠️ 写在 remove_dir 判断**之前**：落盘后目录就不是空的了 ⇒ 下面的清理不会误删缓存。
    stamp_if_decisive(new, key, &out, now);

    // 还原来时的样子：目录是我们为了探测才建的、而且探测没在里面留下任何东西 → 删掉。
    // （判明是**新内核**时内核会把 stealth 写进去，目录非空 ⇒ 留着，迁移马上要用。）
    if created && dir_is_empty(new) {
        let _ = fs::remove_dir(new);
    }

    // 🟢 v2.9 兼容软链：迁移跑完之后（无论干净搬完还是留下了冲突）都处理一次。
    //     `kernel_probe == new_datadir` = 本轮门控确认过新内核在跑 ⇒ 允许接管老目录。
    let link = ensure_compat_link_at(
        legacy,
        new,
        out.kernel_probe == Some(DatadirProbe::New.as_str()),
        now,
    );
    log_compat_link(&link);
    out.compat_link = Some(link);
    Ok(out)
}

/// 🔴2（v2.10）**第二道防线**：无论上面走了哪条捷径（含门控缓存命中、
/// `LinkedToNew` 幂等早返回），都在**唯一出口**再用一次 `lstat` 复检
/// "`/data/adb/ksu` 仍然是指向 `/data/adb/sevenk` 的软链"。
///
/// 为什么需要（真实风险，不是理论）：旧管理器（`com.sevenkimimasy.love`，
/// v0.13.18 ~ v0.13.151）里的 ksud 用的是**旧路径常量** `/data/adb/ksu/`；
/// 它的 `install()` **会先 `rm -rf /data/adb/ksu` 再建目录** ⇒ 把我们的兼容软链
/// **顶掉、换成真实目录**，迁移与兼容一次性报废（第三方模块又全找不到文件）。
/// App 侧现在只能"劝用户卸载旧包"，挡不住已经装着的旧包 ⇒ ksud 必须自己兜住。
///
/// 做法刻意**廉价**：健康（已经是软链）时只多花一次 `lstat`、**零写入零探测**，
/// 所以每次 `su` 都跑得起。发现不再是软链就交给 [`ensure_compat_link_at`] 按 v2.9
/// 的安全规则再处理一遍（有内容的老目录 → **只有确认新内核在跑**才改名留存 + 建软链；
/// 拿不准 → 一个字节都不动）。结论既进 [`Report`]（App 侧 `ksud migrate` 的"迁移体检"
/// 能看到），也刷一条日志（真机 logcat 能看到）。
fn reassert_compat_link(r: &mut Report, legacy: &Path, new: &Path, now: u64) {
    match legacy_shape(legacy, new) {
        // 健康（已经是软链）⇒ 什么都不做：这是每次 `su` 的主路径。
        // 非 root 进程看不见旧路径、也动不了 ⇒ 与 v2.8 一样静默（不刷警告）。
        LegacyShape::LinkedToNew | LegacyShape::Unreadable => return,
        _ => {}
    }
    // 只有"确认新内核在跑"才敢接管有内容的老目录；其余按安全方向处理。
    let kernel_is_new = r.kernel_probe == Some(DatadirProbe::New.as_str());
    let link = ensure_compat_link_at(legacy, new, kernel_is_new, now);
    // 本轮流程可能刚在同一个结论上处理过它 ⇒ 只有**结论真的变了**才重复记一条，
    // 免得每次 `su` 都多写一条一模一样的日志/笔记。
    if r.compat_link.as_ref() == Some(&link) {
        return;
    }
    log::warn!(
        "compat link health: {LEGACY_DIR} 不是软链（很可能被旧管理器顶掉了）⇒ 复检：{}",
        link.describe()
    );
    r.notes.push(format!(
        "🔴2 复检：{LEGACY_DIR} 不再是软链（旧管理器可能顶掉了它）⇒ 已按安全规则重新处理：{}",
        link.describe()
    ));
    r.compat_link = Some(link);
}

/// 兼容软链的**唯一出口包装**（v2.10）：先跑正常迁移流程，末尾无条件做一次
/// 🔴2 的廉价复检（见 [`reassert_compat_link`]）。
///
/// # Errors
/// 同 [`auto_migrate_inner`]。
pub fn auto_migrate_with<IO: StealthIo>(
    io: &IO,
    legacy: &Path,
    new: &Path,
    key: Option<&GateKey>,
    now: u64,
) -> Result<Report> {
    auto_migrate_with_opts(io, legacy, new, key, now, false)
}

/// 同 [`auto_migrate_with`]，但可以要求**采纳旧目录独有的 `.allowlist` 记录**
/// （🔴1，v2.11；`ksud migrate --adopt-legacy-allowlist` / App 首页「采纳并重启」走这条）。
///
/// # Errors
/// 同 [`auto_migrate_inner`]。
pub fn auto_migrate_with_opts<IO: StealthIo>(
    io: &IO,
    legacy: &Path,
    new: &Path,
    key: Option<&GateKey>,
    now: u64,
    adopt_allowlist: bool,
) -> Result<Report> {
    let mut r = auto_migrate_inner(io, legacy, new, key, now, adopt_allowlist)?;
    reassert_compat_link(&mut r, legacy, new, now);
    Ok(r)
}

/// 结论"定型"才写缓存（拿不准绝不落盘；见 [`verdict_of`]）
fn stamp_if_decisive(new: &Path, key: Option<&GateKey>, r: &Report, now: u64) {
    let (Some(k), Some(v)) = (key, verdict_of(r)) else {
        return;
    };
    write_stamp(
        new,
        &GateStamp {
            verdict: v,
            kernel: k.kernel,
            osrelease: k.osrelease.clone(),
            boot: k.boot.clone(),
            at: now,
        },
    );
}

/// 设备上的默认迁移（`/data/adb/ksu` → `/data/adb/sevenk`），**带"新内核在跑"门控**。
///
/// ⚠️ 这个函数在**每次** `ksud` 调用（含每次 `su`）开头都会跑 ⇒ 走门控缓存，
/// 命中时**不探测、不体检、不写任何文件**（2026-09-21 省电）。
///
/// # Errors
/// 只有在"连建目录都做不到"（权限/IO 硬错误）时才返回 `Err`；
/// 单条条目的复制失败会记进 [`Report::failed`]，**不会**删旧目录。
/// 门控不通过时返回 `Ok`（`skipped_not_new_kernel = true`），不搬任何东西。
#[cfg(target_os = "android")]
pub fn auto_migrate() -> Result<Report> {
    auto_migrate_with(
        &RealStealthIo,
        Path::new(LEGACY_DIR),
        Path::new(NEW_DIR),
        Some(&GateKey::current()),
        unix_now(),
    )
}

/// 显式迁移（`ksud migrate` / App 预检后那次调用）：**绕开门控缓存**，永远走真流程。
///
/// 为什么必须绕过：这是用户/App 主动要求"现在就把结论重新算一遍"，
/// 缓存是给"每次 `su` 都跑的隐式路径"省电用的，不该挡住显式命令。
///
/// # Errors
/// 同 [`auto_migrate`]。
#[cfg(target_os = "android")]
pub fn auto_migrate_full() -> Result<Report> {
    auto_migrate_with(
        &RealStealthIo,
        Path::new(LEGACY_DIR),
        Path::new(NEW_DIR),
        None,
        unix_now(),
    )
}

/// 🔴1（v2.11）：显式**采纳旧目录独有的 `.allowlist` 记录**（并集合并；同 UID 以新目录为准）。
///
/// 走的是和 `ksud migrate` 完全一样的流程（绕开缓存、门控照旧），只把
/// `adopt_allowlist` 打开 ⇒ **不会**无脑 `--force`（不清冲突标记、不重扫旧目录、
/// 不搬任何非名单数据）。写盘前留备份、写后逐字节校验。
///
/// # Errors
/// 同 [`auto_migrate`]。
#[cfg(target_os = "android")]
pub fn auto_migrate_adopt_allowlist() -> Result<Report> {
    auto_migrate_with_opts(
        &RealStealthIo,
        Path::new(LEGACY_DIR),
        Path::new(NEW_DIR),
        None,
        unix_now(),
        true,
    )
}

/// 同 [`auto_migrate`]，但忽略冲突标记、强制重试一次（同样绕开缓存）。
///
/// ⚠️ **不提供**"绕过门控硬搬"的开关：🔴A 是安全红线，
/// 这个 `--force` 只清冲突标记（清了之后全量重跑，顺带就有了 🔴B 的检测/修复）。
///
/// # Errors
/// 同 [`auto_migrate`]。
#[cfg(target_os = "android")]
pub fn auto_migrate_force() -> Result<Report> {
    clear_conflict_marker(Path::new(NEW_DIR))?;
    auto_migrate_full()
}

/// 🔴（v2.18）**清掉**冲突标记（`ksud migrate --force` 专用）：**删不掉必须报错**。
///
/// 旧写法是 `let _ = fs::remove_file(marker);` —— 错误被吞掉 ⇒ 标记还在原地 ⇒
/// 紧接着的 `auto_migrate_full()` 又会走"标记存在 ⇒ 只体检、不全量重来"那条分支，
/// 于是 `--force` **静默失效**：用户以为强制重迁了，实际什么都没重来。
/// 判据照本仓 `utils.rs::remove_file_checked`：不存在算成功，别的错误要冒出来。
///
/// 为什么抽成独立函数：`auto_migrate_force` 是 `#[cfg(target_os = "android")]`，
/// 抽出来后这条判据能在**宿主上单测**（`remove_conflict_marker_*`）。
pub fn clear_conflict_marker(new: &Path) -> Result<()> {
    let marker = new.join(CONFLICT_MARKER);
    match fs::remove_file(&marker) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(anyhow::Error::from(e)).with_context(|| {
            format!(
                "清不掉冲突标记 {}（`--force` 不会生效，请先修好这个）",
                marker.display()
            )
        }),
    }
}

/// 只做门控探测、不迁移（给 `ksud migrate --probe-only` / App 侧做"预检"用）。
///
/// ⚠️ 老实话：`ksud` 进程开头那次自动迁移（同样经过门控）不会被这个开关跳过，
/// 所以"先 probe 再 migrate"只是让 App 能明确看到结论、并且**只在新内核确认时才发第二条命令**。
///
/// # Errors
/// 建不了新目录等硬错误。
#[cfg(target_os = "android")]
pub fn probe_verdict_line() -> Result<String> {
    let legacy = Path::new(LEGACY_DIR);
    let new = Path::new(NEW_DIR);
    // 🟢 v2.9：旧路径已经是指向新目录的**软链** ⇒ 不管跑的是新内核还是老内核，
    // 它顺着软链读到的都是新目录里那份（**同一个文件**），所以直接报 `new_datadir`：
    // 既不用再探测（**省掉一次真实的闪存写**——探测会让内核同值重写一遍 stealth），
    // 也避免 App 拿到误导性的 `unknown`（真机 HA247EY4 实测过这个形态）。
    // 🔴1（v2.10）：必须**同时**确认目标是真实目录 —— 悬空软链也满足 `LinkedToNew`，
    // 但那种情况下报 `new_datadir` 是假的（新目录根本不在）。悬空的走下面真探测，
    // 顺带把新目录建回来（`auto_migrate()` 在进程开头也已经修过一次，这里是双保险）。
    if legacy_shape(legacy, new) == LegacyShape::LinkedToNew && new.is_dir() {
        return Ok("KERNEL_DATADIR=new_datadir".to_string());
    }
    if !path_exists(legacy) {
        return Ok("KERNEL_DATADIR=legacy_absent".to_string());
    }
    let created = !path_exists(new);
    if created {
        fs::create_dir_all(new).with_context(|| format!("建不了新目录 {}", new.display()))?;
    }
    let probe = probe_datadir_with(&RealStealthIo, legacy, new);
    if created && dir_is_empty(new) {
        let _ = fs::remove_dir(new);
    }
    Ok(format!("KERNEL_DATADIR={}", probe.as_str()))
}

// ══════════════════════════════════════════════════════════════════════
// 迁移主流程
// ══════════════════════════════════════════════════════════════════════

/// 带门控的迁移主入口（宿主上也能用假内核整条跑，见单测）。
///
/// # Errors
/// 同 [`migrate`]。
#[allow(dead_code)] // Android 侧只用 `migrate_gated_with`；这个薄包装留给宿主单测
pub fn migrate_gated<IO: StealthIo>(io: &IO, legacy: &Path, new: &Path) -> Result<Report> {
    migrate_gated_with(io, legacy, new, false)
}

/// 同 [`migrate_gated`]，但可以要求采纳旧目录独有的 `.allowlist` 记录（🔴1，v2.11）。
///
/// # Errors
/// 同 [`migrate`]。
pub fn migrate_gated_with<IO: StealthIo>(
    io: &IO,
    legacy: &Path,
    new: &Path,
    adopt_allowlist: bool,
) -> Result<Report> {
    if !path_exists(legacy) {
        return Ok(Report {
            legacy_absent: true,
            ..Report::default()
        });
    }
    let probe = probe_datadir_with(io, legacy, new);
    if !gate_allows(probe) {
        return Ok(skipped_report(probe));
    }
    let mut r = migrate_with(legacy, new, adopt_allowlist)?;
    r.kernel_probe = Some(probe.as_str());
    Ok(r)
}

/// 主入口（**不含门控**）：把 `legacy` 的内容搬到 `new`（`new` 不存在时优先原子 rename）。
///
/// # Errors
/// 只有在"连建目录都做不到"（权限/IO 硬错误）时才返回 `Err`；
/// 单条条目的复制失败会记进 [`Report::failed`]，**不会**删旧目录。
#[allow(dead_code)] // Android 侧只用 `migrate_with`；这个薄包装留给宿主单测
pub fn migrate(legacy: &Path, new: &Path) -> Result<Report> {
    migrate_with(legacy, new, false)
}

/// 同 [`migrate`]，但 `adopt_allowlist = true` 时**真的**采纳旧目录独有的 `.allowlist` 记录
/// （🔴1，v2.11；`adopt_allowlist = false` 时只把"还有 N 条没被采纳"记进 report）。
///
/// # Errors
/// 同 [`migrate`]。
pub fn migrate_with(legacy: &Path, new: &Path, adopt_allowlist: bool) -> Result<Report> {
    let mut r = Report::default();

    if !path_exists(legacy) {
        r.legacy_absent = true;
        return Ok(r);
    }

    // 🟢 v2.9 安全带：老路径上如果是**软链**（兼容软链已经就位），那它后面没有
    // "真实的老目录"可搬 —— 绝不能顺着软链去 `read_dir`/`remove_dir_all` 目标目录。
    if fs::symlink_metadata(legacy).is_ok_and(|m| m.file_type().is_symlink()) {
        r.legacy_absent = true;
        r.notes
            .push("旧路径是软链（兼容软链已就位）⇒ 没有真实旧目录要迁移".to_string());
        // 🔴1（v2.11）：但老名单可能还在改名留存的 `ksu.legacy_backup_*` 里 ⇒ 仍体检一次。
        repair_allowlist(legacy, new, adopt_allowlist, &mut r)?;
        return Ok(r);
    }

    let marker = new.join(CONFLICT_MARKER);
    if path_exists(&marker) {
        // 🔴B：不再全量重扫，但**关键文件必须再体检一次** ——
        // 否则"上次留下了空的 .allowlist"这种状态自动流程永远修不回。
        r.notes.push(
            "上次迁移有冲突/修过关键文件，本次只做「关键文件体检」（要全量重来用 `ksud migrate --force`）"
                .to_string(),
        );
        repair_key_files(legacy, new, adopt_allowlist, &mut r)?;
        if !r.repaired.is_empty() || !r.conflicts.is_empty() || !r.failed.is_empty() {
            write_conflict_marker(new, &mut r);
        }
        return Ok(r);
    }

    if !path_exists(new) {
        if let Some(parent) = new.parent() {
            fs::create_dir_all(parent)
                .with_context(|| format!("建不了父目录 {}", parent.display()))?;
        }
        match fs::rename(legacy, new) {
            Ok(()) => {
                r.renamed = true;
                write_done_marker(new, &mut r);
                return Ok(r);
            }
            Err(e) => r.notes.push(format!(
                "原子改名没成功（{e}），改用「复制 → 校验 → 删旧的」"
            )),
        }
    }

    fs::create_dir_all(new).with_context(|| format!("建不了新目录 {}", new.display()))?;

    let mut walk = WalkState::default();
    copy_tree(legacy, new, Path::new(""), &mut r, &mut walk)?;

    if r.is_clean() {
        // 🟡（v2.11）：`NotFound` **视为成功** —— 并发（另一个 ksud 刚搬完 / 用户手删了它）
        // 不该让整个迁移报错、更不该在日志里刷一条红。只有真删不动（EACCES/EIO…）才 `Err`。
        match fs::remove_dir_all(legacy) {
            Ok(()) => r.deleted_legacy = true,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                r.deleted_legacy = true;
                r.notes
                    .push("旧目录已经不在了（并发删除？）⇒ 视为删除成功".to_string());
            }
            Err(e) => {
                return Err(anyhow::Error::from(e))
                    .with_context(|| format!("删不掉旧目录 {}", legacy.display()));
            }
        }
        write_done_marker(new, &mut r);
    } else {
        // 有冲突/失败/修过关键文件 ⇒ 一条都不删；写标记避免每次调用都重扫
        write_conflict_marker(new, &mut r);
    }

    Ok(r)
}

fn write_done_marker(new: &Path, r: &mut Report) {
    let marker = new.join(DONE_MARKER);
    let body = format!(
        "已在 {LEGACY_DIR} -> {NEW_DIR} 上完成改名迁移；旧目录已安全删除。\n\
         本文件只是排查用标记，删掉也不影响功能。\n"
    );
    match fs::write(&marker, body) {
        Ok(()) => r
            .notes
            .push(format!("迁移完成标记已写到 {}", marker.display())),
        Err(e) => r
            .notes
            .push(format!("迁移完成标记写不进去（{e}）：功能不受影响")),
    }
}

fn write_conflict_marker(new: &Path, r: &mut Report) {
    let marker = new.join(CONFLICT_MARKER);
    let mut why = String::new();
    for c in &r.conflicts {
        why.push_str("冲突: ");
        why.push_str(c);
        why.push('\n');
    }
    for f in &r.failed {
        why.push_str("失败: ");
        why.push_str(f);
        why.push('\n');
    }
    for x in &r.repaired {
        why.push_str("已修复（用旧目录的完整版本覆盖新目录里空/截断的那份）: ");
        why.push_str(x);
        why.push('\n');
    }
    for x in &r.ignored_broken_legacy {
        why.push_str("忽略（旧目录那份空/截断）: ");
        why.push_str(x);
        why.push('\n');
    }
    // 追加而不是覆盖：这个标记是给人排查用的，历史不该被冲掉。
    //
    // ⚠️ 2026-09-21（省电）：**同样的内容只写一次**。
    //    以前是无条件 append —— 而 `auto_migrate()` 每次 `su` 都会跑；
    //    只要存在**修不好的**冲突（例如新/旧 `stealth` 值不同，两边都"完整"），
    //    `repair_key_files()` 每次都会把同一条冲突再报一遍 ⇒ 标记文件每次 `su`
    //    涨 ~150 字节，而且是"读全文 + 写全文"。
    //    实测 HA247EY4：一夜之间涨到 24 KB+（100+ 段完全重复的内容），纯浪费闪存寿命。
    //    现在：逐行比对，只追加**这次新出现的**行；没有新行就一个字节都不写。
    if why.trim().is_empty() {
        return;
    }
    let old = fs::read_to_string(&marker).unwrap_or_default();
    // 🟠7（v2.11）**规范化后再比对**：冲突行里带着"新 N 字节 / 旧 M 字节"这类数量，
    // 只要有一侧的文件大小变了（哪怕内容是同一件冲突），旧写法就会当成"新行"再追加一遍
    // ⇒ 假新行、标记文件无意义地涨。规范化 = 把**带单位的数量**抹成 `#`
    // （`2360 字节` 与 `8 字节` ⇒ 同一行），只抹数量、不动路径/UID/说明文字。
    let old_keys: HashSet<String> = old.lines().map(normalize_marker_line).collect();
    let fresh = why
        .lines()
        .filter(|l| {
            let t = l.trim();
            !t.is_empty() && !old_keys.contains(&normalize_marker_line(t))
        })
        .fold(String::new(), |mut acc, l| {
            acc.push_str(l);
            acc.push('\n');
            acc
        });
    if fresh.is_empty() {
        r.notes.push(format!(
            "同样的冲突/失败/修复上次已经记过 ⇒ 冲突标记**原样不动**（省一次闪存写）{}",
            marker.display()
        ));
    } else {
        let body = if old.trim().is_empty() {
            fresh
        } else {
            format!("{old}\n--- 再次体检（本次）---\n{fresh}")
        };
        // 🟠7（v2.11）**上限**：超过 `MARKER_MAX_BYTES` 就从**前面**裁掉旧记录，
        // 只保留最近的一段（标记是排查日志，不是账本；不设上限就等于给闪存挖无底洞）。
        let body = cap_marker_body(&body);
        // 🔴（v2.18）写标记**失败不能撒谎**。旧写法 `let _ = fs::write(...)` 之后无条件
        // push"冲突标记已更新" —— 磁盘满 / SELinux 拒绝时，报告与现实正好相反，
        // 用户失去唯一的排查线索（还以为下次 `--force` 能生效）。现在：成功才说"已更新"，
        // 失败如实进 `failed`（旧目录仍然保留，绝不因为写不进标记就去删数据）。
        match fs::write(&marker, body) {
            Ok(()) => r.notes.push(format!(
                "有冲突/失败/修复 ⇒ 旧目录**保留**，冲突标记已更新 {}",
                marker.display()
            )),
            Err(e) => r.failed.push(format!(
                "冲突标记写不进去（{e}）：{}（旧目录仍保留；下次会自动重试这条体检）",
                marker.display()
            )),
        }
    }
}

/// 🟠7（v2.11）把冲突行里的**带单位数量**抹平（`123 字节` / `5 条` / `2 项` ⇒ `# 字节`…），
/// 只用于"这一行上次记过没有"的比对，写进文件的还是原文。
/// 不带单位的数字（例如路径里的目录名）**原样保留** —— 不能把"uid 10001"和"uid 10002"当成同一行。
fn normalize_marker_line(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let mut i = 0;
    while i < line.len() {
        let b = line.as_bytes()[i];
        if b.is_ascii_digit() {
            let start = i;
            while i < line.len() && line.as_bytes()[i].is_ascii_digit() {
                i += 1;
            }
            let rest = &line[i..];
            if rest.starts_with(" 字节") || rest.starts_with(" 条") || rest.starts_with(" 项") {
                out.push('#');
            } else {
                out.push_str(&line[start..i]);
            }
        } else {
            // 只有 ASCII 分支会按字节推进；这里必须按**字符**推进，否则会切断 UTF-8
            let ch = line[i..].chars().next().unwrap_or('\u{fffd}');
            out.push(ch);
            i += ch.len_utf8();
        }
    }
    out
}

/// 🟠7（v2.11）冲突标记的**体积上限**：超了就从前面裁，保留最近的完整行。
fn cap_marker_body(body: &str) -> String {
    if body.len() <= MARKER_MAX_BYTES {
        return body.to_string();
    }
    let mut start = body.len() - MARKER_MAX_BYTES;
    while start < body.len() && !body.is_char_boundary(start) {
        start += 1;
    }
    if let Some(pos) = body[start..].find('\n') {
        start += pos + 1;
    }
    format!(
        "（…较早的体检记录已按 {} KB 上限裁剪，只保留最近的部分…）\n{}",
        MARKER_MAX_BYTES / 1024,
        &body[start..]
    )
}

/// 只体检关键文件（冲突标记已存在时走这里）：**空/截断的不算数**，
/// 能用旧目录那份修好就修好（🔴B 的"自动修回"）。
///
/// 🟠2 **新侧缺失**也要能补回：标记存在时**全量迁移不会跑**（见 [`migrate`]），
/// 所以"新目录里根本没这个文件"（例如上次复制时 ENOSPC/EIO 丢掉 `.allowlist`）
/// 在这里是**最后一次自动补回的机会** —— 内核读不到名单 = 全员掉授权 ✗。
/// 规则：
///   · 新侧缺失 + 旧侧存在且**有效** ⇒ 从旧侧复制过去（**只有这一种情况才写**，复制后校验）；
///   · 新侧缺失 + 旧侧空/截断   ⇒ 不敢补，记冲突（危险状态，值得人工看）；
///   · 两边都缺失              ⇒ 记进 report 的 notes（不崩，也不撑大冲突标记）；
///   · 新侧存在 + 旧侧缺失      ⇒ **不动作**（新侧那份就是当前状态）；
///   · 两边都在                ⇒ 老规矩：内容不同才做"空/截断"体检。
///
/// 🔴1（v2.11）：`.allowlist` 走**另一条**路（`handle_legacy_allowlist`）——
/// 它不是"空/截断二选一"，而是**按记录并集合并**（同 UID 以新目录为准、旧侧独有的并进来）；
/// `adopt_allowlist = false` 时只**报数**（App 首页据此提示"旧目录还有 N 条授权未被采用"），
/// `true` 时才真的写盘（`ksud migrate --adopt-legacy-allowlist` / App 的「采纳并重启」）。
/// 为什么默认不自动写：`.allowlist` 是命根子，而且 `auto_migrate()` 每次 `su` 都跑 ——
/// 默认只报数、由用户显式采纳，能避免"用户刚撤销完授权、下一次 su 又把它加回来"的惊吓。
fn repair_key_files(
    legacy: &Path,
    new: &Path,
    adopt_allowlist: bool,
    r: &mut Report,
) -> Result<()> {
    // ① `.allowlist`：并集合并（含"改名留存的 `ksu.legacy_backup_*`"里的老名单）。
    //    新侧缺失时的"补回"也在这里做（旧来源里找一份有效的整体复制过去）。
    repair_allowlist(legacy, new, adopt_allowlist, r)?;

    // ② `stealth` / `stealth_code`：维持原有判定（空/截断才算数，不做内容合并）。
    for name in [STEALTH_FILE, STEALTH_CODE_FILE] {
        let src = legacy.join(name);
        let dst = new.join(name);
        let Some(kind) = key_file_kind(Path::new(name)) else {
            continue;
        };
        let rel = Path::new(name);
        let src_exists = path_exists(&src);
        let dst_exists = path_exists(&dst);

        if !dst_exists {
            if !src_exists {
                // 两边都缺 ⇒ 谁都补不了：记进 report（notes，不崩）。
                // 这里**故意不用 `conflicts`**：`notes` 不会触发重写冲突标记，
                // 而 `auto_migrate()` 每次 `su` 都会跑一遍，用 conflicts 会让标记文件无限增长。
                r.notes.push(format!(
                    "{}: 新旧目录都没有这份关键文件，无法自动补回（保持现状）",
                    show(rel)
                ));
                continue;
            }
            // 旧侧存在：只有"有效"才敢往新侧写（空/截断的不算数）
            let ins = inspect_key_file(kind, &src);
            if ins.quality == KeyQuality::Broken {
                r.conflicts.push(format!(
                    "{}: 新目录缺失，但旧目录那份空/截断（{} 字节），无法自动补回",
                    show(rel),
                    ins.len
                ));
                continue;
            }
            // 🔴（v2.18）TOCTOU：`path_exists(&src)` 判定之后、真正 `lstat` 之前，旧目录那份
            // 可能被并发删掉。走 `lstat_or_gone`：`NotFound` = "它已经不在了" ⇒ 记 note 跳过；
            // 只有真读不动（EACCES/EIO）才冒错。
            let meta = match lstat_or_gone(&src) {
                Ok(Some(m)) => m,
                Ok(None) => {
                    r.notes.push(format!(
                        "{}: 检查期间已消失（并发删除？）⇒ 跳过补回",
                        show(rel)
                    ));
                    continue;
                }
                Err(e) => {
                    return Err(anyhow::Error::from(e))
                        .with_context(|| format!("读不了 {}", src.display()));
                }
            };
            match copy_verify(&src, &dst, &meta, r) {
                Ok(()) => {
                    r.repaired.push(show(rel));
                    log::warn!(
                        "migrate: 关键文件 {} 在新目录缺失，已从旧目录补回（{} 字节）",
                        show(rel),
                        ins.len
                    );
                }
                Err(e) => r.failed.push(format!("{}: 补回失败 {e}", show(rel))),
            }
            continue;
        }

        // 新侧存在、旧侧缺失 ⇒ 不动作（新侧那份就是当前状态）
        if !src_exists {
            continue;
        }

        match files_equal(&src, &dst) {
            Ok(true) => continue,
            Ok(false) => {}
            Err(e) => {
                r.failed.push(format!("{name}: 比对失败 {e}"));
                continue;
            }
        }
        // 🔴（v2.18）同上的 TOCTOU 防护：比对之后源可能已经消失，`?` 会变成每次 `su`
        // 的硬错误。`NotFound` ⇒ 没什么可体检的，记 note 跳过。
        let meta = match lstat_or_gone(&src) {
            Ok(Some(m)) => m,
            Ok(None) => {
                r.notes.push(format!(
                    "{}: 体检期间源文件已消失（并发删除？）⇒ 跳过",
                    show(rel)
                ));
                continue;
            }
            Err(e) => {
                return Err(anyhow::Error::from(e))
                    .with_context(|| format!("读不了 {}", src.display()));
            }
        };
        // 体检模式：只修"空/截断"，不做"明显偏小"的激进判定
        resolve_key_file(kind, &src, &dst, &meta, rel, false, r);
    }
    Ok(())
}

/// 🔴1（v2.11）：`.allowlist` 的体检/采纳。
///
/// 与 `stealth`/`stealth_code` 分开的原因：它要同时看**多个旧来源**
/// （真·旧目录 + `ksu.legacy_backup_*`），而且判定规则是"并集合并"而不是"空/截断二选一"。
fn repair_allowlist(
    legacy: &Path,
    new: &Path,
    adopt_allowlist: bool,
    r: &mut Report,
) -> Result<()> {
    let rel = Path::new(ALLOWLIST_FILE);
    let dst = new.join(ALLOWLIST_FILE);
    let sources = allowlist_legacy_sources(legacy);

    // 新侧缺失：从**最新鲜的、结构有效的那份**旧来源整体补回（复制后逐字节校验）。
    // 这一步不看 `adopt_allowlist`：文件名都没有 = 内核读不到名单 = 全员掉授权，
    // 属于"空/截断"级别的抢救，方向与老代码一致（老代码在新侧缺失时也是直接补）。
    if !path_exists(&dst) {
        if sources.is_empty() {
            r.notes.push(format!(
                "{}: 新旧目录都没有这份关键文件，无法自动补回（保持现状）",
                show(rel)
            ));
            return Ok(());
        }
        let Some(src) = sources
            .iter()
            .find(|p| parse_allowlist(&fs::read(p).unwrap_or_default()).is_some())
        else {
            r.conflicts.push(format!(
                "{}: 新目录缺失，但旧目录（含改名留存的备份）那份都空/截断，无法自动补回",
                show(rel)
            ));
            return Ok(());
        };
        // 🔴（v2.18）TOCTOU：找到的"最新鲜的那份旧来源"也可能在两次调用之间消失。
        // `NotFound` ⇒ 没什么可补的（新目录维持缺失，下次 `su` 还有机会），
        // 绝不把它升级成"每次 `su` 都报错"。
        let meta = match lstat_or_gone(src) {
            Ok(Some(m)) => m,
            Ok(None) => {
                r.notes.push(format!(
                    "{}: 选中的旧来源 {} 在检查期间已消失 ⇒ 本次不补回",
                    show(rel),
                    src.display()
                ));
                return Ok(());
            }
            Err(e) => {
                return Err(anyhow::Error::from(e))
                    .with_context(|| format!("读不了 {}", src.display()));
            }
        };
        return match copy_verify(src, &dst, &meta, r) {
            Ok(()) => {
                let n = parse_allowlist(&fs::read(&dst)?).map_or(0, |v| v.records.len());
                r.repaired.push(show(rel));
                if n > 0 {
                    r.allowlist_adopted = n;
                }
                log::warn!(
                    "migrate: 关键文件 {} 在新目录缺失，已从 {} 补回（{n} 条）",
                    show(rel),
                    src.display()
                );
                Ok(())
            }
            Err(e) => {
                r.failed.push(format!("{}: 补回失败 {e}", show(rel)));
                Ok(())
            }
        };
    }

    // 新侧存在：并集合并（`adopt` 决定"只报数"还是"真的写盘"）。
    //
    // ⚠️ 拿不准时（新侧空/截断/版本不符）**必须退回**老的"空/截断"判定 ——
    // 否则体检模式会退步：v0.13 时代"新目录里是截断名单 ⇒ 用旧目录那份修回来"
    // 这条自动修复就没了 ✗（全量迁移那条路由 `copy_tree` 做同样的兜底）。
    // 只对"真实的老目录"做这个兜底：旧路径是兼容软链时它读到的就是新目录那份
    // （自己跟自己比会凭空报一条冲突，每次 `su` 都刷）。
    let legacy_is_real_dir =
        fs::symlink_metadata(legacy).is_ok_and(|m| m.is_dir() && !m.file_type().is_symlink());
    let legacy_file = legacy.join(ALLOWLIST_FILE);
    let outcome = handle_legacy_allowlist(new, legacy, adopt_allowlist, r);
    if outcome == AllowlistOutcome::NotApplicable && legacy_is_real_dir && path_exists(&legacy_file)
    {
        // 🟡（v2.19）与 v2.18 修的另外三处统一：`path_exists` 与 `symlink_metadata` 之间
        // 文件可能被并发删掉（另一次迁移 / 用户手动清 / prune）。那是**良性**竞态，
        // 不能升级成硬 `Err` —— 否则每次 `su` 都刷一条错误、`ksud migrate` 直接失败退出。
        if let Some(meta) = lstat_or_gone(&legacy_file)? {
            resolve_key_file(KeyKind::Allowlist, &legacy_file, &dst, &meta, rel, false, r);
        }
    }
    Ok(())
}

// ══════════════════════════════════════════════════════════════════════
// 🔴B 关键文件的"内容优劣"判定
// ══════════════════════════════════════════════════════════════════════

/// 关键文件的种类（决定"什么算完整"）
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum KeyKind {
    /// `.allowlist`：二进制（magic + version + N×记录）
    Allowlist,
    /// `stealth`：一个字节的 `'0'`/`'1'`
    Stealth,
    /// `stealth_code`：纯数字，1..=12 位
    StealthCode,
}

/// 一份关键文件的"体检"结论
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum KeyQuality {
    /// 空 / 截断 / 结构不合法 ⇒ **不算数**
    Broken,
    /// 结构完整但没有内容（目前只有 `.allowlist` 的"0 条记录"会走到这里）
    Emptyish,
    /// 结构完整且有内容
    Complete,
}

/// 体检明细
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct KeyInspect {
    quality: KeyQuality,
    len: u64,
}

/// 关键文件冲突的处置方式
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum KeyChoice {
    /// 旧目录那份完整、新目录那份空/截断/明显偏小 ⇒ **用旧的覆盖新的**
    TakeLegacy,
    /// 新目录那份完整、旧目录那份空/截断 ⇒ **以新的为准**（旧的不算数）
    TakeNew,
    /// 两边都完整但不同 ⇒ 保留新目录那份，**不删旧目录**、写冲突标记
    ConflictKeepNew,
    /// 两边都不完整 ⇒ 谁都修不了：保留新的 + 保留旧目录 + 写冲突标记
    ConflictUnfixable,
}

/// 关键文件的判定规则（**纯函数**，单测逐条覆盖）：
///
/// | 旧目录那份 | 新目录那份 | 结论 |
/// |---|---|---|
/// | 完整 | 空/截断 | 用旧的覆盖新的（[`KeyChoice::TakeLegacy`]）|
/// | 空/截断 | 完整 | 以新的为准（[`KeyChoice::TakeNew`]）|
/// | 完整 | 完整但不同 | 保留新的 + 不删旧目录（[`KeyChoice::ConflictKeepNew`]）|
/// | 空/截断 | 空/截断 | 修不了：都留着（[`KeyChoice::ConflictUnfixable`]）|
///
/// `aggressive = true`（首次全量迁移）时额外把"结构完整但 0 条记录的 `.allowlist`"
/// 当成"明显偏小"⇒ 以旧目录那份（有记录）为准 —— 审计明确点名的"新目录里是空名单 ⇒
/// 全员掉授权"就是这个形状。`aggressive = false`（冲突标记已存在时的体检）**不做**
/// 这条激进判定：那时用户可能真的在"撤销所有人"，反复套用会把人已经撤掉的授权又加回来。
fn decide_key_file(legacy: KeyInspect, new: KeyInspect, aggressive: bool) -> KeyChoice {
    let legacy_ok = legacy.quality != KeyQuality::Broken;
    let new_ok = new.quality != KeyQuality::Broken;
    match (legacy_ok, new_ok) {
        (true, false) => KeyChoice::TakeLegacy,
        (false, true) => KeyChoice::TakeNew,
        (false, false) => KeyChoice::ConflictUnfixable,
        (true, true) => {
            let obviously_smaller =
                new.quality == KeyQuality::Emptyish && legacy.quality == KeyQuality::Complete;
            if aggressive && obviously_smaller {
                KeyChoice::TakeLegacy
            } else {
                KeyChoice::ConflictKeepNew
            }
        }
    }
}

fn key_file_kind(rel: &Path) -> Option<KeyKind> {
    if rel.components().count() != 1 {
        return None;
    }
    let name = rel.to_str()?;
    if name == ALLOWLIST_FILE {
        Some(KeyKind::Allowlist)
    } else if name == STEALTH_FILE {
        Some(KeyKind::Stealth)
    } else if name == STEALTH_CODE_FILE {
        Some(KeyKind::StealthCode)
    } else {
        None
    }
}

fn inspect_key_file(kind: KeyKind, path: &Path) -> KeyInspect {
    let zero = KeyInspect {
        quality: KeyQuality::Broken,
        len: 0,
    };
    let Ok(data) = fs::read(path) else {
        return zero;
    };
    let len = data.len() as u64;
    let quality = match kind {
        KeyKind::Allowlist => allowlist_quality(&data),
        KeyKind::Stealth => {
            if data.as_slice() == b"0" || data.as_slice() == b"1" {
                KeyQuality::Complete
            } else {
                KeyQuality::Broken
            }
        }
        KeyKind::StealthCode => {
            // App 侧 `StealthCodeStore.sanitize()`：取前 12 位数字；空 = 读不到
            if !data.is_empty() && data.len() <= 12 && data.iter().all(u8::is_ascii_digit) {
                KeyQuality::Complete
            } else {
                KeyQuality::Broken
            }
        }
    };
    KeyInspect { quality, len }
}

/// `.allowlist` 的结构体检（**只看长度/头部，不解释记录内容**）：
///   · 不足 8 字节 / magic 不对 / version 不在 2..=4 / 记录数不是整数倍 ⇒ 截断
///   · 结构完整但 0 条记录 ⇒ "空名单"（`Emptyish`）
fn allowlist_quality(data: &[u8]) -> KeyQuality {
    if data.len() < 8 {
        return KeyQuality::Broken;
    }
    let magic = u32::from_le_bytes([data[0], data[1], data[2], data[3]]);
    let version = u32::from_le_bytes([data[4], data[5], data[6], data[7]]);
    if magic != ALLOWLIST_MAGIC
        || !(ALLOWLIST_MIN_VERSION..=ALLOWLIST_MAX_VERSION).contains(&version)
    {
        return KeyQuality::Broken;
    }
    let record = if version < ALLOWLIST_MAX_VERSION {
        ALLOWLIST_RECORD_PRE_V4
    } else {
        ALLOWLIST_RECORD_V4
    };
    let body = data.len() - 8;
    if record == 0 || !body.is_multiple_of(record) {
        return KeyQuality::Broken;
    }
    if body == 0 {
        KeyQuality::Emptyish
    } else {
        KeyQuality::Complete
    }
}

// ══════════════════════════════════════════════════════════════════════
// 🔴1（v2.11）`.allowlist` 的"按记录并集合并"
// ══════════════════════════════════════════════════════════════════════
//
// 审计抓到的真实缺陷（真机 HA247EY4 形状：新目录 8 字节 = 0 条 / 旧目录 2360 字节 = 3 条）：
//   · `allowlist_quality()` 把"magic+version、0 条记录"判成 `Emptyish`（**不是** Broken）；
//   · `decide_key_file(旧=Complete, 新=Emptyish, aggressive=false)` ⇒ `ConflictKeepNew`
//     ⇒ **只记冲突、永不修复**；而 `aggressive=false` 正是"冲突标记已存在"的体检模式，
//     App 又只跑 `migrate`（从不 `--force`）⇒ 名单永远修不回来 ✗；
//   · 内核读到合法 8 字节头 ⇒ 3 条一条都不加载；之后任何一次 persist（`O_TRUNC`）
//     把内存里那份（空的）再写一遍 ⇒ 旧目录那份成了唯一副本，重启后也丢了 ✗。
//
// 修法 = **并集合并**（不是"二选一"）：
//   · 解析两侧记录 → 以 `curr_uid`（默认 profile 用 `key`）为键；
//   · **新侧覆盖同键**（同键 = 同一份 profile，新目录那份就是当前状态，撤销语义不丢：
//     内核"撤销授权"是把记录改成 `allow_su=false` 而不是删掉，所以被撤销的 UID
//     在新侧**仍然有一条记录**，并集合并时它会覆盖旧侧那条 `allow_su=true` 的 ✓）；
//   · **旧侧独有的记录并入**（这正是"别掉授权"的方向）；
//   · 写前留备份、写后逐字节校验（复用既有 `copy_verify`），任何一步失败都**不动新文件**。
//
// 为什么这样不会误覆盖/丢数据：
//   · 只有"两侧结构都合法、且记录长度一致（version 同为 <4 或同为 4）"才做记录级合并；
//     其它情况（空/截断/版本不同）**原样退回**老的 `decide_key_file` 判定，行为不变；
//   · 记录内容**原样搬运**（只按 `curr_uid`/`key` 判重），从不重新生成 profile；
//   · 合并结果与"新目录那份"完全相同时**一个字节都不写**；
//   · 真正写盘前先把当前新文件原样备份成 `.allowlist.pre-merge-<时间戳>`。

/// 解析出来的一份 `.allowlist`（只取判重需要的字段，记录字节原样保留）
#[derive(Debug, Clone)]
struct AllowlistView {
    /// 每条记录的字节数（version 4 = 784，更老 = 776）
    record_size: usize,
    /// 文件头 8 字节（magic + version），原样保留
    header: [u8; 8],
    /// 记录（保持文件里的顺序）：(判重键, 原始字节)
    records: Vec<(String, Vec<u8>)>,
}

/// 记录里的 `key`（C 字符串，`\0` 结尾；坏字节用 lossy 展示，只用于判重/日志）
fn allowlist_record_key_str(rec: &[u8]) -> String {
    let raw = &rec[ALLOWLIST_KEY_RANGE];
    let end = raw.iter().position(|b| *b == 0).unwrap_or(raw.len());
    String::from_utf8_lossy(&raw[..end]).into_owned()
}

/// 一条记录的判重键。
///
/// 内核按 `curr_uid` 做 hash 查找（`ksu_get_app_profile` / `ksu_set_app_profile` 都是
/// `hash_for_each_possible(..., profile->curr_uid)`），**同 uid 的不同 key 是同一份 profile**
/// ⇒ 普通记录必须以 `curr_uid` 为键（用 `(uid,key)` 会留下两条同 uid 记录，
/// 内核加载后 `hash_add` 两条、查到的可能是旧那条 ✗）。
/// 只有 9999（默认 profile）例外：它的身份就是 `key`。
fn allowlist_record_key(rec: &[u8]) -> String {
    let uid = i32::from_le_bytes([
        rec[ALLOWLIST_UID_OFFSET],
        rec[ALLOWLIST_UID_OFFSET + 1],
        rec[ALLOWLIST_UID_OFFSET + 2],
        rec[ALLOWLIST_UID_OFFSET + 3],
    ]);
    let key = allowlist_record_key_str(rec);
    if uid == ALLOWLIST_PRESERVE_UID {
        format!("key:{key}")
    } else {
        format!("uid:{uid}")
    }
}

/// 结构体检 + 解析（`None` = magic/version/长度不合法 ⇒ 不算数，退回老判定）
fn parse_allowlist(data: &[u8]) -> Option<AllowlistView> {
    if data.len() < 8 {
        return None;
    }
    let header: [u8; 8] = data[..8].try_into().ok()?;
    let magic = u32::from_le_bytes([data[0], data[1], data[2], data[3]]);
    let version = u32::from_le_bytes([data[4], data[5], data[6], data[7]]);
    if magic != ALLOWLIST_MAGIC
        || !(ALLOWLIST_MIN_VERSION..=ALLOWLIST_MAX_VERSION).contains(&version)
    {
        return None;
    }
    let record_size = if version < ALLOWLIST_MAX_VERSION {
        ALLOWLIST_RECORD_PRE_V4
    } else {
        ALLOWLIST_RECORD_V4
    };
    let body = &data[8..];
    if record_size == 0 || !body.len().is_multiple_of(record_size) {
        return None;
    }
    let mut records = Vec::with_capacity(body.len() / record_size);
    for chunk in body.chunks_exact(record_size) {
        records.push((allowlist_record_key(chunk), chunk.to_vec()));
    }
    Some(AllowlistView {
        record_size,
        header,
        records,
    })
}

/// 并集合并的累加器：先放"新目录那份"（当前状态），再把各旧来源里**独有**的记录并进来。
struct AllowlistUnion {
    header: [u8; 8],
    record_size: usize,
    records: Vec<Vec<u8>>,
    index: HashMap<String, usize>,
}

impl AllowlistUnion {
    fn from_new(view: &AllowlistView) -> Self {
        let mut index = HashMap::with_capacity(view.records.len());
        let mut records = Vec::with_capacity(view.records.len());
        for (key, bytes) in &view.records {
            // 新侧自身若有重复键：**第一条为准**（内核 hash_add 后查到的就是先加的那条）
            if index.contains_key(key) {
                continue;
            }
            index.insert(key.clone(), records.len());
            records.push(bytes.clone());
        }
        Self {
            header: view.header,
            record_size: view.record_size,
            records,
            index,
        }
    }

    /// 并入一份旧来源；返回 (旧侧独有并进来的条数, 同键但内容不同的条数)
    fn absorb(&mut self, view: &AllowlistView) -> (usize, usize) {
        let mut legacy_only = 0usize;
        let mut same_key_diff = 0usize;
        for (key, bytes) in &view.records {
            match self.index.get(key) {
                Some(i) if self.records[*i] == *bytes => {}
                Some(_) => same_key_diff += 1,
                None => {
                    self.index.insert(key.clone(), self.records.len());
                    self.records.push(bytes.clone());
                    legacy_only += 1;
                }
            }
        }
        (legacy_only, same_key_diff)
    }

    fn finish(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(8 + self.records.len() * self.record_size);
        out.extend_from_slice(&self.header);
        for r in &self.records {
            out.extend_from_slice(r);
        }
        out
    }
}

/// 写入并集合并后的 `.allowlist`：**先备份、再写、再逐字节校验**，失败一个字节都不留。
///
/// 写盘走"临时文件 → `copy_verify`（既有的复制 + 逐字节校验 + 属主/SELinux 还原）"，
/// 校验失败就把备份拷回去（理论上不会走到：`copy_verify` 失败时目标已被写坏）。
fn write_merged_allowlist(dst: &Path, merged: &[u8], rel: &Path, r: &mut Report) -> Result<()> {
    let meta = fs::symlink_metadata(dst).with_context(|| format!("读不了 {}", dst.display()))?;
    let original = fs::read(dst).with_context(|| format!("读不了 {}", dst.display()))?;
    let stamp = unix_now();
    let bak = dst.with_file_name(format!("{ALLOWLIST_FILE}.pre-merge-{stamp}"));
    let tmp = dst.with_file_name(format!("{ALLOWLIST_FILE}.merge-tmp-{stamp}"));
    // ① 写前备份（同目录、不同名 ⇒ 内核/迁移都不会把它当名单读）
    fs::write(&bak, &original).with_context(|| format!("备份失败 {}", bak.display()))?;
    anyhow::ensure!(
        fs::read(&bak).is_ok_and(|b| b == original),
        "写前备份校验失败（{}）",
        bak.display()
    );
    // ② 写临时文件 + 逐字节校验地覆盖到 dst
    let applied = (|| -> Result<()> {
        fs::write(&tmp, merged).with_context(|| format!("写临时文件失败 {}", tmp.display()))?;
        copy_verify(&tmp, dst, &meta, r)
    })();
    let _ = fs::remove_file(&tmp);
    match applied {
        Ok(()) => {
            r.notes.push(format!(
                "🔴1 `.allowlist` 写前备份在 {}（不放心可手动回滚；它不会被内核读取）",
                bak.display()
            ));
            log::warn!(
                "migrate: {} 已并集合并（结果 {} 字节），旧目录那份原样保留",
                show(rel),
                merged.len()
            );
            Ok(())
        }
        Err(e) => {
            // ③ 校验失败 ⇒ 把备份拷回去（尽力而为），并如实记为 failed
            if let Err(back) = fs::copy(&bak, dst) {
                log::error!("migrate: {} 合并失败后回滚也失败：{back}", show(rel));
            }
            Err(e)
        }
    }
}

/// 🔴1：体检/采纳"旧目录（含改名留存的 `ksu.legacy_backup_*`）里新目录还没有的授权"。
///
/// 任何一步拿不准（读不出来 / 结构不合法 / 版本不同）都返回
/// [`AllowlistOutcome::NotApplicable`]，由调用方退回老的 `decide_key_file` 判定 —— 绝不猜。
fn handle_legacy_allowlist(
    new: &Path,
    legacy: &Path,
    adopt: bool,
    r: &mut Report,
) -> AllowlistOutcome {
    let sources = allowlist_legacy_sources(legacy);
    handle_legacy_allowlist_at(&new.join(ALLOWLIST_FILE), &sources, adopt, r)
}

/// `.allowlist` 并集合并的结论（决定调用方要不要退回"空/截断"老判定）
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AllowlistOutcome {
    /// 拿不准 / 没有旧来源 ⇒ **什么都不做**，调用方退回老判定
    NotApplicable,
    /// 处理过了（`adopted` = 本次写盘采纳的条数，`pending` = 还没采纳的条数）
    Handled { adopted: usize, pending: usize },
}

/// 上面那个的"本体"：给定新目录那份名单的路径 + 一组旧来源（已按新鲜度排序）。
fn handle_legacy_allowlist_at(
    dst: &Path,
    sources: &[PathBuf],
    adopt: bool,
    r: &mut Report,
) -> AllowlistOutcome {
    let rel = Path::new(ALLOWLIST_FILE);
    if sources.is_empty() {
        return AllowlistOutcome::NotApplicable;
    }
    let Ok(new_bytes) = fs::read(dst) else {
        // 新目录里根本没有名单：这一支交给 `repair_allowlist` 的"缺失就补回"逻辑，
        // 这里不重复处理（避免两处都写）。
        return AllowlistOutcome::NotApplicable;
    };
    let Some(new_view) = parse_allowlist(&new_bytes) else {
        // 新侧结构不合法（空/截断/版本不对）⇒ 退回老判定（会被 TakeLegacy 修好）
        return AllowlistOutcome::NotApplicable;
    };
    let mut union = AllowlistUnion::from_new(&new_view);
    let mut pending = 0usize;
    let mut same_key_diff = 0usize;
    let mut used: Vec<String> = Vec::new();
    let mut compatible_source = false;
    for src in sources {
        let Ok(bytes) = fs::read(src) else { continue };
        let Some(view) = parse_allowlist(&bytes) else {
            continue;
        };
        // 记录长度必须一致：version 3(776) 的记录混进 version 4(784) 的文件 = 结构性损坏
        if view.record_size != union.record_size {
            r.notes.push(format!(
                "{}：{} 的记录长度（{}）与当前名单（{}）不同 ⇒ 不做记录级合并（原样保留）",
                show(rel),
                src.display(),
                view.record_size,
                union.record_size
            ));
            continue;
        }
        compatible_source = true;
        let (only, diff) = union.absorb(&view);
        if only > 0 || diff > 0 {
            used.push(src.display().to_string());
        }
        pending += only;
        same_key_diff += diff;
    }
    if !compatible_source {
        return AllowlistOutcome::NotApplicable;
    }
    if same_key_diff > 0 {
        r.conflicts.push(format!(
            "{}: {} 条同 UID 记录内容不同（以新目录那份为准，旧目录不删）",
            show(rel),
            same_key_diff
        ));
    }
    if pending == 0 {
        return AllowlistOutcome::Handled {
            adopted: 0,
            pending: 0,
        };
    }
    let merged = union.finish();
    if merged == new_bytes {
        return AllowlistOutcome::Handled {
            adopted: 0,
            pending: 0,
        };
    }
    if adopt {
        match write_merged_allowlist(dst, &merged, rel, r) {
            Ok(()) => {
                r.repaired.push(show(rel));
                r.allowlist_adopted = pending;
                r.notes.push(format!(
                    "🔴1 已从 {} 采纳旧目录独有的 {} 条授权（并集合并，同 UID 以新目录为准）",
                    used.join("、"),
                    pending
                ));
                AllowlistOutcome::Handled {
                    adopted: pending,
                    pending: 0,
                }
            }
            Err(e) => {
                r.failed.push(format!("{}: 并集合并失败 {e}", show(rel)));
                r.allowlist_legacy_only = pending;
                AllowlistOutcome::Handled {
                    adopted: 0,
                    pending,
                }
            }
        }
    } else {
        r.allowlist_legacy_only = pending;
        r.conflicts.push(format!(
            "{}: 旧目录还有 {} 条授权未被采用（App 首页点「采纳并重启」，或跑 `ksud migrate --adopt-legacy-allowlist`）",
            show(rel),
            pending
        ));
        AllowlistOutcome::Handled {
            adopted: 0,
            pending,
        }
    }
}

/// 🔴1：旧授权名单的**来源**（按"最新鲜"排序）。
///
/// ① 真·旧目录 `<legacy>/.allowlist` —— 只在 `legacy` 是**真实目录**时算
///    （它要是兼容软链，读它就等于读新目录那份，自己跟自己合并没有意义）；
/// ② v2.9/v2.10 的"改名留存"目录 `<parent>/ksu.legacy_backup_*/`（新→旧）——
///    ⚠️ 真机上老目录**很可能已经被改名留存**（新内核被确认后 `ensure_compat_link_at`
///    会把有内容的老目录改名成 `ksu.legacy_backup_*` 再建软链）；这时老名单只在这里，
///    不扫它就会得出"没有未采纳的授权"的**假结论** ✗。
fn allowlist_legacy_sources(legacy: &Path) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = Vec::new();
    if fs::symlink_metadata(legacy).is_ok_and(|m| m.is_dir() && !m.file_type().is_symlink()) {
        out.push(legacy.join(ALLOWLIST_FILE));
    }
    if let (Some(parent), Some(base)) = (legacy.parent(), legacy.file_name()) {
        for dir in list_legacy_backups(parent, &base.to_string_lossy()) {
            out.push(dir.join(ALLOWLIST_FILE));
        }
    }
    out.retain(|p| path_exists(p));
    out
}

/// 体检两边 → 判定 → 落地（复制/记录都由这里统一处理）
fn resolve_key_file(
    kind: KeyKind,
    src: &Path,
    dst: &Path,
    meta: &fs::Metadata,
    rel: &Path,
    aggressive: bool,
    r: &mut Report,
) {
    let legacy = inspect_key_file(kind, src);
    let new = inspect_key_file(kind, dst);
    match decide_key_file(legacy, new, aggressive) {
        KeyChoice::TakeLegacy => match copy_verify(src, dst, meta, r) {
            Ok(()) => {
                r.repaired.push(show(rel));
                log::warn!(
                    "migrate: 关键文件 {} 的新目录那份是空/截断（{} 字节），已用旧目录那份（{} 字节）修复",
                    show(rel),
                    new.len,
                    legacy.len
                );
            }
            Err(e) => r.failed.push(format!("{}: 修复失败 {e}", show(rel))),
        },
        KeyChoice::TakeNew => {
            r.ignored_broken_legacy.push(show(rel));
            log::warn!(
                "migrate: 关键文件 {} 的旧目录那份空/截断（{} 字节），以新目录那份（{} 字节）为准",
                show(rel),
                legacy.len,
                new.len
            );
        }
        KeyChoice::ConflictKeepNew => r.conflicts.push(format!(
            "{}: 两边都完整但内容不同（新 {} 字节 / 旧 {} 字节；保留新目录那份，不覆盖）",
            show(rel),
            new.len,
            legacy.len
        )),
        KeyChoice::ConflictUnfixable => r.conflicts.push(format!(
            "{}: 两边都是空/截断（新 {} 字节 / 旧 {} 字节；无法自动判断取舍，都保留）",
            show(rel),
            new.len,
            legacy.len
        )),
    }
}

#[derive(Default)]
struct WalkState {
    depth: usize,
}

fn copy_tree(
    src_root: &Path,
    dst_root: &Path,
    rel: &Path,
    r: &mut Report,
    walk: &mut WalkState,
) -> Result<()> {
    let src = src_root.join(rel);
    let meta = fs::symlink_metadata(&src).with_context(|| format!("读不了 {}", src.display()))?;
    let ft = meta.file_type();
    let dst = dst_root.join(rel);

    if ft.is_dir() {
        if !rel.as_os_str().is_empty() {
            if path_exists(&dst) {
                if !fs::symlink_metadata(&dst)?.is_dir() {
                    r.conflicts
                        .push(format!("{}: 新目录里是个非目录", show(rel)));
                    return Ok(());
                }
            } else {
                fs::create_dir_all(&dst)
                    .with_context(|| format!("建不了目录 {}", dst.display()))?;
                let _ = fs::set_permissions(&dst, meta.permissions());
                restore_meta(&src, &dst, &meta, false, r);
            }
        }
        if walk.depth > 64 {
            r.failed.push(format!("{}: 目录太深，放弃", show(rel)));
            return Ok(());
        }
        let mut names: Vec<std::ffi::OsString> = Vec::new();
        for ent in fs::read_dir(&src).with_context(|| format!("列不了 {}", src.display()))? {
            match ent {
                Ok(e) => names.push(e.file_name()),
                Err(e) => r.failed.push(format!("{}: {e}", show(rel))),
            }
        }
        names.sort();
        walk.depth += 1;
        for n in names {
            copy_tree(src_root, dst_root, &rel.join(n), r, walk)?;
        }
        walk.depth -= 1;
    } else if ft.is_symlink() {
        let target =
            fs::read_link(&src).with_context(|| format!("读不了链接 {}", src.display()))?;
        if path_exists(&dst) {
            let same = fs::read_link(&dst).is_ok_and(|t| t == target);
            if same {
                r.already += 1;
            } else {
                r.conflicts.push(format!("{}: 符号链接指向不同", show(rel)));
            }
        } else {
            copy_symlink(&target, &dst, &show(rel), r);
            let lmeta = fs::symlink_metadata(&src)?;
            restore_meta(&src, &dst, &lmeta, true, r);
        }
    } else if ft.is_file() {
        if path_exists(&dst) {
            match files_equal(&src, &dst) {
                Ok(true) => r.already += 1,
                Ok(false) => match key_file_kind(rel) {
                    // 🔴1（v2.11）：`.allowlist` 先走"按记录并集合并"（同 UID 以新目录为准、
                    // 旧侧独有的并进来）。拿不准（空/截断/版本不同）才退回老的"空/截断"判定。
                    Some(KeyKind::Allowlist) => {
                        if handle_legacy_allowlist_at(&dst, std::slice::from_ref(&src), true, r)
                            == AllowlistOutcome::NotApplicable
                        {
                            resolve_key_file(KeyKind::Allowlist, &src, &dst, &meta, rel, true, r);
                        }
                    }
                    Some(kind) => {
                        // 全量迁移：允许"明显偏小"（结构完整但 0 条记录的名单）的激进判定
                        resolve_key_file(kind, &src, &dst, &meta, rel, true, r);
                    }
                    None => r.conflicts.push(format!("{}: 两边内容不同", show(rel))),
                },
                Err(e) => r.failed.push(format!("{}: 比对失败 {e}", show(rel))),
            }
        } else {
            match copy_verify(&src, &dst, &meta, r) {
                Ok(()) => r.copied += 1,
                Err(e) => r.failed.push(format!("{}: {e}", show(rel))),
            }
        }
    } else {
        r.failed.push(format!(
            "{}: 不支持的条目类型（不是文件/目录/链接）",
            show(rel)
        ));
    }
    Ok(())
}

#[cfg(unix)]
fn copy_symlink(target: &Path, dst: &Path, label: &str, r: &mut Report) {
    match std::os::unix::fs::symlink(target, dst) {
        Ok(()) => r.copied += 1,
        Err(e) => r.failed.push(format!("{label}: 建链接失败 {e}")),
    }
}

#[cfg(not(unix))]
fn copy_symlink(_target: &Path, _dst: &Path, label: &str, r: &mut Report) {
    r.failed.push(format!("{label}: 本平台不支持符号链接"));
}

fn copy_verify(src: &Path, dst: &Path, meta: &fs::Metadata, r: &mut Report) -> Result<()> {
    fs::copy(src, dst).with_context(|| format!("复制失败 {}", src.display()))?;
    // 🟠（v2.18）**复制完先落盘再继续**。`fs::copy` 只把数据交给页缓存，不保证进了闪存；
    // 而迁移成功后的下一步是 `remove_dir_all(legacy)` —— 如果此刻掉电，可能落成
    // "新目录里是空文件 / 旧目录已经删掉" ⇒ 授权名单、隐身标志这类关键文件**两头都没了**。
    // 照本仓 `module_config.rs::save_config` 的正确样例，复制后先 `sync_all`；
    // sync 失败就返回 Err（迁移会中止且**不删旧目录**，方向永远是"宁可不搬"）。
    std::fs::File::open(dst)
        .and_then(|f| f.sync_all())
        .with_context(|| format!("落盘（sync）失败 {}", dst.display()))?;
    let _ = fs::set_permissions(dst, meta.permissions());
    // 🟠 补属主 + SELinux 标签（做不到就记日志/记 report，不阻断）
    restore_meta(src, dst, meta, false, r);
    anyhow::ensure!(
        files_equal(src, dst)?,
        "复制后逐字节校验不一致（{} -> {}）",
        src.display(),
        dst.display()
    );
    Ok(())
}

/// 🟠 复制后恢复元数据：**属主（uid/gid）** + **SELinux 标签**。
///
/// * 属主：`chown`/`lchown`（符号链接用 `lchown`，不跟随）；
/// * SELinux：把源文件的 `security.selinux` 扩展属性原样写到目标（`lsetxattr`），
///   写完**再读回来核对**；读不到（源就没有标签）就跳过，写不进去就把原因写进 report。
///   Android 上 ksud 是 root，正常都能成功；容器/开发机上没这套语义 → 空实现。
fn restore_meta(src: &Path, dst: &Path, meta: &fs::Metadata, symlink: bool, r: &mut Report) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let res = if symlink {
            std::os::unix::fs::lchown(dst, Some(meta.uid()), Some(meta.gid()))
        } else {
            std::os::unix::fs::chown(dst, Some(meta.uid()), Some(meta.gid()))
        };
        if let Err(e) = res {
            log::warn!(
                "migrate: 属主没恢复（{} -> {}，期望 {}:{}）: {e}",
                src.display(),
                dst.display(),
                meta.uid(),
                meta.gid()
            );
            r.notes.push(format!(
                "属主未恢复（期望 {}:{}）: {}",
                meta.uid(),
                meta.gid(),
                e
            ));
        }
    }
    copy_selinux_label(src, dst, r);
}

#[cfg(target_os = "android")]
#[allow(clippy::cast_possible_wrap, clippy::cast_sign_loss)]
fn copy_selinux_label(src: &Path, dst: &Path, r: &mut Report) {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;

    const NAME: &[u8] = b"security.selinux";
    let (Ok(csrc), Ok(cdst)) = (
        CString::new(src.as_os_str().as_bytes()),
        CString::new(dst.as_os_str().as_bytes()),
    ) else {
        return; // 路径里有 NUL 字节（不可能）→ 静默跳过
    };
    let mut name: Vec<u8> = NAME.to_vec();
    name.push(0);

    let mut buf = vec![0_u8; 512];
    // SAFETY: 两个指针都来自上面的 CString/Vec，长度用 buf.len() 如实传入
    let n = unsafe {
        libc::lgetxattr(
            csrc.as_ptr(),
            name.as_ptr().cast(),
            buf.as_mut_ptr().cast(),
            buf.len(),
        )
    };
    if n <= 0 {
        // 源没有标签（ENODATA/ENOTSUP）是正常情况：没什么可恢复的
        return;
    }
    let ctx = &buf[..n as usize];

    // SAFETY: ctx 指向刚读出来的缓冲区，长度是内核回写的 n
    let ret = unsafe {
        libc::lsetxattr(
            cdst.as_ptr(),
            name.as_ptr().cast(),
            ctx.as_ptr().cast(),
            ctx.len(),
            0,
        )
    };
    if ret != 0 {
        let e = std::io::Error::last_os_error();
        log::warn!(
            "migrate: SELinux 标签没恢复（{} -> {}）: {e}",
            src.display(),
            dst.display()
        );
        r.notes.push(format!(
            "SELinux 标签未恢复（{}，来源上下文 {}）: {e}",
            dst.display(),
            String::from_utf8_lossy(ctx).trim_end_matches('\0')
        ));
        return;
    }

    // 复核：读回来逐字节比
    let mut got = vec![0_u8; 512];
    // SAFETY: 同上面那次读取
    let m = unsafe {
        libc::lgetxattr(
            cdst.as_ptr(),
            name.as_ptr().cast(),
            got.as_mut_ptr().cast(),
            got.len(),
        )
    };
    if m != n || got[..m.max(0) as usize] != *ctx {
        r.notes
            .push(format!("SELinux 标签复核不一致: {}", dst.display()));
    }
}

#[cfg(not(target_os = "android"))]
fn copy_selinux_label(_src: &Path, _dst: &Path, _r: &mut Report) {
    // 开发机（macOS/Linux 容器）没有 Android 的 `security.selinux` 语义：
    // 这里什么都不做；真正的恢复与复核在设备上由 ksud 做（失败会写日志 + 进 report）。
    // 宿主单测覆盖的是属主恢复与两条判定逻辑（🔴A 门控 / 🔴B 关键文件）。
}

/// 分块逐字节比对（不吃内存，大文件也稳）。
fn files_equal(a: &Path, b: &Path) -> Result<bool> {
    let ma = fs::metadata(a).with_context(|| format!("读不了 {}", a.display()))?;
    let mb = fs::metadata(b).with_context(|| format!("读不了 {}", b.display()))?;
    if ma.len() != mb.len() {
        return Ok(false);
    }
    let mut fa = fs::File::open(a).with_context(|| format!("开不了 {}", a.display()))?;
    let mut fb = fs::File::open(b).with_context(|| format!("开不了 {}", b.display()))?;
    let mut ba = vec![0_u8; 64 * 1024];
    let mut bb = vec![0_u8; 64 * 1024];
    loop {
        let na = read_full(&mut fa, &mut ba)?;
        let nb = read_full(&mut fb, &mut bb)?;
        // na != nb 时 `||` 会短路，后面那半不会真的去比（两边缓冲区同长，写 bb[..na] 更直白）
        if na != nb || ba[..na] != bb[..na] {
            return Ok(false);
        }
        if na == 0 {
            return Ok(true);
        }
    }
}

fn read_full(f: &mut fs::File, buf: &mut [u8]) -> Result<usize> {
    let mut n = 0;
    while n < buf.len() {
        match f.read(&mut buf[n..])? {
            0 => break,
            k => n += k,
        }
    }
    Ok(n)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::path::{Path, PathBuf};

    fn write_file(p: &Path, s: &[u8]) {
        if let Some(d) = p.parent() {
            fs::create_dir_all(d).unwrap();
        }
        let mut f = fs::File::create(p).unwrap();
        f.write_all(s).unwrap();
    }

    fn rd(p: &Path) -> Vec<u8> {
        fs::read(p).unwrap()
    }

    /// 造一份"结构完整"的 `.allowlist`（magic + version 4 + n 条 784 字节记录）
    fn allowlist_bytes(records: usize) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&ALLOWLIST_MAGIC.to_le_bytes());
        v.extend_from_slice(&ALLOWLIST_MAX_VERSION.to_le_bytes());
        v.extend(std::iter::repeat_n(0xAB_u8, records * ALLOWLIST_RECORD_V4));
        v
    }

    /// 造一条**真实形状**的记录（`struct app_profile`：version + key[256] + curr_uid + allow_su + 填充）。
    ///
    /// ⚠️ `allowlist_bytes()` 造的记录**每条都一模一样**（同一个 uid），只适合"条数 vs 条数"的
    /// 粗测；凡是要走 🔴1 的"按记录并集合并"，都必须用这个（否则同 uid 的记录会被正确地判成重复）。
    fn allowlist_record(uid: i32, key: &str, allow: bool, fill: u8) -> Vec<u8> {
        let mut rec = vec![fill; ALLOWLIST_RECORD_V4];
        let kb = key.as_bytes();
        let n = kb.len().min(255);
        rec[ALLOWLIST_KEY_RANGE.start..ALLOWLIST_KEY_RANGE.start + n].copy_from_slice(&kb[..n]);
        for b in &mut rec[ALLOWLIST_KEY_RANGE.start + n..ALLOWLIST_KEY_RANGE.end] {
            *b = 0;
        }
        rec[ALLOWLIST_UID_OFFSET..ALLOWLIST_UID_OFFSET + 4].copy_from_slice(&uid.to_le_bytes());
        rec[ALLOWLIST_UID_OFFSET + 4] = u8::from(allow);
        rec
    }

    /// 把若干条记录拼成一份 `.allowlist`（header = magic + version 4）
    fn allowlist_of(records: &[Vec<u8>]) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&ALLOWLIST_MAGIC.to_le_bytes());
        v.extend_from_slice(&ALLOWLIST_MAX_VERSION.to_le_bytes());
        for r in records {
            assert_eq!(r.len(), ALLOWLIST_RECORD_V4);
            v.extend_from_slice(r);
        }
        v
    }

    /// 解析一份名单里的 (uid, allow_su)，按文件顺序（单测断言用）
    fn allowlist_uids(data: &[u8]) -> Vec<(i32, bool)> {
        let v = parse_allowlist(data).expect("结构必须合法");
        v.records
            .iter()
            .map(|(_, bytes)| {
                let uid = i32::from_le_bytes([
                    bytes[ALLOWLIST_UID_OFFSET],
                    bytes[ALLOWLIST_UID_OFFSET + 1],
                    bytes[ALLOWLIST_UID_OFFSET + 2],
                    bytes[ALLOWLIST_UID_OFFSET + 3],
                ]);
                (uid, bytes[ALLOWLIST_UID_OFFSET + 4] != 0)
            })
            .collect()
    }

    // ── 门控（🔴A）用的"假内核"：set() 会把 stealth 写进它被配置的那个目录 ──
    struct FakeKernel {
        /// 内核自己那个数据目录（模拟"内核里的路径常量"）
        datadir: PathBuf,
        /// 读到的当前值
        value: bool,
        /// 模拟"老内核不认新命令"（get/set 都失败）
        unsupported: bool,
        /// `set()` 被调用了几次（用来验证"缓存命中就不该再探测"）
        sets: std::cell::Cell<usize>,
        /// `get()` 被调用了几次
        gets: std::cell::Cell<usize>,
        /// 🔴2（v2.17）：`set()` **依次收到的值** —— 用来钉死
        /// "老内核结论下，写进去的每个值都必须等于内核自己的当前值"。
        set_values: std::cell::RefCell<Vec<bool>>,
    }

    impl FakeKernel {
        fn new(datadir: &Path, value: bool) -> Self {
            Self {
                datadir: datadir.to_path_buf(),
                value,
                unsupported: false,
                sets: std::cell::Cell::new(0),
                gets: std::cell::Cell::new(0),
                set_values: std::cell::RefCell::new(Vec::new()),
            }
        }

        fn unsupported(datadir: &Path) -> Self {
            Self {
                datadir: datadir.to_path_buf(),
                value: false,
                unsupported: true,
                sets: std::cell::Cell::new(0),
                gets: std::cell::Cell::new(0),
                set_values: std::cell::RefCell::new(Vec::new()),
            }
        }

        fn set_count(&self) -> usize {
            self.sets.get()
        }

        /// 依次收到的 `set()` 值（🔴2 用）
        fn set_values(&self) -> Vec<bool> {
            self.set_values.borrow().clone()
        }
    }

    impl StealthIo for FakeKernel {
        fn get(&self) -> Result<bool> {
            self.gets.set(self.gets.get() + 1);
            anyhow::ensure!(!self.unsupported, "内核不认这条命令（-ENOTTY）");
            Ok(self.value)
        }

        fn set(&self, enabled: bool) -> Result<()> {
            self.sets.set(self.sets.get() + 1);
            self.set_values.borrow_mut().push(enabled);
            anyhow::ensure!(!self.unsupported, "内核不认这条命令（-ENOTTY）");
            // 真内核是同步写盘的；这里也同步写，顺便保证 mtime 一定变化
            std::thread::sleep(std::time::Duration::from_millis(2));
            fs::write(
                self.datadir.join(STEALTH_FILE),
                if enabled { b"1" } else { b"0" },
            )?;
            Ok(())
        }
    }

    #[test]
    fn gate_only_allows_confirmed_new_kernel() {
        assert!(gate_allows(DatadirProbe::New));
        assert!(!gate_allows(DatadirProbe::Legacy));
        assert!(!gate_allows(DatadirProbe::Unknown));
    }

    #[test]
    fn probe_detects_legacy_kernel_and_blocks_migration() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        write_file(&old.join(".allowlist"), &allowlist_bytes(2));
        write_file(&old.join("webadmin.conf"), b"token=abc");
        // 老内核：数据目录还是 old
        let kernel = FakeKernel::new(&old, true);

        assert_eq!(
            probe_datadir_with(&kernel, &old, &new),
            DatadirProbe::Legacy
        );

        let r = migrate_gated(&kernel, &old, &new).unwrap();
        assert!(r.skipped_not_new_kernel, "{r:?}");
        assert_eq!(r.kernel_probe, Some("legacy_datadir"));
        assert!(r.summary().contains("不迁移"), "{}", r.summary());
        // 旧目录一个字节都不能动；新目录不能凭空出现
        assert_eq!(rd(&old.join(".allowlist")), allowlist_bytes(2));
        assert_eq!(
            rd(&old.join("webadmin.conf")),
            b"token=abc",
            "旧目录一个字节都不能动"
        );
        assert!(!new.exists(), "老内核在跑时绝不能把数据搬到新目录");
    }

    #[test]
    fn probe_detects_new_kernel_and_migrates() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        write_file(&old.join(".allowlist"), &allowlist_bytes(2));
        write_file(&old.join("webadmin.conf"), b"token=abc");
        fs::create_dir_all(&new).unwrap(); // auto_migrate 在探测前会先把新目录建出来
        let kernel = FakeKernel::new(&new, true);

        assert_eq!(probe_datadir_with(&kernel, &old, &new), DatadirProbe::New);

        let r = migrate_gated(&kernel, &old, &new).unwrap();
        assert!(!r.skipped_not_new_kernel, "{r:?}");
        assert!(r.is_clean(), "{r:?}");
        assert!(r.deleted_legacy, "{r:?}");
        assert!(!old.exists());
        assert_eq!(rd(&new.join("stealth")), b"1");
        assert_eq!(rd(&new.join(".allowlist")), allowlist_bytes(2));
        assert_eq!(rd(&new.join("webadmin.conf")), b"token=abc");
    }

    #[test]
    fn probe_unknown_when_kernel_has_no_such_command() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        fs::create_dir_all(&new).unwrap();
        // 老内核（<= v0.13.150）连命令号都不认 → 拿不准 → 不迁移
        let kernel = FakeKernel::unsupported(&new);
        assert_eq!(
            probe_datadir_with(&kernel, &old, &new),
            DatadirProbe::Unknown
        );
        let r = migrate_gated(&kernel, &old, &new).unwrap();
        assert!(r.skipped_not_new_kernel, "{r:?}");
        assert!(old.join("stealth").exists());
    }

    #[test]
    fn probe_unknown_when_nothing_changes() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        fs::create_dir_all(&new).unwrap();
        // 假内核：说支持命令，但谁也没写（模拟"写失败/竞态"）→ 必须判 Unknown
        struct SilentKernel;
        impl StealthIo for SilentKernel {
            fn get(&self) -> Result<bool> {
                Ok(true)
            }
            fn set(&self, _enabled: bool) -> Result<()> {
                Ok(())
            }
        }
        assert_eq!(
            probe_datadir_with(&SilentKernel, &old, &new),
            DatadirProbe::Unknown
        );
    }

    #[test]
    fn probe_value_falls_back_to_kernel_when_no_stealth_file_exists() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        fs::create_dir_all(&old).unwrap();
        fs::create_dir_all(&new).unwrap();
        // 两边都没有 stealth 文件 → 用内核当前值（true），新内核会写下 '1'
        let kernel = FakeKernel::new(&new, true);
        assert_eq!(probe_datadir_with(&kernel, &old, &new), DatadirProbe::New);
        assert_eq!(rd(&new.join("stealth")), b"1");
        assert!(!old.join("stealth").exists(), "老目录不该多出文件");
    }

    // ── 🟠1：两边都有效、但值不同 ⇒ 拿不准（绝不能把老内核的隐身改掉）──

    #[test]
    fn probe_conflicting_stealth_values_is_unknown_and_writes_nothing() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        // 触发路径：新内核用过（新目录留下 '1'）→ 降级回老内核（旧目录 '0'）。
        // 老代码"新目录优先" ⇒ 把 '1' 写进旧目录 = 改掉运行中老内核的隐身语义。
        write_file(&old.join("stealth"), b"0");
        write_file(&new.join("stealth"), b"1");
        let old_kernel = FakeKernel::new(&old, false);

        assert_eq!(
            probe_datadir_with(&old_kernel, &old, &new),
            DatadirProbe::Unknown,
            "两边都有效但不同 ⇒ 必须判 Unknown"
        );
        // 关键证据：**一个字节都没写**（老内核的隐身既没被打开也没被关掉）
        assert_eq!(rd(&old.join("stealth")), b"0", "老内核的隐身值不能被改");
        assert_eq!(rd(&new.join("stealth")), b"1", "新目录那份也不该被改");

        // 反方向（老目录 '1'、新目录 '0'）同样 Unknown
        write_file(&old.join("stealth"), b"1");
        write_file(&new.join("stealth"), b"0");
        assert_eq!(
            probe_datadir_with(&old_kernel, &old, &new),
            DatadirProbe::Unknown
        );
        assert_eq!(rd(&old.join("stealth")), b"1");
        assert_eq!(rd(&new.join("stealth")), b"0", "反方向也一个字节没写");

        // 门控必须拒绝：本次不迁移、旧目录一个字节都不动
        write_file(&old.join(".allowlist"), &allowlist_bytes(2));
        let r = migrate_gated(&old_kernel, &old, &new).unwrap();
        assert!(r.skipped_not_new_kernel, "{r:?}");
        assert_eq!(r.kernel_probe, Some("unknown"));
        assert_eq!(rd(&old.join("stealth")), b"1");
        assert_eq!(rd(&old.join(".allowlist")), allowlist_bytes(2));
    }

    #[test]
    fn probe_same_stealth_value_on_both_sides_is_not_a_conflict() {
        // 两边都有效且**相同** ⇒ 不算冲突，照常按"谁被写"判定内核
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        write_file(&new.join("stealth"), b"1");

        let new_kernel = FakeKernel::new(&new, true);
        assert_eq!(
            probe_datadir_with(&new_kernel, &old, &new),
            DatadirProbe::New
        );
        assert_eq!(rd(&old.join("stealth")), b"1", "老目录那份不该被写");

        let old_kernel = FakeKernel::new(&old, true);
        assert_eq!(
            probe_datadir_with(&old_kernel, &old, &new),
            DatadirProbe::Legacy
        );
        assert_eq!(rd(&new.join("stealth")), b"1", "新目录那份不该被写");
    }

    #[test]
    fn probe_single_valid_side_keeps_original_priority() {
        // 只有一边有有效值 ⇒ 探测照常（🔴2 之后：探测写的是**内核自己的当前值**，
        // 与文件里那份是同一个值时看不出区别；不同值时的行为见下面两条 🔴2 专测）
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"0");
        fs::create_dir_all(&new).unwrap();
        let old_kernel = FakeKernel::new(&old, false);
        assert_eq!(
            probe_datadir_with(&old_kernel, &old, &new),
            DatadirProbe::Legacy
        );
        assert_eq!(rd(&old.join("stealth")), b"0", "同值写回，语义不变");

        // 另一个场景：只有新目录有一份有效值
        let t2 = tempfile::tempdir().unwrap();
        let old2 = t2.path().join("ksu");
        let new2 = t2.path().join("sevenk");
        fs::create_dir_all(&old2).unwrap();
        write_file(&new2.join("stealth"), b"1");
        let new_kernel = FakeKernel::new(&new2, true);
        assert_eq!(
            probe_datadir_with(&new_kernel, &old2, &new2),
            DatadirProbe::New
        );
        assert!(!old2.join("stealth").exists(), "老目录不该多出文件");

        // 一边有效一边是坏值（空文件）⇒ 不算"两边都有效"，仍按有效的那份走
        let t3 = tempfile::tempdir().unwrap();
        let old3 = t3.path().join("ksu");
        let new3 = t3.path().join("sevenk");
        write_file(&old3.join("stealth"), b"0");
        write_file(&new3.join("stealth"), b""); // 坏值：不算数
        let old_kernel3 = FakeKernel::new(&old3, false);
        assert_eq!(
            probe_datadir_with(&old_kernel3, &old3, &new3),
            DatadirProbe::Legacy
        );
        assert_eq!(rd(&old3.join("stealth")), b"0");
    }

    // ── 🔴2（v2.17）：老内核在跑时，**绝不能拿新侧的值去写旧目录** ──

    #[test]
    fn probe_old_kernel_with_new_side_stealth_never_writes_new_value_to_old_dir() {
        // 完全复刻审计描述的那条路：
        //   · 新目录里留着一份 '1'（以前新内核写过）
        //   · 旧目录**没有** stealth 文件
        //   · 跑着的却是**老内核**（它的当前值是 false，且它的数据目录是旧目录）
        // 旧代码 `target = new_value.or(...)` = Some(true) ⇒ 写 true 进旧目录
        // ⇒ 老内核的隐身**当场被打开**（开关被改；若新侧是 '0' 就是"关掉 = 暴露"）。
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        fs::create_dir_all(&old).unwrap();
        write_file(&new.join("stealth"), b"1");
        let old_kernel = FakeKernel::new(&old, false);

        assert_eq!(
            probe_datadir_with(&old_kernel, &old, &new),
            DatadirProbe::Legacy,
            "老内核在跑（它把旧目录那份写了）⇒ 必须判 Legacy"
        );
        // 关键证据 ①：写进老内核的每个值都等于**它自己的当前值**（false）——
        // 隐身开关的语义零变化；绝不能出现新侧那个 true。
        let written = old_kernel.set_values();
        assert!(!written.is_empty(), "探测至少要写一次（不然拿不到笔迹）");
        assert!(
            written.iter().all(|v| !*v),
            "老内核结论下写进去的值只能是它自己的当前值 false，实际写了 {written:?}"
        );
        // 关键证据 ②：旧目录里**永远不出现新侧那个 '1'**
        assert_eq!(
            rd(&old.join("stealth")),
            b"0",
            "旧目录那份只能是内核自己的值，绝不能是新侧的 '1'"
        );
        // 关键证据 ③：新目录那份一个字节没动
        assert_eq!(rd(&new.join("stealth")), b"1");
    }

    #[test]
    fn probe_old_kernel_does_not_flip_stealth_off_when_new_side_says_zero() {
        // 危险方向：新侧留着一份 '0'（隐身关），老内核**当前是开着的** true。
        // 旧代码写新侧的值 ⇒ `SET_G(0)` ⇒ **把运行中老内核的隐身关掉 = 暴露** ✗。
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        fs::create_dir_all(&new).unwrap();
        write_file(&new.join("stealth"), b"0");
        // ⚠️ 两边都有效但不同 ⇒ 会先被判 Unknown（v2.10 的防线）—— 这里想让探测真的跑起来，
        //    所以把旧目录那份做成**坏值**（空文件），让"哪边有效"只剩新侧一份。
        write_file(&old.join("stealth"), b"");
        let old_kernel = FakeKernel::new(&old, true); // 老内核当前隐身是开的

        assert_eq!(
            probe_datadir_with(&old_kernel, &old, &new),
            DatadirProbe::Legacy
        );
        let written = old_kernel.set_values();
        assert!(
            written.iter().all(|v| *v),
            "老内核当前值是 true ⇒ 写进去的必须都是 true（绝不能写新侧的 0）实际 {written:?}"
        );
        assert_eq!(
            rd(&old.join("stealth")),
            b"1",
            "老内核的隐身必须保持开着（新侧的 '0' 绝不能落进旧目录）"
        );
    }

    #[test]
    fn probe_new_kernel_still_applies_authoritative_value() {
        // 反向：确认是**新内核**在跑时，"文件里那份权威值"才可以补写下去。
        // 场景：新内核在跑、新目录还没有 stealth、旧目录那份是 '1' ⇒ 必须补成 '1'，
        // 否则探测那次写的 '0'（内核默认值）会被后续迁移当成"新侧权威值" ⇒ 隐身被关掉。
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        fs::create_dir_all(&new).unwrap();
        let new_kernel = FakeKernel::new(&new, false); // 新内核当前是关的（新目录没有文件 = 默认关）

        assert_eq!(
            probe_datadir_with(&new_kernel, &old, &new),
            DatadirProbe::New
        );
        let written = new_kernel.set_values();
        assert_eq!(
            written,
            vec![false, true],
            "先同值写回探测，再补写权威值 true（实际 {written:?}）"
        );
        assert_eq!(rd(&new.join("stealth")), b"1", "权威值必须落进新目录");
        assert_eq!(rd(&old.join("stealth")), b"1", "旧目录一个字节没动");
    }


    // ── 关键文件判定（🔴B）──

    #[test]
    fn decide_key_file_matrix() {
        let complete = KeyInspect {
            quality: KeyQuality::Complete,
            len: 800,
        };
        let emptyish = KeyInspect {
            quality: KeyQuality::Emptyish,
            len: 8,
        };
        let broken = KeyInspect {
            quality: KeyQuality::Broken,
            len: 0,
        };
        // 空/截断的不算数 → 以完整的那份为准
        assert_eq!(decide_key_file(broken, complete, true), KeyChoice::TakeNew);
        assert_eq!(
            decide_key_file(complete, broken, true),
            KeyChoice::TakeLegacy
        );
        // 两边都完整但不同 → 保留新的（不覆盖、不删旧目录）
        assert_eq!(
            decide_key_file(complete, complete, true),
            KeyChoice::ConflictKeepNew
        );
        // "空名单"（结构完整但 0 条）只在首次全量迁移里算"明显偏小"
        assert_eq!(
            decide_key_file(complete, emptyish, true),
            KeyChoice::TakeLegacy
        );
        assert_eq!(
            decide_key_file(complete, emptyish, false),
            KeyChoice::ConflictKeepNew
        );
        // 都坏 → 谁都修不了
        assert_eq!(
            decide_key_file(broken, broken, true),
            KeyChoice::ConflictUnfixable
        );
    }

    #[test]
    fn allowlist_quality_matrix() {
        assert_eq!(allowlist_quality(&[]), KeyQuality::Broken, "0 字节");
        assert_eq!(
            allowlist_quality(&allowlist_bytes(0)),
            KeyQuality::Emptyish,
            "只有头 = 空名单"
        );
        assert_eq!(allowlist_quality(&allowlist_bytes(1)), KeyQuality::Complete);
        // 截断：少半个记录
        let mut cut = allowlist_bytes(2);
        cut.truncate(cut.len() - 7);
        assert_eq!(allowlist_quality(&cut), KeyQuality::Broken, "截断半个记录");
        // 头部被写坏
        let mut bad = allowlist_bytes(1);
        bad[0] ^= 0xFF;
        assert_eq!(allowlist_quality(&bad), KeyQuality::Broken, "magic 不对");
        let mut bad_ver = allowlist_bytes(1);
        bad_ver[4] = 99;
        assert_eq!(
            allowlist_quality(&bad_ver),
            KeyQuality::Broken,
            "version 离谱"
        );
    }

    #[test]
    fn allowlist_format_constants_match_kernel_source() {
        // 逐条跟**内核源码**对账（宿主上就能跑）。
        // ⚠️ 记录大小 `ALLOWLIST_RECORD_V4 = 784` 不在这里按字段手算（手算容易漏对齐填充：
        //    `root_profile` 实测 248 字节、`app_profile` 784 字节，bindgen 的布局断言为准）。
        //    它由文件顶部那条 `#[cfg(target_os = "android")] const _` **编译期**断言核对 ——
        //    跟着 `cargo ndk -t arm64-v8a check` 一起编，常量写错就编不过。
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        let allowlist_c =
            fs::read_to_string(root.join("kernel/policy/allowlist.c")).expect("读内核 allowlist.c");
        assert!(
            allowlist_c.contains("#define FILE_MAGIC 0x7f4b5355"),
            "内核 FILE_MAGIC 与我们不一致"
        );
        assert!(
            allowlist_c.contains(&format!(
                "#define FILE_FORMAT_VERSION {ALLOWLIST_MAX_VERSION}"
            )),
            "内核 FILE_FORMAT_VERSION 与我们不一致"
        );
        assert!(
            allowlist_c.contains(&format!("kAppProfileSizePreV4 = {ALLOWLIST_RECORD_PRE_V4}")),
            "内核 pre-v4 记录大小与我们不一致"
        );
        let app_profile_h =
            fs::read_to_string(root.join("uapi/app_profile.h")).expect("读 uapi 头");
        for needle in [
            format!("#define KSU_APP_PROFILE_VER {ALLOWLIST_MAX_VERSION}"),
            "#define KSU_MAX_PACKAGE_NAME 256".to_string(),
            "#define KSU_MAX_GROUPS 32".to_string(),
            "#define KSU_SELINUX_DOMAIN 64".to_string(),
        ] {
            assert!(
                app_profile_h.contains(&needle),
                "uapi/app_profile.h 里没有 {needle}"
            );
        }
    }

    #[test]
    fn stealth_and_code_quality() {
        assert_eq!(
            inspect_key_file(KeyKind::Stealth, Path::new("/nonexistent")).quality,
            KeyQuality::Broken
        );
        let t = tempfile::tempdir().unwrap();
        let p = t.path().join("stealth");
        write_file(&p, b"1");
        assert_eq!(
            inspect_key_file(KeyKind::Stealth, &p).quality,
            KeyQuality::Complete
        );
        write_file(&p, b"");
        assert_eq!(
            inspect_key_file(KeyKind::Stealth, &p).quality,
            KeyQuality::Broken
        );
        write_file(&p, b"10");
        assert_eq!(
            inspect_key_file(KeyKind::Stealth, &p).quality,
            KeyQuality::Broken
        );

        let c = t.path().join("stealth_code");
        write_file(&c, b"70707");
        assert_eq!(
            inspect_key_file(KeyKind::StealthCode, &c).quality,
            KeyQuality::Complete
        );
        write_file(&c, b"abc");
        assert_eq!(
            inspect_key_file(KeyKind::StealthCode, &c).quality,
            KeyQuality::Broken
        );
        write_file(&c, b"");
        assert_eq!(
            inspect_key_file(KeyKind::StealthCode, &c).quality,
            KeyQuality::Broken
        );
    }

    // ── 端到端：空/截断的关键文件必须被修好，而且旧目录要留着 ──

    #[test]
    fn empty_new_allowlist_is_repaired_from_legacy() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join(".allowlist"), &allowlist_bytes(3));
        write_file(&new.join(".allowlist"), b""); // 内核建目录时留下的空文件
        write_file(&new.join("webadmin.conf"), b"token=abc");

        let r = migrate(&old, &new).unwrap();
        assert_eq!(r.repaired, vec![".allowlist".to_string()], "{r:?}");
        assert!(r.conflicts.is_empty(), "{r:?}");
        assert!(!r.is_clean(), "修过关键文件 ⇒ 不删旧目录");
        assert!(!r.deleted_legacy);
        assert!(old.join(".allowlist").exists(), "旧目录必须留着");
        assert!(
            new.join(CONFLICT_MARKER).exists(),
            "要给人工排查留标记：{}",
            r.summary()
        );
        assert_eq!(
            rd(&new.join(".allowlist")),
            allowlist_bytes(3),
            "新目录那份必须变成完整的那份"
        );
        assert_eq!(rd(&new.join("webadmin.conf")), b"token=abc");
    }

    #[test]
    fn truncated_new_allowlist_is_repaired_from_legacy() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        let full = allowlist_bytes(4);
        write_file(&old.join(".allowlist"), &full);
        let mut cut = full.clone();
        cut.truncate(8 + ALLOWLIST_RECORD_V4 + 100); // 半个记录
        write_file(&new.join(".allowlist"), &cut);

        let r = migrate(&old, &new).unwrap();
        assert_eq!(r.repaired, vec![".allowlist".to_string()], "{r:?}");
        assert_eq!(rd(&new.join(".allowlist")), full);
        assert!(old.exists());
    }

    #[test]
    fn wellformed_empty_new_allowlist_is_adopted_on_first_pass_only() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        let full = allowlist_of(&[
            allowlist_record(10001, "com.a", true, 0x11),
            allowlist_record(10002, "com.b", true, 0x11),
        ]);
        write_file(&old.join(".allowlist"), &full);
        write_file(&new.join(".allowlist"), &allowlist_bytes(0)); // 结构完整但 0 条

        let r = migrate(&old, &new).unwrap();
        assert_eq!(r.repaired, vec![".allowlist".to_string()], "{r:?}");
        assert_eq!(
            rd(&new.join(".allowlist")),
            full,
            "首次全量迁移采纳有记录的那份"
        );
        assert_eq!(r.allowlist_adopted, 2, "{r:?}");

        // 之后再被内核写成"0 条"（比如用户真的把所有人撤销了）→ 体检模式**不再自动写盘**，
        // 只报数 + 记冲突（避免反复把用户刚撤销的授权又塞回去 ✗）；
        // 真要采纳必须由用户显式点「采纳并重启」（`--adopt-legacy-allowlist`）。
        write_file(&new.join(".allowlist"), &allowlist_bytes(0));
        let r2 = migrate(&old, &new).unwrap();
        assert!(r2.repaired.is_empty(), "{r2:?}");
        assert!(!r2.conflicts.is_empty(), "{r2:?}");
        assert_eq!(r2.allowlist_legacy_only, 2, "{r2:?}");
        assert_eq!(r2.allowlist_adopted, 0, "{r2:?}");
        assert_eq!(rd(&new.join(".allowlist")), allowlist_bytes(0));
        assert!(
            r2.summary().contains("2 条授权未被采用"),
            "{}",
            r2.summary()
        );

        // 用户显式采纳 ⇒ 写盘（写前留备份、写后逐字节校验）
        let r3 = migrate_with(&old, &new, true).unwrap();
        assert_eq!(r3.allowlist_adopted, 2, "{r3:?}");
        assert_eq!(r3.allowlist_legacy_only, 0, "{r3:?}");
        assert_eq!(
            rd(&new.join(".allowlist")),
            full,
            "采纳后必须与旧目录那份一致"
        );
        assert!(
            fs::read_dir(&new).unwrap().flatten().any(|e| e
                .file_name()
                .to_string_lossy()
                .starts_with(".allowlist.pre-merge-")),
            "写前必须留备份"
        );
    }

    #[test]
    fn both_complete_same_uid_different_profile_keeps_new_and_legacy() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        // 同一个 UID、但 profile 内容不同 ⇒ 并集合并判"同键以新为准"（不能把两条都留下）
        let new_rec = allowlist_record(10001, "com.a", true, 0x22);
        let old_rec = allowlist_record(10001, "com.a", false, 0x11);
        write_file(&old.join(".allowlist"), &allowlist_of(&[old_rec.clone()]));
        write_file(&new.join(".allowlist"), &allowlist_of(&[new_rec.clone()]));
        write_file(&new.join("keep"), b"k");

        let r = migrate(&old, &new).unwrap();
        assert!(!r.is_clean());
        assert!(!r.deleted_legacy, "有冲突时绝不能删旧目录");
        assert!(r.repaired.is_empty(), "{r:?}");
        assert!(!r.conflicts.is_empty(), "{r:?}");
        assert_eq!(r.allowlist_legacy_only, 0, "{r:?}");
        assert_eq!(
            rd(&new.join(".allowlist")),
            allowlist_of(&[new_rec]),
            "保留新目录那份"
        );
        assert_eq!(
            rd(&old.join(".allowlist")),
            allowlist_of(&[old_rec]),
            "旧目录那份不动"
        );
        assert!(new.join(CONFLICT_MARKER).exists());
    }

    #[test]
    fn broken_legacy_allowlist_is_ignored_and_migration_can_finish() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join(".allowlist"), b""); // 旧目录那份是空的
        write_file(&new.join(".allowlist"), &allowlist_bytes(2));

        let r = migrate(&old, &new).unwrap();
        assert_eq!(
            r.ignored_broken_legacy,
            vec![".allowlist".to_string()],
            "{r:?}"
        );
        assert!(r.is_clean(), "{r:?}");
        assert!(r.deleted_legacy, "{r:?}");
        assert_eq!(rd(&new.join(".allowlist")), allowlist_bytes(2));
    }

    #[test]
    fn empty_new_stealth_is_repaired_and_marker_path_repairs_too() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        write_file(&new.join("stealth"), b""); // 空的 stealth = 隐身默认关闭 = 暴露

        let r = migrate(&old, &new).unwrap();
        assert_eq!(r.repaired, vec!["stealth".to_string()], "{r:?}");
        assert_eq!(rd(&new.join("stealth")), b"1");
        assert!(new.join(CONFLICT_MARKER).exists());

        // 关键点：冲突标记存在时**也要**能修回（🔴B 的"自动流程永远修不回"）
        write_file(&new.join("stealth"), b"");
        let r2 = migrate(&old, &new).unwrap();
        assert_eq!(r2.repaired, vec!["stealth".to_string()], "{r2:?}");
        assert_eq!(rd(&new.join("stealth")), b"1");
        assert!(old.join("stealth").exists(), "旧目录仍然保留");
    }

    #[test]
    fn empty_new_stealth_code_is_repaired() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth_code"), b"70707");
        write_file(&new.join("stealth_code"), b"");
        write_file(&new.join("other"), b"x");

        let r = migrate(&old, &new).unwrap();
        assert_eq!(r.repaired, vec!["stealth_code".to_string()], "{r:?}");
        assert_eq!(rd(&new.join("stealth_code")), b"70707");
    }

    // ── 🟠2：标记模式下"新侧关键文件整个缺失"必须能从旧侧补回 ──

    /// 造一个"上次迁移有冲突"的状态：非关键文件内容不同 ⇒ 落冲突标记、保留旧目录
    fn seed_conflict_marker(old: &Path, new: &Path) {
        write_file(&old.join("webadmin.conf"), b"a=1");
        write_file(&new.join("webadmin.conf"), b"a=2");
        let r = migrate(old, new).unwrap();
        assert!(new.join(CONFLICT_MARKER).exists(), "{r:?}");
    }

    #[test]
    fn missing_new_key_file_is_restored_from_legacy_in_marker_mode() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        seed_conflict_marker(&old, &new);

        // 旧目录有、新目录整个没有（模拟上次复制时 ENOSPC/EIO 丢掉了 .allowlist）——
        // 标记存在 ⇒ 全量迁移不会再跑 ⇒ 老代码永远修不回。
        write_file(&old.join(".allowlist"), &allowlist_bytes(2));
        assert!(!new.join(".allowlist").exists());
        let r = migrate(&old, &new).unwrap();
        assert_eq!(r.repaired, vec![".allowlist".to_string()], "{r:?}");
        assert_eq!(
            rd(&new.join(".allowlist")),
            allowlist_bytes(2),
            "必须从旧目录把完整的那份复制过去"
        );
        assert!(
            old.join(".allowlist").exists(),
            "旧目录仍然保留（标记还在）"
        );
        assert!(r.failed.is_empty(), "{r:?}");

        // 幂等：补回之后再跑，不再重复修
        let r2 = migrate(&old, &new).unwrap();
        assert!(r2.repaired.is_empty(), "{r2:?}");
        assert_eq!(rd(&new.join(".allowlist")), allowlist_bytes(2));

        // stealth 也走同一条路（新侧整个缺失）
        write_file(&old.join("stealth"), b"1");
        let r3 = migrate(&old, &new).unwrap();
        assert_eq!(r3.repaired, vec!["stealth".to_string()], "{r3:?}");
        assert_eq!(rd(&new.join("stealth")), b"1");
        assert!(old.join("stealth").exists(), "旧目录那份原样保留");
    }

    #[test]
    fn missing_new_key_file_with_broken_legacy_is_only_reported() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        seed_conflict_marker(&old, &new);

        write_file(&old.join(".allowlist"), b""); // 旧目录那份空 = 不算数
        assert!(!new.join(".allowlist").exists());
        let r = migrate(&old, &new).unwrap();
        assert!(r.repaired.is_empty(), "空/截断的旧文件不敢往新侧写: {r:?}");
        assert!(
            r.conflicts.iter().any(|c| c.contains(".allowlist")),
            "{r:?}"
        );
        assert!(!new.join(".allowlist").exists(), "不能凭空造出空文件");
    }

    #[test]
    fn existing_new_key_file_with_missing_legacy_is_left_alone() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        seed_conflict_marker(&old, &new);

        // 新侧存在、旧侧缺失 ⇒ 不动作
        write_file(&new.join(".allowlist"), &allowlist_bytes(1));
        assert!(!old.join(".allowlist").exists());
        let r = migrate(&old, &new).unwrap();
        assert!(r.repaired.is_empty(), "{r:?}");
        assert!(
            !r.conflicts.iter().any(|c| c.contains(".allowlist")),
            "新侧存在旧侧缺失时不该报冲突: {r:?}"
        );
        assert_eq!(
            rd(&new.join(".allowlist")),
            allowlist_bytes(1),
            "新侧那份必须原样不动"
        );
    }

    #[test]
    fn both_sides_missing_key_file_is_recorded_without_panic() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        seed_conflict_marker(&old, &new);
        // 三个关键文件两边都没有 ⇒ 记 report（notes）、不崩、也不造文件
        let r = migrate(&old, &new).unwrap();
        assert!(r.repaired.is_empty(), "{r:?}");
        assert!(r.failed.is_empty(), "{r:?}");
        assert!(
            r.notes
                .iter()
                .filter(|n| n.contains("无法自动补回"))
                .count()
                == KEY_FILES.len(),
            "三个关键文件都要记进 report: {r:?}"
        );
        assert!(!new.join(".allowlist").exists());
        assert!(!new.join("stealth").exists());
        assert!(!new.join("stealth_code").exists());
    }

    #[test]
    fn stealth_conflict_keeps_new_value() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        write_file(&new.join("stealth"), b"0");
        let r = migrate(&old, &new).unwrap();
        assert!(!r.is_clean());
        assert_eq!(
            rd(&new.join("stealth")),
            b"0",
            "两边都完整 → 保留新目录那份"
        );
        assert!(old.join("stealth").exists());
    }

    #[test]
    fn owner_and_group_are_preserved_on_copy() {
        use std::os::unix::fs::{MetadataExt, PermissionsExt};
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth_code"), b"70707");
        fs::create_dir_all(&new).unwrap();
        let src = fs::metadata(old.join("stealth_code")).unwrap();

        let r = migrate(&old, &new).unwrap();
        assert!(r.is_clean(), "{r:?}");
        let dst = fs::metadata(new.join("stealth_code")).unwrap();
        assert_eq!(
            (src.uid(), src.gid()),
            (dst.uid(), dst.gid()),
            "uid/gid 要保住"
        );
        assert_eq!(src.permissions().mode(), dst.permissions().mode());
    }

    // ── 原有回归用例（改名搬运的基本行为）──

    #[test]
    fn rename_when_new_absent() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        write_file(&old.join("bin/busybox"), b"BUSYBOX");

        let r = migrate(&old, &new).unwrap();
        assert!(r.renamed, "{r:?}");
        assert!(r.summary().contains("原子改名成功"), "{}", r.summary());
        assert!(!old.exists(), "旧目录应该已经搬走");
        assert_eq!(rd(&new.join("stealth")), b"1");
        assert_eq!(rd(&new.join("bin/busybox")), b"BUSYBOX");
        assert!(rd(&new.join(DONE_MARKER)).len() > 0, "成功要落一个排查标记");
        // 绝不能在新旧之间留符号链接
        assert!(!path_exists(&old), "旧路径不能残留任何东西（包括符号链接）");
    }

    #[test]
    fn merge_copy_verify_then_delete() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth_code"), b"70707");
        write_file(&old.join("bin/ksud"), b"KSUD");
        write_file(&old.join("log/a.log"), b"aaa");
        // 新目录已存在（比如上一次搬了一半）且里面已经有无关文件
        write_file(&new.join("webadmin.conf"), b"token=abc");
        // 已经搬过去、内容一致的一份
        write_file(&new.join("bin/ksud"), b"KSUD");

        let r = migrate(&old, &new).unwrap();
        assert!(r.is_clean(), "{r:?}");
        assert!(r.deleted_legacy, "{r:?}");
        assert!(!old.exists());
        assert_eq!(r.already, 1);
        assert_eq!(r.copied, 2);
        assert_eq!(rd(&new.join("stealth_code")), b"70707");
        assert_eq!(rd(&new.join("log/a.log")), b"aaa");
        assert_eq!(
            rd(&new.join("webadmin.conf")),
            b"token=abc",
            "新目录原有文件不能被覆盖"
        );
    }

    #[test]
    fn conflict_keeps_legacy_and_writes_marker() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("webadmin.conf"), b"a=1");
        write_file(&new.join("webadmin.conf"), b"a=2"); // 非关键文件，内容不同 → 冲突

        let r = migrate(&old, &new).unwrap();
        assert!(!r.is_clean());
        assert!(!r.deleted_legacy, "有冲突时绝不能删旧目录");
        assert!(old.join("webadmin.conf").exists(), "旧目录必须原样留着");
        assert!(new.join(CONFLICT_MARKER).exists(), "应写冲突标记");
        assert!(r.summary().contains("保留"), "{}", r.summary());
        assert_eq!(
            rd(&new.join("webadmin.conf")),
            b"a=2",
            "冲突时保留新目录那份，不覆盖"
        );

        // 再跑一次：见到标记就跳过全量重扫，不再重复报冲突
        let r2 = migrate(&old, &new).unwrap();
        assert!(r2.copied == 0 && r2.already == 0, "{r2:?}");
        assert!(old.join("webadmin.conf").exists());
    }

    #[test]
    fn idempotent_after_success() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        let r = migrate(&old, &new).unwrap();
        assert!(r.renamed);
        let r2 = migrate(&old, &new).unwrap();
        assert!(r2.legacy_absent, "{r2:?}");
    }

    #[test]
    fn absent_legacy_is_noop() {
        let t = tempfile::tempdir().unwrap();
        let r = migrate(&t.path().join("nope"), &t.path().join("sevenk")).unwrap();
        assert!(r.legacy_absent);
        assert!(!r.deleted_legacy);
    }

    #[cfg(unix)]
    #[test]
    fn symlinks_are_copied_as_links_not_legacy_dir_links() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("real"), b"x");
        // 目录里本来就有个符号链接（KernelSU 的 bin/ksud -> /data/adb/ksud）
        fs::create_dir_all(&old).unwrap();
        std::os::unix::fs::symlink("/data/adb/ksud", old.join("bin_ksud")).unwrap();
        write_file(&new.join("keep"), b"k");

        let r = migrate(&old, &new).unwrap();
        assert!(r.is_clean(), "{r:?}");
        assert!(r.deleted_legacy);
        assert_eq!(
            fs::read_link(new.join("bin_ksud")).unwrap(),
            PathBuf::from("/data/adb/ksud")
        );
        // 旧路径不得残留
        assert!(fs::symlink_metadata(&old).is_err());
    }

    #[test]
    fn large_file_is_verified_in_chunks() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        let big: Vec<u8> = (0..300_000_u32).map(|i| (i % 251) as u8).collect();
        write_file(&old.join("big.bin"), &big);
        fs::create_dir_all(&new).unwrap();
        let r = migrate(&old, &new).unwrap();
        assert!(r.is_clean() && r.deleted_legacy, "{r:?}");
        assert_eq!(rd(&new.join("big.bin")), big);
    }

    #[test]
    fn constants_are_the_agreed_paths() {
        assert_eq!(LEGACY_DIR, "/data/adb/ksu");
        assert_eq!(NEW_DIR, "/data/adb/sevenk");
    }

    // ══════════════════════════════════════════════════════════════════
    // 门控结论缓存（2026-09-21 省电）：每次 `su` 都不该再探测/写盘
    // ══════════════════════════════════════════════════════════════════

    const REL: &str = "6.1.75-android14-test";

    fn key(kernel: Option<i32>, boot: &str) -> GateKey {
        GateKey::new(kernel, REL, boot)
    }

    /// 造一个"旧内核在跑"的现场（旧目录有完整数据，新目录还不存在）
    fn legacy_scene(t: &Path) -> (PathBuf, PathBuf, FakeKernel) {
        let old = t.join("ksu");
        let new = t.join("sevenk");
        write_file(&old.join("stealth"), b"1");
        write_file(&old.join(".allowlist"), &allowlist_bytes(2));
        // 老内核：数据目录还是 old（set() 会写到 old/stealth）
        let kernel = FakeKernel::new(&old, true);
        (old, new, kernel)
    }

    #[test]
    fn stamp_roundtrip_and_parse_garbage() {
        let s = GateStamp {
            verdict: StampVerdict::Legacy,
            kernel: Some(32123),
            osrelease: REL.to_string(),
            boot: "uuid-1".to_string(),
            at: 1000,
        };
        let back = GateStamp::parse(&s.render()).unwrap();
        assert_eq!(back, s);
        // 缺项 / 垃圾 ⇒ 一律 None（当作没缓存 ⇒ 重新探测）
        assert!(GateStamp::parse("").is_none());
        assert!(GateStamp::parse("verdict=legacy_datadir\n").is_none());
        assert!(GateStamp::parse("verdict=??\nboot=x\nat=1\n").is_none());
        assert!(GateStamp::parse("verdict=checked\nboot=x\nosrelease=y\n").is_none());
    }

    #[test]
    fn stamp_freshness_rules() {
        let k = key(Some(32123), "boot-a");
        let legacy = GateStamp {
            verdict: StampVerdict::Legacy,
            kernel: Some(32123),
            osrelease: REL.to_string(),
            boot: "boot-a".to_string(),
            at: 0,
        };
        // 同内核 + 同开机 ⇒ 一直有效（不受 TTL 限制）
        assert!(legacy.is_fresh(&k, 10 * STAMP_TTL_SECS));
        // 换了内核版本（LKM 热加载）⇒ 立刻失效
        assert!(!legacy.is_fresh(&key(Some(32124), "boot-a"), 0));
        // 重启（boot_id 变）⇒ 失效
        assert!(!legacy.is_fresh(&key(Some(32123), "boot-b"), 0));
        // uname -r 变了 ⇒ 失效
        let mut other_rel = legacy.clone();
        other_rel.osrelease = "6.6.1".to_string();
        assert!(!other_rel.is_fresh(&k, 0));
        // 拿不到内核版本号 ⇒ **绝不复用"旧内核"结论**（可能是 LKM 换过内核）
        let no_ver = GateStamp {
            kernel: None,
            ..legacy.clone()
        };
        assert!(!no_ver.is_fresh(&key(None, "boot-a"), 0));
        assert!(!legacy.is_fresh(&key(None, "boot-a"), 0));
        // boot_id 读不到 ⇒ 也不复用（安全方向）
        assert!(!legacy.is_fresh(&key(Some(32123), ""), 0));

        // 体检结论：同开机 + 1 小时内有效，过期就重做
        let checked = GateStamp {
            verdict: StampVerdict::Checked,
            kernel: Some(32123),
            osrelease: REL.to_string(),
            boot: "boot-a".to_string(),
            at: 1000,
        };
        assert!(checked.is_fresh(&k, 1000 + STAMP_TTL_SECS));
        assert!(!checked.is_fresh(&k, 1001 + STAMP_TTL_SECS));
    }

    /// 🔴 本优化的核心断言：第二次调用**不该**再让内核写 stealth。
    #[test]
    fn second_call_does_not_probe_again() {
        let t = tempfile::tempdir().unwrap();
        let (old, new, kernel) = legacy_scene(t.path());
        let k = key(Some(32123), "boot-a");

        let r1 = auto_migrate_with(&kernel, &old, &new, Some(&k), 1000).unwrap();
        assert!(r1.skipped_not_new_kernel, "{r1:?}");
        assert_eq!(r1.kernel_probe, Some("legacy_datadir"));
        assert_eq!(
            kernel.set_count(),
            1,
            "第一次必须真探测（内核写一次 stealth）"
        );
        assert!(new.join(GATE_STAMP_FILE).exists(), "结论应落盘");

        let r2 = auto_migrate_with(&kernel, &old, &new, Some(&k), 1001).unwrap();
        assert!(r2.cached, "{r2:?}");
        assert_eq!(
            kernel.set_count(),
            1,
            "第二次必须**一次都不探测**（这就是省电的关键）"
        );
        // 旧目录一个字节都没动
        assert_eq!(rd(&old.join(".allowlist")), allowlist_bytes(2));
    }

    #[test]
    fn cache_invalidated_by_reboot_and_by_new_kernel() {
        let t = tempfile::tempdir().unwrap();
        let (old, new, kernel) = legacy_scene(t.path());
        let k1 = key(Some(32123), "boot-a");
        auto_migrate_with(&kernel, &old, &new, Some(&k1), 1000).unwrap();
        assert_eq!(kernel.set_count(), 1);

        // 重启 ⇒ 重新探测
        let k2 = key(Some(32123), "boot-b");
        let r = auto_migrate_with(&kernel, &old, &new, Some(&k2), 2000).unwrap();
        assert!(!r.cached);
        assert_eq!(kernel.set_count(), 2, "换开机必须重新探测");

        // 内核换了（版本号变）⇒ 重新探测
        let k3 = key(Some(32124), "boot-b");
        let r = auto_migrate_with(&kernel, &old, &new, Some(&k3), 2001).unwrap();
        assert!(!r.cached);
        assert_eq!(kernel.set_count(), 3, "换内核必须重新探测");
    }

    /// 拿不准（`unknown`）**绝不缓存**：老内核/ioctl 偶发失败必须继续重试。
    #[test]
    fn unknown_verdict_is_never_cached() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        fs::create_dir_all(&new).unwrap();
        let kernel = FakeKernel::unsupported(&old);
        let k = key(Some(32123), "boot-a");

        let r1 = auto_migrate_with(&kernel, &old, &new, Some(&k), 1000).unwrap();
        assert_eq!(r1.kernel_probe, Some("unknown"), "{r1:?}");
        let r2 = auto_migrate_with(&kernel, &old, &new, Some(&k), 1001).unwrap();
        assert!(!r2.cached, "拿不准不能缓存：{r2:?}");
    }

    /// 显式 `ksud migrate`（`key = None`）永远走真流程，而且不写缓存。
    #[test]
    fn explicit_migrate_bypasses_cache() {
        let t = tempfile::tempdir().unwrap();
        let (old, new, kernel) = legacy_scene(t.path());
        let k = key(Some(32123), "boot-a");
        auto_migrate_with(&kernel, &old, &new, Some(&k), 1000).unwrap();
        assert_eq!(kernel.set_count(), 1);

        let r = auto_migrate_with(&kernel, &old, &new, None, 1001).unwrap();
        assert!(!r.cached);
        assert_eq!(kernel.set_count(), 2, "显式命令必须每次都真探测");
    }

    /// 冲突标记路径：第一次补写标记，之后**同样的冲突不再重复追加**（旧的实现每次 `su` 涨 ~150 字节）。
    #[test]
    fn conflict_marker_does_not_grow_on_every_run() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        // 两边都是"完整"但内容不同的 stealth ⇒ 修不了的冲突（真机上就是这个形状：
        // 新目录 '1' / 旧目录 '0'）
        write_file(&old.join("stealth"), b"0");
        write_file(&new.join("stealth"), b"1");
        write_file(&old.join(ALLOWLIST_FILE), &allowlist_bytes(2));
        write_file(&new.join(ALLOWLIST_FILE), &allowlist_bytes(2));

        let mut r1 = Report::default();
        r1.conflicts
            .push("stealth: 两边都完整但内容不同".to_string());
        write_conflict_marker(&new, &mut r1);
        let after_first = rd(&new.join(CONFLICT_MARKER));
        assert!(String::from_utf8_lossy(&after_first).contains("stealth"));

        for _ in 0..5 {
            let mut r = Report::default();
            r.conflicts
                .push("stealth: 两边都完整但内容不同".to_string());
            write_conflict_marker(&new, &mut r);
        }
        assert_eq!(
            rd(&new.join(CONFLICT_MARKER)),
            after_first,
            "同样的冲突重复上报 5 次，标记文件必须**一个字节都不涨**"
        );

        // 新冲突还是要记进去（不能因为"省写"把新信息丢了）
        let mut r = Report::default();
        r.conflicts.push("另一个新冲突".to_string());
        write_conflict_marker(&new, &mut r);
        let body = String::from_utf8_lossy(&rd(&new.join(CONFLICT_MARKER))).to_string();
        assert!(body.contains("另一个新冲突"));
        assert!(body.contains("stealth"));
    }

    /// 冲突标记存在时：第一次体检（并做一次门控探测以决定兼容软链能否接管老目录），
    /// 之后同开机内直接命中缓存。
    ///
    /// ⚠️ v2.9 行为变化（**故意的**）：老代码在这里断言"有冲突标记时不做门控探测"，
    /// 为的是省一次闪存写。但 v2.9 的兼容软链必须知道"内核读的是不是新目录"
    /// 才敢把有内容的老目录改名腾路径 —— 所以"结论不明"时**必须探一次**。
    /// 探到的结论会以明确的 `New`/`Legacy` 落盘 ⇒ **最多探一次**（下面 r2 就是证明）。
    #[test]
    fn conflict_path_probes_once_then_is_cached() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        // 两边都是"完整"但值不同的 stealth ⇒ 探测拿不准（Unknown），而且**一个字节都不写**
        write_file(&old.join("stealth"), b"0");
        write_file(&new.join("stealth"), b"1");
        write_file(&old.join(ALLOWLIST_FILE), &allowlist_bytes(2));
        write_file(&new.join(ALLOWLIST_FILE), &allowlist_bytes(2));
        write_file(&new.join(CONFLICT_MARKER), "冲突: stealth\n".as_bytes());

        let kernel = FakeKernel::new(&new, true);
        let k = key(Some(32123), "boot-a");
        let r1 = auto_migrate_with(&kernel, &old, &new, Some(&k), 1000).unwrap();
        assert!(!r1.cached);
        // 探测跑了，但"两边 stealth 有效且不同"这条路**不写盘** ⇒ set 次数仍是 0
        assert_eq!(kernel.set_count(), 0, "拿不准的探测不写盘");
        assert_eq!(r1.kernel_probe, Some("unknown"), "{r1:?}");
        // 拿不准 ⇒ **不接管**老目录（老目录一个字节没动、也没有软链）
        assert_eq!(
            r1.compat_link,
            Some(CompatLink::BlockedNotNewKernel),
            "{r1:?}"
        );
        assert_eq!(rd(&old.join(".allowlist")), allowlist_bytes(2));
        assert!(!old.is_symlink(), "拿不准时绝不把老目录换成软链");

        let r2 = auto_migrate_with(&kernel, &old, &new, Some(&k), 1001).unwrap();
        assert!(r2.cached, "{r2:?}");
        assert_eq!(kernel.set_count(), 0, "缓存命中 ⇒ 不再探测");
        // v2.9：`Unknown` 也落成 `Legacy`（故意的）⇒ "老目录还占着路径"这个过渡状态
        // 不会变成"每次 su 都重探一遍"。
        let stamp = read_stamp(&new).expect("结论应落盘");
        assert_eq!(stamp.verdict, StampVerdict::Legacy);

        // 重启（boot_id 变）⇒ 缓存作废，重新探
        let k2 = key(Some(32123), "boot-b");
        let r3 = auto_migrate_with(&kernel, &old, &new, Some(&k2), 2000).unwrap();
        assert!(!r3.cached);
    }

    // ══════════════════════════════════════════════════════════════════
    // 🟢（v2.9）兼容软链：/data/adb/ksu -> /data/adb/sevenk
    // ══════════════════════════════════════════════════════════════════
    //
    // 这一组单测就是"第三方模块（Zygisk Next 等）写死的旧路径能不能用"的回归闸门。

    /// 情形 1：旧路径**不存在**（v2.8 老用户升级的主路径）⇒ 直接建软链
    #[test]
    fn compat_link_created_when_legacy_absent() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        fs::create_dir_all(&new).unwrap();

        assert_eq!(
            ensure_compat_link_at(&old, &new, false, 1000),
            CompatLink::Created
        );
        assert!(old.is_symlink(), "旧路径必须变成软链");
        assert_eq!(fs::read_link(&old).unwrap(), new);
        // 顺着软链能读到新目录里的东西（这就是第三方模块看到的效果）
        write_file(&new.join("bin").join("ksud"), b"x");
        assert!(old.join("bin").join("ksud").exists());
    }

    /// 情形 2：已经是正确的软链 ⇒ 幂等跳过，**一个字节都不写**
    #[test]
    fn compat_link_is_idempotent() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        fs::create_dir_all(&new).unwrap();
        assert_eq!(
            ensure_compat_link_at(&old, &new, false, 1000),
            CompatLink::Created
        );
        let before = fs::symlink_metadata(&old).unwrap().modified().unwrap();

        assert_eq!(
            ensure_compat_link_at(&old, &new, true, 2000),
            CompatLink::AlreadyLinked
        );
        let after = fs::symlink_metadata(&old).unwrap().modified().unwrap();
        assert_eq!(before, after, "幂等跳过时不能碰软链");
        assert_eq!(fs::read_link(&old).unwrap(), new);
    }

    /// 情形 3：旧路径是**空目录**（没数据可丢）⇒ 换成软链
    #[test]
    fn compat_link_replaces_empty_real_dir() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        fs::create_dir_all(&old).unwrap();
        fs::create_dir_all(&new).unwrap();

        assert_eq!(
            ensure_compat_link_at(&old, &new, false, 1000),
            CompatLink::ReplacedEmptyDir
        );
        assert!(old.is_symlink());
        assert_eq!(fs::read_link(&old).unwrap(), new);
    }

    /// 情形 4a：旧路径是**有内容的真实目录**、但**没确认新内核** ⇒ 不动（安全方向）
    #[test]
    fn compat_link_never_touches_nonempty_legacy_without_new_kernel() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join(".allowlist"), &allowlist_bytes(3));
        write_file(&old.join("stealth"), b"1");
        fs::create_dir_all(&new).unwrap();

        assert_eq!(
            ensure_compat_link_at(&old, &new, false, 1000),
            CompatLink::BlockedNotNewKernel
        );
        assert!(old.is_dir() && !old.is_symlink(), "老目录必须原样保留");
        assert_eq!(rd(&old.join(".allowlist")), allowlist_bytes(3));
        // 也没留下任何备份目录
        assert!(!t.path().join("ksu.legacy_backup_1000").exists());
    }

    /// 情形 4b：旧路径是**有内容的真实目录**、且**确认新内核** ⇒
    /// 改名留存（**一个字节都不删**）+ 建软链
    #[test]
    fn compat_link_takes_over_nonempty_legacy_on_new_kernel() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join(".allowlist"), &allowlist_bytes(3));
        write_file(&old.join("stealth"), b"1");
        write_file(&old.join("webadmin.conf"), b"token=abc");
        fs::create_dir_all(&new).unwrap();

        let link = ensure_compat_link_at(&old, &new, true, 1000);
        let CompatLink::TookOverLegacy(backup) = &link else {
            panic!("应当接管：{link:?}");
        };
        // 软链就位
        assert!(old.is_symlink(), "老路径必须变成软链");
        assert_eq!(fs::read_link(&old).unwrap(), new);
        // 老数据**一个字节都没丢**（只是改了名）
        let backup = PathBuf::from(backup);
        assert!(backup.is_dir(), "留存目录必须存在");
        assert_eq!(rd(&backup.join(".allowlist")), allowlist_bytes(3));
        assert_eq!(rd(&backup.join("stealth")), b"1");
        assert_eq!(rd(&backup.join("webadmin.conf")), b"token=abc");
        assert!(link.changed());

        // 再来一次 ⇒ 幂等
        assert_eq!(
            ensure_compat_link_at(&old, &new, true, 2000),
            CompatLink::AlreadyLinked
        );
    }

    /// 情形 4c：改名留存时**时间戳撞车**要自动换一个名字（绝不覆盖别人的目录）
    #[test]
    fn compat_backup_path_never_collides() {
        let t = tempfile::tempdir().unwrap();
        let legacy = t.path().join("ksu");
        write_file(&legacy.join("a"), b"1");
        let p1 = free_backup_path(&legacy, 1000);
        assert_eq!(p1, t.path().join("ksu.legacy_backup_1000"));
        fs::create_dir_all(&p1).unwrap();
        let p2 = free_backup_path(&legacy, 1000);
        assert_eq!(p2, t.path().join("ksu.legacy_backup_1000_2"));
        fs::create_dir_all(&p2).unwrap();
        let p3 = free_backup_path(&legacy, 1000);
        assert_eq!(p3, t.path().join("ksu.legacy_backup_1000_3"));
    }

    // ── 🔴1（v2.17）：软链"目标相同"的判据 —— **读失败绝不算相同** ──

    /// 判据本体：(Err, Err) / (Ok, Err) / (Err, Ok) 一律**不得**判相同。
    ///
    /// 为什么测这个纯函数而不是直接造目录：真实的 `read_link` 在"软链存在"时
    /// （`lstat` 成功）几乎不会失败，宿主文件系统上没法自然构造出 `(Err, Err)`，
    /// 所以把判据抽成 [`symlink_targets_equal`] 后在这里逐组合钉死。
    #[test]
    fn symlink_targets_equal_requires_both_reads_to_succeed() {
        use std::path::PathBuf;
        let ok = |s: &str| -> std::io::Result<PathBuf> { Ok(PathBuf::from(s)) };
        let err = || -> std::io::Result<PathBuf> {
            Err(std::io::Error::from(std::io::ErrorKind::PermissionDenied))
        };

        // 正常路径不变：都成功且相等才算相同
        assert!(symlink_targets_equal(
            ok("/data/adb/sevenk"),
            ok("/data/adb/sevenk")
        ));
        assert!(!symlink_targets_equal(
            ok("/data/adb/sevenk"),
            ok("/data/adb/ksu")
        ));

        // 修前（`read_link(a).ok() == read_link(b).ok()`）：两边都失败 = `None == None` = 相同 ✗
        let (a, b) = (err(), err());
        assert!(
            a.ok() == b.ok(),
            "旧写法在 (Err, Err) 时确实判'相同' —— 这就是这个 bug 的本体"
        );
        // 修后：三种"读失败"组合一律不同（不确定 ⇒ 不复用备份 ⇒ 绝不 remove_dir_all）
        assert!(!symlink_targets_equal(err(), err()), "(Err, Err) 不得判相同");
        assert!(
            !symlink_targets_equal(ok("/data/adb/sevenk"), err()),
            "(Ok, Err) 不得判相同"
        );
        assert!(
            !symlink_targets_equal(err(), ok("/data/adb/sevenk")),
            "(Err, Ok) 不得判相同"
        );
    }

    /// 真实文件系统上的行为回归：目标相同 ⇒ 仍然判相同（修 🔴1 不能把"真的相同"改坏）。
    #[cfg(unix)]
    #[test]
    fn trees_equal_still_matches_identical_symlink_targets() {
        let t = tempfile::tempdir().unwrap();
        let a = t.path().join("a");
        let b = t.path().join("b");
        let target = t.path().join("target");
        fs::create_dir_all(&target).unwrap();
        for d in [&a, &b] {
            fs::create_dir_all(d).unwrap();
            write_file(&d.join("same"), b"hello");
            std::os::unix::fs::symlink(&target, d.join("link")).unwrap();
        }
        assert!(trees_equal(&a, &b), "软链目标相同 + 普通文件相同 ⇒ 必须判相同");

        // 目标不同 ⇒ 判不同（这条旧代码本来就对，留作回归）
        let other = t.path().join("other");
        fs::create_dir_all(&other).unwrap();
        fs::remove_file(b.join("link")).unwrap();
        std::os::unix::fs::symlink(&other, b.join("link")).unwrap();
        assert!(!trees_equal(&a, &b), "软链目标不同 ⇒ 必须判不同");
    }

    /// 情形 5a：旧路径是**指向别处的软链** ⇒ 不猜、不动
    #[test]
    fn compat_link_leaves_foreign_symlink_alone() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        let elsewhere = t.path().join("somewhere-else");
        fs::create_dir_all(&elsewhere).unwrap();
        fs::create_dir_all(&new).unwrap();
        std::os::unix::fs::symlink(&elsewhere, &old).unwrap();

        let link = ensure_compat_link_at(&old, &new, true, 1000);
        assert!(matches!(link, CompatLink::Foreign(_)), "{link:?}");
        assert_eq!(fs::read_link(&old).unwrap(), elsewhere, "别人的软链不能改");
        assert!(elsewhere.is_dir());
    }

    /// 情形 5b：旧路径是**普通文件** ⇒ 不猜、不动
    #[test]
    fn compat_link_leaves_other_types_alone() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old, b"not a dir");
        fs::create_dir_all(&new).unwrap();

        let link = ensure_compat_link_at(&old, &new, true, 1000);
        assert!(matches!(link, CompatLink::Foreign(_)), "{link:?}");
        assert!(old.is_file(), "必须原样保留");
        assert_eq!(rd(&old), b"not a dir");
    }

    /// 新目录本身不存在（还没迁移）⇒ **不乱建软链**（指向不存在的东西没有意义）
    #[test]
    fn compat_link_skipped_when_new_dir_absent() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        let link = ensure_compat_link_at(&old, &new, false, 1000);
        assert!(matches!(link, CompatLink::Skipped(_)), "{link:?}");
        assert!(!old.exists());
    }

    // ══════════════════════════════════════════════════════════════════
    // 🔴1（v2.10 回归闸门）：悬空软链自愈 + 卸载时只删我们那条软链
    // ══════════════════════════════════════════════════════════════════

    /// 🔴1 回归闸门：**悬空软链必须自愈**。
    ///
    /// 现场 = `ksud uninstall`（v2.9）之后的机器：`/data/adb/sevenk` 被删掉了，
    /// `/data/adb/ksu` 那条软链却留在原地 ⇒ 指向不存在目标的死链。
    /// v2.9 的写法在这里**无条件早返回 `AlreadyLinked`** ⇒ 死链永久留着、第三方全 ENOENT ✗。
    #[test]
    fn compat_link_repairs_dangling_symlink() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        // 造一条**悬空**软链：指向 new，但 new 还不存在
        std::os::unix::fs::symlink(&new, &old).unwrap();
        assert!(old.is_symlink());
        assert!(!new.exists(), "前置条件：目标不存在 ⇒ 这就是死链");

        // ① 必须当场自愈：删死链 + 补回空的新目录 + 重建软链
        let link = ensure_compat_link_at(&old, &new, false, 1000);
        assert_eq!(
            link,
            CompatLink::DeadLinkRepaired,
            "死链必须被自愈：{link:?}"
        );
        assert!(link.changed(), "改了盘就必须让调用方知道（日志靠它刷出来）");
        assert!(old.is_symlink(), "旧路径必须重新是一条软链");
        assert_eq!(fs::read_link(&old).unwrap(), new);
        assert!(new.is_dir(), "新目录必须被补回（否则新链还是悬空的）");
        // 第三方顺着旧路径真的能读到新目录里的东西 —— 这才是"修好了"的定义
        write_file(&new.join("bin").join("ksud"), b"x");
        assert!(old.join("bin").join("ksud").exists());
        // 只补了一个**空目录**：里面除了我们刚写的 bin/，没有任何别的（绝不清/建别的数据）
        assert_eq!(fs::read_dir(&new).unwrap().count(), 1);

        // ② 幂等：再来一次必须零写入（每次 `su` 都走这条）
        let before = fs::symlink_metadata(&old).unwrap().modified().unwrap();
        assert_eq!(
            ensure_compat_link_at(&old, &new, false, 2000),
            CompatLink::AlreadyLinked
        );
        assert_eq!(
            fs::symlink_metadata(&old).unwrap().modified().unwrap(),
            before,
            "健康路径不能碰软链"
        );
    }

    /// 🔴1：死链自愈**绝不能覆盖真实数据** —— 目标是普通文件时只删死链、文件一个字节不动。
    #[test]
    fn compat_dead_link_repair_never_overwrites_real_data() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        // 新路径上是个**普通文件**（不是目录）—— 不知道是谁的，绝不能删/覆盖
        write_file(&new, b"i am a real file");
        std::os::unix::fs::symlink(&new, &old).unwrap();

        let link = ensure_compat_link_at(&old, &new, false, 1000);
        assert!(matches!(link, CompatLink::Skipped(_)), "{link:?}");
        assert_eq!(rd(&new), b"i am a real file", "真实文件一个字节都不能动");
        assert!(
            fs::symlink_metadata(&old).is_err(),
            "死链本身应当已经删掉（留着只会继续误导第三方）"
        );
    }

    /// 🔴1：卸载专用 —— **只删我们那条软链**，真实目录 / 别人的软链一律不碰。
    #[test]
    fn remove_compat_link_only_removes_our_symlink() {
        // ① 我们的软链（`old -> new`）⇒ 删掉；新目录（真实数据）不受影响
        {
            let t = tempfile::tempdir().unwrap();
            let old = t.path().join("ksu");
            let new = t.path().join("sevenk");
            fs::create_dir_all(&new).unwrap();
            write_file(&new.join("stealth"), b"1");
            std::os::unix::fs::symlink(&new, &old).unwrap();
            assert_eq!(
                remove_compat_link_at(&old, &new),
                CompatLinkRemoval::Removed
            );
            assert!(fs::symlink_metadata(&old).is_err(), "软链必须被删掉");
            assert_eq!(rd(&new.join("stealth")), b"1", "新目录里的数据一个字节不动");
        }
        // ② 指向**别处**的软链 ⇒ 不碰（那是别人的）
        {
            let t = tempfile::tempdir().unwrap();
            let old = t.path().join("ksu");
            let new = t.path().join("sevenk");
            let elsewhere = t.path().join("somewhere-else");
            fs::create_dir_all(&new).unwrap();
            fs::create_dir_all(&elsewhere).unwrap();
            std::os::unix::fs::symlink(&elsewhere, &old).unwrap();
            assert!(matches!(
                remove_compat_link_at(&old, &new),
                CompatLinkRemoval::Kept(_)
            ));
            assert_eq!(fs::read_link(&old).unwrap(), elsewhere);
        }
        // ③ 真实目录（有内容）⇒ 不碰
        {
            let t = tempfile::tempdir().unwrap();
            let old = t.path().join("ksu");
            let new = t.path().join("sevenk");
            fs::create_dir_all(&new).unwrap();
            write_file(&old.join(ALLOWLIST_FILE), &allowlist_bytes(2));
            assert!(matches!(
                remove_compat_link_at(&old, &new),
                CompatLinkRemoval::Kept(_)
            ));
            assert_eq!(rd(&old.join(ALLOWLIST_FILE)), allowlist_bytes(2));
        }
        // ④ 什么都没有 ⇒ Absent（正常情况）
        {
            let t = tempfile::tempdir().unwrap();
            let old = t.path().join("ksu");
            let new = t.path().join("sevenk");
            fs::create_dir_all(&new).unwrap();
            assert_eq!(remove_compat_link_at(&old, &new), CompatLinkRemoval::Absent);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 🔴2（v2.10 回归闸门）：旧管理器把软链顶成真实目录 ⇒ 按安全规则重处理
    // ══════════════════════════════════════════════════════════════════

    /// 🔴2 回归闸门（端到端）：旧管理器的 ksud 用旧路径常量，`install()` 会
    /// `rm -rf /data/adb/ksu` 再建目录 ⇒ 把兼容软链顶成真实目录。
    /// 新内核在跑（门控缓存命中）⇒ 必须**改名留存 + 重建软链**，一个字节都不删。
    #[test]
    fn auto_migrate_reasserts_link_clobbered_by_legacy_manager() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        // 正常状态（v2.9）：兼容软链就位、新目录有数据
        write_file(&new.join("stealth"), b"1");
        write_file(&new.join(ALLOWLIST_FILE), &allowlist_bytes(1));
        assert_eq!(
            ensure_compat_link_at(&old, &new, false, 900),
            CompatLink::Created
        );
        // 门控结论缓存：上次已确认"新内核在跑"（真机上每次 `su` 走的就是这条捷径）
        write_stamp(
            &new,
            &GateStamp {
                verdict: StampVerdict::New,
                kernel: Some(32123),
                osrelease: REL.to_string(),
                boot: "boot-a".to_string(),
                at: 1000,
            },
        );
        // 🔴2 现场：旧管理器 `rm -rf /data/adb/ksu` + `mkdir` + 写它自己那份 ksud
        fs::remove_file(&old).unwrap();
        assert!(!old.exists());
        write_file(&old.join("bin").join("ksud"), b"old-manager-ksud");

        let kernel = FakeKernel::new(&new, true);
        let k = key(Some(32123), "boot-a");
        let r = auto_migrate_with(&kernel, &old, &new, Some(&k), 2000).unwrap();

        // ① 旧路径必须**重新**变成指向新目录的软链
        assert!(old.is_symlink(), "被顶掉的软链必须被复检修回：{r:?}");
        assert_eq!(fs::read_link(&old).unwrap(), new);
        // ② 老管理器写进去的东西**一个字节都没删** —— 整目录改名留存
        let Some(CompatLink::TookOverLegacy(backup)) = r.compat_link.clone() else {
            panic!("必须按安全规则接管（改名留存 + 建软链）：{r:?}");
        };
        assert_eq!(
            rd(&PathBuf::from(&backup).join("bin").join("ksud")),
            b"old-manager-ksud",
            "真实数据一个字节都不能丢"
        );
        // ③ 新目录里的关键文件没被动过
        assert_eq!(rd(&new.join("stealth")), b"1");
        assert_eq!(rd(&new.join(ALLOWLIST_FILE)), allowlist_bytes(1));
    }

    /// 🔴2 回归闸门（直接驱动复检本体）：`reassert_compat_link` 必须**守住门控** ——
    /// 已知新内核才接管（改名留存 + 建软链）；结论未知则一个字节都不动（v2.9 铁律）。
    #[test]
    fn reassert_compat_link_respects_kernel_gate() {
        // ① 已知新内核 ⇒ 接管
        {
            let t = tempfile::tempdir().unwrap();
            let old = t.path().join("ksu");
            let new = t.path().join("sevenk");
            fs::create_dir_all(&new).unwrap();
            write_file(&old.join("bin").join("ksud"), b"old-manager-ksud");
            let mut r = Report {
                kernel_probe: Some(DatadirProbe::New.as_str()),
                compat_link: Some(CompatLink::AlreadyLinked),
                ..Report::default()
            };
            reassert_compat_link(&mut r, &old, &new, 1000);
            assert!(old.is_symlink(), "确认新内核 ⇒ 必须接管：{r:?}");
            assert!(
                matches!(r.compat_link, Some(CompatLink::TookOverLegacy(_))),
                "{r:?}"
            );
            assert!(
                r.notes.iter().any(|n| n.contains("🔴2 复检")),
                "必须留下一条体检信息（App 侧的 `ksud migrate` 能看到）：{:?}",
                r.notes
            );
        }
        // ② 没探测过（拿不准）⇒ 一个字节不动
        {
            let t = tempfile::tempdir().unwrap();
            let old = t.path().join("ksu");
            let new = t.path().join("sevenk");
            fs::create_dir_all(&new).unwrap();
            write_file(&old.join("bin").join("ksud"), b"old-manager-ksud");
            let mut r = Report {
                compat_link: Some(CompatLink::AlreadyLinked),
                ..Report::default()
            };
            reassert_compat_link(&mut r, &old, &new, 1000);
            assert!(old.is_dir() && !old.is_symlink(), "拿不准 ⇒ 绝不动老目录");
            assert_eq!(rd(&old.join("bin").join("ksud")), b"old-manager-ksud");
            assert!(
                matches!(r.compat_link, Some(CompatLink::BlockedNotNewKernel)),
                "{r:?}"
            );
        }
        // ③ 健康（已经是软链）⇒ 静默、零改动
        {
            let t = tempfile::tempdir().unwrap();
            let old = t.path().join("ksu");
            let new = t.path().join("sevenk");
            fs::create_dir_all(&new).unwrap();
            std::os::unix::fs::symlink(&new, &old).unwrap();
            let mut r = Report {
                compat_link: Some(CompatLink::AlreadyLinked),
                ..Report::default()
            };
            reassert_compat_link(&mut r, &old, &new, 1000);
            assert_eq!(r.compat_link, Some(CompatLink::AlreadyLinked));
            assert!(
                r.notes.is_empty(),
                "健康路径不能留下任何笔记：{:?}",
                r.notes
            );
        }
    }

    /// 🔴1 + 入口：`auto_migrate_with` 走 `LinkedToNew` 早返回时，也必须能修死链
    /// （v2.9 的早返回直接报 `AlreadyLinked`，把死链永久跳过 ✗）。
    #[test]
    fn auto_migrate_heals_dangling_symlink_on_the_cheap_path() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        std::os::unix::fs::symlink(&new, &old).unwrap();
        let kernel = FakeKernel::new(&new, true);
        let k = key(Some(32123), "boot-a");

        let r = auto_migrate_with(&kernel, &old, &new, Some(&k), 1000).unwrap();
        assert_eq!(
            r.compat_link,
            Some(CompatLink::DeadLinkRepaired),
            "死链必须在每次 `su` 的主路径上被修掉：{r:?}"
        );
        assert!(old.is_symlink());
        assert!(new.is_dir());
    }

    /// 端到端（v2.9 的核心验收）：老用户机器 —— 数据已经在 `sevenk`、
    /// `ksu` 上还留着一个**有内容的真实老目录** ⇒ 一次 `auto_migrate` 之后
    /// 旧路径必须变成软链，而且老数据一个字节都没丢。
    #[test]
    fn auto_migrate_takes_over_legacy_dir_and_links_it() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        // 新目录是"当前状态"（新内核在跑，写的是它）
        write_file(&new.join("stealth"), b"1");
        write_file(&new.join(ALLOWLIST_FILE), &allowlist_bytes(1));
        // 老目录留着改名前的存量（两边 .allowlist 都能读、但内容不同 ⇒ 迁移会报冲突）
        write_file(&old.join("stealth"), b"1");
        write_file(&old.join(ALLOWLIST_FILE), &allowlist_bytes(3));
        write_file(&old.join("stealth_code"), b"70707");
        // 冲突标记：真机上就是这个状态（老目录被"安全起见"留着）
        write_file(&new.join(CONFLICT_MARKER), "冲突: .allowlist\n".as_bytes());
        // 内核读的是新目录
        let kernel = FakeKernel::new(&new, true);
        let k = key(Some(32123), "boot-a");

        let r = auto_migrate_with(&kernel, &old, &new, Some(&k), 1000).unwrap();
        assert_eq!(r.kernel_probe, Some("new_datadir"), "{r:?}");
        let Some(CompatLink::TookOverLegacy(backup)) = r.compat_link.clone() else {
            panic!("必须接管老目录：{r:?}");
        };
        // ① 旧路径变成软链，第三方写死的 /data/adb/ksu/... 又能用了
        assert!(old.is_symlink());
        assert_eq!(fs::read_link(&old).unwrap(), new);
        assert_eq!(rd(&old.join(ALLOWLIST_FILE)), allowlist_bytes(1));
        // ② 老数据没丢（改名留存）
        let backup = PathBuf::from(backup);
        assert_eq!(rd(&backup.join(ALLOWLIST_FILE)), allowlist_bytes(3));
        assert_eq!(rd(&backup.join("stealth_code")), b"70707");
        // ③ 新目录那份（当前状态）没被覆盖
        assert_eq!(rd(&new.join(ALLOWLIST_FILE)), allowlist_bytes(1));
        // ④ summary 里能看出这件事（真机排查靠它）
        assert!(r.summary().contains("兼容软链"), "{}", r.summary());

        // ⑤ 之后每次 `su` 都只是 `lstat`：不探测、不写盘、直接 AlreadyLinked
        let sets_before = kernel.set_count();
        let r2 = auto_migrate_with(&kernel, &old, &new, Some(&key(Some(32123), "boot-a")), 1001)
            .unwrap();
        assert!(r2.legacy_absent, "{r2:?}");
        assert_eq!(r2.compat_link, Some(CompatLink::AlreadyLinked));
        assert_eq!(kernel.set_count(), sets_before, "软链就位后不该再探测");
    }

    /// 端到端：**旧内核**在跑 + 老目录有内容 ⇒ 一个字节都不动、也不建软链
    /// （动了 = 老内核读不到隐身标志/授权名单 = 掉授权/暴露）
    #[test]
    fn auto_migrate_keeps_legacy_dir_untouched_on_legacy_kernel() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        write_file(&old.join(ALLOWLIST_FILE), &allowlist_bytes(2));
        // 新目录还不存在（老内核机器上是正常的）
        let kernel = FakeKernel::new(&old, true);
        let k = key(Some(32123), "boot-a");

        let r = auto_migrate_with(&kernel, &old, &new, Some(&k), 1000).unwrap();
        assert!(r.skipped_not_new_kernel, "{r:?}");
        assert_eq!(
            r.compat_link,
            Some(CompatLink::BlockedNotNewKernel),
            "{r:?}"
        );
        assert!(old.is_dir() && !old.is_symlink(), "旧内核在跑 ⇒ 老目录原样");
        assert_eq!(rd(&old.join(ALLOWLIST_FILE)), allowlist_bytes(2));
    }

    /// 端到端：干净迁移（新内核 + 老目录有内容 + 无冲突）⇒
    /// 老目录被安全搬走（复制+校验通过后删除 / 新目录不存在时原子 rename），
    /// 随后旧路径上补出软链
    #[test]
    fn auto_migrate_creates_compat_link_after_clean_migration() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        write_file(&old.join(ALLOWLIST_FILE), &allowlist_bytes(2));
        let kernel = FakeKernel::new(&new, true); // 新内核：数据目录是 new
        let k = key(Some(32123), "boot-a");

        let r = auto_migrate_with(&kernel, &old, &new, Some(&k), 1000).unwrap();
        assert!(r.deleted_legacy, "{r:?}");
        assert_eq!(r.compat_link, Some(CompatLink::Created), "{r:?}");
        assert!(old.is_symlink(), "搬完之后旧路径上必须有软链");
        assert_eq!(fs::read_link(&old).unwrap(), new);
        assert_eq!(rd(&old.join(ALLOWLIST_FILE)), allowlist_bytes(2));
    }

    /// `Report::summary()` 在任何分支都要带上兼容软链的结论（真机排查靠它）
    #[test]
    fn summary_reports_compat_link_in_every_branch() {
        let mut r = Report {
            legacy_absent: true,
            compat_link: Some(CompatLink::Created),
            ..Report::default()
        };
        assert!(r.summary().contains("兼容软链"), "{}", r.summary());
        assert!(r.summary().contains("已新建"), "{}", r.summary());
        r.compat_link = Some(CompatLink::AlreadyLinked);
        assert!(r.summary().contains("幂等跳过"), "{}", r.summary());
    }

    /// 老路径是软链时，`migrate()`（不带门控的本体）必须**当没有旧目录**处理 ——
    /// 绝不能顺着软链去读/删目标目录。
    #[test]
    fn migrate_treats_symlinked_legacy_as_absent() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&new.join("stealth"), b"1");
        std::os::unix::fs::symlink(&new, &old).unwrap();

        let r = migrate(&old, &new).unwrap();
        assert!(r.legacy_absent, "{r:?}");
        assert!(new.join("stealth").exists(), "目标目录不能被碰");
    }

    /// **非 root 调用**（内核直接 exec 出来的 `su`）连 `stat /data/adb/ksu` 都 EACCES：
    /// 这种形态必须被判成 [LegacyShape::Unreadable]（⇒ 静默跳过），
    /// **绝不能**报成"旧路径不是我们的旧数据目录"（那是误导 + 每次 `su` 刷一条警告，
    /// 真机 HA247EY4 上一版就是这样刷出来的）。
    #[test]
    fn unreadable_legacy_is_detected_and_skipped_silently() {
        use std::os::unix::fs::PermissionsExt;
        let t = tempfile::tempdir().unwrap();
        let adb = t.path().join("adb"); // 模拟 /data/adb：0700 root
        let old = adb.join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        fs::create_dir_all(&new).unwrap();

        fs::set_permissions(&adb, fs::Permissions::from_mode(0o000)).unwrap();
        // 造不出 EACCES（例如测试本身就是 root）⇒ **跳过断言**，不假通过
        if fs::symlink_metadata(&old).is_ok() {
            fs::set_permissions(&adb, fs::Permissions::from_mode(0o700)).unwrap();
            eprintln!("以 root 跑测试 ⇒ 造不出 EACCES，跳过这条断言");
            return;
        }
        assert_eq!(
            legacy_shape(&old, &new),
            LegacyShape::Unreadable,
            "EACCES 必须单独判成 Unreadable"
        );
        // auto_migrate 走这一支：**静默**跳过、不写任何东西、不报 Foreign
        let kernel = FakeKernel::new(&old, true);
        let r = auto_migrate_with(&kernel, &old, &new, None, 1000).unwrap();
        fs::set_permissions(&adb, fs::Permissions::from_mode(0o700)).unwrap();
        assert!(r.legacy_absent, "{r:?}");
        assert_eq!(
            r.compat_link, None,
            "非 root 调用不该产生兼容软链结论/日志: {r:?}"
        );
        assert_eq!(kernel.set_count(), 0);
    }

    // ══════════════════════════════════════════════════════════════════
    // 🔴1（v2.11）`.allowlist` 并集合并 —— "升级后授权列表可能变空"的回归闸门
    // ══════════════════════════════════════════════════════════════════
    //
    // 真机形状（HA247EY4）：新目录 8 字节 = 0 条 / 旧目录 2360 字节 = 3 条。
    // 老行为：`Emptyish` + `Complete` + `aggressive=false`（冲突标记已存在的体检模式）
    // ⇒ `ConflictKeepNew` ⇒ **只记冲突、永不修复** ✗。

    /// 🔴1 核心：新侧 0 条 + 旧侧 3 条 ⇒ 全量迁移直接并集合并成 3 条
    #[test]
    fn allowlist_union_adopts_legacy_only_records() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        let legacy = allowlist_of(&[
            allowlist_record(10001, "com.a", true, 0x11),
            allowlist_record(10002, "com.b", true, 0x11),
            allowlist_record(10003, "com.c", true, 0x11),
        ]);
        write_file(&old.join(ALLOWLIST_FILE), &legacy);
        write_file(&new.join(ALLOWLIST_FILE), &allowlist_bytes(0)); // 8 字节 = 0 条

        let r = migrate(&old, &new).unwrap();
        assert_eq!(r.allowlist_adopted, 3, "{r:?}");
        assert_eq!(r.allowlist_legacy_only, 0, "{r:?}");
        assert_eq!(allowlist_uids(&rd(&new.join(ALLOWLIST_FILE))).len(), 3);
        assert_eq!(
            rd(&new.join(ALLOWLIST_FILE)),
            legacy,
            "0 条 + 3 条 ⇒ 结果就是那 3 条（顺序也保持）"
        );
        assert!(old.exists(), "有修复动作 ⇒ 旧目录必须留着");
    }

    /// 🔴1：同 UID 以**新目录**为准（撤销语义不丢：新侧那条 `allow_su=false` 必须赢）
    #[test]
    fn allowlist_union_same_uid_new_wins_and_revocation_survives() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        // 旧侧：10001 是"已授权"；新侧：10001 已撤销（记录还在，但 allow_su=false）
        let new_rec = allowlist_record(10001, "com.a", false, 0x22);
        let old_rec = allowlist_record(10001, "com.a", true, 0x11);
        write_file(
            &old.join(ALLOWLIST_FILE),
            &allowlist_of(&[old_rec, allowlist_record(10002, "com.b", true, 0x11)]),
        );
        write_file(&new.join(ALLOWLIST_FILE), &allowlist_of(&[new_rec.clone()]));

        let r = migrate(&old, &new).unwrap();
        assert_eq!(r.allowlist_adopted, 1, "只该并进 10002 这一条：{r:?}");
        let got = allowlist_uids(&rd(&new.join(ALLOWLIST_FILE)));
        assert_eq!(got, vec![(10001, false), (10002, true)], "{got:?}");
        assert_eq!(
            &rd(&new.join(ALLOWLIST_FILE))[8..8 + ALLOWLIST_RECORD_V4],
            new_rec.as_slice(),
            "同 UID 必须原样保留新目录那条（撤销不能被旧侧'授权'覆盖）"
        );
    }

    /// 🔴1：**体检模式**（冲突标记已存在）默认只报数、不写盘；显式采纳才写
    #[test]
    fn allowlist_pending_reported_then_adopted_explicitly() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        let legacy = allowlist_of(&[
            allowlist_record(10001, "com.a", true, 0x11),
            allowlist_record(10002, "com.b", true, 0x11),
            allowlist_record(10003, "com.c", true, 0x11),
        ]);
        write_file(&old.join(ALLOWLIST_FILE), &legacy);
        write_file(&new.join(ALLOWLIST_FILE), &allowlist_bytes(0));
        write_file(&new.join(CONFLICT_MARKER), b"conflict\n");

        // 默认体检：只报数（App 首页那张卡靠 `ALLOWLIST_PENDING=`）
        let r = migrate(&old, &new).unwrap();
        assert_eq!(r.allowlist_legacy_only, 3, "{r:?}");
        assert_eq!(r.allowlist_adopted, 0, "{r:?}");
        assert_eq!(
            rd(&new.join(ALLOWLIST_FILE)),
            allowlist_bytes(0),
            "不许偷偷写盘"
        );
        assert!(r.summary().contains("3 条授权未被采用"), "{}", r.summary());

        // 显式采纳（App「采纳并重启」/ `ksud migrate --adopt-legacy-allowlist`）
        let r2 = migrate_with(&old, &new, true).unwrap();
        assert_eq!(r2.allowlist_adopted, 3, "{r2:?}");
        assert_eq!(r2.allowlist_legacy_only, 0, "{r2:?}");
        assert_eq!(rd(&new.join(ALLOWLIST_FILE)), legacy);
        assert!(r2.summary().contains("已采纳"), "{}", r2.summary());
        // 幂等：再采纳一次没有可采纳的了
        let r3 = migrate_with(&old, &new, true).unwrap();
        assert_eq!(r3.allowlist_adopted, 0, "{r3:?}");
        assert_eq!(rd(&new.join(ALLOWLIST_FILE)), legacy);
    }

    /// 🔴1：老目录**已经被改名留存**（旧路径是软链）时，也要从 `ksu.legacy_backup_*` 里找名单。
    ///
    /// 这是真机上最可能的形态：v2.9/v2.10 确认新内核后会把有内容的老目录改名成
    /// `ksu.legacy_backup_<时间戳>` 再在 `/data/adb/ksu` 原地建软链 ⇒ 只看旧路径会得出
    /// "没有未采纳的授权"的**假结论** ✗。
    #[test]
    fn allowlist_union_reads_renamed_legacy_backup() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        fs::create_dir_all(&new).unwrap();
        // 旧路径 = 指向新目录的软链（改名留存已经发生）
        std::os::unix::fs::symlink(&new, &old).unwrap();
        let backup = t.path().join("ksu.legacy_backup_1000");
        let legacy = allowlist_of(&[
            allowlist_record(10001, "com.a", true, 0x11),
            allowlist_record(10002, "com.b", true, 0x11),
            allowlist_record(10003, "com.c", true, 0x11),
        ]);
        write_file(&backup.join(ALLOWLIST_FILE), &legacy);
        write_file(&new.join(ALLOWLIST_FILE), &allowlist_bytes(0));

        let kernel = FakeKernel::new(&new, true);
        let k = key(Some(32123), "boot-a");
        // 隐式路径（每次 su）：只报数，一个字节都不写
        let r = auto_migrate_with(&kernel, &old, &new, Some(&k), 1000).unwrap();
        assert!(r.legacy_absent, "{r:?}");
        assert_eq!(r.allowlist_legacy_only, 3, "{r:?}");
        assert_eq!(rd(&new.join(ALLOWLIST_FILE)), allowlist_bytes(0));

        // App/用户显式采纳
        let r2 = auto_migrate_with_opts(&kernel, &old, &new, None, 1001, true).unwrap();
        assert_eq!(r2.allowlist_adopted, 3, "{r2:?}");
        assert_eq!(rd(&new.join(ALLOWLIST_FILE)), legacy);
        assert_eq!(
            rd(&backup.join(ALLOWLIST_FILE)),
            legacy,
            "备份里那份一个字节都不动"
        );
        assert!(old.is_symlink());
    }

    /// 🔴1：两侧记录长度不同（version 3 的 776 字节记录 vs version 4 的 784）⇒ **不做**
    /// 记录级合并（混在一起 = 结构性损坏），退回老的"空/截断"判定。
    #[test]
    fn allowlist_union_version_mismatch_falls_back_to_quality_rule() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        let mut v3 = Vec::new();
        v3.extend_from_slice(&ALLOWLIST_MAGIC.to_le_bytes());
        v3.extend_from_slice(&3u32.to_le_bytes());
        v3.extend(std::iter::repeat_n(0xCD_u8, 2 * ALLOWLIST_RECORD_PRE_V4));
        write_file(&old.join(ALLOWLIST_FILE), &v3);
        let v4 = allowlist_bytes(1);
        write_file(&new.join(ALLOWLIST_FILE), &v4);

        let r = migrate(&old, &new).unwrap();
        assert_eq!(r.allowlist_adopted, 0, "{r:?}");
        assert_eq!(r.allowlist_legacy_only, 0, "{r:?}");
        assert!(!r.conflicts.is_empty(), "版本不同只能按老规则记冲突：{r:?}");
        assert_eq!(rd(&new.join(ALLOWLIST_FILE)), v4, "新目录那份原样");
    }

    /// 🔴1：写盘前必须留备份，且备份内容 = 写之前的新目录那份
    #[test]
    fn allowlist_adoption_leaves_pre_merge_backup() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        let before = allowlist_bytes(0);
        write_file(
            &old.join(ALLOWLIST_FILE),
            &allowlist_of(&[allowlist_record(10001, "com.a", true, 0x11)]),
        );
        write_file(&new.join(ALLOWLIST_FILE), &before);

        let r = migrate_with(&old, &new, true).unwrap();
        assert_eq!(r.allowlist_adopted, 1, "{r:?}");
        let baks: Vec<PathBuf> = fs::read_dir(&new)
            .unwrap()
            .flatten()
            .map(|e| e.path())
            .filter(|p| {
                p.file_name()
                    .is_some_and(|n| n.to_string_lossy().starts_with(".allowlist.pre-merge-"))
            })
            .collect();
        assert_eq!(baks.len(), 1, "应当恰好留一份写前备份：{baks:?}");
        assert_eq!(rd(&baks[0]), before, "备份必须是写之前那份");
    }

    /// 🔴1 负向：新侧结构不合法（截断）时，并集合并**不接管**，仍由老的 TakeLegacy 修
    #[test]
    fn allowlist_union_stands_down_on_broken_new_side() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        let legacy = allowlist_of(&[allowlist_record(10001, "com.a", true, 0x11)]);
        write_file(&old.join(ALLOWLIST_FILE), &legacy);
        let mut cut = legacy.clone();
        cut.truncate(8 + 100);
        write_file(&new.join(ALLOWLIST_FILE), &cut);

        let r = migrate(&old, &new).unwrap();
        assert_eq!(r.repaired, vec![ALLOWLIST_FILE.to_string()], "{r:?}");
        assert_eq!(
            rd(&new.join(ALLOWLIST_FILE)),
            legacy,
            "老的 TakeLegacy 路径照常工作"
        );
    }

    /// 🔴1 回归闸门：**体检模式**（冲突标记已存在）也必须能修"新侧截断的名单"。
    ///
    /// 这条兜底是 v0.13 时代就有的自动修复；引入并集合并时如果忘了退回，
    /// 它会在体检模式下悄悄退步 ✗（全量迁移走的是 `copy_tree` 那个入口，暴露不出这个问题）。
    #[test]
    fn checkup_mode_still_repairs_truncated_allowlist() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        let legacy = allowlist_of(&[
            allowlist_record(10001, "com.a", true, 0x11),
            allowlist_record(10002, "com.b", true, 0x11),
        ]);
        write_file(&old.join(ALLOWLIST_FILE), &legacy);
        let mut cut = legacy.clone();
        cut.truncate(8 + ALLOWLIST_RECORD_V4 + 100); // 一个半记录
        write_file(&new.join(ALLOWLIST_FILE), &cut);
        write_file(&new.join(CONFLICT_MARKER), b"conflict\n");

        let r = migrate(&old, &new).unwrap();
        assert_eq!(r.repaired, vec![ALLOWLIST_FILE.to_string()], "{r:?}");
        assert_eq!(
            rd(&new.join(ALLOWLIST_FILE)),
            legacy,
            "体检模式必须把完整名单修回来"
        );
    }

    // ══════════════════════════════════════════════════════════════════
    // 🟠/🟡（v2.11）：冲突标记规范化 + 上限 / 备份复用与保留 / 锁 / 软链形状
    // ══════════════════════════════════════════════════════════════════

    /// 🟠7：字节数变化不该产生"假新行"（不再重复追加同一件冲突）
    #[test]
    fn conflict_marker_ignores_byte_count_changes() {
        let t = tempfile::tempdir().unwrap();
        let new = t.path().join("sevenk");
        fs::create_dir_all(&new).unwrap();
        let mut r = Report::default();
        r.conflicts.push(
            ".allowlist: 两边都完整但内容不同（新 2360 字节 / 旧 8 字节；保留新目录那份）"
                .to_string(),
        );
        write_conflict_marker(&new, &mut r);
        let after_first = rd(&new.join(CONFLICT_MARKER));

        for size in [100usize, 999, 12_345] {
            let mut r = Report::default();
            r.conflicts.push(format!(
                ".allowlist: 两边都完整但内容不同（新 {size} 字节 / 旧 8 字节；保留新目录那份）"
            ));
            write_conflict_marker(&new, &mut r);
        }
        assert_eq!(
            rd(&new.join(CONFLICT_MARKER)),
            after_first,
            "只有字节数不同 ⇒ 必须判成同一行、一个字节都不涨"
        );
    }

    /// 🟠7：冲突标记有 64KB 上限（旧的从前面裁掉，最近的留着）
    #[test]
    fn conflict_marker_is_capped() {
        let t = tempfile::tempdir().unwrap();
        let new = t.path().join("sevenk");
        fs::create_dir_all(&new).unwrap();
        // 先灌一个超大的"历史"标记
        let mut old = String::new();
        while old.len() < MARKER_MAX_BYTES * 2 {
            old.push_str("冲突: 历史遗留的另一件事（不一样的文字）\n");
        }
        fs::write(new.join(CONFLICT_MARKER), &old).unwrap();

        let mut r = Report::default();
        r.conflicts.push("最新的一条冲突".to_string());
        write_conflict_marker(&new, &mut r);
        let body = String::from_utf8_lossy(&rd(&new.join(CONFLICT_MARKER))).to_string();
        assert!(
            body.len() <= MARKER_MAX_BYTES + 256,
            "标记必须被裁剪到上限附近，实际 {} 字节",
            body.len()
        );
        assert!(body.contains("最新的一条冲突"), "最近的内容必须保留");
        assert!(
            body.contains("已按"),
            "裁剪过就要说明（{}）",
            &body[..80.min(body.len())]
        );
    }

    /// 🟠6：内容指纹相同的旧备份**复用**（不叠加第二份）
    #[test]
    fn legacy_backup_reused_when_content_identical() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&new.join("stealth"), b"1");
        write_file(&old.join(ALLOWLIST_FILE), b"same-bytes");
        write_file(&old.join("stealth_code"), b"70707");
        let existing = t.path().join("ksu.legacy_backup_500");
        write_file(&existing.join(ALLOWLIST_FILE), b"same-bytes");
        write_file(&existing.join("stealth_code"), b"70707");

        assert_eq!(
            plan_legacy_backup(&old, 1000),
            LegacyBackupPlan::ReuseExisting(existing.clone())
        );
        let link = ensure_compat_link_at(&old, &new, true, 1000);
        assert!(
            matches!(&link, CompatLink::TookOverLegacy(p) if Path::new(p) == existing),
            "{link:?}"
        );
        assert!(old.is_symlink());
        assert_eq!(
            rd(&existing.join(ALLOWLIST_FILE)),
            b"same-bytes",
            "老数据仍在"
        );
        // 没有第二份备份被建出来
        let backups: Vec<_> = fs::read_dir(t.path())
            .unwrap()
            .flatten()
            .filter(|e| e.file_name().to_string_lossy().contains(".legacy_backup_"))
            .collect();
        assert_eq!(backups.len(), 1, "内容相同就该复用，不再叠加");
    }

    /// 🟠6：备份内容不同 ⇒ 老老实实新建一份（绝不误判成重复而删数据）
    #[test]
    fn legacy_backup_not_reused_when_content_differs() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        write_file(&old.join(ALLOWLIST_FILE), b"new-content");
        let existing = t.path().join("ksu.legacy_backup_500");
        write_file(&existing.join(ALLOWLIST_FILE), b"old-content");
        assert_eq!(
            plan_legacy_backup(&old, 1000),
            LegacyBackupPlan::Rename(t.path().join("ksu.legacy_backup_1000"))
        );
        assert_eq!(rd(&old.join(ALLOWLIST_FILE)), b"new-content");
    }

    /// 🟠6：只保留最近 3 份，且**只碰**严格命名的真实目录（软链/普通文件一律不动）
    #[test]
    fn legacy_backups_pruned_to_newest_three_only() {
        let t = tempfile::tempdir().unwrap();
        for stamp in [100u64, 200, 300, 400, 500] {
            fs::create_dir_all(t.path().join(format!("ksu.legacy_backup_{stamp}"))).unwrap();
        }
        // 干扰项：名字像但不是真实目录 / 完全无关的名字
        let decoy_link = t.path().join("ksu.legacy_backup_600");
        let target = t.path().join("elsewhere");
        fs::create_dir_all(&target).unwrap();
        std::os::unix::fs::symlink(&target, &decoy_link).unwrap();
        write_file(&t.path().join("ksu.legacy_backup_notanumber"), b"x");
        fs::create_dir_all(t.path().join("unrelated")).unwrap();

        let removed = prune_legacy_backups(t.path(), "ksu", LEGACY_BACKUP_KEEP);
        assert_eq!(removed.len(), 2, "{removed:?}");
        assert!(!t.path().join("ksu.legacy_backup_100").exists());
        assert!(!t.path().join("ksu.legacy_backup_200").exists());
        for keep in [300u64, 400, 500] {
            assert!(t.path().join(format!("ksu.legacy_backup_{keep}")).exists());
        }
        assert!(decoy_link.is_symlink(), "软链绝不能被跟随/删除");
        assert!(target.is_dir());
        assert!(t.path().join("ksu.legacy_backup_notanumber").exists());
        assert!(t.path().join("unrelated").exists());
    }

    /// 🟠4：老目录要改名前，**先**把新目录建出来（否则"改了名却没建链"）
    #[test]
    fn compat_link_creates_missing_new_dir_before_rename() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk"); // 故意不存在
        write_file(&old.join("stealth"), b"1");

        let link = ensure_compat_link_at(&old, &new, true, 1000);
        let CompatLink::TookOverLegacy(backup) = link else {
            panic!("必须接管老目录：{link:?}");
        };
        assert!(new.is_dir(), "改名之前必须先把新目录建出来");
        assert!(old.is_symlink(), "老路径必须变成软链");
        assert_eq!(fs::read_link(&old).unwrap(), new);
        // 老数据一个字节没丢：改名到了 ksu.legacy_backup_1000
        assert_eq!(
            PathBuf::from(&backup),
            t.path().join("ksu.legacy_backup_1000")
        );
        assert_eq!(rd(&PathBuf::from(&backup).join("stealth")), b"1");
    }

    /// 🟠4：新目录被**普通文件**占据 ⇒ 一个字节都不动（老目录保持原样）
    #[test]
    fn compat_link_refuses_to_take_over_when_new_is_a_file() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        write_file(&new, b"not a dir");
        let link = ensure_compat_link_at(&old, &new, true, 1000);
        assert!(matches!(link, CompatLink::Skipped(_)), "{link:?}");
        assert!(old.is_dir() && !old.is_symlink(), "绝不能先改名");
        assert_eq!(rd(&old.join("stealth")), b"1");
    }

    /// 🟠5：门控缓存命中那条路，也要按同样的安全规则接管老目录
    #[test]
    fn cached_verdict_path_still_takes_over_legacy_dir() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        write_file(&new.join("stealth"), b"1");
        let k = key(Some(32123), "boot-a");
        write_stamp(
            &new,
            &GateStamp {
                verdict: StampVerdict::New,
                kernel: k.kernel,
                osrelease: k.osrelease.clone(),
                boot: k.boot.clone(),
                at: 1000,
            },
        );
        let kernel = FakeKernel::new(&new, true);
        let r = auto_migrate_with(&kernel, &old, &new, Some(&k), 1001).unwrap();
        assert!(r.cached, "{r:?}");
        assert!(
            matches!(r.compat_link, Some(CompatLink::TookOverLegacy(_))),
            "{r:?}"
        );
        assert_eq!(kernel.set_count(), 0, "缓存命中不该再探测");
        assert!(new.is_dir() && old.is_symlink());
    }

    /// 🟡：尾斜杠软链（`ln -s /data/adb/sevenk/ /data/adb/ksu`）必须认成"我们的软链"
    #[test]
    fn trailing_slash_symlink_is_recognized() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        fs::create_dir_all(&new).unwrap();
        let with_slash = PathBuf::from(format!("{}/", new.display()));
        std::os::unix::fs::symlink(&with_slash, &old).unwrap();
        assert_eq!(legacy_shape(&old, &new), LegacyShape::LinkedToNew);
        assert_eq!(
            ensure_compat_link_at(&old, &new, false, 1000),
            CompatLink::AlreadyLinked
        );
    }

    /// 🟡：`.migrate.lock` —— 别人拿着就跳过（不探测、不动数据、不删别人的锁）；
    /// 陈旧锁（进程被 kill）则抢过来继续。
    #[test]
    fn migrate_lock_blocks_concurrent_and_steals_stale() {
        let t = tempfile::tempdir().unwrap();
        let old = t.path().join("ksu");
        let new = t.path().join("sevenk");
        write_file(&old.join("stealth"), b"1");
        let lock = t.path().join(MIGRATE_LOCK_FILE);
        let kernel = FakeKernel::new(&new, true);

        // 新鲜的锁 ⇒ 本次跳过
        write_file(&lock, b"5000");
        let r = auto_migrate_with(&kernel, &old, &new, None, 5001).unwrap();
        assert!(
            r.notes.iter().any(|n| n.contains("另一个迁移进程")),
            "{r:?}"
        );
        assert!(
            old.is_dir() && !old.is_symlink(),
            "拿不到锁就一个字节都不动"
        );
        assert!(!new.exists());
        assert_eq!(kernel.set_count(), 0, "拿不到锁就不该探测");
        assert!(lock.exists(), "别人的锁不能删");

        // 陈旧锁（>120s）⇒ 抢过来正常迁移，用完释放
        fs::write(&lock, b"9999").unwrap();
        let r2 = auto_migrate_with(&kernel, &old, &new, None, 20_000).unwrap();
        assert!(r2.deleted_legacy, "{r2:?}");
        assert!(!lock.exists(), "迁移完必须释放锁");
    }

    // ══════════════════════════════════════════════════════════════════
    // 🔴（v2.18）三条稳定性修复的专测（"修前会怎样 / 修后不会"的证据）
    // ══════════════════════════════════════════════════════════════════

    /// 🔴1：`lstat_or_gone` 必须把 `NotFound` 变成 `Ok(None)`。
    ///
    /// 旧写法是 `fs::symlink_metadata(p)?` —— 在"先 `path_exists` 判定、随后 lstat"
    /// 的 TOCTOU 窗口里，只要文件被并发删掉就返回 `Err`，一路冒出 `auto_migrate()`，
    /// 于是**每次 `su` 都刷一条硬错误**（脚本里把这一行改回 `?` 即可复现失败）。
    #[test]
    fn lstat_or_gone_treats_notfound_as_absent() {
        let t = tempfile::tempdir().unwrap();
        let missing = t.path().join("nope");
        assert!(
            matches!(lstat_or_gone(&missing), Ok(None)),
            "NotFound 必须是 Ok(None)，不能是 Err"
        );

        let here = t.path().join("here");
        write_file(&here, b"1");
        assert!(
            matches!(lstat_or_gone(&here), Ok(Some(_))),
            "存在的文件照旧返回 Some"
        );
        // 软链（哪怕是悬空软链）也必须 lstat 得到 Some —— 本函数不跟随软链
        let link = t.path().join("link");
        std::os::unix::fs::symlink(t.path().join("does-not-exist"), &link).unwrap();
        assert!(matches!(lstat_or_gone(&link), Ok(Some(_))), "悬空软链也要 Some");
    }

    /// 🔴2a：`--force` 清冲突标记，**删不掉必须报错**。
    ///
    /// 旧写法 `let _ = fs::remove_file(marker);` 把错误吞掉 ⇒ 标记还在 ⇒ 紧接着的全量迁移
    /// 又会走"标记存在 ⇒ 只体检"那条分支 ⇒ `--force` 静默失效。
    #[test]
    fn clear_conflict_marker_reports_failure() {
        let t = tempfile::tempdir().unwrap();
        let new = t.path();

        // ① 压根不存在 ⇒ 算成功（与 `remove_file_checked` 的判据一致）
        assert!(clear_conflict_marker(new).is_ok(), "不存在应视为成功");

        // ② 把标记位置做成**目录** ⇒ `remove_file` 必定失败（EISDIR）⇒ 必须 Err
        std::fs::create_dir(new.join(CONFLICT_MARKER)).unwrap();
        assert!(
            clear_conflict_marker(new).is_err(),
            "删不掉冲突标记却返回 Ok = `--force` 静默失效（旧写法的 bug）"
        );

        // ③ 真文件 ⇒ 成功，并且真被删掉
        std::fs::remove_dir(new.join(CONFLICT_MARKER)).unwrap();
        write_file(&new.join(CONFLICT_MARKER), b"x");
        assert!(clear_conflict_marker(new).is_ok());
        assert!(!new.join(CONFLICT_MARKER).exists(), "标记必须真的没了");
    }

    /// 🔴2b：冲突标记**写不进去时报告不能撒谎**。
    ///
    /// 旧写法 `let _ = fs::write(...)` 之后无条件 push"冲突标记已更新" —— 磁盘满 /
    /// SELinux 拒绝时报告与现实相反，用户失去唯一的排查线索。
    #[test]
    fn conflict_marker_write_failure_is_reported_not_claimed_updated() {
        let t = tempfile::tempdir().unwrap();
        let new = t.path();
        // 让标记路径是个**目录** ⇒ `fs::write` 必定失败（EISDIR），与 uid 无关
        std::fs::create_dir(new.join(CONFLICT_MARKER)).unwrap();

        let mut r = Report::default();
        r.conflicts.push("冲突X".to_string());
        write_conflict_marker(new, &mut r);

        assert!(
            r.failed.iter().any(|f| f.contains("冲突标记写不进去")),
            "写失败必须进 failed：{r:?}"
        );
        assert!(
            !r.notes.iter().any(|n| n.contains("已更新")),
            "写失败时绝不能报'已更新'（旧写法就是这么撒谎的）：{r:?}"
        );
    }
}
