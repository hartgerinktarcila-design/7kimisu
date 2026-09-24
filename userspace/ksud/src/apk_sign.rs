use anyhow::{Result, ensure};
use std::io::{Read, Seek, SeekFrom};

pub fn get_apk_signature(apk: &str) -> Result<(u32, String)> {
    let mut buffer = [0u8; 0x10];
    let mut size4 = [0u8; 4];
    let mut size8 = [0u8; 8];
    let mut size_of_block = [0u8; 8];

    let mut f = std::fs::File::open(apk)?;

    let mut i = 0;
    loop {
        let mut n = [0u8; 2];
        f.seek(SeekFrom::End(-i - 2))?;
        f.read_exact(&mut n)?;

        let n = u16::from_le_bytes(n);
        if i64::from(n) == i {
            f.seek(SeekFrom::Current(-22))?;
            f.read_exact(&mut size4)?;

            if u32::from_le_bytes(size4) ^ 0xcafe_babe_u32 == 0xccfb_f1ee_u32 {
                if i > 0 {
                    println!("warning: comment length is {i}");
                }
                break;
            }
        }

        ensure!(n != 0xffff, "not a zip file");

        i += 1;
    }

    f.seek(SeekFrom::Current(12))?;
    // offset
    f.read_exact(&mut size4)?;
    // 🟡（v2.19）两处减法都必须**受检**：`size4` / `size8` 全是**不受信的文件内容**，
    // 构造出比 0x18 小的中央目录偏移（或超大的 sig block 长度）时，裸减法在 release 下
    // 静默回绕成 ~1.8e19 ⇒ `seek` 到天边 ⇒ 报一句和真正原因毫无关系的
    // "failed to fill whole buffer"（debug 构建还会直接 overflow panic）。
    // 现在给出明确错误。上游这两行也是裸减法（同款）。
    let cd_offset = u64::from(u32::from_le_bytes(size4));
    let sig_block_start = cd_offset
        .checked_sub(0x18)
        .ok_or_else(|| anyhow::anyhow!("APK central directory offset {cd_offset} is below 0x18"))?;
    f.seek(SeekFrom::Start(sig_block_start))?;

    f.read_exact(&mut size8)?;
    f.read_exact(&mut buffer)?;

    ensure!(&buffer == b"APK Sig Block 42", "Can not found sig block");

    let block_end = u64::from_le_bytes(size8)
        .checked_add(0x8)
        .ok_or_else(|| anyhow::anyhow!("APK sig block size overflows u64"))?;
    let pos = cd_offset
        .checked_sub(block_end)
        .ok_or_else(|| anyhow::anyhow!("APK sig block is larger than the central directory offset"))?;
    f.seek(SeekFrom::Start(pos))?;
    f.read_exact(&mut size_of_block)?;

    ensure!(size_of_block == size8, "not a signed apk");

    let mut v2_signing: Option<(u32, String)> = None;
    let mut v3_signing_exist = false;
    let mut v3_1_signing_exist = false;

    // 🟠（v2.19）两道防死循环：
    //   ① `size8` 完全由**不受信的文件内容**决定。构造 `size8 = 0xFFFF_FFFF_FFFF_FFF8`
    //      （i64 = -8）时 `-8 - 4 = -12` ⇒ `seek` **往回退** 12 字节 ⇒ 下一轮读到同一块
    //      ⇒ **死循环 100% CPU**（`ksud debug get-sign`，以及模块安装的签名校验路径）。
    //   ② 万一还有别的花样让位移恒为 0，再加一个块数上限兜底（正常 APK 只有 1~3 个）。
    // 判据抽成 `signing_block_skip()`（宿主可单测，见文件末尾测试）。
    let mut blocks_seen = 0usize;
    loop {
        let mut id = [0u8; 4];
        let mut offset = 4u32;

        blocks_seen += 1;
        if blocks_seen > MAX_SIGNING_BLOCKS {
            return Err(anyhow::anyhow!(
                "too many APK signing blocks ({blocks_seen}); malformed APK"
            ));
        }

        f.read_exact(&mut size8)?; // sequence length
        if size8 == size_of_block {
            break;
        }

        f.read_exact(&mut id)?; // id

        let id = u32::from_le_bytes(id);
        if id == 0x7109_871a_u32 {
            v2_signing = Some(calc_cert_sha256(&mut f, &mut size4, &mut offset)?);
        } else if id == 0xf053_68c0_u32 {
            // v3 signature scheme
            v3_signing_exist = true;
        } else if id == 0x1b93_ad61_u32 {
            // v3.1 signature scheme: credits to vvb2060
            v3_1_signing_exist = true;
        }

        f.seek(SeekFrom::Current(signing_block_skip(size8, offset)?))?;
    }

    if v3_signing_exist || v3_1_signing_exist {
        return Err(anyhow::anyhow!("Unexpected v3 signature found!"));
    }

    v2_signing.ok_or_else(|| anyhow::anyhow!("No signature found!"))
}

