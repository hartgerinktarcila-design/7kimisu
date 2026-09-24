use anyhow::{Context, Ok, Result};
use clap::Parser;
use std::path::PathBuf;

use android_logger::Config;
use log::{LevelFilter, error, info};

use crate::boot_patch::{BootPatchArgs, BootRestoreArgs};
use crate::lkm_image::BootPatchV2Args;
use crate::module::regenerate_preinit_rc;
use crate::{
    apk_sign, assets, debug, defs, init_event, ksu_uapi, ksucalls, migrate, module, module_config,
    sulog, utils,
};

/// KernelSU userspace cli
#[derive(Parser, Debug)]
#[command(author, version = defs::FULL_VERSION, about, long_about = None)]
struct Args {
    #[command(subcommand)]
    command: Commands,
}

/// 网页管理器的子命令
#[derive(clap::Subcommand, Debug)]
enum Webadmin {
    /// 打开（下次开机或 restart 后生效）
    On,
    /// 关闭
    Off,
    /// 看状态（enabled / token / url）
    Status,
    /// 只打印访问地址（**含专属密钥**，直接复制到浏览器就能用）
    Url,
    /// 换一把访问密钥（用户忘了/怕泄露时用；老链接立刻失效）
    ResetToken,
    /// 在后台起一个（当前进程内，用于调试；正常由开机流程启动）
    Serve {
        /// 指定监听端口（优先于配置文件；换端口靠它）
        #[arg(long)]
        port: Option<u16>,
    },
    /// 重启常驻进程（服务"救活"用：先停再起）
    Restart,
    /// 确保在跑、且跑的是当前这份 ksud；**已经是同一个就不动**
    /// （App 每次打开都调它，避免无谓地断一下服务）
    Sync,
}

#[derive(clap::Subcommand, Debug)]
enum Commands {
    /// Manage KernelSU modules
    Module {
        #[command(subcommand)]
        command: Module,
    },

    /// 内置网页管理器（跑在 ksud 里，不依赖 App）
    Webadmin {
        #[command(subcommand)]
        command: Webadmin,
    },

    /// Trigger `post-fs-data` event
    PostFsData,

    /// Trigger `service` event
    Services,

    /// Run sulog reader daemon. Not for user. Use `ksud debug sulogd` to launch daemon.
    #[command(hide = true)]
    Sulogd,

    /// Trigger `boot-complete` event
    BootCompleted,

    /// Load sevenk.ko and execute late-load stage scripts
    LateLoad {
        /// Use adb root to execute late-load for jailbreaking by Magica
        #[arg(long, default_missing_value = "5555", num_args = 0..=1)]
        magica: Option<u16>,

        /// Pass allow_shell=1 when loading sevenk.ko
        #[arg(long)]
        allow_shell: bool,

        /// Restore adb properties after magica late-load
        #[arg(long)]
        post_magica: bool,

        /// Specify kernel KMI version instead of auto-detection
        #[arg(long)]
        kmi: Option<String>,

        /// manager package name
        #[arg(long, default_value_t = String::from(defs::DEFAULT_PACKAGE_NAME))]
        package_name: String,
    },

    /// Emulate system reboot
    SoftReboot,

    /// Load a kernel module with kallsyms access
    Insmod {
        /// kernel module path
        module: PathBuf,
        /// module load parameters (e.g. key=val key2=val2)
        #[arg(trailing_var_arg = true, allow_hyphen_values = true, num_args = 0..)]
        params: Vec<String>,
    },

    /// Install KernelSU userspace component to system
    Install {
        #[arg(long, default_value = None)]
        libadbroot: Option<PathBuf>,

        #[arg(long, default_value = None)]
        data_path: Option<PathBuf>,
    },

    /// Unload KernelSU kernel module (LKM Only)
    Unload,

    /// Uninstall KernelSU modules and itself(LKM Only)
    Uninstall {
        #[arg(long, default_value_t = String::from(defs::DEFAULT_PACKAGE_NAME))]
        package_name: String,
    },

