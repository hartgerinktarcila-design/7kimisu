#include <linux/err.h>
#include <linux/fs.h>
#include <linux/gfp.h>
#include <linux/kernel.h>
#include <linux/limits.h>
#include <linux/slab.h>
#include <linux/version.h>
#ifdef CONFIG_KSU_DEBUG
#include <linux/moduleparam.h>
#endif
#include <crypto/hash.h>
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 11, 0)
#include <crypto/sha2.h>
#else
#include <crypto/sha.h>
#endif
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 4, 0)
#include <linux/hex.h>
#endif

#include "manager/apk_sign.h"
#include "uapi/app_profile.h"
#include "klog.h" // IWYU pragma: keep

struct sdesc {
    struct shash_desc shash;
    char ctx[];
};

static struct sdesc *init_sdesc(struct crypto_shash *alg)
{
    struct sdesc *sdesc;
    int size;

    size = sizeof(struct shash_desc) + crypto_shash_descsize(alg);
    sdesc = kzalloc(size, GFP_KERNEL);
    if (!sdesc)
        return ERR_PTR(-ENOMEM);
    sdesc->shash.tfm = alg;
    return sdesc;
}

static int calc_hash(struct crypto_shash *alg, const unsigned char *data, unsigned int datalen, unsigned char *digest)
{
    struct sdesc *sdesc;
    int ret;

    sdesc = init_sdesc(alg);
    if (IS_ERR(sdesc)) {
        pr_info("can't alloc sdesc\n");
        return PTR_ERR(sdesc);
    }

    ret = crypto_shash_digest(&sdesc->shash, data, datalen, digest);
    kfree(sdesc);
    return ret;
}

static int ksu_sha256(const unsigned char *data, unsigned int datalen, unsigned char *digest)
{
    struct crypto_shash *alg;
    char *hash_alg_name = "sha256";
    int ret;

    alg = crypto_alloc_shash(hash_alg_name, 0, 0);
    if (IS_ERR(alg)) {
        pr_info("can't alloc alg %s\n", hash_alg_name);
        return PTR_ERR(alg);
    }
    ret = calc_hash(alg, data, datalen, digest);
    crypto_free_shash(alg);
    return ret;
}

static bool read_exact(struct file *fp, void *buffer, size_t size, loff_t *pos, loff_t end)
{
    if (*pos < 0 || *pos > end || size > (size_t)(end - *pos))
        return false;

    return kernel_read(fp, buffer, size, pos) == (ssize_t)size;
}

static bool read_length_prefixed_end(struct file *fp, loff_t *pos, loff_t container_end, loff_t *value_end)
{
    u32 length;

    if (!read_exact(fp, &length, sizeof(length), pos, container_end))
        return false;
    if (length > INT_MAX || length > (u64)(container_end - *pos))
        return false;

    *value_end = *pos + length;
    return true;
}

static bool check_block(struct file *fp, loff_t *pos, loff_t block_end, unsigned expected_size,
                        const char *expected_sha256)
{
    loff_t signers_end, signer_end, signed_data_end, digests_end, certificates_end;
    u32 certificate_size;

    // v2 block: signers sequence -> first signer -> signed data -> digests
    if (!read_length_prefixed_end(fp, pos, block_end, &signers_end) ||
        !read_length_prefixed_end(fp, pos, signers_end, &signer_end) ||
        !read_length_prefixed_end(fp, pos, signer_end, &signed_data_end) ||
        !read_length_prefixed_end(fp, pos, signed_data_end, &digests_end))
        return false;

    *pos = digests_end;
    if (!read_length_prefixed_end(fp, pos, signed_data_end, &certificates_end) ||
        !read_exact(fp, &certificate_size, sizeof(certificate_size), pos, certificates_end))
        return false;

    if (certificate_size > INT_MAX || certificate_size > (u64)(certificates_end - *pos))
        return false;

#define CERT_MAX_LENGTH 1024
    if (certificate_size != expected_size)
        return false;

    if (certificate_size > CERT_MAX_LENGTH) {
        pr_info("cert length overlimit\n");
        return false;
    }

    char cert[CERT_MAX_LENGTH];
    if (!read_exact(fp, cert, certificate_size, pos, certificates_end))
        return false;

    unsigned char digest[SHA256_DIGEST_SIZE];
    if (ksu_sha256(cert, certificate_size, digest)) {
        pr_info("sha256 error\n");
        return false;
    }

    char hash_str[SHA256_DIGEST_SIZE * 2 + 1];
    hash_str[SHA256_DIGEST_SIZE * 2] = '\0';

    bin2hex(hash_str, digest, SHA256_DIGEST_SIZE);
    pr_info("sha256: %s, expected: %s\n", hash_str, expected_sha256);
    return strcmp(expected_sha256, hash_str) == 0;
}

