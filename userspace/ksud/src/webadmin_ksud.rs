//! 网页管理器的 Android 侧数据来源：把 ksud 自己的能力接到 [crate::webadmin] 的接口上。
//!
//! 这里只做"取数/执行"，HTTP、鉴权、限流都在 `webadmin.rs` 里（那部分能在开发机上测）。

#![cfg(target_os = "android")]

use std::os::unix::process::CommandExt;
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};

use anyhow::Context;
use serde_json::{Value, json};

use crate::module;
use crate::webadmin::{self, Backend, Config};
use crate::{ksucalls, ksu_uapi};
use ksu_uapi::{
    app_profile, app_profile__bindgen_ty_1__bindgen_ty_1 as RootBranch,
    app_profile__bindgen_ty_1__bindgen_ty_2 as NonRootBranch,
};
use log::{info, warn};

pub struct KsudBackend;

impl Backend for KsudBackend {
    fn status(&self) -> Value {
        let release = kernel_release();
        json!({
            // 这个网页服务本身就跑在 ksud（root 守护进程）里，所以 root 一定可用；
            // 不存在"划掉 App 就没了"的问题（这正是搬过来的原因）
            "root": true,
            "rootShell": true,
            "managerNotRecognized": false,
            "stealth": stealth_enabled(),
            "gki": is_gki(&release),
            "lkm": ksucalls::is_lkm(),
            "lateLoad": ksucalls::is_late_load(),
            "safeMode": ksucalls::check_kernel_safemode(),
            "manager": "ksud 内置",
            "managerCode": 0,
            "ksu": ksucalls::get_version(),
            "kernel": release,
            "kmi": kmi_of(&release),
            "model": device_model(),
            "moduleCount": module_ids().len(),
            "superuserCount": ksucalls::allow_list_uids().len(),
            "stealthCode": stealth_code(),
            "port": real_port(),
        })
    }

    fn modules(&self) -> Value {
        match module::list_modules_json() {
            Ok(text) => serde_json::from_str(&text).unwrap_or_else(|_| json!([])),
            Err(e) => {
                warn!("网页管理器读取模块列表失败: {e:#}");
                json!([])
            }
        }
    }

    fn toggle_module(&self, id: &str, enable: bool) -> Result<String, String> {
        let r = if enable {
            module::enable_module(id)
        } else {
            module::disable_module(id)
        };
        match r {
            Ok(()) => Ok(if enable { "已启用" } else { "已禁用" }.to_string()),
            Err(e) => Err(format!("{e:#}")),
        }
    }

    fn uninstall_module(&self, id: &str) -> Result<String, String> {
        module::uninstall_module(id)
            .map(|()| "已标记卸载，重启后生效".to_string())
            .map_err(|e| format!("{e:#}"))
    }

    fn undo_uninstall_module(&self, id: &str) -> Result<String, String> {
        module::undo_uninstall_module(id)
            .map(|()| "已撤销卸载".to_string())
            .map_err(|e| format!("{e:#}"))
    }

    fn set_stealth(&self, enable: bool) -> Result<String, String> {
        // 同上：以内核要求的管理器身份去切换
        match ksucalls::stealth_set_authed(enable) {
            Ok(()) => Ok(if enable { "隐身已开启" } else { "隐身已关闭" }.to_string()),
            Err(e) => Err(format!("{e:#}")),
        }
    }




    fn set_stealth_code(&self, code: &str) -> Result<String, String> {
        // 与 App 里 StealthCodeStore.write 写同一个文件：
        // App 的拨号接收器判的是「磁盘 ∪ prefs」，所以写磁盘这一份就生效。
        //
        // 🔴（v2.18）**原子写**。旧写法 `std::fs::write` 会先把目标截断：掉电 / 被 kill /
        // ENOSPC 时留下一份**空或半截**的 `stealth_code` ⇒ 拨号密令失效 ⇒
        // 用户**退不出隐身**（这是"保命入口"，坏了代价极高）。
        // 旧代码还有第二个窗口：`File::create` **之后**才 `set_permissions(0600)`，
        // 而 `init_event.rs` 显式 `umask(0)` ⇒ 那一瞬间密令是同机可读的；错误还被 `let _` 吞掉。
        // 现在照本仓两处已验过的写法：`module_config.rs::save_config` 的
        // "tmp + sync_all + rename"，以及 `webadmin.rs::Config::save` 的
        // "**先给临时文件收紧权限、再 rename**"（避免"已是最终名字但还没收紧权限"的窗口）。
        // 现在走本仓抽好的单一入口 `atomic::write_atomic`
        // （tmp + 先收紧权限 + sync_all + rename；实现处有完整说明与单测）。
        const PATH: &str = "/data/adb/sevenk/stealth_code";
        crate::atomic::write_atomic(std::path::Path::new(PATH), code.as_bytes(), Some(0o600))
            .map_err(|e| format!("写入失败: {e}"))?;
        // SELinux 标签：ksud 自己写的数据文件用 `ksu_file`（与 Config::save 一致）。
        // 失败不致命（只记一条），但**不静默**。
        #[cfg(target_os = "android")]
        {
            if let Err(e) = crate::restorecon::lsetfilecon(PATH, crate::restorecon::KSU_CON) {
                log_line(&format!("给 {PATH} 打 SELinux 标签失败: {e:#}"));
            }
        }
        // 🔐（v2.19）日志**只记"已更新"、不记密令本体**。
        //
        // 旧写法是 `log_line(&format!("隐身密令已改为 {code}"))` —— 当时的口径是"刻意留一条
        // 审计线索"（文件 0600、只有 root 读得到）。但没必要：这条日志会让**密令本体**同时出现在
        // `webadmin.log` 与 logcat（`log_line` 末尾还有 `info!(...)`），
        // 而密令是"退不出隐身"时唯一的保命入口 —— 少一处落盘就少一处泄露面。
        // 现在只记"改了几位数字"，足够证明"确实写过一次"，又拿不到本体。
        // ⚠️ 下面返回给**已鉴权调用方**的那句话仍带新密令（用户自己要看），那是有意的。
        log_line(&format!("隐身密令已更新（{} 位数字，日志不记录本体）", code.len()));
        Ok(format!("已改成 {code}（退出隐身拨 *{code}#*#*）"))
    }

    fn superusers(&self) -> Value {
        let uids = ksucalls::allow_list_uids();
        let pkgs = uid_package_map();
        let list: Vec<Value> = uids
            .iter()
            .filter_map(|uid| {
                let package = pkgs.get(uid).cloned().unwrap_or_default();
                if package.is_empty() {
                    return None;
                }
                Some(json!({ "label": package, "package": package, "uid": uid }))
            })
            .collect();
        json!({ "ok": true, "list": list })
    }

    // ══════════════════════════════════════════════════════════════
    // 第二批：超级用户 / 超管日志 / 上传装模块
    // ══════════════════════════════════════════════════════════════

    /// 全部已安装 App + 授权状态。
    ///
    /// 数据源：`/data/system/packages.list`（root 读得到，一行一个包）+ 内核的两张表：
    /// · `NEW_GET_ALLOW_LIST` → allow_su == true 的 uid（= 授权了 root）
    /// · `NEW_GET_DENY_LIST`  → allow_su == false 的 uid（= 单独配过非 root 策略）
    ///
    /// ⚠️ 网页**拿不到 APK 里的中文应用名**（那需要解析 resources.arsc），
    /// 所以列表按包名展示 —— 这是用户 2026-09-17 拍板的选择。
    fn apps(&self) -> Value {
        let granted: std::collections::HashSet<u32> =
            ksucalls::allow_list_uids().into_iter().collect();
        let configured: std::collections::HashSet<u32> =
            ksucalls::deny_list_uids().into_iter().collect();
        let manager = ksucalls::manager_appid().unwrap_or(u32::MAX);

        let mut list = Vec::new();
        for (pkg, uid) in package_list() {
            // 管理器自己不在列表里（内核也把它排除在授权表外）
            if uid % 100_000 == manager {
                continue;
            }
            list.push(json!({
                "pkg": pkg,
                "uid": uid,
                "granted": granted.contains(&uid),
                "configured": configured.contains(&uid),
                // Android 的系统应用 uid 都在 10000 以下（普通安装的 App 从 10000 起）
                "system": uid < 10_000,
                "userId": uid / 100_000,
            }));
        }
        json!({
            "ok": true,
            "list": list,
            "grantedCount": granted.len(),
            "total": list.len(),
        })
    }