    /// SELinux policy Patch tool
    Sepolicy {
        #[command(subcommand)]
        command: Sepolicy,
    },

    /// Manage App Profiles
    Profile {
        #[command(subcommand)]
        command: Profile,
    },

    /// Manage kernel features
    Feature {
        #[command(subcommand)]
        command: Feature,
    },

    /// Patch boot or init_boot images to apply KernelSU
    BootPatch(BootPatchArgs),

    /// Restore boot or init_boot images patched by KernelSU
    BootRestore(BootRestoreArgs),

    /// Patch KernelSU into a boot image
    ///
    /// Always operates on a boot image; never selects init_boot or vendor_boot.
    BootPatchV2(BootPatchV2Args),

    /// Show boot information
    BootInfo {
        #[command(subcommand)]
        command: BootInfo,
    },
    /// For developers
    Debug {
        #[command(subcommand)]
        command: Debug,
    },
    /// Kernel interface
    Kernel {
        #[command(subcommand)]
        command: Kernel,
    },

    /// Resetprop - Magisk-compatible system property tool
    #[command(disable_help_flag = true)]
    Resetprop {
        /// Arguments passed to resetprop
        #[arg(trailing_var_arg = true, allow_hyphen_values = true, num_args = 0..)]
        args: Vec<String>,
    },

    /// Manage initrc injection
    Initrc {
        #[command(subcommand)]
        command: Initrc,
    },

    /// 把旧数据目录 /data/adb/ksu 搬到 /data/adb/sevenk（改名迁移；可反复跑）
    ///
    /// ⚠️ 只有**确认新内核在跑**（它读的是 /data/adb/sevenk）才会搬：
    /// 老内核读的还是 /data/adb/ksu，搬走会让它读不到隐身标志/授权名单。
    /// 复制 → 逐字节校验 → 才删旧的；任何冲突/修复都保留旧目录、绝不删一半。
    Migrate {
        /// 忽略上次的冲突标记，强制重试一次（**不会**绕过"新内核"门控）
        #[arg(long, default_value_t = false)]
        force: bool,
        /// 只打印门控探测结论（`KERNEL_DATADIR=new_datadir|legacy_datadir|unknown`），给 App 预检用
        #[arg(long = "probe-only", default_value_t = false)]
        probe_only: bool,
        /// 🔴1（v2.11）**采纳**旧目录（含改名留存的 `ksu.legacy_backup_*`）里"新目录还没有"的
        /// `.allowlist` 记录：按 `curr_uid` 做**并集合并**（同 UID 以新目录那份为准）。
        ///
        /// 不会无脑 `--force`：不清冲突标记、不重扫/重搬旧目录，只合并名单；
        /// 写前留备份、写后逐字节校验。用完**必须重启**（内核只在开机读一次名单）。
        #[arg(long = "adopt-legacy-allowlist", default_value_t = false)]
        adopt_legacy_allowlist: bool,
    },

    /// 🔴2（v2.11）从**卸载备份**恢复用户数据（App 首页一键恢复也走它）
    ///
    /// 备份是 `ksud uninstall` 时自动留在 `/data/adb/sevenk_uninstall_backup_<时间戳>/` 的
    /// （`.allowlist` / `stealth` / `stealth_code` / `module_configs/` / `profile/` /
    /// `.feature_config`）。本命令**默认取最新一份**，逐字节校验后覆盖/新增回去；
    /// 动手前会先把当前数据快照到 `<备份>/pre_restore_<时间戳>/`（可回退），
    /// **绝不删除**任何现有文件。恢复完请重启一次。
    RestoreUserData {
        /// 指定备份目录（绝对路径，或相对 `/data/adb` 的目录名）；默认取最新一份
        #[arg(long)]
        from: Option<PathBuf>,
    },
}

#[derive(clap::Subcommand, Debug)]
enum BootInfo {
    /// show current kmi version
    CurrentKmi,

    /// show supported kmi versions
    SupportedKmis,

    /// check if device is A/B capable
    IsAbDevice,

    /// show auto-selected boot partition name
    DefaultPartition,

