#![deny(clippy::all, clippy::pedantic)]
#![warn(clippy::nursery)]
#![allow(
    clippy::module_name_repetitions,
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss,
    clippy::doc_markdown,
    clippy::too_many_lines,
    clippy::cast_possible_wrap
)]

mod apk_sign;
mod assets;
mod boot_patch;
#[cfg(target_os = "android")]
mod cli;
#[cfg(not(target_os = "android"))]
mod cli_non_android;
#[cfg(target_os = "android")]
mod debug;
mod defs;
#[cfg(target_os = "android")]
mod feature;
#[cfg(target_os = "android")]
mod init_event;
#[cfg(target_os = "android")]
mod ksucalls;
#[cfg(target_os = "android")]
mod late_load;
mod lkm_image;
mod lkm_image_btf;
#[cfg(target_os = "android")]
mod magica;
#[cfg(target_os = "android")]
mod metamodule;
// 数据目录改名迁移（/data/adb/ksu -> /data/adb/sevenk）。纯 std，宿主上也能单测。
mod migrate;
// 🔴2（v2.11）从"卸载备份"恢复用户数据（`ksud restore-user-data`）。纯 std，宿主上也能单测。
#[cfg(target_os = "android")]
mod module;
#[cfg(target_os = "android")]
mod module_config;
#[cfg(target_os = "android")]
mod profile;
mod restore_user_data;
// 资源标签解析：宿主上也能编译/测试（用我们自己的 APK 当样本），所以不限定 android
mod res_label;
#[cfg(target_os = "android")]
mod resetprop;
#[cfg(target_os = "android")]
mod restorecon;
#[cfg(target_os = "android")]
mod sepolicy;
#[cfg(target_os = "android")]
mod su;
#[cfg(target_os = "android")]
mod sulog;
#[cfg(target_os = "android")]
mod unload;
#[cfg(target_os = "android")]
mod utils;
mod webadmin;
// 🔴（v2.18）跨平台的原子写工具（**故意不加 cfg** —— 就是为了让它的单测能在宿主上跑）
mod atomic;
// 🟡（v2.19）脚本工作目录解析（同样**故意不加 cfg** —— `module.rs` 是 android-only，
//   判据放里面就在 Mac 上回归不了）
mod script_path;
#[cfg(target_os = "android")]
mod webadmin_ksud;

#[cfg(target_os = "android")]
#[allow(nonstandard_style, unused, unsafe_op_in_unsafe_fn)]
mod ksu_uapi;

fn main() -> anyhow::Result<()> {
    #[cfg(target_os = "android")]
    {
        cli::run()
    }
    #[cfg(not(target_os = "android"))]
    {
        cli_non_android::run()
    }
}