    /// 授权 / 撤销 root。
    ///
    /// 语义与 App 里「打开 App 详情页 → 允许 root」完全一致：
    /// 写一份新的 app_profile（`allow_su` 决定走 root 分支还是非 root 分支）。
    /// **不覆盖别的字段** —— 因为 union 里只能存一个分支，切分支时按默认值重建，
    /// 这与 App/JNI 的行为一致（`manager/app/src/main/cpp/jni.cc`）。
    fn set_app_root(&self, pkg: &str, uid: u32, allow: bool) -> Result<String, String> {
        if allow && uid < 2000 && uid != 1000 {
            return Err("系统核心 App（uid < 2000）不能授予 root".to_string());
        }
        let cur = ksucalls::get_app_profile(uid, pkg).map_err(|e| format!("读取配置失败: {e:#}"))?;
        if let Some(p) = &cur
            && p.allow_su == allow
        {
            return Ok(if allow {
                format!("{pkg} 已经是授权状态")
            } else {
                format!("{pkg} 本来就没有 root 权限")
            });
        }
        let profile = if allow {
            new_root_profile(pkg, uid)
        } else {
            new_nonroot_profile(pkg, uid)
        };
        ksucalls::set_app_profile(&profile).map_err(|e| format!("写入失败: {e:#}"))?;
        log_line(&format!(
            "超级用户：{} {pkg}(uid={uid})",
            if allow { "授予 root" } else { "撤销 root" }
        ));
        Ok(if allow {
            format!("已授予 {pkg} root 权限")
        } else {
            format!("已撤销 {pkg} 的 root 权限")
        })
    }

    /// 读某个 App 的 root 配置（没有条目时给出默认值，`exists=false`）
    fn app_profile(&self, pkg: &str, uid: u32) -> Result<Value, String> {
        let cur = ksucalls::get_app_profile(uid, pkg).map_err(|e| format!("读取失败: {e:#}"))?;
        let mut v = json!({
            "pkg": pkg,
            "uid": uid,
            "exists": cur.is_some(),
            "allowSu": false,
            "rootUseDefault": true,
            "rootUid": 0,
            "rootGid": 0,
            "groups": [],
            "context": DEFAULT_SELINUX_DOMAIN,
            "namespace": 0,
            "noNewPrivs": true,
            "nonRootUseDefault": true,
            "umountModules": true,
        });
        if let Some(p) = cur {
            v["allowSu"] = json!(p.allow_su);
            if p.allow_su {
                let rp = root_profile_of(&p);
                let n = (rp.profile.groups_count as usize).min(ksu_uapi::KSU_MAX_GROUPS as usize);
                v["rootUseDefault"] = json!(rp.use_default);
                v["rootUid"] = json!(rp.profile.uid);
                v["rootGid"] = json!(rp.profile.gid);
                v["groups"] = json!(rp.profile.groups[..n].to_vec());
                v["context"] = json!(ksucalls::read_cstr(&rp.profile.selinux_domain));
                v["namespace"] = json!(rp.profile.namespaces);
                v["noNewPrivs"] = json!(rp.profile.flags & FLAG_KSU_NO_NEW_PRIVS != 0);
            } else {
                let nrp = non_root_profile_of(&p);
                v["nonRootUseDefault"] = json!(nrp.use_default);
                v["umountModules"] = json!(nrp.profile.umount_modules);
            }
        }
        Ok(v)
    }

    /// 保存某个 App 的 root 配置（表单里出现哪个字段就改哪个）
    fn save_app_profile(
        &self,
        pkg: &str,
        uid: u32,
        form: &std::collections::HashMap<String, String>,
    ) -> Result<String, String> {
        let cur = ksucalls::get_app_profile(uid, pkg).map_err(|e| format!("读取配置失败: {e:#}"))?;
        let stored_allow = cur.as_ref().map(|p| p.allow_su);
        let allow = form
            .get("allowSu")
            .map_or_else(|| stored_allow.unwrap_or(false), |v| v == "1");
        if allow && uid < 2000 && uid != 1000 {
            return Err("系统核心 App（uid < 2000）不能授予 root".to_string());
        }

        let mut p = cur.unwrap_or_else(|| base_profile(pkg, uid));
        p.version = ksu_uapi::KSU_APP_PROFILE_VER;
        write_key(&mut p, pkg);
        p.curr_uid = uid as i32;
        p.allow_su = allow;

        // union 里只能存一个分支：换分支时按默认值重建，避免把旧分支的字节当新分支读
        let same_branch = stored_allow == Some(allow);
        if allow {
            let mut rp = if same_branch {
                root_profile_of(&p)
            } else {
                default_root_branch()
            };
            if let Some(v) = form.get("rootUseDefault") {
                rp.use_default = v == "1";
            }
            if let Some(v) = form.get("rootUid") {
                rp.profile.uid = parse_i32(v, "UID")?;
            }
            if let Some(v) = form.get("rootGid") {
                rp.profile.gid = parse_i32(v, "GID")?;
            }
            if let Some(v) = form.get("groups") {
                let g = parse_groups(v)?;
                rp.profile.groups_count = g.len() as u32;
                for (i, x) in g.iter().enumerate() {
                    rp.profile.groups[i] = *x;
                }
            }
            if let Some(v) = form.get("context") {
                let d = v.trim();
                if d.is_empty() || d.len() >= ksu_uapi::KSU_SELINUX_DOMAIN as usize || d.contains('\0') {
                    return Err("SELinux 域不能为空、也不能超过 63 个字符".to_string());
                }
                ksucalls::write_cstr(&mut rp.profile.selinux_domain, d);
            }
            if let Some(v) = form.get("namespace") {
                let ns = v.trim().parse::<i32>().map_err(|_| "命名空间取值不合法".to_string())?;
                if !(0..=2).contains(&ns) {
                    return Err("命名空间只能是 0(继承) / 1(全局) / 2(独立)".to_string());
                }
                rp.profile.namespaces = ns;
            }
            if let Some(v) = form.get("noNewPrivs") {
                if v == "1" {
                    rp.profile.flags |= FLAG_KSU_NO_NEW_PRIVS;
                } else {
                    rp.profile.flags &= !FLAG_KSU_NO_NEW_PRIVS;
                }
            }
            // 授权时 SELinux 域必须有值，否则内核 profile_valid 会 -EINVAL
            if ksucalls::read_cstr(&rp.profile.selinux_domain).is_empty() {
                ksucalls::write_cstr(&mut rp.profile.selinux_domain, DEFAULT_SELINUX_DOMAIN);
            }
            set_root_branch(&mut p, &rp);
        } else {
            let mut nrp = if same_branch {
                non_root_profile_of(&p)
            } else {
                default_nonroot_branch()
            };
            if let Some(v) = form.get("nonRootUseDefault") {
                nrp.use_default = v == "1";
            }
            if let Some(v) = form.get("umountModules") {
                nrp.profile.umount_modules = v == "1";
            }
            set_nonroot_branch(&mut p, nrp);
        }

        ksucalls::set_app_profile(&p).map_err(|e| format!("写入失败: {e:#}"))?;
        log_line(&format!("超级用户：保存 {pkg}(uid={uid}) 的 root 配置"));
        Ok(format!("已保存 {pkg} 的配置"))
    }

    /// 超级用户日志：读 sulogd 落盘的文本（新的在前，支持关键字过滤）
    fn sulog(&self, limit: usize, query: &str) -> Value {
        let files = sulog_files();
        let (enabled, supported) = ksucalls::get_feature(crate::feature::FeatureId::Sulog as u32)
            .unwrap_or((0, false));
        let q = query.trim().to_lowercase();
        let mut lines: Vec<String> = Vec::new();
        for (_, path) in &files {
            let Ok(text) = std::fs::read_to_string(path) else {
                continue;
            };
            for line in text.lines().rev() {
                if line.is_empty() {
                    continue;
                }
                if !q.is_empty() && !line.to_lowercase().contains(&q) {
                    continue;
                }
                lines.push(line.to_string());
                if lines.len() >= limit {
                    break;
                }
            }
            if lines.len() >= limit {
                break;
            }
        }
        json!({
            "ok": true,
            "enabled": supported && enabled != 0,
            "supported": supported,
            "files": files.len(),
            "count": lines.len(),
            "lines": lines,
        })
    }