static __always_inline bool check_v2_signature(char *path, unsigned expected_size, const char *expected_sha256)
{
    unsigned char buffer[0x10] = { 0 };
    u32 cd_offset, cd_size;
    u32 zip64_locator_magic;
    u64 size_of_block, size_of_block_at_head;

    loff_t pos, pairs_end, file_size, eocd_offset;

    bool v2_signing_valid = false;
    int v2_signing_blocks = 0;

    int i;
    struct file *fp = filp_open(path, O_RDONLY, 0);
    if (IS_ERR(fp)) {
        pr_err("open %s error.\n", path);
        return false;
    }

    // disable inotify for this file
    fp->f_mode |= FMODE_NONOTIFY;

    file_size = generic_file_llseek(fp, 0, SEEK_END);
    if (file_size < 0)
        goto clean;

    // https://en.wikipedia.org/wiki/Zip_(file_format)#End_of_central_directory_record_(EOCD)
    for (i = 0;; ++i) {
        unsigned short comment_size;
        u32 magic;
        pos = file_size - i - 2;
        if (!read_exact(fp, &comment_size, sizeof(comment_size), &pos, file_size))
            goto clean;
        if (comment_size == i) {
            pos -= 22;
            if (!read_exact(fp, &magic, sizeof(magic), &pos, file_size))
                goto clean;
            if (magic == 0x06054b50) {
                eocd_offset = pos - sizeof(magic);
                break;
            }
        }
        if (i == 0xffff) {
            pr_info("error: cannot find eocd\n");
            goto clean;
        }
    }

    // reject ZIP64 before looking for a signing block
    if (eocd_offset >= 20) {
        pos = eocd_offset - 20;
        if (!read_exact(fp, &zip64_locator_magic, sizeof(zip64_locator_magic), &pos, file_size))
            goto clean;
        if (zip64_locator_magic == 0x07064b50)
            goto clean;
    }

    pos = eocd_offset + 12;
    // size of central directory
    if (!read_exact(fp, &cd_size, sizeof(cd_size), &pos, file_size))
        goto clean;
    // offset of central directory
    if (!read_exact(fp, &cd_offset, sizeof(cd_offset), &pos, file_size))
        goto clean;
    if ((u64)cd_offset > (u64)eocd_offset || (u64)cd_size != (u64)eocd_offset - cd_offset)
        goto clean;
    if (cd_offset < 0x20)
        goto clean;

    pairs_end = (loff_t)cd_offset - 0x18;
    pos = pairs_end;

    if (!read_exact(fp, &size_of_block, sizeof(size_of_block), &pos, cd_offset))
        goto clean;
    if (!read_exact(fp, buffer, sizeof(buffer), &pos, cd_offset))
        goto clean;
    if (memcmp((char *)buffer, "APK Sig Block 42", sizeof(buffer)))
        goto clean;

    if (size_of_block < 0x18 || size_of_block > INT_MAX - 0x8 || size_of_block > (u64)cd_offset - 0x8)
        goto clean;

    pos = (loff_t)cd_offset - (loff_t)size_of_block - 0x8;
    if (!read_exact(fp, &size_of_block_at_head, sizeof(size_of_block_at_head), &pos, pairs_end))
        goto clean;
    if (size_of_block_at_head != size_of_block)
        goto clean;

    // Scan every length-prefixed pair, matching AOSP's signing block parser
    // Each valid pair consumes an 8-byte length plus at least a 4-byte ID, so
    // malformed entries fail below instead of spinning in place.
    while (pos < pairs_end) {
        uint32_t id;
        u64 size_of_pair;
        loff_t pair_end;

        if (!read_exact(fp, &size_of_pair, sizeof(size_of_pair), &pos, pairs_end))
            goto invalid;
        if (size_of_pair < sizeof(id) || size_of_pair > INT_MAX || size_of_pair > (u64)(pairs_end - pos))
            goto invalid;

        pair_end = pos + (loff_t)size_of_pair;
        if (!read_exact(fp, &id, sizeof(id), &pos, pair_end))
            goto invalid;

        if (id == 0x7109871au) {
            v2_signing_blocks++;
            v2_signing_valid = check_block(fp, &pos, pair_end, expected_size, expected_sha256);
        } else if (id != 0x42726577u) { // APK verity padding
            // https://cs.android.com/android/platform/superproject/+/android-latest-release:tools/apksig/src/main/java/com/android/apksig/internal/apk/ApkSigningBlockUtils.java;l=102;drc=ebe4dfd4fd6550c949a6c7c2427484bf5e96500b
#ifdef CONFIG_KSU_DEBUG
            pr_info("Unexpected signature block id: 0x%08x\n", id);
#endif
            goto invalid;
        }
        pos = pair_end;
    }

    if (v2_signing_blocks != 1) {
#ifdef CONFIG_KSU_DEBUG
        pr_err("Unexpected v2 signature count: %d\n", v2_signing_blocks);
#endif
        v2_signing_valid = false;
    }

    goto clean;

invalid:
    v2_signing_valid = false;
clean:
    filp_close(fp, 0);

    return v2_signing_valid;
}

