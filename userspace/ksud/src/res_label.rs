//! 从 APK 里读出「应用名」（`android:label`）。
//!
//! 为什么要自己解析：模块网页的 `ksu.getPackagesInfo()` 要返回 appLabel，
//! 管理器 App 是靠 Android 框架的 PackageManager 拿的，而 ksud 这边**没有框架可用**：
//!   · `dumpsys package <pkg>` 里根本没有标签（只有 `labelRes` 这种资源 id）；
//!   · 设备上没有 `aapt`/`aapt2`；`cmd package` 也没有出标签的子命令；
//!   · 拿 `app_process` 去跑框架类会被拒（真机实测 `Aborted`）。
//! 所以只能自己读 APK 里的二进制资源：
//!   1. `AndroidManifest.xml` 的 `<application android:label="…">`
//!      —— 值可能是**字符串**，也可能是**资源引用**（绝大多数应用是后者）；
//!   2. 若是资源引用，再到 `resources.arsc` 里把那个 id 解成字符串
//!      （优先匹配设备语言，其次默认配置）。
//!
//! 解析失败一律返回 `None`（调用方退回用包名），**绝不 panic** —— 这只是个显示用的名字。

// 在开发机上（非安卓）这些函数的真正调用方不存在，只有测试在用 —— 别让 dead_code 刷屏
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::path::Path;

const RES_STRING_POOL_TYPE: u16 = 0x0001;
const RES_TABLE_TYPE: u16 = 0x0002;
const RES_XML_TYPE: u16 = 0x0003;
const RES_XML_RESOURCE_MAP_TYPE: u16 = 0x0180;
const RES_XML_START_ELEMENT_TYPE: u16 = 0x0102;
const RES_TABLE_PACKAGE_TYPE: u16 = 0x0200;
const RES_TABLE_TYPE_TYPE: u16 = 0x0201;

/// `android:label` 的属性资源 id
const ANDROID_ATTR_LABEL: u32 = 0x0101_0001;

// ── 防"畸形 APK / 伪造 resources.arsc"的上限 ──────────────────────────────
// 所有**来自文件的数量/长度字段**都必须先夹到合理范围再用：
// 这些字段是 u32，伪造的 arsc 能把它们写成 40 亿 / 21 亿，
// 直接拿去 `Vec::with_capacity` 就是一次性申请十几 GB（OOM abort），
// 拿去做 `0..n` 循环就是空转几十亿次（卡死）。真实 APK 的取值离这些上限都还很远：
// 最大的 framework-res.arsc 也只有几万条字符串、单条字符串几百个码元。
/// 单个 StringPool 最多认这么多条字符串
const MAX_STRING_COUNT: usize = 1 << 20; // 1,048,576
/// 单条字符串最多认这么多码元（UTF-16 code unit）
const MAX_STRING_CHARS: usize = 1 << 20; // 1,048,576
/// type chunk 里最多认这么多条目
const MAX_TYPE_ENTRY_COUNT: usize = 1 << 20; // 1,048,576
/// 单个 zip 条目最多解出/读入这么多字节（resources.arsc 实际只有几 MB）
const MAX_ZIP_ENTRY_BYTES: u64 = 256 << 20; // 256 MiB

/// type 段的两个标志位（Android 12+ 会用 16 位偏移，真机上很常见）
const TYPE_FLAG_SPARSE: u8 = 0x01;
const TYPE_FLAG_OFFSET16: u8 = 0x02;

const TYPE_REFERENCE: u8 = 0x01;
const TYPE_STRING: u8 = 0x03;
/// ResTable_entry 的复杂项标记（bag/map，标签不会是这种）
const ENTRY_FLAG_COMPLEX: u16 = 0x0001;

fn u16le(b: &[u8], o: usize) -> Option<u16> {
    Some(u16::from_le_bytes([*b.get(o)?, *b.get(o + 1)?]))
}
fn u32le(b: &[u8], o: usize) -> Option<u32> {
    Some(u32::from_le_bytes([
        *b.get(o)?,
        *b.get(o + 1)?,
        *b.get(o + 2)?,
        *b.get(o + 3)?,
    ]))
}

/// 资源里的字符串池（UTF-8 与 UTF-16 两种编码都要支持）
struct StringPool {
    data: Vec<u8>,
    strings_start: usize,
    utf8: bool,
    offsets: Vec<u32>,
}