    /// 清空日志：**截断**而不是删除 —— sulogd 正拿着这些文件的句柄，
    /// 删掉的话它会继续往"已经被 unlink 的 inode"里写（等于白记）。
    /// 截断后 O_APPEND 会让下一次写落在新文件末尾（0），立刻恢复记录。
    fn clear_sulog(&self) -> Result<String, String> {
        let files = sulog_files();
        if files.is_empty() {
            return Ok("本来就没有日志文件".to_string());
        }
        let mut n = 0usize;
        for (_, path) in &files {
            std::fs::OpenOptions::new()
                .write(true)
                .truncate(true)
                .open(path)
                .map_err(|e| format!("清空 {} 失败: {e}", path.display()))?;
            n += 1;
        }
        log_line(&format!("超级用户日志已清空（{n} 个文件）"));
        Ok(format!("已清空 {n} 个日志文件"))
    }

    /// 超级用户日志开关（内核 feature: sulog；打开时顺手把 sulogd 拉起来）
    fn set_sulog_enabled(&self, enable: bool) -> Result<String, String> {
        ksucalls::set_feature(
            crate::feature::FeatureId::Sulog as u32,
            u64::from(enable),
        )
        .map_err(|e| format!("切换失败: {e:#}"))?;
        if enable
            && let Err(e) = crate::sulog::ensure_sulogd_running()
        {
            warn!("拉起 sulogd 失败: {e:#}");
        }
        log_line(&format!(
            "超级用户日志{}",
            if enable { "已开启" } else { "已关闭" }
        ));
        Ok(if enable {
            "超级用户日志已开启".to_string()
        } else {
            "超级用户日志已关闭".to_string()
        })
    }

    // ── 第二批第 4 项：动作脚本输出 + 模块网页（WebUI）──

    /// 跑模块的动作脚本，把输出带回来
    fn module_action(&self, id: &str) -> Result<Value, String> {
        let (code, stdout, stderr) =
            module::run_action_capture(id).map_err(|e| format!("{e:#}"))?;
        log_line(&format!("网页：跑模块 {id} 的动作脚本（退出码 {code}）"));
        let mut output = stdout;
        if !stderr.trim().is_empty() {
            if !output.is_empty() && !output.ends_with('\n') {
                output.push('\n');
            }
            output.push_str("—— stderr ——\n");
            output.push_str(&stderr);
        }
        if output.trim().is_empty() {
            output = "(脚本没有产生输出)".to_string();
        }
        Ok(json!({ "id": id, "code": code, "output": output }))
    }

    /// 模块网页里的 `ksu.exec` —— **以 root 跑命令**。
    ///
    /// ⚠️ 这个接口能拿到 root，所以只对**带正确访问密钥**的请求开放
    /// （鉴权在 `webadmin.rs`，密钥在 `webadmin.conf` 里，0600）。
    /// 每次调用仍然记一条日志，便于事后追查（`/data/adb/sevenk/webadmin.log`）。
    fn module_exec(&self, id: &str, cmd: &str, options_json: &str) -> Result<Value, String> {
        let short: String = cmd.chars().take(200).collect();
        let opts = module::parse_exec_options(options_json);
        log_line(&format!(
            "网页：模块 {id} 的网页界面执行命令：{short}\
             （cwd={} env={} 条）",
            opts.cwd.as_deref().unwrap_or("模块目录"),
            opts.env.len()
        ));
        let (code, stdout, stderr) =
            module::exec_in_module(id, cmd, options_json).map_err(|e| format!("{e:#}"))?;
        Ok(json!({ "errno": code, "stdout": stdout, "stderr": stderr }))
    }

    /// 模块网页的 `ksu.listPackages(type)`（同步接口，返回包名数组）
    fn module_packages(&self, kind: &str) -> Result<Value, String> {
        let names = module::list_package_names(kind).map_err(|e| format!("{e:#}"))?;
        log_line(&format!(
            "网页：模块网页取包列表（{kind}）→ {} 个",
            names.len()
        ));
        Ok(json!({ "packages": names }))
    }

    /// 模块网页的 `ksu.getPackagesInfo(names)`（同步接口）
    fn module_packages_info(&self, names_json: &str) -> Result<Value, String> {
        let names: Vec<String> = serde_json::from_str(names_json).unwrap_or_default();
        let list = module::packages_info(&names).map_err(|e| format!("{e:#}"))?;
        log_line(&format!(
            "网页：模块网页取 {} 个包的信息",
            list.len()
        ));
        Ok(json!({ "list": list }))
    }

    /// 模块网页的 `ksu.spawn(...)`：起一个子进程，输出靠轮询往回搬
    fn module_spawn_start(
        &self,
        id: &str,
        cmd: &str,
        args: &[String],
        options_json: &str,
    ) -> Result<Value, String> {
        use std::process::Stdio;
        module::validate_module_id(id).map_err(|e| format!("{e:#}"))?;
        let dir = std::path::PathBuf::from(format!("{}{id}", crate::defs::MODULE_DIR));
        if !dir.exists() {
            return Err(format!("模块目录不存在：{id}"));
        }
        // options 里的 cwd/env（与 exec 同一套解析）
        let opts = module::parse_exec_options(options_json);
        let cwd = match &opts.cwd {
            Some(c) => {
                let p = std::path::PathBuf::from(c);
                if !p.exists() {
                    return Err(format!("options 里的 cwd 不存在：{c}"));
                }
                p
            }
            None => dir,
        };
        // 拼命令行（参数逐个转义，避免被 shell 拆开）
        let mut line = cmd.to_string();
        for a in args {
            line.push(' ');
            line.push_str(&shell_quote(a));
        }
        log_line(&format!(
            "网页：模块 {id} 的网页界面启动子进程：{}",
            line.chars().take(200).collect::<String>()
        ));

        let mut command = std::process::Command::new(crate::assets::BUSYBOX_PATH);
        #[cfg(unix)]
        unsafe {
            command.pre_exec(|| {
                crate::utils::detach_process_group(true);
                crate::utils::switch_cgroups();
                Ok(())
            });
        }
        let mut child = command
            .current_dir(&cwd)
            .args(["sh", "-c", line.as_str()])
            .envs(module::get_common_script_envs(Some(id)))
            .envs(opts.env.iter().map(|(k, v)| (k.as_str(), v.as_str())))
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| format!("起不来：{e}"))?;

        let out_buf = Arc::new(Mutex::new(String::new()));
        let err_buf = Arc::new(Mutex::new(String::new()));
        let done = Arc::new(AtomicBool::new(false));
        let code = Arc::new(AtomicI32::new(0));

        // 两个读线程：按行累加（和 App 的桥一样，模块拿到的是"一行一个 data 事件"）
        for (pipe, buf) in [
            (child.stdout.take().map(ChildPipe::Out), out_buf.clone()),
            (child.stderr.take().map(ChildPipe::Err), err_buf.clone()),
        ] {
            let Some(pipe) = pipe else { continue };
            std::thread::spawn(move || {
                use std::io::BufRead as _;
                let reader: Box<dyn std::io::Read + Send> = match pipe {
                    ChildPipe::Out(r) => Box::new(r),
                    ChildPipe::Err(r) => Box::new(r),
                };
                let reader = std::io::BufReader::new(reader);
                for line in reader.split(b'\n') {
                    let Ok(mut bytes) = line else { break };
                    bytes.push(b'\n');
                    let text = String::from_utf8_lossy(&bytes).to_string();
                    if let Ok(mut b) = buf.lock()
                        && b.len() < 1024 * 1024 {
                            b.push_str(&text);
                        }
                }
            });
        }
        // 等结束的线程
        {
            let done = done.clone();
            let code = code.clone();
            std::thread::spawn(move || {
                let st = child.wait();
                code.store(st.ok().and_then(|s| s.code()).unwrap_or(-1), Ordering::SeqCst);
                done.store(true, Ordering::SeqCst);
            });
        }