    /// list available partitions for current or OTA toggled slot
    AvailablePartitions,

    /// show slot suffix for current or OTA toggled slot
    SlotSuffix {
        /// toggle to another slot
        #[arg(short = 'u', long, default_value = "false")]
        ota: bool,
    },

    /// print the ramdisk state of a boot image:
    /// `ours` | `compatible` | `foreign` | `stock` (or `unknown` when it cannot be read).
    ///
    /// READ-ONLY: never writes to any partition. `compatible` means the image was patched by
    /// official KernelSU (or 7kimisu <= v0.13.151) and a direct install is safe.
    RamdiskState {
        /// boot image path; default: the auto-detected boot partition of this device
        #[arg(short, long, default_value = None)]
        boot: Option<PathBuf>,
    },
}

#[derive(clap::Subcommand, Debug)]
enum Debug {
    /// Set the manager app, kernel CONFIG_KSU_DEBUG should be enabled.
    SetManager {
        /// manager package name
        #[arg(default_value_t = String::from(defs::DEFAULT_PACKAGE_NAME))]
        apk: String,
    },

    /// Get apk size and hash
    GetSign {
        /// apk path
        apk: String,
    },

    /// Root Shell
    Su {
        /// switch to gloabl mount namespace
        #[arg(short, long, default_value = "false")]
        global_mnt: bool,
    },

    /// Get kernel version
    Version,

    /// For testing
    Test,

    /// Extract an embedded binary to a specified path
    ExtractBinary {
        /// binary name (e.g. busybox, resetprop, bootctl)
        name: String,
        /// destination file path
        path: PathBuf,
    },

    /// Process mark management
    Mark {
        #[command(subcommand)]
        command: MarkCommand,
    },

    /// Launch sulogd daemon manually
    Sulogd,

    /// Get kernel info
    Info,

    /// Print default package name
    Package,
}

#[derive(clap::Subcommand, Debug)]
enum MarkCommand {
    /// Get mark status for a process (or all)
    Get {
        /// target pid (0 for total count)
        #[arg(default_value = "0")]
        pid: i32,
    },

    /// Mark a process
    Mark {
        /// target pid (0 for all processes)
        #[arg(default_value = "0")]
        pid: i32,
    },

    /// Unmark a process
    Unmark {
        /// target pid (0 for all processes)
        #[arg(default_value = "0")]
        pid: i32,
    },

    /// Refresh mark for all running processes
    Refresh,
}

#[derive(clap::Subcommand, Debug)]
enum Sepolicy {
    /// Patch sepolicy
    Patch {
        /// sepolicy statements
        sepolicy: String,
    },

    /// Apply sepolicy from file
    Apply {
        /// sepolicy file path
        file: String,
    },

    /// Check if sepolicy statement is supported/valid
    Check {
        /// sepolicy statements
        sepolicy: String,
    },
}

#[derive(clap::Subcommand, Debug)]
enum Module {
    /// Install module <ZIP>
    Install {
        /// module zip file path
        zip: String,
    },

    /// Undo module uninstall mark <id>
    UndoUninstall {
        /// module id
        id: String,
    },

    /// Uninstall module <id>
    Uninstall {
        /// module id
        id: String,
    },

    /// enable module <id>
    Enable {
        /// module id
        id: String,
    },

    /// disable module <id>
    Disable {
        // module id
        id: String,
    },

    /// run action for module <id>
    Action {
        // module id
        id: String,
    },

    /// list all modules
    List,

    /// manage module configuration
    Config {
        /// target internal module name (resolved as internal.<name>)
        #[arg(long)]
        internal: Option<String>,
        #[command(subcommand)]
        command: ModuleConfigCmd,
    },
}

#[derive(clap::Subcommand, Debug)]
enum ModuleConfigCmd {
    /// Get a config value
    Get {
        /// config key
        key: String,
    },

    /// Set a config value
    Set {
        /// config key
        key: String,
        /// config value (omit to read from stdin)
        value: Option<String>,
        /// read value from stdin (default if value not provided)
        #[arg(long)]
        stdin: bool,
        /// use temporary config (cleared on reboot)
        #[arg(short, long)]
        temp: bool,
    },