impl StringPool {
    fn parse(chunk: &[u8]) -> Option<Self> {
        // ResChunk_header(8) + stringCount + styleCount + flags + stringsStart + stylesStart
        let declared = u32le(chunk, 8)? as usize;
        let flags = u32le(chunk, 16)?;
        let strings_start = u32le(chunk, 20)? as usize;

        // ⚠️ string_count 直接来自文件：伪造的 arsc 可以写成 0xffff_ffff，
        //    旧代码 `Vec::with_capacity(string_count)` 会一口气申请 ~16GB → OOM abort。
        //    两道闸：① 硬上限；② 偏移表必须装得进这个 chunk（每条偏移 4 字节）——
        //    真实的池一定满足，装不下说明文件是坏的，直接放弃。
        let max_fit = chunk.len().saturating_sub(28) / 4;
        if declared > MAX_STRING_COUNT || declared > max_fit {
            return None;
        }
        let string_count = declared;

        let mut offsets = Vec::with_capacity(string_count);
        for i in 0..string_count {
            offsets.push(u32le(chunk, 28 + i * 4)?);
        }
        Some(Self {
            data: chunk.to_vec(),
            strings_start,
            utf8: flags & (1 << 8) != 0,
            offsets,
        })
    }

    fn get(&self, idx: usize) -> Option<String> {
        let off = self.strings_start.checked_add(*self.offsets.get(idx)? as usize)?;
        let s = self.data.get(off..)?;
        if self.utf8 {
            // UTF-8：先是"字符数"，再是"字节数"，各自可能是 1 或 2 字节（高位标记）
            let (_, n1) = read_len8(s, 0)?;
            let (byte_len, n2) = read_len8(s, n1)?;
            let body = s.get(n1 + n2..n1 + n2 + byte_len)?;
            Some(String::from_utf8_lossy(body).to_string())
        } else {
            // UTF-16：先是字符数（1 或 2 个 u16 高位标记），随后是 UTF-16 码元。
            // 整段交给 from_utf16 解，代理对（emoji 等）才不会变成半个字符
            let (char_len, n) = read_len16(s, 0)?;
            // ⚠️ char_len 同样来自文件，最大可写成 0x7fff_ffff(≈21 亿)。
            //    旧代码 `(0..char_len).filter_map(...)` 会空转 21 亿次 —— filter_map 不会因为
            //    后面读越界就停下来，于是整个 ksud 卡死。这里两道闸：
            //    ① 硬上限；② 剩余的字节数（每个码元 2 字节）装不下就说明长度是假的，直接放弃；
            //    并且换成 `map_while`（一读到越界就停，不再空转）。
            let available = s.len().saturating_sub(n) / 2;
            if char_len > MAX_STRING_CHARS || char_len > available {
                return None;
            }
            let units: Vec<u16> = (0..char_len).map_while(|i| u16le(s, n + i * 2)).collect();
            Some(String::from_utf16_lossy(&units))
        }
    }
}

/// UTF-8 风格的长度：最高位为 1 表示用 2 字节
fn read_len8(b: &[u8], o: usize) -> Option<(usize, usize)> {
    let first = *b.get(o)?;
    if first & 0x80 != 0 {
        let second = *b.get(o + 1)?;
        Some(((((first & 0x7f) as usize) << 8) | second as usize, 2))
    } else {
        Some((first as usize, 1))
    }
}

/// UTF-16 风格的长度：最高位为 1 表示用 2 个 u16
fn read_len16(b: &[u8], o: usize) -> Option<(usize, usize)> {
    let first = u16le(b, o)?;
    if first & 0x8000 != 0 {
        let second = u16le(b, o + 2)?;
        Some(((((first & 0x7fff) as usize) << 16) | second as usize, 4))
    } else {
        Some((first as usize, 2))
    }
}

/// 遍历一个 chunk 序列（从 `start` 开始，到 `end` 为止）
fn chunks(data: &[u8], start: usize, end: usize) -> Vec<(u16, usize, usize)> {
    let mut out = Vec::new();
    let mut p = start;
    while p + 8 <= end {
        let Some(ty) = u16le(data, p) else { break };
        let Some(size) = u32le(data, p + 4).map(|v| v as usize) else {
            break;
        };
        if size < 8 || p + size > end {
            break;
        }
        out.push((ty, p, size));
        p += size;
    }
    out
}

