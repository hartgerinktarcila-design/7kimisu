package com.sevenk.core.security

import android.content.Context
import com.topjohnwu.superuser.ShellUtils
import com.sevenk.core.data.SettingsRepository
import com.sevenk.core.ui.util.getRootShell

/**
 * 隐身密令的「跨卸载备份」。
 *
 * 为什么需要它(这是个真会把人锁死的 bug):
 *   · 隐身状态存在**内核侧** `/data/adb/sevenk/stealth` —— 卸载 App 也不会丢;
 *   · 而密令数字以前只存在 **App 自己的 shared_prefs** 里 —— 卸载就没了。
 *   于是:改了自定义密令 → 卸载重装 → 内核仍处于隐身,而 App 里的"期望密令"
 *   已经退回默认 70707 → 接收器**静默忽略** → 恢复不回来,彻底锁死。
 *
 * 所以这里把密令额外写一份到 `/data/adb/sevenk/stealth_code`(root,卸载不丢),
 * 读取时**优先用它**,拿不到才退回 App 设置,再退回默认值。
 *
 * （自旧版原样移植，仅改包名与 Settings 仓库引用。）
 */
object StealthCodeStore {

    private const val TAG = "7kkernel-stealth"

    private const val DIR = "/data/adb/sevenk"
    private const val PATH = "$DIR/stealth_code"

    /**
     * 改名迁移的**只读兜底**：旧目录 `/data/adb/ksu` 下的那份密令。
     * 现在 `/data/adb/ksu` 故意保留一条指向 `/data/adb/sevenk` 的兼容软链
     * （权威实现在 ksud `migrate.rs`），这一次只读 `cat` 走的就是那条软链；
     * 它保留的意义是"软链还没补上 / 被旧管理器顶掉"那几秒里的兜底（读到就补写到新路径，自愈）。
     */
    private const val LEGACY_PATH = "/data/adb/ksu/stealth_code"

    /** 默认密令(和 StealthReceiver 保持一致) */
    const val DEFAULT_CODE = "70707"

    /**
     * KernelSU 自己那些文件的 SELinux 类型(和 ksud 的 `restorecon.rs` 里 `KSU_CON` 同一个值)。
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
     * 做法(**先写临时文件、验过了才顶替真文件**):
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
        // 🔒 只记成败，不记 shell 原文。
        android.util.Log.i(TAG, "密令落盘核验:${if (out.contains("7K_OK")) "OK" else "FAIL"}")
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
        val pref = runCatching { SettingsRepository().stealthCode }.getOrNull()
        return sanitize(pref) ?: DEFAULT_CODE
    }

    /**
     * **可接受的密令集合(取并集)** —— 接收器比对用这个。
     *
     * 取并集之后,磁盘和设置里的**任意一个**都能开锁,不会再因为两份不一致而锁死。
     *
     * ⚠️ 有意**不**把默认值 70707 作为"万能兜底"塞进来 —— 那等于给所有人一把钥匙。
     *    只有两边都**没配**时才用默认值(全新安装的正常情况)。
     */
    fun acceptedCodes(context: Context? = null): Set<String> {
        val codes = LinkedHashSet<String>()
        read()?.let { codes.add(it) }
        runCatching { SettingsRepository().stealthCode }.getOrNull()
            ?.let { sanitize(it) }?.let { codes.add(it) }
        return if (codes.isEmpty()) setOf(DEFAULT_CODE) else codes
    }

    /**
     * 自愈/迁移(App 打开设置页时调一次,跑在 IO 线程):
     *   · /data/adb 有、App 设置没有 → 用 /data/adb 的覆盖 App 设置(卸载重装后的正路)
     *   · App 设置有、/data/adb 没有 → 把 App 设置写进 /data/adb(老用户补备份)
     * @return 是否需要界面刷新
     */
    fun sync(): Boolean {
        val repo = runCatching { SettingsRepository() }.getOrNull() ?: return false
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
