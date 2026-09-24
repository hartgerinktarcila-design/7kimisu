use anyhow::Result;
use clap::Parser;
use std::path::PathBuf;

use crate::boot_patch::{BootPatchArgs, BootRestoreArgs};
use crate::lkm_image::BootPatchV2Args;
use crate::{apk_sign, defs};

/// KernelSU cli for non-android
#[derive(Parser, Debug)]
#[command(author, version = defs::VERSION_NAME, about, long_about = None)]
struct Args {
    #[command(subcommand)]
    command: Commands,
}

#[derive(clap::Subcommand, Debug)]
enum Commands {
    /// Patch boot or init_boot images to apply KernelSU
    BootPatch(BootPatchArgs),

    /// Restore boot or init_boot images patched by KernelSU
    BootRestore(BootRestoreArgs),

    /// Patch KernelSU into a boot image
    ///
    /// Always operates on a boot image; never selects init_boot or vendor_boot.
    BootPatchV2(BootPatchV2Args),

    /// Get apk size and hash
    GetSign {
        /// apk path
        apk: String,
    },

    /// show supported kmi versions
    SupportedKmis,

    /// print the ramdisk state of a boot image:
    /// `ours` | `compatible` | `foreign` | `stock` (or `unknown` when it cannot be read).
    ///
    /// READ-ONLY: never writes anything. On the host the image path is mandatory
    /// (there is no "current device partition" to auto-detect).
    ///
    /// Same criterion as the Android `boot-info ramdisk-state` command; this host variant is
    /// what the offline evidence run uses.
    RamdiskState {
        /// boot image path
        #[arg(short, long)]
        boot: PathBuf,
    },
}

pub fn run() -> Result<()> {
    env_logger::init();

    let cli = Args::parse();

    log::info!("command: {:?}", cli.command);

    let result = match cli.command {
        Commands::GetSign { apk } => {
            let sign = apk_sign::get_apk_signature(&apk)?;
            println!("size: {:#x}, hash: {}", sign.0, sign.1);
            Ok(())
        }

        Commands::BootPatch(boot_patch) => crate::boot_patch::patch(boot_patch),

        Commands::BootRestore(boot_restore) => crate::boot_patch::restore(boot_restore),

        Commands::BootPatchV2(patch) => crate::lkm_image::patch_boot(&patch),

        Commands::SupportedKmis => {
            let kmi = crate::assets::list_supported_kmi();
            for kmi in &kmi {
                println!("{kmi}");
            }
            Ok(())
        }

        Commands::RamdiskState { boot } => {
            // v2.6：与 Android 侧 `boot-info ramdisk-state` 同源同判据（[`query_ramdisk_state`]）。
            println!("{}", crate::boot_patch::query_ramdisk_state(Some(&boot)));
            Ok(())
        }
    };

    if let Err(e) = &result {
        log::error!("Error: {e:?}");
    }
    result
}