/// 从二进制 `AndroidManifest.xml` 里找 `<application android:label>`
/// 返回：(直接字符串, 资源 id)
fn xml_label(xml: &[u8]) -> Option<(Option<String>, Option<u32>)> {
    if u16le(xml, 0)? != RES_XML_TYPE {
        return None;
    }
    let mut pool: Option<StringPool> = None;
    let mut res_map: Vec<u32> = Vec::new();
    for (ty, off, size) in chunks(xml, u16le(xml, 2)? as usize, xml.len()) {
        let chunk = xml.get(off..off + size)?;
        match ty {
            RES_STRING_POOL_TYPE => pool = StringPool::parse(chunk),
            RES_XML_RESOURCE_MAP_TYPE => {
                let n = (size - 8) / 4;
                res_map = (0..n).filter_map(|i| u32le(chunk, 8 + i * 4)).collect();
            }
            RES_XML_START_ELEMENT_TYPE => {
                let Some(pool) = pool.as_ref() else { continue };
                // ResXMLTree_node(16) + attrExt：ns, name, attributeStart, attributeSize, count, …
                let name_idx = u32le(chunk, 20)? as usize;
                let is_application = pool
                    .get(name_idx)
                    .is_some_and(|n| n == "application");
                if !is_application {
                    continue;
                }
                let attr_start = u16le(chunk, 24)? as usize;
                let attr_size = u16le(chunk, 26)? as usize;
                let attr_count = u16le(chunk, 28)? as usize;
                for i in 0..attr_count {
                    let a = 16 + attr_start + i * attr_size;
                    let attr_name_idx = u32le(chunk, a + 4)? as usize;
                    // 属性名要先过 resource map 才是真正的资源 id
                    let attr_res_id = res_map.get(attr_name_idx).copied().unwrap_or(0);
                    if attr_res_id != ANDROID_ATTR_LABEL {
                        continue;
                    }
                    let data_type = *chunk.get(a + 15)?;
                    let data = u32le(chunk, a + 16)?;
                    return Some(match data_type {
                        TYPE_STRING => (pool.get(data as usize), None),
                        TYPE_REFERENCE => (None, Some(data)),
                        _ => (None, None),
                    });
                }
            }
            _ => {}
        }
    }
    None
}

/// 设备当前语言（`persist.sys.locale`，形如 `zh-Hans-CN`），只取一次
fn device_locale() -> (String, String) {
    static LOCALE: std::sync::OnceLock<(String, String)> = std::sync::OnceLock::new();
    LOCALE
        .get_or_init(|| {
            // 测试/排查时可以覆盖：SEVENK_LOCALE=zh-cn
            let raw = std::env::var("SEVENK_LOCALE").ok().unwrap_or_else(|| {
                std::process::Command::new("/system/bin/getprop")
                    .arg("persist.sys.locale")
                    .output()
                    .ok()
                    .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
                    .unwrap_or_default()
            });
            let mut it = raw.split(['-', '_']);
            let lang = it.next().unwrap_or("").to_ascii_lowercase();
            // zh-Hans-CN：第二段可能是脚本（Hans），国家在最后一段
            let rest: Vec<&str> = it.collect();
            let country = rest
                .last()
                .filter(|c| c.len() == 2)
                .map(|c| c.to_ascii_lowercase())
                .unwrap_or_default();
            (lang, country)
        })
        .clone()
}