    /// List all config entries
    List,

    /// Delete a config entry
    Delete {
        /// config key
        key: String,
        /// delete from temporary config
        #[arg(short, long)]
        temp: bool,
    },

    /// Clear all config entries
    Clear {
        /// clear temporary config
        #[arg(short, long)]
        temp: bool,
    },
}

#[derive(clap::Subcommand, Debug)]
enum Profile {
    /// get root profile's selinux policy of <package-name>
    GetSepolicy {
        /// package name
        package: String,
    },

    /// set root profile's selinux policy of <package-name> to <profile>
    SetSepolicy {
        /// package name
        package: String,
        /// policy statements
        policy: String,
    },

    /// get template of <id>
    GetTemplate {
        /// template id
        id: String,
    },

    /// set template of <id> to <template string>
    SetTemplate {
        /// template id
        id: String,
        /// template string
        template: String,
    },

    /// delete template of <id>
    DeleteTemplate {
        /// template id
        id: String,
    },

    /// list all templates
    ListTemplates,
}

#[derive(clap::Subcommand, Debug)]
enum Feature {
    /// Get feature value and support status
    Get {
        /// Feature ID or name (su_compat, kernel_umount, sulog, adb_root, selinux_hide)
        id: String,
        /// Read from config file
        #[arg(long, default_value_t = false)]
        config: bool,
    },

    /// Set feature value
    Set {
        /// Feature ID or name
        id: String,
        /// Feature value (0=disable, 1=enable)
        value: u64,
    },

    /// List all available features
    List,

    /// Check feature status (supported/unsupported/managed)
    Check {
        /// Feature ID or name (su_compat, kernel_umount, sulog, adb_root, selinux_hide)
        id: String,
    },

    /// Load configuration from file and apply to kernel
    Load,

    /// Save current kernel feature states to file
    Save,
}

#[derive(clap::Subcommand, Debug)]
enum Kernel {
    /// Nuke ext4 sysfs
    NukeExt4Sysfs {
        /// mount point
        mnt: String,
    },
    /// Manage umount list
    Umount {
        #[command(subcommand)]
        command: UmountOp,
    },
    /// Notify that module is mounted
    NotifyModuleMounted,
}

#[derive(clap::Subcommand, Debug)]
enum UmountOp {
    /// Add mount point to umount list
    Add {
        /// mount point path
        mnt: String,
        /// umount flags (default: 0, MNT_DETACH: 2)
        #[arg(short, long, default_value = "0")]
        flags: u32,
    },
    /// Delete mount point from umount list
    Del {
        /// mount point path
        mnt: String,
    },
    /// Wipe all entries from umount list
    Wipe,
}

#[derive(clap::Subcommand, Debug)]
enum Initrc {
    /// Regenerate preinit rc file
    Refresh,
}