fn calc_cert_sha256(
    f: &mut std::fs::File,
    size4: &mut [u8; 4],
    offset: &mut u32,
) -> Result<(u32, String)> {
    f.read_exact(size4)?; // signer-sequence length
    f.read_exact(size4)?; // signer length
    f.read_exact(size4)?; // signed data length
    *offset += 0x4 * 3;

    f.read_exact(size4)?; // digests-sequence length
    let pos = u32::from_le_bytes(*size4); // skip digests
    f.seek(SeekFrom::Current(i64::from(pos)))?;
    *offset += 0x4 + pos;

    f.read_exact(size4)?; // certificates length
    f.read_exact(size4)?; // certificate length
    *offset += 0x4 * 2;

    let cert_len = u32::from_le_bytes(*size4);
    let mut cert: Vec<u8> = vec![0; cert_len as usize];
    f.read_exact(&mut cert)?;
    *offset += cert_len;

    Ok((cert_len, sha256::digest(&cert)))
}

/// APK signing block 的块数上限（防"位移恒为 0"这类构造导致的死循环）。
/// 正常 APK 只有 1~3 个块，256 是纯兜底。
const MAX_SIGNING_BLOCKS: usize = 256;

/// 由 sig block 头部算"跳到下一个块"的位移。
///
/// 🟠（v2.19）为什么单抽一个函数：`size8` 是**不受信的文件内容**，
/// `i64::from_le_bytes(size8) - i64::from(offset)` 可能是**负数** ——
/// `size8 = 0xFFFF_FFFF_FFFF_FFF8`（= -8）时位移是 -12，`seek` 往回退，
/// 下一轮读到同一块 ⇒ **死循环**。这里强制"只能向前"，
/// 并抽出来让宿主单测能直接回归（`apk_sign.rs` 不加 cfg，宿主编得进来）。
fn signing_block_skip(size8: [u8; 8], offset: u32) -> Result<i64> {
    let raw = i64::from_le_bytes(size8);
    let skip = raw
        .checked_sub(i64::from(offset))
        .ok_or_else(|| anyhow::anyhow!("APK signing block size out of range: {raw}"))?;
    if skip < 0 {
        anyhow::bail!(
            "APK signing block would move backwards ({skip} bytes, size8={raw}); refusing to loop"
        );
    }
    Ok(skip)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 🔴（v2.19）"修前会怎样"：旧写法直接 `seek(size8 - offset)`，
    /// 下面这个 `size8` 会算出 **-12** ⇒ 往回退 ⇒ 死循环。
    /// 现在必须报错返回。
    #[test]
    fn signing_block_skip_rejects_backwards_move() {
        let evil = 0xFFFF_FFFF_FFFF_FFF8u64.to_le_bytes();
        let e = signing_block_skip(evil, 4);
        assert!(e.is_err(), "往回退的位移必须报错（旧写法在这里死循环）");
        assert!(
            format!("{e:?}").contains("backwards"),
            "错误要说清是往回退: {e:?}"
        );
    }

    /// 正常 APK 的形状必须照旧能算出正确位移（不能把好包也拒了）。
    #[test]
    fn signing_block_skip_accepts_normal_forward_move() {
        // 块长 0x100，已消耗 4+0x40 ⇒ 还要跳 0xBC
        let size8 = 0x100u64.to_le_bytes();
        assert_eq!(signing_block_skip(size8, 0x44).unwrap(), 0xBC);
        // 位移为 0（刚好走完这块）也是合法的：下一轮 read_exact 会继续前进
        assert_eq!(signing_block_skip(size8, 0x100).unwrap(), 0);
    }

    /// 溢出面：`i64::MIN` 减 offset 不会 panic（旧写法在 debug 下会 overflow panic）。
    #[test]
    fn signing_block_skip_never_panics_on_extremes() {
        for raw in [
            0x8000_0000_0000_0000u64, // i64::MIN
            0xFFFF_FFFF_FFFF_FFFFu64, // -1
            0u64,
        ] {
            let _ = signing_block_skip(raw.to_le_bytes(), u32::MAX);
        }
    }
}