/*
 * ══════════════════════════════════════════════════════════════════════
 * 断代闸门（2026-09-20）
 *
 *   认主是内核扫包**自动**完成的（throne_tracker.c）：扫到「签名匹配」的
 *   base.apk 就认成管理器。闸门就加在这一步 —— 除签名外，APK 还必须声明
 *   世代号，声明的形式是包内的一条 ZIP 条目：
 *
 *       assets/ksu_manager_gen<N>        （N 为十进制数字，N >= 1）
 *
 *   · 老版本 APK 的包里**没有**这条条目 → 世代号 0 → 不被认主；
 *   · 条目名在 ZIP 中央目录里（明文、未压缩），所以只读中央目录即可，
 *     不必解压 APK，也不用碰签名块（v2 签名校验那条路一行没动）；
 *   · 条目名是**包的静态属性**，不依赖 App 是否运行、不依赖任何可写文件，
 *     所以"先装新版再换老版"也骗不过去；干净设备第一次开机就能认主。
 *
 *   为什么不用「/data/adb/sevenk/ 里的标记文件」：那种标记是一次性解锁，
 *   新版管理器装过一次之后，老 APK 照样能被认主（要求是"任何老 APK"）。
 *
 *   为什么不用"认主那次系统调用加世代参数"：这里的认主是内核扫包自动完成、
 *   没有来自 App 的系统调用可加参数；改成"App 主动申请"会动到认主时序
 *   （开机早期、App 还没启动时管理器就不成立），风险远大于收益。
 * ══════════════════════════════════════════════════════════════════════
 */
#define KSU_GEN_ENTRY_PREFIX "assets/ksu_manager_gen"
#define KSU_CD_HEADER_SIZE 46 // sizeof(central directory file header)
#define KSU_CD_MAX_NAME 512

/* ZIP 里的多字节字段一律小端；这里不引 asm/unaligned.h，手工拼，省一个依赖 */
static u32 ksu_le32(const unsigned char *p)
{
    return (u32)p[0] | ((u32)p[1] << 8) | ((u32)p[2] << 16) | ((u32)p[3] << 24);
}

static u16 ksu_le16(const unsigned char *p)
{
    return (u16)((u16)p[0] | ((u16)p[1] << 8));
}