/// 从 `resources.arsc` 里把资源 id 解成字符串（优先设备语言 → 默认配置 → 其它）
fn arsc_string(arsc: &[u8], res_id: u32, locale: &(String, String)) -> Option<String> {
    if u16le(arsc, 0)? != RES_TABLE_TYPE {
        return None;
    }
    let want_pkg = (res_id >> 24) as u8;
    let want_type = ((res_id >> 16) & 0xff) as u8;
    let want_entry = (res_id & 0xffff) as usize;

    let mut global_pool: Option<StringPool> = None;
    let mut best: Option<(i32, u32)> = None; // (配置匹配分, 字符串索引)

    for (ty, off, size) in chunks(arsc, u16le(arsc, 2)? as usize, arsc.len()) {
        let chunk = arsc.get(off..off + size)?;
        match ty {
            RES_STRING_POOL_TYPE => {
                if global_pool.is_none() {
                    global_pool = StringPool::parse(chunk);
                }
            }
            RES_TABLE_PACKAGE_TYPE => {
                if (*chunk.get(8)?) != want_pkg {
                    continue;
                }
                let header_size = u16le(chunk, 2)? as usize;
                for (pty, poff, psize) in chunks(chunk, header_size, chunk.len()) {
                    if pty != RES_TABLE_TYPE_TYPE {
                        continue;
                    }
                    let tchunk = chunk.get(poff..poff + psize)?;
                    if *tchunk.get(8)? != want_type {
                        continue;
                    }
                    let flags = *tchunk.get(9)?;
                    // entry_count 也来自文件：这里只夹上限（不 return None），
                    // 免得伪造的大数字让下面的 sparse 扫描空转
                    let entry_count = (u32le(tchunk, 12)? as usize).min(MAX_TYPE_ENTRY_COUNT);
                    let entries_start = u32le(tchunk, 16)? as usize;
                    let cfg_size = u32le(tchunk, 20)? as usize;
                    // ⚠️ 条目偏移表紧跟在 **type chunk 自己的** header 之后 ——
                    //    这里必须用 tchunk 的 headerSize，不能用包（package）的（踩过：
                    //    用成包的 288 会读到表外面的字节，解析出乱七八糟的字符串）
                    let type_header_size = u16le(tchunk, 2)? as usize;
                    // 配置打分：语言+国家 3 分，仅语言 2 分，默认配置 1 分，其它 0 分
                    let (lang, country) = config_locale(tchunk, cfg_size);
                    // 打分：语言+国家都对 3 分，只有语言对 2 分，默认配置（无语言）1 分，其它 0 分
                    let lang_match = !lang.is_empty() && lang == locale.0;
                    let score: i32 = if lang_match {
                        if country.is_empty() {
                            2
                        } else {
                            i32::from(country == locale.1) * 3
                        }
                    } else {
                        i32::from(lang.is_empty())
                    };
                    if best.as_ref().is_some_and(|(s, _)| *s >= score) {
                        continue;
                    }
                    // 找条目偏移。三种布局都要认（真机验出来的）：
                    //   · 普通：u32 偏移数组（0xffff_ffff = 无此条目）
                    //   · FLAG_OFFSET16：u16 偏移数组，真实偏移 = 值 * 4（0xffff = 无此条目）
                    //   · FLAG_SPARSE：(idx u16, offset u16) 对，按 idx 升序
                    let offset16 = flags & TYPE_FLAG_OFFSET16 != 0;
                    let entry_off = if flags & TYPE_FLAG_SPARSE != 0 {
                        let mut found = None;
                        for i in 0..entry_count {
                            let idx = u16le(tchunk, type_header_size + i * 4)? as usize;
                            if idx == want_entry {
                                let raw = u16le(tchunk, type_header_size + i * 4 + 2)? as usize;
                                found = if raw == 0xffff {
                                    None
                                } else if offset16 {
                                    Some(raw * 4)
                                } else {
                                    Some(raw)
                                };
                                break;
                            }
                            if idx > want_entry {
                                break; // 升序，后面不可能有了
                            }
                        }
                        found
                    } else if offset16 {
                        let raw = u16le(tchunk, type_header_size + want_entry * 2)? as usize;
                        if raw == 0xffff { None } else { Some(raw * 4) }
                    } else {
                        let o = u32le(tchunk, type_header_size + want_entry * 4)?;
                        if o == 0xffff_ffff { None } else { Some(o as usize) }
                    };
                    let Some(rel) = entry_off else { continue };
                    let e = entries_start + rel;
                    if offset16 {
                        // **紧凑条目**（`FLAG_OFFSET16` 配套，Android 14+ 的 ROM 会用）：
                        // 每个条目 8 字节 —— {u16 key; u8 size; u8 dataType; u32 data}
                        // （踩过：按常规的"entry + size 再读 Res_value"会整段读歪，
                        //   真机上表现就是一大堆系统应用解析不出名字）
                        let data_type = *tchunk.get(e + 3)?;
                        if data_type != TYPE_STRING {
                            continue;
                        }
                        let sidx = u32le(tchunk, e + 4)?;
                        best = Some((score, sidx));
                    } else {
                        let eflags = u16le(tchunk, e + 2)?;
                        if eflags & ENTRY_FLAG_COMPLEX != 0 {
                            continue; // bag/map 不是字符串
                        }
                        let esize = u16le(tchunk, e)? as usize;
                        let v = e + esize;
                        if *tchunk.get(v + 3)? != TYPE_STRING {
                            continue;
                        }
                        let sidx = u32le(tchunk, v + 4)?;
                        best = Some((score, sidx));
                    }
                }
            }
            _ => {}
        }
    }
    let (_, sidx) = best?;
    global_pool?.get(sidx as usize)
}