        let sid = webadmin::random_hex(16).ok_or("取不到随机数")?;
        {
            let mut map = spawn_sessions().lock().map_err(|_| "会话表坏了")?;
            // 顺手清理：超过 10 分钟的老会话（防泄漏）
            map.retain(|_, s| s.started.elapsed().as_secs() < 600);
            map.insert(
                sid.clone(),
                SpawnSession {
                    stdout: out_buf,
                    stderr: err_buf,
                    out_cursor: 0,
                    err_cursor: 0,
                    done,
                    code,
                    started: std::time::Instant::now(),
                },
            );
        }
        Ok(json!({ "sid": sid }))
    }

    /// 轮询 spawn 的输出增量（桥每 ~250ms 调一次，直到 done）
    fn module_spawn_poll(&self, sid: &str) -> Result<Value, String> {
        let mut map = spawn_sessions().lock().map_err(|_| "会话表坏了")?;
        map.retain(|_, s| s.started.elapsed().as_secs() < 600);
        let Some(sess) = map.get_mut(sid) else {
            drop(map);
            return Err("会话不存在或已过期".to_string());
        };
        let new_out = {
            let all = sess.stdout.lock().map(|b| b.clone()).unwrap_or_default();
            let from = sess.out_cursor.min(all.len());
            sess.out_cursor = all.len();
            all[from..].to_string()
        };
        let new_err = {
            let all = sess.stderr.lock().map(|b| b.clone()).unwrap_or_default();
            let from = sess.err_cursor.min(all.len());
            sess.err_cursor = all.len();
            all[from..].to_string()
        };
        let done = sess.done.load(Ordering::SeqCst);
        let code = sess.code.load(Ordering::SeqCst);
        let finished = done && new_out.is_empty() && new_err.is_empty();
        if finished {
            map.remove(sid); // 最后一批已经给出去了，收掉
        }
        drop(map);
        Ok(json!({
            "stdout": new_out,
            "stderr": new_err,
            "done": done,
            "code": code,
        }))
    }

    /// 模块网页的静态文件（html 会注入桥脚本，让模块里的 `ksu.*` 能用）
    fn module_web_file(&self, id: &str, rel: &str) -> Result<(String, Vec<u8>), String> {
        use std::path::PathBuf;

        // 🟠（v2.18）**先校验模块 id 再拼路径**：其它所有模块操作（装/卸/启停/读配置）
        // 都调了 `validate_module_id`，只有这里漏了，靠后面的 canonicalize + `starts_with`
        // 兜底。而上游调用侧只做了 `valid_id`（它只挡控制字符和斜杠，**挡不住 `..`
        // 这种以点开头的相对段**）。补齐后与其它入口判据一致（上游同款正则）。
        crate::module::validate_module_id(id).map_err(|e| format!("非法模块 id {id}: {e}"))?;

        let root = PathBuf::from(format!("{}{id}/webroot", crate::defs::MODULE_DIR));
        let root_c = root
            .canonicalize()
            .map_err(|e| format!("模块 {id} 没有网页目录（{e}）"))?;

        let rel2 = {
            let t = rel.trim_start_matches('/');
            if t.is_empty() { "index.html".to_string() } else { t.to_string() }
        };
        // ① 目录穿越：先拼再规范化，规范完必须还在 webroot 里
        let mut target = root_c
            .join(&rel2)
            .canonicalize()
            .map_err(|e| format!("找不到 {rel2}（{e}）"))?;
        if !target.starts_with(&root_c) {
            return Err("路径越界".to_string());
        }
        if target.is_dir() {
            target = target.join("index.html");
        }
        // ② 单文件大小上限（模块可以塞很大的东西，别把内存吃爆）
        let meta = std::fs::metadata(&target).map_err(|e| format!("{e}"))?;
        if meta.len() > 8 * 1024 * 1024 {
            return Err("文件太大（上限 8MB）".to_string());
        }
        let data = std::fs::read(&target).map_err(|e| format!("读不了文件：{e}"))?;
        let ext = target
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_ascii_lowercase();
        let mime = match ext.as_str() {
            "html" | "htm" => "text/html; charset=utf-8",
            "js" | "mjs" => "application/javascript; charset=utf-8",
            "css" => "text/css; charset=utf-8",
            "json" => "application/json; charset=utf-8",
            "svg" => "image/svg+xml",
            "png" => "image/png",
            "jpg" | "jpeg" => "image/jpeg",
            "gif" => "image/gif",
            "webp" => "image/webp",
            "ico" => "image/x-icon",
            "woff" => "font/woff",
            "woff2" => "font/woff2",
            "ttf" => "font/ttf",
            "txt" | "md" | "sh" | "prop" => "text/plain; charset=utf-8",
            _ => "application/octet-stream",
        }
        .to_string();

        if ext == "html" {
            let html = String::from_utf8_lossy(&data).to_string();
            let info = module_info_json(id);
            // 注入：模块信息（同步可读）+ 桥脚本
            // 🔴3（v2.17）：这里的 base **必须**是 `base_path()`（含 `/token`）——
            //   裸 `webadmin::PATH` 会让注入的 `window.__7K_BASE__` 与 `<script src>` 都缺 token，
            //   于是 `modbridge.js` 取不到、`ksu.exec/spawn/...` 全 401（v2.13 鉴权漏改）。
            //   字符串形状由 `webadmin::module_bridge_tag` 统一产出，那条有宿主单测。
            let tag = webadmin::module_bridge_tag(&webadmin::base_path(), &info);
            let out = html.rfind("</body>").map_or_else(
                || format!("{html}{tag}"),
                |pos| format!("{}{}{}", &html[..pos], tag, &html[pos..]),
            );
            log_line(&format!("网页：打开模块 {id} 的网页界面（{rel2}）"));
            return Ok((mime, out.into_bytes()));
        }
        Ok((mime, data))
    }

    /// 上传装模块：zip 已由 HTTP 层落到 `path`，这里调 ksud 自己的安装函数
    fn install_module_zip(&self, path: &str) -> Result<String, String> {
        let before = module_ids();
        module::install_module(path).map_err(|e| format!("安装失败: {e:#}"))?;
        let after = module_ids();
        let added: Vec<&String> = after.iter().filter(|id| !before.contains(id)).collect();
        log_line(&format!("网页上传装模块成功（zip={path}）"));
        Ok(added.first().map_or_else(
            || "安装完成（模块列表里没看到新 id，可能覆盖安装了已有模块）".to_string(),
            |id| format!("已安装模块 {id}，重启手机后生效"),
        ))
    }
}

/// 网页上显示"实际在用的端口"：bound_port 是守护进程绑成功后写回的真实值，
/// 首选端口被占时会顺延 —— 只显示 port 会骗人（2026-09-17 的误判来源之一）。
fn real_port() -> u16 {
    let c = webadmin::Config::load();
    let fallback = if c.bound_port > 0 { c.bound_port } else { c.port };
    // 优先"真的在回话"的端口：配置里的 bound_port 可能是过期值
    webadmin::effective_port().unwrap_or(fallback)
}

/// pid 文件：用来判断常驻进程在不在、以及关闭它
const PID_PATH: &str = "/data/adb/sevenk/webadmin.pid";
/// 日志：设备上到底发生了什么（App 的诊断页会 tail 它）
const LOG_PATH: &str = "/data/adb/sevenk/webadmin.log";
/// 状态文件：给 App 看的自述（pid/端口/时间/错误）
const STATUS_PATH: &str = "/data/adb/sevenk/webadmin.status";
/// 换端口时新进程的 stdout/stderr 落这里（不再丢进 /dev/null，起不来时能查）
const SERVE_OUT_PATH: &str = "/data/adb/sevenk/webadmin-serve.out";

/// 往日志里追加一行（带时间戳）。超过 64KB 就重开，避免无限增长。
pub fn log_line(msg: &str) {
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    if std::fs::metadata(LOG_PATH).is_ok_and(|m| m.len() > 64 * 1024) {
        let _ = std::fs::write(LOG_PATH, "");
    }
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(LOG_PATH) {
        use std::io::Write as _;
        let _ = writeln!(f, "[{ts}] {msg}");
    }
    info!("{msg}");
}