/*
 * 找 EOCD（End Of Central Directory）记录的位置。
 * 逻辑与 check_v2_signature() 里那段一致，但**单独写一份**：签名校验是 root
 * 的命门，不去重构它、免得闸门这边出错连累签名。
 */
static bool ksu_find_eocd(struct file *fp, loff_t file_size, loff_t *eocd_offset)
{
    int i;
    loff_t pos;

    for (i = 0; i <= 0xffff; ++i) {
        unsigned short comment_size;
        u32 magic;

        pos = file_size - i - 2;
        if (!read_exact(fp, &comment_size, sizeof(comment_size), &pos, file_size))
            return false;
        if (comment_size != i)
            continue;

        pos -= 22; // 回到 EOCD 记录开头
        if (!read_exact(fp, &magic, sizeof(magic), &pos, file_size))
            return false;
        if (magic == 0x06054b50) {
            *eocd_offset = pos - sizeof(magic);
            return true;
        }
    }

    return false;
}

/* 条目名形如 assets/ksu_manager_gen2 → 返回 2；不是这个形状 → 返回 0 */
static int ksu_parse_gen_name(const char *name, u16 len)
{
    static const char prefix[] = KSU_GEN_ENTRY_PREFIX;
    const u16 plen = (u16)(sizeof(prefix) - 1);
    u16 i;
    int gen = 0;

    if (len <= plen || len > KSU_CD_MAX_NAME)
        return 0;
    if (memcmp(name, prefix, plen))
        return 0;

    /* 前缀后面必须**全是数字**（.bak / .txt 之类的后缀一律不算，防止误判） */
    for (i = plen; i < len; i++) {
        if (name[i] < '0' || name[i] > '9')
            return 0;
        gen = gen * 10 + (name[i] - '0');
        if (gen > 1000000)
            return 0;
    }

    return gen;
}

/*
 * 扫一遍中央目录，返回 APK 声明的世代号；0 = 没有这条条目（= 老 APK）。
 * 只在 zipalign/签名后的正常 APK 上跑；任何异常都退回 0（宁可不认主，也不误认）。
 */
static int ksu_scan_manager_gen(struct file *fp, u32 cd_offset, u32 cd_size, loff_t file_size)
{
    loff_t pos = (loff_t)cd_offset;
    const loff_t end = (loff_t)cd_offset + (loff_t)cd_size;

    if (end > file_size || end <= pos)
        return 0;

    while (pos + KSU_CD_HEADER_SIZE <= end) {
        unsigned char hdr[KSU_CD_HEADER_SIZE];
        char name[KSU_CD_MAX_NAME + 1];
        u16 name_len, extra_len, comment_len;
        loff_t skip;

        if (!read_exact(fp, hdr, sizeof(hdr), &pos, end))
            return 0;
        if (ksu_le32(hdr) != 0x02014b50) // central directory file header 签名
            return 0;

        name_len = ksu_le16(hdr + 28);
        extra_len = ksu_le16(hdr + 30);
        comment_len = ksu_le16(hdr + 32);
        if (name_len == 0 || name_len > KSU_CD_MAX_NAME)
            return 0;

        if (!read_exact(fp, name, name_len, &pos, end))
            return 0;
        name[name_len] = '\0';

        {
            int gen = ksu_parse_gen_name(name, name_len);
            if (gen > 0)
                return gen;
        }

        skip = (loff_t)extra_len + (loff_t)comment_len;
        if (skip < 0 || pos + skip > end)
            return 0;
        pos += skip;
    }

    return 0;
}