/// 从 ResTable_config 里取 (语言, 国家)：size(4) + imsi{ mcc u16, mnc u16 } 之后就是 language(2)、country(2)
fn config_locale(chunk: &[u8], cfg_size: usize) -> (String, String) {
    if cfg_size < 12 {
        return (String::new(), String::new());
    }
    let read = |o: usize| -> String {
        match u16le(chunk, o) {
            Some(v) if v != 0 => {
                // ⚠️ 语言/国家是按**内存顺序**存的两个 ASCII 字符：低字节是第一个字符
                //    （踩过：反过来读会把 "zh" 读成 "hz"，于是永远匹配不上中文配置，
                //     真机上表现就是系统应用全都给英文名）
                let first = (v & 0xff) as u8;
                let second = ((v >> 8) & 0xff) as u8;
                let mut out = String::new();
                if first.is_ascii_alphabetic() {
                    out.push(first.to_ascii_lowercase() as char);
                }
                if second.is_ascii_alphabetic() {
                    out.push(second.to_ascii_lowercase() as char);
                }
                out
            }
            _ => String::new(),
        }
    };
    (read(20 + 8), read(20 + 10))
}

/// 极简 ZIP 取条目：只够读 `AndroidManifest.xml` / `resources.arsc` 用。
///
/// 为什么不用 `zip` crate：它是**安卓专属依赖**，用了这个模块在开发机上就编不过，
/// 也就没法拿真实 APK 做本机测试。自己走中央目录 + 只借一个纯 Rust 的 inflate 就够。
fn zip_read_entry(path: &Path, want: &str) -> Option<Vec<u8>> {
    use std::io::{Read as _, Seek as _, SeekFrom};
    let mut f = std::fs::File::open(path).ok()?;
    let len = f.metadata().ok()?.len();
    if len < 22 {
        return None;
    }
    // ① 从尾部找 EOCD（签名 "PK"），最多回退 64KB
    let scan = len.min(64 * 1024) as usize;
    let mut tail = vec![0u8; scan];
    f.seek(SeekFrom::Start(len - scan as u64)).ok()?;
    f.read_exact(&mut tail).ok()?;
    let eocd = (0..scan.saturating_sub(3))
        .rev()
        .find(|i| tail.get(*i..*i + 4) == Some(b"PK\x06"))?;
    let cd_count = u16le(&tail, eocd + 10)? as usize;
    let cd_off = u64::from(u32le(&tail, eocd + 16)?);
    if cd_off == 0xffff_ffff {
        return None; // zip64：用不到，直接放弃（调用方退回包名）
    }
    // ② 遍历中央目录
    f.seek(SeekFrom::Start(cd_off)).ok()?;
    for _ in 0..cd_count {
        let mut h = [0u8; 46];
        f.read_exact(&mut h).ok()?;
        if &h[0..4] != b"PK\x01\x02" {
            return None;
        }
        let method = u16le(&h, 10)?;
        let comp_size = u64::from(u32le(&h, 20)?);
        let name_len = u16le(&h, 28)? as usize;
        let extra_len = u16le(&h, 30)? as usize;
        let comment_len = u16le(&h, 32)? as usize;
        let local_off = u64::from(u32le(&h, 42)?);
        let mut name = vec![0u8; name_len];
        f.read_exact(&mut name).ok()?;
        f.seek(SeekFrom::Current((extra_len + comment_len) as i64))
            .ok()?;
        if String::from_utf8_lossy(&name) != want {
            continue;
        }
        if comp_size == 0xffff_ffff || local_off == 0xffff_ffff {
            return None;
        }
        // ⚠️ comp_size 也是从文件里读的（中央目录），可以伪造成 4GB：
        //    下面 `vec![0u8; comp_size]` 会一次性申请 4GB → OOM abort。超限直接放弃
        //    （调用方会退回用包名显示，不影响功能）。
        if comp_size > MAX_ZIP_ENTRY_BYTES {
            return None;
        }
        // ③ 读本地头，跳过它自己的 name/extra，再取压缩数据
        f.seek(SeekFrom::Start(local_off)).ok()?;
        let mut lh = [0u8; 30];
        f.read_exact(&mut lh).ok()?;
        if &lh[0..4] != b"PK\x03\x04" {
            return None;
        }
        let l_name = u16le(&lh, 26)? as usize;
        let l_extra = u16le(&lh, 28)? as usize;
        f.seek(SeekFrom::Current((l_name + l_extra) as i64)).ok()?;
        let mut data = vec![0u8; comp_size as usize];
        f.read_exact(&mut data).ok()?;
        return match method {
            0 => Some(data), // stored
            8 => {
                // deflate（资源的压缩数据大小以中央目录为准，绕开 data descriptor 的坑）
                // ⚠️ 再加一道"解压炸弹"闸：压缩数据可以展开成远超 comp_size 的内容，
                //    这里用 take 限制解压出来的总量（超出的部分直接截断，解析自然失败 → 退回包名）。
                let mut out = Vec::new();
                flate2::read::DeflateDecoder::new(&data[..])
                    .take(MAX_ZIP_ENTRY_BYTES)
                    .read_to_end(&mut out)
                    .ok()?;
                Some(out)
            }
            _ => None,
        };
    }
    None
}