/// 写给 App 看的状态（诊断页直接展示）
fn write_status(extra: &str) {
    let cfg = Config::load();
    let pid = read_pid().map_or_else(|| "无".to_string(), |p| p.to_string());
    // ⚠️ 以前这里写的是常量 PORT(18427)，改过端口后状态文件就是错的
    //    （2026-09-17 的误判来源之一）—— 现在以配置里的实际端口为准。
    let real_port = if cfg.bound_port > 0 { cfg.bound_port } else { cfg.port };
    let text = format!(
        "enabled={}\npid={}\nport={}\nksud={}\nnote={}\n",
        u8::from(cfg.enabled),
        pid,
        real_port,
        std::env::current_exe().map(|p| p.display().to_string()).unwrap_or_default(),
        extra
    );
    let _ = std::fs::write(STATUS_PATH, text);
}

/// 开机（post-fs-data）调用：配置里开着就确保常驻进程在跑。
///
/// 为什么不是"在本进程里起个线程"：ksud 的 `post-fs-data` / `services` 都是
/// **一次性进程**（跑完就退），在里面起线程等于服务跟着一起死。
/// 所以这里 **fork+exec 一个脱离会话的子进程**（`ksud webadmin serve`），
/// 它就常驻了 —— 划掉 App、一键清理、甚至 App 卸载都不影响，重启后由本函数再拉起。
pub fn start_if_enabled() -> bool {
    let cfg = Config::load();
    log_line(&format!(
        "post-fs-data 钩子：enabled={} cfg={}",
        u8::from(cfg.enabled),
        webadmin::CONFIG_PATH
    ));
    if !cfg.enabled {
        write_status("未启用（设置里打开开关即可）");
        return false;
    }
    match ensure_running() {
        Ok(true) => {
            log_line("常驻进程已在运行（或刚拉起）");
            write_status("已拉起");
            true
        }
        Ok(false) => {
            write_status("拉起失败");
            false
        }
        Err(e) => {
            log_line(&format!("拉起异常: {e:#}"));
            write_status(&format!("拉起异常: {e:#}"));
            false
        }
    }
}

/// 在当前进程里前台跑服务（`ksud webadmin serve` 用；也用于调试）
pub fn serve_forever(port: Option<u16>) -> ! {
    if let Some(p) = port {
        webadmin::set_port_override(p);
        log_line(&format!("命令行指定端口 {p}（优先于配置文件）"));
    }
    // 彻底脱离婚管：App 的 root 会话结束时通常会向整组发 SIGHUP，
    // 忽略它，服务才不会被"带走"
    unsafe { libc::signal(libc::SIGHUP, libc::SIG_IGN) };
    // 🔴 最关键的一步：把自己移出 App 的 cgroup。
    // 实测：本进程是从 App 的 root 会话里 fork 出来的，默认落在 /apps/uid_xxx/ 里，
    // 而 force-stop（或 ROM 的"一键清理"）会把 App 的**整个 cgroup**端掉 ——
    // 于是 setsid、PPID=1 全都没用，服务照死。移到根 cgroup（init 那层）之后
    // 再 force-stop App，服务依然活着（2026-09-17 在设备上实测确认）。
    match std::fs::write("/sys/fs/cgroup/cgroup.procs", std::process::id().to_string()) {
        Ok(()) => log_line("已把自己移出 App 的 cgroup（force-stop 也杀不到）"),
        Err(e) => log_line(&format!("移出 cgroup 失败（可能被 force-stop 带走）: {e}")),
    }
    log_line(&format!(
        "serve 启动 pid={} ppid={} port={}",
        std::process::id(),
        unsafe { libc::getppid() },
        webadmin::PORT
    ));
    let server = webadmin::Server::new(Arc::new(KsudBackend));
    match webadmin::serve(&server) {
        Ok(()) => std::process::exit(0),
        Err(e) => {
            log_line(&format!("serve 启动失败（端口被占/权限？）: {e}"));
            write_status(&format!("serve 启动失败: {e}"));
            std::process::exit(1);
        }
    }
}

/// 判断服务是否真的活着：**直接连一下端口**。
///
/// 为什么不用 pid 文件：pid 文件只能证明"我们曾经起过一个进程"，
/// 而实测发现这个守护进程会被反复回收（App 的 root 会话一结束就被带走），
/// 于是每次 `ensure_running()` 都以为该起新的 → 反复拉起、pid 一直变、
/// 浏览器正好撞上死掉的窗口就 ERR_CONNECTION_REFUSED。
/// 用"能不能连上"当判据最实在：连得上就绝不重复拉起。
/// 判活：**必须是我们自己的服务在回话**，不能只看"端口有人听"。
///
/// 踩过的坑（2026-09-17 设备实测）：只探测"端口能不能连"时，被别的 App
/// （测试里我放的 busybox httpd）占着 18427 骗过 —— 于是以为服务在跑、
/// 根本不去启动守护进程，结果全网都连不上。
/// 现在改成：发一个真实的请求，拿到我们自己的 JSON（`{"ok":true`）才算活着。
fn is_listening() -> bool {
    let cfg = webadmin::Config::load();
    // ⚠️ 必须把"用户自定义的端口"也算进来：只看固定的 4 个候选端口的话，
    //    用户把端口改成 18999 之后，这里的判断会一直说"没在跑"，
    //    于是 App 开关 / 开机钩子又会去拉一个新的 —— 越拉越乱（2026-09-17 的问题）。
    candidate_ports(&cfg).iter().any(|p| webadmin::probe_own(*p))
}

/// 扫 /proc 找出所有 `ksud webadmin serve` 的 pid（给停/重启用）
///
/// 🟠（v2.18）**不再只凭 cmdline 认进程**。`/proc/<pid>/cmdline` 是进程自己就能改的
/// （普通 App 也能把自己 argv 改成 "ksud webadmin serve"）—— 旧写法一旦命中就会
/// `SIGTERM`/`SIGKILL` 一个**无关进程**（误杀别人的服务 = 稳定性事故）。
/// 现在再加两道判据：
///   · `/proc/<pid>/exe` 解出来的**可执行文件名**必须是 ksud（`ksud` / `libksud.so`，
///     兼容 APK 升级后路径带 " (deleted)" 的旧进程）；
///   · `/proc/<pid>` 的属主必须是 **root**（ksud 守护进程只会以 root 跑）。
/// 两条都过才认为"是我们自己的网页管理器"。
fn find_daemon_pids() -> Vec<i32> {
    let mut pids = Vec::new();
    let Ok(entries) = std::fs::read_dir("/proc") else {
        return pids;
    };
    for e in entries.flatten() {
        let name = e.file_name();
        let Some(pid) = name.to_str().and_then(|n| n.parse::<i32>().ok()) else {
            continue;
        };
        if pid == std::process::id() as i32 {
            continue;
        }
        let Ok(cmdline) = std::fs::read(e.path().join("cmdline")) else {
            continue;
        };
        let line = String::from_utf8_lossy(&cmdline).replace('\0', " ");
        if !(line.contains("webadmin") && line.contains("serve")) {
            continue;
        }
        if !exe_is_ksud(&e.path().join("exe")) || !proc_owner_is_root(pid) {
            log_line(&format!(
                "find_daemon_pids: pid={pid} 命令行像网页管理器，但 exe/uid 复核不通过 ⇒ 不碰它"
            ));
            continue;
        }
        pids.push(pid);
    }
    pids
}

/// `/proc/<pid>/exe` 解出来的可执行文件名是不是 ksud。
///
/// 允许 `(deleted)` 后缀：APK 升级后老守护进程还跑着被 unlink 的旧 inode，
/// `readlink` 会给 `/data/adb/sevenk/bin/ksud (deleted)` —— 那种**正是要收掉的残留**。
fn exe_is_ksud(exe_link: &std::path::Path) -> bool {
    let Ok(target) = std::fs::read_link(exe_link) else {
        // 读不到（权限/内核线程）⇒ 保守认为"不是"，绝不误杀
        return false;
    };
    let s = target.to_string_lossy();
    let s = s.strip_suffix(" (deleted)").unwrap_or(&s);
    let base = std::path::Path::new(s)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("");
    base == "ksud" || base == "libksud.so"
}

/// `/proc/<pid>` 的属主是不是 root（用 inode 的 uid，不读 status，省一次解析）
fn proc_owner_is_root(pid: i32) -> bool {
    use std::os::unix::fs::MetadataExt;
    std::fs::metadata(format!("/proc/{pid}")).is_ok_and(|m| m.uid() == 0)
}