/* 读 APK 里声明的世代号（不校验签名，调用方先过签名） */
static int ksu_read_manager_gen(char *path)
{
    struct file *fp;
    loff_t file_size, eocd_offset, pos;
    u32 cd_size, cd_offset;
    int gen;

    fp = filp_open(path, O_RDONLY, 0);
    if (IS_ERR(fp)) {
        pr_err("gen: open %s error.\n", path);
        return 0;
    }

    file_size = generic_file_llseek(fp, 0, SEEK_END);
    if (file_size <= 0)
        goto out;

    if (!ksu_find_eocd(fp, file_size, &eocd_offset))
        goto out;

    pos = eocd_offset + 12;
    if (!read_exact(fp, &cd_size, sizeof(cd_size), &pos, file_size))
        goto out;
    if (!read_exact(fp, &cd_offset, sizeof(cd_offset), &pos, file_size))
        goto out;

    gen = ksu_scan_manager_gen(fp, cd_offset, cd_size, file_size);
    filp_close(fp, 0);
    return gen;

out:
    filp_close(fp, 0);
    return 0;
}

/*
 * 返回值（认主与安全阀的唯一判据）：
 *   -1 : 不是我们签名的 APK
 *    0 : 签名对，但包里没有世代标记（= 老版本管理器）→ **不认主**
 *   >0 : 签名对 + 声明的世代号（>= KSU_MANAGER_MIN_GEN 才认主）
 */
int ksu_manager_apk_gen(char *path)
{
#ifdef KSU_MANAGER_PACKAGE
    char pkg[KSU_MAX_PACKAGE_NAME];
    if (get_pkg_from_apk_path(pkg, path) < 0) {
        pr_err("Failed to get package name from apk path: %s\n", path);
        return -1;
    }

    // pkg is `<real package>`
    if (strncmp(pkg, KSU_MANAGER_PACKAGE, sizeof(KSU_MANAGER_PACKAGE))) {
        return -1;
    }
#endif
    if (!check_v2_signature(path, EXPECTED_SIZE, EXPECTED_HASH)) {
#ifdef EXPECTED_SIZE2
        if (!check_v2_signature(path, EXPECTED_SIZE2, EXPECTED_HASH2))
            return -1;
#else
        return -1;
#endif
    }

    return ksu_read_manager_gen(path);
}

#ifdef CONFIG_KSU_DEBUG

int ksu_debug_manager_appid = -1;

#include "manager/manager_identity.h"

static int set_expected_size(const char *val, const struct kernel_param *kp)
{
    int rv = param_set_uint(val, kp);
    ksu_set_manager_appid(ksu_debug_manager_appid);
    pr_info("ksu_manager_appid set to %d\n", ksu_debug_manager_appid);
    return rv;
}

static struct kernel_param_ops expected_size_ops = {
    .set = set_expected_size,
    .get = param_get_uint,
};

module_param_cb(ksu_debug_manager_appid, &expected_size_ops, &ksu_debug_manager_appid, S_IRUSR | S_IWUSR);

#endif

int get_pkg_from_apk_path(char *pkg, const char *path)
{
    int len = strlen(path);
    if (len >= KSU_MAX_PACKAGE_NAME || len < 1)
        return -1;

    const char *last_slash = NULL;
    const char *second_last_slash = NULL;

    int i;
    for (i = len - 1; i >= 0; i--) {
        if (path[i] == '/') {
            if (!last_slash) {
                last_slash = &path[i];
            } else {
                second_last_slash = &path[i];
                break;
            }
        }
    }

    if (!last_slash || !second_last_slash)
        return -1;

    const char *last_hyphen = strchr(second_last_slash, '-');
    if (!last_hyphen || last_hyphen > last_slash)
        return -1;

    int pkg_len = last_hyphen - second_last_slash - 1;
    if (pkg_len >= KSU_MAX_PACKAGE_NAME || pkg_len <= 0)
        return -1;

    // Copying the package name
    memcpy(pkg, second_last_slash + 1, pkg_len);
    pkg[pkg_len] = '\0';

    return 0;
}

bool is_manager_apk(char *path)
{
    /*
     * 语义保持原样：**只看签名**（"这个包是不是我们自己签的"）。
     * 认主的世代闸门不在这里，而在 ksu_manager_apk_gen() + throne_tracker.c：
     * 只有安全阀需要"签名对就算"的宽松判据，认主需要"签名对且世代达标"。
     */
    return ksu_manager_apk_gen(path) >= 0;
}