/// 读一个 APK 的应用名；任何问题都返回 None（调用方用包名兜底）
pub fn label_from_apk(apk: &Path) -> Option<String> {
    let manifest = zip_read_entry(apk, "AndroidManifest.xml")?;
    let (direct, reference) = xml_label(&manifest)?;
    if let Some(s) = direct.filter(|s| !s.trim().is_empty()) {
        return Some(s);
    }
    let res_id = reference?;
    let arsc = zip_read_entry(apk, "resources.arsc")?;
    arsc_string(&arsc, res_id, &device_locale()).filter(|s| !s.trim().is_empty())
}


#[cfg(test)]
mod tests {
    use super::*;

    /// 手搓一个最小的字符串池：UTF-8 两条 + UTF-16 一条
    #[test]
    fn string_pool_reads_both_encodings() {
        // UTF-8 池
        let mut data = Vec::new();
        let strings = {
            let mut s = Vec::new();
            // ⚠️ UTF-8 串池里：第一个长度是 **UTF-16 字符数**，第二个才是字节数
            for (text, chars) in [("hi", 2usize), ("你好", 2usize)] {
                s.push(chars as u8); // 字符数
                s.push(text.len() as u8); // 字节数
                s.extend_from_slice(text.as_bytes());
                s.push(0);
            }
            s
        };
        let header_size = 28usize;
        let strings_start = header_size + 2 * 4;
        // chunk header
        data.extend_from_slice(&RES_STRING_POOL_TYPE.to_le_bytes());
        data.extend_from_slice(&(header_size as u16).to_le_bytes());
        data.extend_from_slice(&((strings_start + strings.len()) as u32).to_le_bytes());
        data.extend_from_slice(&2u32.to_le_bytes()); // stringCount
        data.extend_from_slice(&0u32.to_le_bytes()); // styleCount
        data.extend_from_slice(&(1u32 << 8).to_le_bytes()); // UTF8_FLAG
        data.extend_from_slice(&(strings_start as u32).to_le_bytes());
        data.extend_from_slice(&0u32.to_le_bytes()); // stylesStart
        data.extend_from_slice(&0u32.to_le_bytes()); // offset[0]
        // offset[1]：第一条 "hi" 占 5 字节（字符数 1 + 字节数 1 + "hi" 2 + NUL 1）
        data.extend_from_slice(&5u32.to_le_bytes());
        data.extend_from_slice(&strings);

        let pool = StringPool::parse(&data).expect("应该能解析");
        assert_eq!(pool.get(0).as_deref(), Some("hi"));
        assert_eq!(pool.get(1).as_deref(), Some("你好"));
        assert_eq!(pool.get(9), None);
    }