/// 盘上那份 ksud 的路径（优先 /data/adb 下的，稳定且 root 自己的地盘）
fn ksud_exe() -> Option<std::path::PathBuf> {
    ["/data/adb/sevenk/bin/ksud", "/data/adb/ksud"]
        .iter()
        .map(std::path::PathBuf::from)
        .find(|p| p.is_file())
        .or_else(|| std::env::current_exe().ok())
}

fn file_sha(path: &std::path::Path) -> anyhow::Result<String> {
    let bytes = std::fs::read(path).with_context(|| format!("读不了 {}", path.display()))?;
    Ok(sha256::digest(bytes))
}

/// **"确保在跑，而且跑的就是当前这份 ksud"** —— 打开 App 时调它，而不是无脑 restart。
///
/// 为什么要有它（2026-09-17 用户的体验问题）：App 每次 `onCreate` 都会同步一次开关
/// （开关隐身引起的界面重建也算），原来固定走 `restart` → 服务被停掉再拉起，
/// 有约 1 秒的窗口刷不了网页。其实**绝大多数时候 ksud 根本没换过**，没必要重启。
///
/// 判断依据：守护进程 `/api/ping` 上报的"我自己在跑的那个二进制的 sha256"
/// 与**盘上** ksud 的 sha256。一样 → 什么都不做；不一样（刚升级过 APK）→ 硬重启一次。
///
/// 返回是否真的重启了。
pub fn sync_if_needed() -> anyhow::Result<bool> {
    let cfg = Config::load();
    // 谁在跑？把候选端口都探一遍，拿 pid / 端口 / 二进制指纹
    let ports = candidate_ports(&cfg);
    let found = ports
        .iter()
        .find_map(|p| webadmin::probe_ping(*p).map(|i| (*p, i)));

    let Some((running_port, running)) = found else {
        log_line("sync：没在跑 → 拉起");
        return ensure_running().map(|_| true); // ensure_running 会先收残留再起
    };

    // ① 同一时间只该有一个守护进程：把多余的收掉
    //    （2026-09-17 真机踩到：旧版本有口令、新版本没有，于是新代码探不到旧进程，
    //      又起了一个 → 18427 和 18437 上各一个，地址就不"固定"了）
    let strays: Vec<i32> = find_daemon_pids()
        .into_iter()
        .filter(|p| *p as u32 != running.pid)
        .collect();
    if !strays.is_empty() {
        log_line(&format!(
            "sync：发现 {} 个多余守护进程 {strays:?}（占着别的端口）→ 收掉",
            strays.len()
        ));
        for p in &strays {
            unsafe { libc::kill(*p, libc::SIGTERM) };
        }
        std::thread::sleep(std::time::Duration::from_millis(400));
    }

    // ② 二进制一样吗？
    let exe = ksud_exe();
    let disk_sha = exe
        .as_ref()
        .map(|e| file_sha(e).unwrap_or_default())
        .unwrap_or_default();
    let same = webadmin::ksud_same(&running.bin_sha, &disk_sha);

    // ③ 端口对不对？首选端口空着的话，就该待在首选端口上（这样地址才是那个固定地址）
    let preferred = if cfg.port >= 1024 { cfg.port } else { webadmin::PORT };
    let on_preferred = running_port == preferred;
    let preferred_taken_by_other = webadmin::probe_ping(preferred).is_some();

    if same && (on_preferred || preferred_taken_by_other) {
        let why = if on_preferred {
            "端口也对".to_string()
        } else {
            format!("{preferred} 被别的程序占着")
        };
        log_line(&format!(
            "sync：守护进程(pid={})跑的就是盘上这份 ksud，而且{why} → 跳过重启",
            running.pid
        ));
        write_status("已在运行，且就是当前 ksud（未重启）");
        return Ok(false);
    }

    if same {
        log_line(&format!(
            "sync：ksud 没变，但端口从 {running_port} 挪回首选端口 {preferred}（那边已经空出来了）→ 重启一次"
        ));
    } else {
        log_line(&format!(
            "sync：ksud 变过（跑着的 {} / 盘上 {}）→ 重启一次",
            short(&running.bin_sha),
            short(&disk_sha)
        ));
    }
    restart().map(|_| true)
}

/// 候选端口：配置里的实际端口 / 首选端口 / 固定备选（去重）
fn candidate_ports(cfg: &Config) -> Vec<u16> {
    let mut ports: Vec<u16> = Vec::new();
    if cfg.bound_port > 0 {
        ports.push(cfg.bound_port);
    }
    if cfg.port > 0 && !ports.contains(&cfg.port) {
        ports.push(cfg.port);
    }
    for p in webadmin::CANDIDATE_PORTS {
        if !ports.contains(&p) {
            ports.push(p);
        }
    }
    ports
}

/// spawn 会话：缓冲 + 读游标 + 退出状态
struct SpawnSession {
    stdout: Arc<Mutex<String>>,
    stderr: Arc<Mutex<String>>,
    out_cursor: usize,
    err_cursor: usize,
    done: Arc<AtomicBool>,
    code: Arc<AtomicI32>,
    started: std::time::Instant,
}

enum ChildPipe {
    Out(std::process::ChildStdout),
    Err(std::process::ChildStderr),
}

/// 全局 spawn 会话表
fn spawn_sessions() -> &'static Mutex<std::collections::HashMap<String, SpawnSession>> {
    static SESSIONS: std::sync::OnceLock<Mutex<std::collections::HashMap<String, SpawnSession>>> =
        std::sync::OnceLock::new();
    SESSIONS.get_or_init(|| Mutex::new(std::collections::HashMap::new()))
}

/// 把参数安全地拼进 shell 命令行（单引号包裹 + 内部单引号转义）
fn shell_quote(s: &str) -> String {
    if !s.is_empty()
        && s.chars()
            .all(|c| c.is_ascii_alphanumeric() || "._-/:@=+,".contains(c))
    {
        return s.to_string();
    }
    format!("'{}'", s.replace('\'', "'\\''"))
}

/// 模块信息（形状对齐管理器 App 的 ksu.moduleInfo：module.prop 的字段 + moduleDir）
fn module_info_json(id: &str) -> String {
    let mut obj = serde_json::Map::new();
    obj.insert("id".to_string(), json!(id));
    obj.insert(
        "moduleDir".to_string(),
        json!(format!("{}{id}", crate::defs::MODULE_DIR)),
    );
    if let Ok(text) = module::list_modules_json()
        && let Ok(Value::Array(list)) = serde_json::from_str::<Value>(&text)
    {
        for m in list {
            if m.get("id").and_then(|v| v.as_str()) == Some(id) {
                if let Some(o) = m.as_object() {
                    for (k, v) in o {
                        obj.insert(k.clone(), v.clone());
                    }
                }
                obj.insert("id".to_string(), json!(id));
                break;
            }
        }
    }
    Value::Object(obj).to_string()
}

fn short(sha: &str) -> String {
    if sha.is_empty() {
        "空".to_string()
    } else {
        // 🟠（v2.19）旧写法是 `&sha[..sha.len().min(12)]` —— 按**字节**切片：
        // 第 12 个字节正好落在多字节字符中间时**直接 panic**（release 是 `panic = "abort"`）。
        // `bin_sha` 来自"探活"到的那个进程（本机任意 App 占住被探测的端口就能回包），
        // 所以这条是**本机可达**的崩溃路径。改成按**字符**取前 12 个。
        format!("{}…", sha.chars().take(12).collect::<String>())
    }
}