pub fn run() -> Result<()> {
    android_logger::init_once(
        Config::default()
            .with_max_level(crate::debug_select!(LevelFilter::Trace, LevelFilter::Info))
            .with_tag("KernelSU"),
    );

    ksucalls::setup_sigsys_handler();

    // ── 数据目录改名迁移：/data/adb/ksu -> /data/adb/sevenk（2026-09-20）────────
    // 放在所有分支之前 —— 连 `su` 都要先保证 PATH 里的 /data/adb/sevenk/bin 存在。
    // 旧目录不存在时只有一次 stat（开销可忽略）；失败只记日志、绝不阻断任何功能。
    // 🔴A 门控：只有确认"新内核在跑"才搬（内部先做一次无害探测，见 migrate.rs）。
    // 🟢 省电（2026-09-21）：结论缓存在 /data/adb/sevenk/.gate_stamp —— 同内核同开机的
    //    后续调用**既不探测也不写盘**；缓存命中时连日志都不打（省一次 logcat 写）。
    match migrate::auto_migrate() {
        // 注意：本文件顶部 `use anyhow::Ok` —— `Ok` 在这里是 anyhow 的函数，
        // 不能直接当模式用，所以写全 `std::result::Result::Ok`。
        std::result::Result::Ok(rep) => {
            if rep.cached {
                // 命中缓存 = 本次什么都没做，别刷日志
            } else if rep.skipped_not_new_kernel {
                log::info!(
                    "legacy data dir migration skipped (probe={:?}): {}",
                    rep.kernel_probe,
                    rep.summary()
                );
            } else if !rep.legacy_absent {
                log::info!("legacy data dir migration: {}", rep.summary());
            }
        }
        Err(e) => log::warn!("legacy data dir migration failed (old dir kept): {e:#}"),
    }

    // the kernel executes su with argv[0] = "su" and replace it with us
    let arg0 = std::env::args().next().unwrap_or_default();
    if arg0 == "su" || arg0.ends_with("/su") {
        return crate::su::root_shell();
    }

    if arg0.ends_with("resetprop") {
        let all_args: Vec<String> = std::env::args().collect();
        crate::resetprop::resetprop_main(&all_args)
    }

    let cli = Args::parse();

    log::info!("command: {:?}", cli.command);

    let result = match cli.command {
        Commands::Webadmin { command } => match command {
            Webadmin::On => {
                crate::webadmin_ksud::set_enabled(true)?;
                crate::webadmin_ksud::ensure_running()?;
                println!("网页管理器：已开启（ksud 常驻，不依赖 App）");
                // 顺手把**带密钥的完整地址**打出来：用户/App 都需要它
                println!("{}", crate::webadmin::url());
                Ok(())
            }
            Webadmin::Off => {
                crate::webadmin_ksud::set_enabled(false)?;
                crate::webadmin_ksud::stop_running()?;
                println!("网页管理器：已关闭");
                Ok(())
            }
            Webadmin::Status => {
                println!("{}", crate::webadmin_ksud::status_text());
                Ok(())
            }
            Webadmin::Url => {
                // ⚠️ 这条命令的 stdout **含访问密钥** —— 只有调用方（App / 用户）看得到，
                //    绝不要把它重定向进日志文件。
                println!("{}", crate::webadmin::url());
                Ok(())
            }
            Webadmin::ResetToken => {
                let url = crate::webadmin_ksud::reset_token_url()?;
                println!("网页管理器：访问密钥已重置（旧链接立即失效）");
                println!("{url}");
                Ok(())
            }
            Webadmin::Sync => {
                let restarted = crate::webadmin_ksud::sync_if_needed()?;
                println!(
                    "网页管理器：{}",
                    if restarted {
                        "ksud 换过版本（或本来没在跑），已确保新版在跑"
                    } else {
                        "已在运行且就是当前版本，没动它"
                    }
                );
                Ok(())
            }
            Webadmin::Restart => {
                crate::webadmin_ksud::set_enabled(true)?;
                let ok = crate::webadmin_ksud::restart()?;
                println!("网页管理器：已重启（{}）", if ok { "在跑" } else { "拉起失败" });
                Ok(())
            }
            Webadmin::Serve { port } => {
                // 常驻进程本体：前台跑到底（由 ensure_running() 以脱离会话的方式拉起）
                crate::webadmin_ksud::serve_forever(port);
            }
        },

        Commands::PostFsData => init_event::on_post_data_fs(),
        Commands::BootCompleted => {
            init_event::on_boot_completed();
            Ok(())
        }

        Commands::SoftReboot => init_event::soft_reboot(),

        Commands::Insmod { module, params } => debug::insmod(&module, &params),

        Commands::Module { command } => {
            utils::switch_mnt_ns(1)?;
            match command {
                Module::Install { zip } => module::install_module(&zip),
                Module::UndoUninstall { id } => module::undo_uninstall_module(&id),
                Module::Uninstall { id } => module::uninstall_module(&id),
                Module::Enable { id } => module::enable_module(&id),
                Module::Disable { id } => module::disable_module(&id),
                Module::Action { id } => module::run_action(&id),
                Module::List => module::list_modules(),
                Module::Config { internal, command } => {
                    let module_id = match internal {
                        Some(internal_name) => format!("internal.{internal_name}"),
                        None => std::env::var("KSU_MODULE").map_err(|_| {
                            anyhow::anyhow!(
                                "This command must be run in the context of a module or passed --internal <name>"
                            )
                        })?,
                    };
                    crate::module::validate_module_id(&module_id)?;

                    match command {
                        ModuleConfigCmd::Get { key } => {
                            // Use merge_configs to respect priority (temp overrides persist)
                            let config = module_config::merge_configs(&module_id)?;
                            match config.get(&key) {
                                Some(value) => {
                                    println!("{value}");
                                    Ok(())
                                }
                                None => anyhow::bail!("Key '{key}' not found"),
                            }
                        }
                        ModuleConfigCmd::Set {
                            key,
                            value,
                            stdin,
                            temp,
                        } => {
                            // Validate key at CLI layer for better user experience
                            module_config::validate_config_key(&key)?;

                            // Read value from stdin or argument
                            let value_str = match value {
                                Some(v) if !stdin => v,
                                _ => {
                                    // Read from stdin
                                    use std::io::Read;
                                    let mut buffer = String::new();
                                    std::io::stdin()
                                        .read_to_string(&mut buffer)
                                        .context("Failed to read from stdin")?;
                                    buffer
                                }
                            };

                            // Validate value
                            module_config::validate_config_value(&value_str)?;

                            let config_type = if temp {
                                module_config::ConfigType::Temp
                            } else {
                                module_config::ConfigType::Persist
                            };
                            module_config::set_config_value(
                                &module_id,
                                &key,
                                &value_str,
                                config_type,
                            )
                        }
                        ModuleConfigCmd::List => {
                            let config = module_config::merge_configs(&module_id)?;
                            if config.is_empty() {
                                println!("No config entries found");
                            } else {
                                for (key, value) in config {
                                    println!("{key}={value}");
                                }
                            }
                            Ok(())
                        }
                        ModuleConfigCmd::Delete { key, temp } => {
                            let config_type = if temp {
                                module_config::ConfigType::Temp
                            } else {
                                module_config::ConfigType::Persist
                            };
                            module_config::delete_config_value(&module_id, &key, config_type)
                        }
                        ModuleConfigCmd::Clear { temp } => {
                            let config_type = if temp {
                                module_config::ConfigType::Temp
                            } else {
                                module_config::ConfigType::Persist
                            };
                            module_config::clear_config(&module_id, config_type)
                        }
                    }
                }
            }
        }
        Commands::Install {
            libadbroot,
            data_path,
        } => utils::install(libadbroot, data_path),
        Commands::Unload => crate::unload::unload(),
        Commands::Uninstall { package_name } => utils::uninstall(&package_name),
        Commands::Sepolicy { command } => match command {
            Sepolicy::Patch { sepolicy } => crate::sepolicy::live_patch(&sepolicy),
            Sepolicy::Apply { file } => crate::sepolicy::apply_file(file),
            Sepolicy::Check { sepolicy } => crate::sepolicy::check_rule(&sepolicy),
        },
        Commands::LateLoad {
            magica,
            allow_shell,
            post_magica,
            kmi,
            package_name,
        } => {
            if let Some(port) = magica {
                return crate::magica::run(port, &package_name, allow_shell).map_err(|e| {
                    error!("Error running magica: {e}");
                    e
                });
            }
            let result = crate::late_load::run(&package_name, kmi, allow_shell);
            if post_magica {
                info!("Restoring adb properties (post-magica cleanup)...");
                if let Err(e) = crate::magica::disable_adb_root() {
                    error!("disable adb root failed: {e}");
                }
            }
            result
        }
        Commands::Services => {
            if ksucalls::get_version() <= 0 {
                info!("KernelSU not available, exiting services");
                std::process::exit(0);
            }
            init_event::on_services();
            Ok(())
        }
        Commands::Sulogd => sulog::run_sulogd(),
        Commands::Profile { command } => match command {
            Profile::GetSepolicy { package } => crate::profile::get_sepolicy(package),
            Profile::SetSepolicy { package, policy } => {
                crate::profile::set_sepolicy(package, policy)
            }
            Profile::GetTemplate { id } => crate::profile::get_template(id),
            Profile::SetTemplate { id, template } => crate::profile::set_template(id, template),
            Profile::DeleteTemplate { id } => crate::profile::delete_template(id),
            Profile::ListTemplates => crate::profile::list_templates(),
        },

        Commands::Feature { command } => match command {
            Feature::Get { id, config } => {
                if config {
                    crate::feature::get_feature_config(&id)
                } else {
                    crate::feature::get_feature(&id)
                }
            }
            Feature::Set { id, value } => crate::feature::set_feature(&id, value),
            Feature::List => {
                crate::feature::list_features();
                Ok(())
            }
            Feature::Check { id } => crate::feature::check_feature(&id),
            Feature::Load => crate::feature::load_config_and_apply(),
            Feature::Save => crate::feature::save_config(),
        },

        Commands::Debug { command } => match command {
            Debug::SetManager { apk } => debug::set_manager(&apk),
            Debug::GetSign { apk } => {
                let sign = apk_sign::get_apk_signature(&apk)?;
                println!("size: {:#x}, hash: {}", sign.0, sign.1);
                Ok(())
            }
            Debug::Version => {
                println!("Kernel Version: {}", ksucalls::get_version());
                Ok(())
            }
            Debug::Su { global_mnt } => crate::su::grant_root(global_mnt),
            Debug::Test => assets::ensure_binaries(false),
            Debug::ExtractBinary { name, path } => {
                let data = assets::get_asset_data(&name)?;
                utils::ensure_binary(&path, &data, false)
            }
            Debug::Mark { command } => match command {
                MarkCommand::Get { pid } => debug::mark_get(pid),
                MarkCommand::Mark { pid } => debug::mark_set(pid),
                MarkCommand::Unmark { pid } => debug::mark_unset(pid),
                MarkCommand::Refresh => debug::mark_refresh(),
            },
            Debug::Sulogd => sulog::ensure_sulogd_running(),
            Debug::Info => {
                let info = ksucalls::get_info();
                println!("version: {}", info.version);
                println!("flags: 0x{:x}", info.flags);
                println!("uapi_version: {}", info.uapi_version);
                println!("features: 0x{:x}", info.features);
                println!("lkm: {}", ksucalls::is_lkm());
                println!(
                    "bundled: {}",
                    (info.flags & ksu_uapi::KSU_GET_INFO_FLAG_BUNDLED) != 0
                );
                println!("late_load: {}", ksucalls::is_late_load());
                println!("runtime_mode: {}", ksucalls::runtime_mode());
                println!(
                    "pr_build: {}",
                    (info.flags & ksu_uapi::KSU_GET_INFO_FLAG_PR_BUILD) != 0
                );
                Ok(())
            }
            Debug::Package => {
                println!("{}", defs::DEFAULT_PACKAGE_NAME);
                Ok(())
            }
        },

        Commands::BootPatch(boot_patch) => crate::boot_patch::patch(boot_patch),

        Commands::BootInfo { command } => match command {
            BootInfo::CurrentKmi => {
                let kmi = crate::boot_patch::get_current_kmi()?;
                println!("{kmi}");
                // return here to avoid printing the error message
                return Ok(());
            }
            BootInfo::SupportedKmis => {
                let kmi = crate::assets::list_supported_kmi();
                for kmi in &kmi {
                    println!("{kmi}");
                }
                return Ok(());
            }
            BootInfo::IsAbDevice => {
                let val = crate::utils::getprop("ro.build.ab_update")
                    .unwrap_or_else(|| String::from("false"));
                let is_ab = val.trim().to_lowercase() == "true";
                println!("{}", if is_ab { "true" } else { "false" });
                return Ok(());
            }
            BootInfo::DefaultPartition => {
                let kmi = crate::boot_patch::get_current_kmi().unwrap_or_else(|_| String::new());
                let name = crate::boot_patch::choose_boot_partition(&kmi, false, &None);
                println!("{name}");
                return Ok(());
            }
            BootInfo::SlotSuffix { ota } => {
                let suffix = crate::boot_patch::get_slot_suffix(ota);
                println!("{suffix}");
                return Ok(());
            }
            BootInfo::AvailablePartitions => {
                let parts = crate::boot_patch::list_available_partitions();
                for p in &parts {
                    println!("{p}");
                }
                return Ok(());
            }
            BootInfo::RamdiskState { boot } => {
                // v2.6：**只读**查询 —— App 的安装页靠它决定要不要显示"直接安装"。
                // 任何失败都打印 `unknown`（并返回 Ok）→ App 端 fail-closed（不放行）。
                // 刻意**不**在这里 `bail!`：一个词的结果比一个退出码更好解析，
                // 而"读不到"本身就是要传给调用方的信息（`unknown`）。
                println!(
                    "{}",
                    crate::boot_patch::query_ramdisk_state(boot.as_deref())
                );
                return Ok(());
            }
        },
        Commands::BootRestore(boot_restore) => crate::boot_patch::restore(boot_restore),
        Commands::BootPatchV2(patch) => crate::lkm_image::patch_boot(&patch),
        Commands::Resetprop { args } => {
            let mut full_args = vec!["resetprop".to_string()];
            full_args.extend(args);
            crate::resetprop::resetprop_main(&full_args)
        }

        Commands::Kernel { command } => match command {
            Kernel::NukeExt4Sysfs { mnt } => ksucalls::nuke_ext4_sysfs(&mnt),
            Kernel::Umount { command } => match command {
                UmountOp::Add { mnt, flags } => ksucalls::umount_list_add(&mnt, flags),
                UmountOp::Del { mnt } => ksucalls::umount_list_del(&mnt),
                UmountOp::Wipe => ksucalls::umount_list_wipe(),
            },
            Kernel::NotifyModuleMounted => {
                ksucalls::report_module_mounted();
                Ok(())
            }
        },
        Commands::Initrc { command } => match command {
            Initrc::Refresh => regenerate_preinit_rc(),
        },

        Commands::Migrate {
            force,
            probe_only,
            adopt_legacy_allowlist,
        } => {
            if probe_only {
                // 只打印门控结论，给 App 侧做"先预检、确认了新内核才发 migrate"的两步门控用。
                // ⚠️ 本进程开头那次 auto_migrate()（同样经过门控）不会被这个开关跳过。
                println!("{}", migrate::probe_verdict_line()?);
            } else {
                // ⚠️ 显式命令**绕开门控缓存**（`auto_migrate_full`）：用户/App 主动要求重算结论，
                //    不能被"每次 su 都跑的那条隐式路径"的省电缓存挡住（见 migrate.rs 的 GateStamp）。
                let rep = if adopt_legacy_allowlist {
                    // 🔴1（v2.11）：显式采纳旧目录独有的 `.allowlist` 记录（并集合并）。
                    // **不**用 `--force` 语义：不清冲突标记、不重搬旧目录。
                    migrate::auto_migrate_adopt_allowlist()?
                } else if force {
                    migrate::auto_migrate_force()?
                } else {
                    migrate::auto_migrate_full()?
                };
                println!("{}", rep.summary());
                if let Some(p) = rep.kernel_probe {
                    println!("KERNEL_DATADIR={p}");
                }
                // 🔴1（v2.11）：给 App 用的机器可读两行（App 首页那张卡靠它出数字/清数字）
                println!("ALLOWLIST_PENDING={}", rep.allowlist_legacy_only);
                println!("ALLOWLIST_ADOPTED={}", rep.allowlist_adopted);
            }
            Ok(())
        }

        // 🔴2（v2.11）：从卸载备份恢复用户数据（默认取最新一份）
        Commands::RestoreUserData { from } => utils::restore_user_data(from.as_deref()),
    };

    if let Err(e) = &result {
        log::error!("Error: {e:?}");
    }
    result
}