    /// 拿真实 APK 验：
    ///   SEVENK_APK=/path/a.apk [SEVENK_APK_LABEL=期望名] \
    ///     cargo test --release --offline -- --ignored --nocapture apk_label_against_real_apk
    #[test]
    #[ignore = "需要真实 APK（本机的 getprop 不存在时，只能匹配默认配置的那份字符串）"]
    fn apk_label_against_real_apk() {
        let Ok(path) = std::env::var("SEVENK_APK") else {
            return;
        };
        let label = label_from_apk(Path::new(&path));
        println!("APK {path} 的 label = {label:?}");
        assert!(label.is_some(), "应该能解析出应用名");
        if let Ok(expect) = std::env::var("SEVENK_APK_LABEL") {
            assert_eq!(label.as_deref(), Some(expect.as_str()), "应用名不对");
        }
    }

    #[test]
    fn junk_input_does_not_panic() {
        assert!(xml_label(&[]).is_none());
        assert!(xml_label(&[0xff; 32]).is_none());
        assert!(arsc_string(&[], 0x7f01_0001, &(String::new(), String::new())).is_none());
        assert!(
            arsc_string(&[0xff; 64], 0x7f01_0001, &(String::new(), String::new())).is_none()
        );
        assert!(StringPool::parse(&[0u8; 8]).is_none());
    }

    /// 伪造 arsc：**来自文件的数量/长度字段**写成天文数字时必须立刻放弃 ——
    /// 既不能 `Vec::with_capacity(~43 亿)` 去申请十几 GB（OOM abort），
    /// 也不能 `(0..21 亿)` 空转卡死。
    #[test]
    fn forged_counts_are_rejected() {
        // ① stringCount = 0xffff_ffff（旧代码会 Vec::with_capacity(4294967295)）
        let mut forged_pool = Vec::new();
        forged_pool.extend_from_slice(&RES_STRING_POOL_TYPE.to_le_bytes());
        forged_pool.extend_from_slice(&28u16.to_le_bytes()); // headerSize
        forged_pool.extend_from_slice(&28u32.to_le_bytes()); // chunk size：只够 header
        forged_pool.extend_from_slice(&0xffff_ffffu32.to_le_bytes()); // stringCount ← 伪造
        forged_pool.extend_from_slice(&0u32.to_le_bytes()); // styleCount
        forged_pool.extend_from_slice(&0u32.to_le_bytes()); // flags
        forged_pool.extend_from_slice(&28u32.to_le_bytes()); // stringsStart
        forged_pool.extend_from_slice(&0u32.to_le_bytes()); // stylesStart
        assert!(StringPool::parse(&forged_pool).is_none());

        // ② UTF-16 长度 = 0x7fff_ffff（旧代码 (0..2147483647).filter_map(...) 空转）
        let strings_start = 32usize; // header(28) + offset[0](4)
        let mut forged_len = Vec::new();
        forged_len.extend_from_slice(&RES_STRING_POOL_TYPE.to_le_bytes());
        forged_len.extend_from_slice(&28u16.to_le_bytes());
        forged_len.extend_from_slice(&((strings_start + 4) as u32).to_le_bytes());
        forged_len.extend_from_slice(&1u32.to_le_bytes()); // stringCount = 1
        forged_len.extend_from_slice(&0u32.to_le_bytes()); // styleCount
        forged_len.extend_from_slice(&0u32.to_le_bytes()); // flags：无 UTF8_FLAG → UTF-16
        forged_len.extend_from_slice(&(strings_start as u32).to_le_bytes());
        forged_len.extend_from_slice(&0u32.to_le_bytes()); // stylesStart
        forged_len.extend_from_slice(&0u32.to_le_bytes()); // offset[0] = 0
        forged_len.extend_from_slice(&0xffffu16.to_le_bytes()); // 高位=1 → 长度占 2 个 u16
        forged_len.extend_from_slice(&0xffffu16.to_le_bytes()); // char_len = 0x7fff_ffff
        let pool = StringPool::parse(&forged_len).expect("池本身合法");
        assert_eq!(pool.get(0), None, "伪造的超长字符串必须被拒绝");
    }
}

#[cfg(test)]
mod diag_tests {
    use super::*;

