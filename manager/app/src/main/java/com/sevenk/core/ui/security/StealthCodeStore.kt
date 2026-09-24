package com.sevenk.core.ui.security

import android.content.Context
import com.topjohnwu.superuser.ShellUtils
import com.sevenk.core.data.repository.SettingsRepositoryImpl
import com.sevenk.core.ui.util.getRootShell

/**
 * 隐身密令的「跨卸载备份」。
 *
 * 为什么需要它(这是个真会把人锁死的 bug):
 *   · 隐身状态存在**内核侧** `/data/adb/sevenk/stealth` —— 卸载 App 也不会丢;
 *   · 而密令数字以前只存在 **App 自己的 shared_prefs** 里 —— 卸载就没了。
 *   于是:改了自定义密令 → 卸载重装 → 内核仍处于隐身(界面伪装成"未安装"),
 *   而 App 里的"期望密令"已经退回默认 70707 → 你输入自己那个自定义密令,
 *   和 70707 不匹配 → 接收器**静默忽略** → 恢复不回来,彻底锁死。
 *
 * 所以这里把密令额外写一份到 `/data/adb/sevenk/stealth_code`(root,卸载不丢),
 * 读取时**优先用它**,拿不到才退回 App 设置,再退回默认值。
 */
object StealthCodeStore {

    private const val TAG = "7kkernel-stealth"

    private const val DIR = "/data/adb/sevenk"
    private const val PATH = "$DIR/stealth_code"

    /**
     * 改名迁移的**只读兜底**（2026-09-20）：旧目录 `/data/adb/ksu` 下的那份密令。
     *
     * 为什么留这一条：数据目录刚从 `/data/adb/ksu` 改成 `/data/adb/sevenk`，
     * 迁移由 ksud（开机/每次调用）和 App 启动时的 [com.sevenk.core.ui.util.LegacyDataMigration]
     * 负责。但**万一迁移还没跑完**（App 刚起、ksud 还没轮到），密令就会读不到 ——
     * 而"读不到密令"意味着用户拨号盘退不出隐身 = **被锁死**。这是本项目最怕的事故。
     * 所以这里多读一处旧路径；读到就顺手补写到新路径（自愈）。
     *
     * 🟢 v2.9 起的事实（**下面那段旧注释已作废**）：`/data/adb/ksu` **现在故意**保留一条
     * 指向 `/data/adb/sevenk` 的**兼容软链**（权威实现在 ksud
     * `userspace/ksud/src/migrate.rs::ensure_compat_link_at`）。两个原因：
     *   ① **模块生态硬编码老路径**：改名之后大量第三方模块/工具把自己写死的
     *      `/data/adb/ksu/...` 当成 KernelSU 数据目录（典型 Zygisk Next 报
     *      "Module files corrupted"），没有这条软链它们找不到自己的文件；
     *   ② **不影响隐身**：`/data/adb` 整个目录**只有 root 能读**（实测设备 HA247EY4：
     *      `ls /data/adb` = Permission denied），没有 root 的检测类 App **连看都看不到**
     *      这条软链。
     * 所以下面这一次只读 `cat` 现在**走的就是那条软链、读到的就是同一份文件**；
     * 它保留的意义是"软链还没补上 / 被旧管理器顶掉"那几秒里的兜底
     * （读到就补写到新路径，自愈）。
     */
    private const val LEGACY_PATH = "/data/adb/ksu/stealth_code"

    /** 默认密令(和 StealthReceiver 保持一致) */
    const val DEFAULT_CODE = "70707"

    /**
     * KernelSU 自己那些文件的 SELinux 类型(和 ksud 的 `restorecon.rs` 里 `KSU_CON` 同一个值)。
     *
     * `/data/adb` 下的文件默认标签是 `u:object_r:adb_data_file:s0`,
     * ksud 自己的文件(ksud 本体、模块元数据)统一是 `u:object_r:ksu_file:s0`。
     * v2.1(审计 P1-6):密令这份也归一到 `ksu_file`,和其它落盘物一致。
     */
    private const val KSU_CON = "u:object_r:ksu_file:s0"

    private fun sanitize(raw: String?): String? =
        raw?.trim()?.filter { it.isDigit() }?.take(12)?.takeIf { it.isNotBlank() }

    /** 读 /data/adb 里那份(需要 root;失败返回 null)。新路径读不到时兜底读旧路径(见 [LEGACY_PATH]) */
    fun read(): String? = runCatching {
        sanitize(ShellUtils.fastCmd(getRootShell(), "cat $PATH 2>/dev/null"))
            ?: sanitize(ShellUtils.fastCmd(getRootShell(), "cat $LEGACY_PATH 2>/dev/null"))
                ?.also { write(it) } // 自愈:补写到新路径
    }.getOrNull()

