//! 脚本**工作目录**的解析（`module::exec_script` 用的那一小步）。
//!
//! 为什么单独一个模块：`module.rs` 是 `#[cfg(target_os = "android")]` 的
//! （它依赖 android-only 的 `utils`/`ksucalls`），**宿主机上根本编不进来** ⇒
//! 写在里面的判据没法在 Mac 上回归。这正是 v2.18 抽 `atomic.rs` 时记下的教训：
//! **没办法复现的修复等于没修**。所以把这一步纯路径判断单独放一个**不加 cfg** 的文件。
//!
//! 🔴（v2.19）修的是什么：旧写法（= 上游 `module.rs:237` 的原文）是
//! ```ignore
//! command.current_dir(path.as_ref().parent().unwrap())
//! ```
//! `.unwrap()` 在生产路径上。`Path::parent()` 对 `"/"` 与 `""` 返回 `None` ⇒
//! 一旦有调用点（现在的或将来新加的）传进这两种值，**整个 ksud 进程直接 panic**；
//! 而 ksud 是 `su` / 开机脚本的执行体，panic = 那一条链整条断掉。
//! 现在改成"拿不到父目录就返回 `Err`"：调用方拿得到上下文错误，进程不会死。
//!
//! 顺带把空父目录也当成"没有父目录"：`Path::new("post-fs-data.sh").parent()`
//! 是 `Some("")`，而 `Command::current_dir("")` 会在 spawn 时才失败
//! （错误信息里看不出是路径的问题）—— 提前在这里拒掉，报错更准。

use anyhow::{Result, bail};
use std::path::{Path, PathBuf};

/// 取 `path` 的工作目录（父目录）。
///
/// # Errors
/// `path` 没有父目录（`"/"` / `""`），或父目录是空路径（裸文件名）时返回 `Err`。
/// **任何情况下都不 panic。**
pub fn script_work_dir(path: &Path) -> Result<PathBuf> {
    match path.parent() {
        Some(p) if !p.as_os_str().is_empty() => Ok(p.to_path_buf()),
        _ => bail!(
            "Failed to exec {}: path has no parent directory (refusing to run it)",
            path.display()
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 正常形状：模块脚本的绝对路径 ⇒ 工作目录就是它所在的那个模块目录。
    #[test]
    fn absolute_module_script_resolves_to_its_directory() {
        let p = Path::new("/data/adb/modules/foo/post-fs-data.sh");
        assert_eq!(
            script_work_dir(p).unwrap(),
            PathBuf::from("/data/adb/modules/foo")
        );
        let p2 = Path::new("/data/adb/modules/foo/service.sh");
        assert_eq!(
            script_work_dir(p2).unwrap(),
            PathBuf::from("/data/adb/modules/foo")
        );
    }

    /// 🔴（v2.19）修前会怎样：`.parent().unwrap()` 在这两个输入上**直接 panic**。
    ///
    /// 这条断言就是"修后不会"的证据：同样的输入现在只返回 `Err`，
    /// 进程还活着（能继续往下断言）。
    #[test]
    fn root_and_empty_path_are_an_error_not_a_panic() {
        for bad in ["/", ""] {
            let p = Path::new(bad);
            assert!(p.parent().is_none(), "前提：{bad:?} 的 parent() 必须是 None");
            let e = script_work_dir(p);
            assert!(e.is_err(), "{bad:?} 必须报错（旧写法这里是 unwrap panic）");
            assert!(
                format!("{e:?}").contains("no parent directory"),
                "错误要带上下文（能看出是路径的问题）: {e:?}"
            );
        }
    }

    /// 裸文件名（父目录是空串）：提前拒掉，而不是留到 spawn 才失败。
    #[test]
    fn bare_file_name_is_rejected_up_front() {
        let p = Path::new("post-fs-data.sh");
        assert_eq!(p.parent(), Some(Path::new("")));
        assert!(script_work_dir(p).is_err());
    }
}