/// 确保常驻进程在跑；返回是否（现在）在跑
pub fn ensure_running() -> anyhow::Result<bool> {
    // 连得上 = 已经在跑，绝不再拉一个（避免 pid 反复变化的死循环）
    if is_listening() {
        write_status("已在运行（端口可连）");
        return Ok(true);
    }
    // 🔴 探不到 ≠ 没有守护进程：**旧版本（有口令那几版）的守护进程对"不带口令"的探测
    //    一律回 404**，于是升级到"没口令"这一版时，会以为没在跑、又起一个 ——
    //    结果旧进程还占着 18427、新进程只能顺延到 18437，地址就不"固定"了
    //    （2026-09-17 真机踩到：18427 和 18437 上各有一个 ksud）。
    //    所以：先看有没有残留的守护进程，有就收掉，再起新的。
    let stale = find_daemon_pids();
    if !stale.is_empty() {
        log_line(&format!(
            "探不到服务，但发现 {} 个残留守护进程 {stale:?}（多半是旧版本占着端口）→ 先收掉",
            stale.len()
        ));
        for pid in &stale {
            unsafe { libc::kill(*pid, libc::SIGTERM) };
        }
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
    // 优先用"已安装到 /data/adb 的 ksud"当守护进程：
    // 从 APK 的 lib 目录里 exec 有可能被 SELinux 拦（app 数据目录不允许执行），
    // 而 /data/adb/sevenk/bin/ksud 是 root 自己的地盘，稳。
    let exe = ksud_exe().ok_or_else(|| anyhow::anyhow!("找不到可执行的 ksud"))?;
    log_line(&format!("拉起常驻进程: {}", exe.display()));
    let mut cmd = std::process::Command::new(exe);
    cmd.arg("webadmin").arg("serve");
    cmd.stdin(std::process::Stdio::null());
    // 常驻进程的 stdout/stderr 落文件（**别丢 /dev/null**：起不来时一点线索都没有）。
    // 打不开就退回丢弃，不影响启动。
    match std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(SERVE_OUT_PATH)
    {
        Ok(f) => match f.try_clone() {
            Ok(f2) => {
                cmd.stdout(std::process::Stdio::from(f));
                cmd.stderr(std::process::Stdio::from(f2));
            }
            Err(_) => {
                cmd.stdout(std::process::Stdio::null())
                    .stderr(std::process::Stdio::null());
            }
        },
        Err(_) => {
            cmd.stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null());
        }
    }
    // 脱离会话 + 忽略挂断信号：
    //   setsid()  → 新会话、新进程组、不再有控制终端；父进程退出后被 init 收养
    //   SIGHUP    → exec 后会被重置为默认，所以在 serve_forever() 里再显式忽略一次
    //   stdin 指向 /dev/null（上面已设），避免占用 App 的会话管道；
    //   stdout/stderr 进 webadmin-serve.out，方便事后查
    unsafe {
        use std::os::unix::process::CommandExt;
        cmd.pre_exec(|| {
            libc::setsid();
            Ok(())
        });
    }
    let child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            log_line(&format!("spawn 失败: {e}"));
            write_status(&format!("spawn 失败: {e}"));
            return Err(e.into());
        }
    };
    std::fs::write(PID_PATH, child.id().to_string())?;
    // 父进程这边也试着把子进程挪出 App 的 cgroup（子进程自己也会挪一次，双保险）
    let _ = std::fs::write("/sys/fs/cgroup/cgroup.procs", child.id().to_string());
    log_line(&format!("常驻进程已启动 pid={}", child.id()));
    write_status("常驻进程已启动");
    Ok(true)
}

/// 停掉常驻进程
#[allow(clippy::unnecessary_wraps)] // 现在不会失败，但保留 Result 免得以后加失败路径时改一圈调用方
pub fn stop_running() -> anyhow::Result<()> {
    let mut killed = Vec::new();
    // 🟠（v2.19）先拿**复核过的**那份 pid 列表：`find_daemon_pids()` 用
    // `/proc/<pid>/exe` + 属主 uid 两道复核（见它自己的注释），而 pid 文件里那个数字
    // 只经过 `process_alive()`（`kill(pid, 0)`）—— 机器重启后 **PID 会被复用**，
    // 光凭它就 SIGTERM 会误杀一个无关进程（本文件上面那段注释自己就说这是"稳定性事故"）。
    // 现在：pid 文件里那个 pid **必须**出现在复核列表里才许单独杀。
    let confirmed: Vec<i32> = find_daemon_pids();
    if let Some(pid) = read_pid()
        && process_alive(pid)
        && confirmed.contains(&(pid as i32))
    {
        unsafe { libc::kill(pid as i32, libc::SIGTERM) };
        killed.push(pid);
    }
    // 复核过的那份列表照旧全收（pid 文件可能已经过期，漏网的 serve 进程也收掉）
    for pid in confirmed {
        if killed.contains(&(pid as u32)) {
            continue;
        }
        unsafe { libc::kill(pid, libc::SIGTERM) };
        killed.push(pid as u32);
    }
    let _ = std::fs::remove_file(PID_PATH);
    log_line(&format!("stop_running: 已停止 {killed:?}"));
    Ok(())
}

/// 重启（`ksud webadmin restart`）：停掉再拉起，用来"把服务救活"
pub fn restart() -> anyhow::Result<bool> {
    stop_running()?;
    std::thread::sleep(std::time::Duration::from_millis(500));
    ensure_running()
}

fn read_pid() -> Option<u32> {
    std::fs::read_to_string(PID_PATH)
        .ok()
        .and_then(|s| s.trim().parse::<u32>().ok())
}

fn process_alive(pid: u32) -> bool {
    // kill(pid, 0) 探活
    unsafe { libc::kill(pid as i32, 0) == 0 }
}

fn kernel_release() -> String {
    std::fs::read_to_string("/proc/sys/kernel/osrelease")
        .map(|s| s.trim().to_string())
        .unwrap_or_default()
}

/// 从 uname 推 KMI（如 5.10.198-android12-9-… → android12-5.10）
fn kmi_of(release: &str) -> String {
    let ver = release
        .split(['-', '.'])
        .take(2)
        .collect::<Vec<_>>()
        .join(".");
    let major = release
        .split("android")
        .nth(1)
        .and_then(|s| s.chars().take_while(char::is_ascii_digit).collect::<String>().parse::<u32>().ok())
        .or(match ver.as_str() {
            "5.10" => Some(12),
            "5.15" => Some(13),
            "6.1" => Some(14),
            "6.6" => Some(15),
            "6.12" => Some(16),
            _ => None,
        });
    match major {
        Some(m) if !ver.is_empty() => format!("android{m}-{ver}"),
        Some(m) => format!("android{m}"),
        None => String::new(),
    }
}

fn is_gki(release: &str) -> bool {
    ["5.10", "5.15", "6.1", "6.6", "6.12"]
        .iter()
        .any(|v| release.starts_with(&format!("{v}.")))
}

fn device_model() -> String {
    for path in ["/system/build.prop", "/vendor/build.prop", "/system/system/build.prop"] {
        if let Ok(text) = std::fs::read_to_string(path) {
            for line in text.lines() {
                for key in ["ro.product.model=", "ro.product.vendor.model=", "ro.product.system.model="] {
                    if let Some(v) = line.strip_prefix(key) {
                        let v = v.trim();
                        if !v.is_empty() {
                            return v.to_string();
                        }
                    }
                }
            }
        }
    }
    String::new()
}

fn stealth_enabled() -> bool {
    // 注意：内核只允许"管理器 uid"读隐身状态，root 会被拒 —— 所以要借管理器身份
    ksucalls::stealth_get_authed().unwrap_or(false)
}

/// 隐身密令（退出隐身用；只有拿到口令的本机页面能看）
fn stealth_code() -> String {
    std::fs::read_to_string("/data/adb/sevenk/stealth_code").map_or_else(|_| "70707".to_string(), |s| s.trim().to_string())
}

/// 模块 id 列表（判空用）
fn module_ids() -> Vec<String> {
    module::list_modules_json()
        .ok()
        .and_then(|t| serde_json::from_str::<Vec<serde_json::Map<String, Value>>>(&t).ok())
        .map(|v| {
            v.iter()
                .filter_map(|m| m.get("id").and_then(|i| i.as_str()).map(str::to_string))
                .collect()
        })
        .unwrap_or_default()
}

/// uid → 包名（读 /data/system/packages.list，ksud 是 root 读得到）
fn uid_package_map() -> std::collections::HashMap<u32, String> {
    let mut map = std::collections::HashMap::new();
    let Ok(text) = std::fs::read_to_string("/data/system/packages.list") else {
        return map;
    };
    for line in text.lines() {
        let mut it = line.split_whitespace();
        let (Some(pkg), Some(uid)) = (it.next(), it.next()) else {
            continue;
        };
        if let Ok(uid) = uid.parse::<u32>() {
            map.insert(uid, pkg.to_string());
        }
    }
    map
}