    /**
     * 把密令写到 /data/adb(卸载 App 也不会丢)。
     *
     * ⚠️ v2.1(审计 P1-6):以前只有一句
     * `mkdir -p … && printf … > … && chmod 600 …` 就返回 true ——
     * **既没人验,权限还不可靠**:真机实测这个文件是 `-rwxrwxrwx`(777),
     * 等于任何 App / 任何 uid 都能改掉隐身密令(隐身等于没隐)。
     *
     * 现在的做法(**先写临时文件、验过了才顶替真文件**):
     *   ① 写 `$PATH.new` → `chmod 600` → `chown 0:0` → `chcon ksu_file`(不行就 `restorecon`);
     *   ② **回读核验**:内容一字不差 + 权限正好 600 + 属主 0;
     *   ③ 全对才 `mv -f` 覆盖真文件,并再验一次。
     * 任何一步不对就删掉临时文件、**真文件一个字节都不动** ——
     * 密令文件坏掉 = 用户被锁死在隐身里,这是本项目最怕的事故,所以宁可这次不写。
     *
     * @return 是否**真的**落盘并且核验通过
     */
    fun write(code: String): Boolean = runCatching {
        val c = sanitize(code) ?: return@runCatching false
        val tmp = "$PATH.new"
        // 用 ${'$'} 转义 shell 变量:这段是 sh 脚本,不是 Kotlin 模板
        val script = """
            mkdir -p $DIR
            printf '%s' '$c' > $tmp
            chmod 600 $tmp 2>/dev/null
            chown 0:0 $tmp 2>/dev/null
            chcon $KSU_CON $tmp 2>/dev/null || restorecon $tmp 2>/dev/null || true
            got=${'$'}(cat $tmp 2>/dev/null)
            mode=${'$'}(stat -c %a $tmp 2>/dev/null)
            owner=${'$'}(stat -c %u $tmp 2>/dev/null)
            if [ "${'$'}got" = '$c' ] && [ "${'$'}mode" = '600' ] && [ "${'$'}owner" = '0' ]; then
              mv -f $tmp $PATH
              chmod 600 $PATH 2>/dev/null
              chown 0:0 $PATH 2>/dev/null
              got2=${'$'}(cat $PATH 2>/dev/null)
              if [ "${'$'}got2" = '$c' ]; then echo "7K_OK mode=${'$'}mode owner=${'$'}owner"; else echo 7K_FAIL_AFTER_MV; fi
            else
              rm -f $tmp
              echo "7K_FAIL mode=${'$'}mode owner=${'$'}owner"
            fi
        """.trimIndent()
        val out = ShellUtils.fastCmd(getRootShell(), script)
        android.util.Log.i(TAG, "密令落盘核验:${out.trim()}")
        out.contains("7K_OK")
    }.getOrDefault(false)

    /**
     * 真正生效的密令:优先 /data/adb 那份(能跨卸载),其次 App 设置,最后默认值。
     * **界面显示**用它(设置页要展示"当前生效的是哪个")。
     *
     * ⚠️ 但**接收器比对不要用这个** —— 见 [acceptedCodes]。
     */
    fun effectiveCode(context: Context? = null): String {
        read()?.let { return it }
        val pref = runCatching { SettingsRepositoryImpl().stealthCode }.getOrNull()
        return sanitize(pref) ?: DEFAULT_CODE
    }

    /**
     * **可接受的密令集合(取并集)** —— 接收器比对用这个。
     *
     * 为什么必须是并集:密令存在两个地方 —— `/data/adb/sevenk/stealth_code`(跨卸载不丢)
     * 和 App 设置。以前比对只认"优先的那一份"(磁盘优先),于是只要两份**不一致**
     * 就会锁死。最典型的路径:
     *   · 用户在设置页把密令改成 A → `prefsRepo.stealthCode = A`,同时异步写磁盘;
     *   · 那次写入**失败**(当时拿不到 root / shell 起不来),磁盘上留的还是旧值 B;
     *   · `effectiveCode()` 优先返回磁盘的 B → 用户拨 A → 不匹配 → **静默忽略** → 退不出隐身。
     * (2026-09-16 有用户反馈「隐身退不出去、拨号没反应」,这是其中一条现实路径。)
     *
     * 取并集之后,磁盘和设置里的**任意一个**都能开锁,不会再因为两份不一致而锁死。
     *
     * ⚠️ 有意**不**把默认值 70707 作为"万能兜底"塞进来 —— 那等于给所有人一把钥匙,
     *    会削弱隐身本身的意义。只有两边都**没配**时才用默认值(全新安装的正常情况)。
     */
    fun acceptedCodes(context: Context? = null): Set<String> {
        val codes = LinkedHashSet<String>()
        read()?.let { codes.add(it) }
        runCatching { SettingsRepositoryImpl().stealthCode }.getOrNull()
            ?.let { sanitize(it) }?.let { codes.add(it) }
        return if (codes.isEmpty()) setOf(DEFAULT_CODE) else codes
    }

    /**
     * 自愈/迁移(App 启动、打开设置页时各调一次,跑在 IO 线程):
     *   · /data/adb 有、App 设置没有 → 用 /data/adb 的覆盖 App 设置(卸载重装后的正路)
     *   · App 设置有、/data/adb 没有 → 把 App 设置写进 /data/adb(老用户补备份,以后卸载也不怕)
     * @return 是否需要界面刷新
     */
    fun sync(): Boolean {
        val repo = runCatching { SettingsRepositoryImpl() }.getOrNull() ?: return false
        val pref = sanitize(repo.stealthCode)
        val disk = read()
        return when {
            disk == null && pref != null -> {
                write(pref)
                false
            }

            disk != null && disk != pref -> {
                repo.stealthCode = disk
                true
            }

            else -> false
        }
    }
}