    /// 诊断某个 APK 的标签解析（可指定资源 id）：
    ///   SEVENK_APK=/path/a.apk [SEVENK_RESID=0x7f0a0001] \
    ///     cargo test --release --offline -- --ignored --nocapture diag_label
    #[test]
    #[ignore = "诊断用"]
    fn diag_label() {
        let Ok(path) = std::env::var("SEVENK_APK") else {
            return;
        };
        let manifest = zip_read_entry(Path::new(&path), "AndroidManifest.xml").expect("manifest");
        let (direct, reference) = xml_label(&manifest).expect("解析 manifest");
        println!("直接字符串={direct:?} 引用={reference:?}");
        let resid = std::env::var("SEVENK_RESID")
            .ok()
            .and_then(|v| u32::from_str_radix(v.trim_start_matches("0x"), 16).ok())
            .or(reference);
        let Some(resid) = resid else { return };
        let arsc = zip_read_entry(Path::new(&path), "resources.arsc").expect("arsc");
        let pkg = (resid >> 24) as u8;
        let ty = ((resid >> 16) & 0xff) as u8;
        let ent = (resid & 0xffff) as usize;
        println!("找 0x{resid:08x}（pkg=0x{pkg:02x} type={ty} entry={ent}）");
        let mut gp = None;
        for (t, off, size) in chunks(&arsc, u16le(&arsc, 2).unwrap() as usize, arsc.len()) {
            if t == RES_STRING_POOL_TYPE && gp.is_none() {
                gp = StringPool::parse(&arsc[off..off + size]);
            }
        }
        let gp = gp.expect("全局池");
        for (t, off, size) in chunks(&arsc, u16le(&arsc, 2).unwrap() as usize, arsc.len()) {
            if t != RES_TABLE_PACKAGE_TYPE {
                continue;
            }
            let chunk = &arsc[off..off + size];
            if chunk[8] != pkg {
                continue;
            }
            let hs = u16le(chunk, 2).unwrap() as usize;
            println!("  包 chunk 大小={} headerSize={hs}（typeStrings={} keyStrings={}）",
                chunk.len(), u32le(chunk, 268).unwrap_or(0), u32le(chunk, 276).unwrap_or(0));
            for (pt, po, ps) in chunks(chunk, hs, chunk.len()) {
                println!("    内部 chunk type=0x{pt:04x} off={po} size={ps} id={:?}", chunk.get(po + 8).copied());
                if pt != RES_TABLE_TYPE_TYPE {
                    continue;
                }
                let tc = &chunk[po..po + ps];
                if tc[8] != ty {
                    continue;
                }
                let flags = tc[9];
                let ec = u32le(tc, 12).unwrap() as usize;
                let es = u32le(tc, 16).unwrap() as usize;
                let cs = u32le(tc, 20).unwrap() as usize;
                let ths = u16le(tc, 2).unwrap() as usize;
                let _ = (ec, es, cs);
                let (lang, country) = config_locale(tc, cs);
                let off16 = flags & TYPE_FLAG_OFFSET16 != 0;
                let eo = if flags & TYPE_FLAG_SPARSE != 0 {
                    (0..ec)
                        .find(|i| u16le(tc, ths + i * 4).unwrap() as usize == ent)
                        .map(|i| {
                            let raw = u16le(tc, ths + i * 4 + 2).unwrap() as usize;
                            if off16 { raw * 4 } else { raw }
                        })
                } else if off16 {
                    match u16le(tc, ths + ent * 2).unwrap() as usize {
                        0xffff => None,
                        raw => Some(raw * 4),
                    }
                } else {
                    match u32le(tc, ths + ent * 4).unwrap() {
                        0xffff_ffff => None,
                        o => Some(o as usize),
                    }
                };
                match eo {
                    None => println!(
                        "  lang={lang:?}/{country:?} flags=0x{flags:02x} ths={ths} → 没有这个条目"
                    ),
                    Some(rel) => {
                        let e = es + rel;
                        let (dt, data) = if off16 {
                            (tc[e + 3], u32le(tc, e + 4).unwrap()) // 紧凑条目
                        } else {
                            let esz = u16le(tc, e).unwrap() as usize;
                            let v = e + esz;
                            (tc[v + 3], u32le(tc, v + 4).unwrap())
                        };
                        println!(
                            "  lang={lang:?}/{country:?} flags=0x{flags:02x} entryOff={rel} dataType=0x{dt:02x} data={data} → {:?}",
                            if dt == 3 { gp.get(data as usize) } else { None }
                        );
                    }
                }
            }
        }
        println!("最终 = {:?}", label_from_apk(Path::new(&path)));
    }
}