/// 命令行用：当前状态。
///
/// ⚠️ **这里刻意不打印密钥**：这条输出会被 App 的「诊断」功能整段抓走、用户再复制发出去，
/// 带上密钥就等于把 root 通道一起发出去了。要完整地址请用 `ksud webadmin url`（只进 stdout）。
pub fn status_text() -> String {
    let cfg = Config::load();
    let port = if cfg.bound_port > 0 { cfg.bound_port } else { cfg.port };
    format!(
        "enabled={} url=http://127.0.0.1:{port}{}/<专属密钥已隐藏> token={}",
        u8::from(cfg.enabled),
        webadmin::PATH,
        if cfg.token.is_empty() { "无" } else { "已设置" },
    )
}

/// 命令行用：开关（并立即生效/停止需要重启 ksud，这里只落盘 + 提示）
pub fn set_enabled(enable: bool) -> anyhow::Result<()> {
    let mut cfg = Config::load();
    cfg.enabled = enable;
    // 🔑 开启时确保有一把访问密钥（老版本升级上来的配置里没有这个键）。
    //    ⚠️ 只在**缺失**时生成：关掉再打开必须沿用同一把，否则用户书签/收藏全失效。
    let generated = cfg.ensure_token();
    cfg.save()?;
    if generated {
        info!("网页管理器首次生成访问密钥（已写入 webadmin.conf，0600）");
    }
    info!("网页管理器 enabled={enable}");
    Ok(())
}

/// 命令行用：换一把访问密钥（`ksud webadmin reset-token`）。
///
/// 返回值是新的完整地址 —— **只往 stdout 打，不进日志、不进 `webadmin.status`**
/// （日志别的 App 读得到，stdout 只有调用方拿得到）。
///
/// 不需要重启常驻进程：守护进程**每个请求都重新读配置文件里的密钥**
/// （见 `webadmin.rs::handle` 的鉴权段），所以换完立刻生效、老密钥立刻失效，
/// 也就不会因为重启而让端口顺延、用户地址漂移。
pub fn reset_token_url() -> anyhow::Result<String> {
    let cfg = Config::load();
    let port = if cfg.bound_port > 0 { cfg.bound_port } else { cfg.port };
    let token = webadmin::reset_token()?;
    Ok(webadmin::url_with(webadmin::effective_port().unwrap_or(port), &token))
}

/// 让 `ksu_uapi` 的引用不被优化掉（ioctl 常量来自 bindgen）
#[allow(dead_code)]
const fn _uapi_marker() -> u32 {
    ksu_uapi::KSU_IOCTL_STEALTH_GET
}

// ══════════════════════════════════════════════════════════════════
// 第二批用到的辅助函数
// ══════════════════════════════════════════════════════════════════

/// 默认 SELinux 域（与内核 `KERNEL_SU_CONTEXT`、App 的 `KERNEL_SU_DOMAIN` 一致）
const DEFAULT_SELINUX_DOMAIN: &str = "u:r:ksu:s0";
/// profile 的 flags 第 0 位：禁止在这个 root 身份下再用 KernelSU 提权
const FLAG_KSU_NO_NEW_PRIVS: u64 = 1;

/// 已安装的包 → uid（读 `/data/system/packages.list`，root 可读）
fn package_list() -> Vec<(String, u32)> {
    let mut v = Vec::new();
    let Ok(text) = std::fs::read_to_string("/data/system/packages.list") else {
        warn!("读不到 /data/system/packages.list，超级用户列表会是空的");
        return v;
    };
    for line in text.lines() {
        let mut it = line.split_whitespace();
        let (Some(pkg), Some(uid)) = (it.next(), it.next()) else {
            continue;
        };
        if let Ok(uid) = uid.parse::<u32>() {
            v.push((pkg.to_string(), uid));
        }
    }
    v
}

const fn root_profile_of(p: &app_profile) -> RootBranch {
    // 读 union 成员必须 unsafe；写不需要
    unsafe { p.__bindgen_anon_1.rp_config }
}

const fn non_root_profile_of(p: &app_profile) -> NonRootBranch {
    unsafe { p.__bindgen_anon_1.nrp_config }
}

const fn set_root_branch(p: &mut app_profile, v: &RootBranch) {
    p.__bindgen_anon_1.rp_config = *v;
}

const fn set_nonroot_branch(p: &mut app_profile, v: NonRootBranch) {
    p.__bindgen_anon_1.nrp_config = v;
}

/// root 分支的默认值（与 App 新建 profile 时的取值一致）
fn default_root_branch() -> RootBranch {
    let mut rp: RootBranch = unsafe { std::mem::zeroed() };
    rp.use_default = true;
    rp.profile.uid = 0;
    rp.profile.gid = 0;
    rp.profile.groups_count = 0;
    rp.profile.namespaces = 0;
    rp.profile.flags = FLAG_KSU_NO_NEW_PRIVS;
    ksucalls::write_cstr(&mut rp.profile.selinux_domain, DEFAULT_SELINUX_DOMAIN);
    rp
}

/// 非 root 分支的默认值（不卸载模块）
const fn default_nonroot_branch() -> NonRootBranch {
    let mut nrp: NonRootBranch = unsafe { std::mem::zeroed() };
    nrp.use_default = true;
    nrp.profile.umount_modules = true;
    nrp
}

fn base_profile(pkg: &str, uid: u32) -> app_profile {
    let mut p: app_profile = unsafe { std::mem::zeroed() };
    p.version = ksu_uapi::KSU_APP_PROFILE_VER;
    write_key(&mut p, pkg);
    p.curr_uid = uid as i32;
    p
}

fn write_key(p: &mut app_profile, pkg: &str) {
    ksucalls::write_cstr(&mut p.key, pkg);
}

/// 新授权一个 App（root 分支，默认配置）
fn new_root_profile(pkg: &str, uid: u32) -> app_profile {
    let mut p = base_profile(pkg, uid);
    p.allow_su = true;
    set_root_branch(&mut p, &default_root_branch());
    p
}

/// 新写一个非 root 配置（撤销 root / 只配非 root 策略）
fn new_nonroot_profile(pkg: &str, uid: u32) -> app_profile {
    let mut p = base_profile(pkg, uid);
    p.allow_su = false;
    set_nonroot_branch(&mut p, default_nonroot_branch());
    p
}

fn parse_i32(v: &str, what: &str) -> Result<i32, String> {
    v.trim()
        .parse::<i32>()
        .map_err(|_| format!("{what} 要是整数"))
}

fn parse_groups(v: &str) -> Result<Vec<i32>, String> {
    let max = ksu_uapi::KSU_MAX_GROUPS as usize;
    let mut out = Vec::new();
    for part in v.split([',', '，', ';', ' ', '\n', '\t']) {
        let part = part.trim();
        if part.is_empty() {
            continue;
        }
        let n = part
            .parse::<i32>()
            .map_err(|_| format!("附加组 ID「{part}」不是数字"))?;
        if n < 0 {
            return Err("附加组 ID 不能是负数".to_string());
        }
        out.push(n);
        if out.len() > max {
            return Err(format!("附加组最多 {max} 个"));
        }
    }
    Ok(out)
}

/// sulogd 落盘的日志文件（`sulog-YYYY-MM-DD[-N].log`），新的在前
fn sulog_files() -> Vec<(String, std::path::PathBuf)> {
    let mut v = Vec::new();
    let Ok(rd) = std::fs::read_dir(crate::defs::LOG_DIR) else {
        return v;
    };
    for e in rd.flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        // 只认日志本体，别碰 sulogd.lock
        let is_log = std::path::Path::new(&name)
            .extension()
            .is_some_and(|e| e.eq_ignore_ascii_case("log"));
        if !name.starts_with("sulog-") || !is_log {
            continue;
        }
        v.push((name, e.path()));
    }
    v.sort_by_key(|(name, _)| std::cmp::Reverse(log_key(name)));
    v
}

/// 文件名 → 排序键（日期 + 序号）。注意不能直接按字符串排：
/// `sulog-2026-09-17-1.log` 的 '-' 比 `.` 小，字符串序会把 1 号排到主文件前面。
fn log_key(name: &str) -> (String, u32) {
    let stem = name
        .strip_prefix("sulog-")
        .and_then(|s| s.strip_suffix(".log"))
        .unwrap_or(name);
    match stem.rsplit_once('-') {
        Some((date, idx)) if date.len() == 10 && idx.as_bytes().iter().all(u8::is_ascii_digit) => {
            (date.to_string(), idx.parse().unwrap_or(0))
        }
        _ => (stem.to_string(), 0),
    }
}

