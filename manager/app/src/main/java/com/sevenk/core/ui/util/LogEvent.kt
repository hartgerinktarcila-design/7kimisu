package com.sevenk.core.ui.util

import android.content.Context
import android.os.Build
import android.system.Os
import com.topjohnwu.superuser.ShellUtils
import com.sevenk.core.Natives
import com.sevenk.core.ui.screen.home.getManagerVersion
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun getBugreportFile(context: Context): File {

    val bugreportDir = File(context.cacheDir, "bugreport")
    bugreportDir.mkdirs()

    val processFile = File(bugreportDir, "process.txt")
    val dmesgFile = File(bugreportDir, "dmesg.txt")
    val logcatFile = File(bugreportDir, "logcat.txt")
    val tombstonesFile = File(bugreportDir, "tombstones.tar.gz")
    val dropboxFile = File(bugreportDir, "dropbox.tar.gz")
    val pstoreFile = File(bugreportDir, "pstore.tar.gz")
    // Xiaomi/Readmi devices have diag in /data/vendor/diag
    val diagFile = File(bugreportDir, "diag.tar.gz")
    val oplusFile = File(bugreportDir, "oplus.tar.gz")
    val bootlogFile = File(bugreportDir, "bootlog.tar.gz")
    val mountsFile = File(bugreportDir, "mounts.txt")
    val fileSystemsFile = File(bugreportDir, "filesystems.txt")
    val adbFileTree = File(bugreportDir, "adb_tree.txt")
    val adbFileDetails = File(bugreportDir, "adb_details.txt")
    val ksuFileSize = File(bugreportDir, "ksu_size.txt")
    val appListFile = File(bugreportDir, "packages.txt")
    val propFile = File(bugreportDir, "props.txt")
    val allowListFile = File(bugreportDir, "allowlist.bin")
    val procModules = File(bugreportDir, "proc_modules.txt")
    val bootConfig = File(bugreportDir, "boot_config.txt")
    val kernelConfig = File(bugreportDir, "defconfig.gz")
    val kallsyms = File(bugreportDir, "kallsyms.txt")

    // root shell 起不来（没 root / shell 初始化失败）时不能崩：拿不到就退化成 null，后面整段跳过。
    val shell = runCatching { getRootShell(true) }.getOrNull()

    // ⚠️ 这些 exec()/fastCmd 都会抛异常（设备上没有 toybox/busybox、路径不存在、shell 被杀…）。
    //    整个函数是在协程（SendLogDialog 的 scope.launch）里跑的，异常不捕获 = 直接闪退；
    //    所以整段兜底：能收多少证据就收多少，缺几份文件不影响导出。
    runCatching {
        shell?.let { sh ->
            // busybox ps has very few features for embed devices
            sh.newJob().add("toybox ps -T -A -w -o PID,TID,UID,COMM,CMDLINE,CMD,LABEL,STAT,WCHAN > ${processFile.absolutePath}").exec()
            sh.newJob().add("dmesg -r > ${dmesgFile.absolutePath}").exec()
            sh.newJob().add("logcat -b all -v uid -d > ${logcatFile.absolutePath}").exec()
            sh.newJob().add("tar -czf ${tombstonesFile.absolutePath} -C /data/tombstones .").exec()
            sh.newJob().add("tar -czf ${dropboxFile.absolutePath} -C /data/system/dropbox .").exec()
            sh.newJob().add("tar -czf ${pstoreFile.absolutePath} -C /sys/fs/pstore .").exec()
            sh.newJob().add("tar -czf ${diagFile.absolutePath} -C /data/vendor/diag . --exclude=./minidump.gz").exec()
            sh.newJob().add("tar -czf ${oplusFile.absolutePath} -C /mnt/oplus/op2/media/log/boot_log/ .").exec()
            sh.newJob().add("tar -czf ${bootlogFile.absolutePath} -C /data/adb/sevenk/log .").exec()

            sh.newJob().add("cat /proc/1/mountinfo > ${mountsFile.absolutePath}").exec()
            sh.newJob().add("cat /proc/filesystems > ${fileSystemsFile.absolutePath}").exec()
            sh.newJob().add("busybox tree /data/adb > ${adbFileTree.absolutePath}").exec()
            sh.newJob().add("ls -alRZ /data/adb > ${adbFileDetails.absolutePath}").exec()
            sh.newJob().add("du -sh /data/adb/sevenk/* > ${ksuFileSize.absolutePath}").exec()
            sh.newJob().add("cp /data/system/packages.list ${appListFile.absolutePath}").exec()
            sh.newJob().add("getprop > ${propFile.absolutePath}").exec()
            sh.newJob().add("cp /data/adb/sevenk/.allowlist ${allowListFile.absolutePath}").exec()
            sh.newJob().add("cp /proc/modules ${procModules.absolutePath}").exec()
            sh.newJob().add("cp /proc/bootconfig ${bootConfig.absolutePath}").exec()
            sh.newJob().add("cp /proc/config.gz ${kernelConfig.absolutePath}").exec()
            sh.newJob()
                .add($$"ORIG=$(cat /proc/sys/kernel/kptr_restrict); echo 1 > /proc/sys/kernel/kptr_restrict; cat /proc/kallsyms > $${kallsyms.absolutePath}; echo $ORIG > /proc/sys/kernel/kptr_restrict")
                .exec()
        }
    }.onFailure { android.util.Log.w("LogEvent", "收集部分日志失败", it) }

    val selinux = runCatching { shell?.let { ShellUtils.fastCmd(it, "getenforce") } }.getOrNull().orEmpty()

    // basic information
    val buildInfo = File(bugreportDir, "basic.txt")
    // 写文件同样会抛 IO 异常
    runCatching {
        PrintWriter(FileWriter(buildInfo)).use { pw ->
            pw.println("Kernel: ${System.getProperty("os.version")}")
            pw.println("BRAND: " + Build.BRAND)
            pw.println("MODEL: " + Build.MODEL)
            pw.println("PRODUCT: " + Build.PRODUCT)
            pw.println("MANUFACTURER: " + Build.MANUFACTURER)
            pw.println("SDK: " + Build.VERSION.SDK_INT)
            pw.println("PREVIEW_SDK: " + Build.VERSION.PREVIEW_SDK_INT)
            pw.println("FINGERPRINT: " + Build.FINGERPRINT)
            pw.println("DEVICE: " + Build.DEVICE)
            pw.println("Manager: " + getManagerVersion(context))
            pw.println("SELinux: $selinux")

            val uname = Os.uname()
            pw.println("KernelRelease: ${uname.release}")
            pw.println("KernelVersion: ${uname.version}")
            pw.println("Machine: ${uname.machine}")
            pw.println("Nodename: ${uname.nodename}")
            pw.println("Sysname: ${uname.sysname}")

            val ksuKernel = Natives.version
            pw.println("KernelSU: $ksuKernel")
            val safeMode = Natives.isSafeMode
            pw.println("SafeMode: $safeMode")
            val lkmMode = Natives.isLkmMode
            pw.println("LKM: $lkmMode")
        }
    }.onFailure { android.util.Log.w("LogEvent", "写 basic.txt 失败", it) }

    // modules（listModules() 解析/IO 失败时留空文件即可，不能让导出整体崩）
    val modulesFile = File(bugreportDir, "modules.json")
    runCatching { modulesFile.writeText(listModules()) }

    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm")
    val current = LocalDateTime.now().format(formatter)

    val targetFile = File(context.cacheDir, "KernelSU_bugreport_${current}.tar.gz")

    runCatching {
        shell?.newJob()?.add("tar czf ${targetFile.absolutePath} -C ${bugreportDir.absolutePath} .")?.exec()
        shell?.newJob()?.add("rm -rf ${bugreportDir.absolutePath}")?.exec()
        shell?.newJob()?.add("chmod 0644 ${targetFile.absolutePath}")?.exec()
    }.onFailure { android.util.Log.w("LogEvent", "打包 bugreport 失败", it) }

    return targetFile
}

