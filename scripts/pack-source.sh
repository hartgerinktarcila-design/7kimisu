#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────
# 打一份"与所发布 APK 完全对应"的源码包(上游 GPL 协议要求)
#
# 用法:  scripts/pack-source.sh <版本号> [输出目录]
# 例:    scripts/pack-source.sh v0.13.55
#        scripts/pack-source.sh v0.13.55 ~/projects/7kkernel/v0.13.55/源码
#
# 特点:
#   · 用 git 的文件清单打包 → 自动遵守 .gitignore(不会把 build/target 打进去),
#     同时会包含【还没提交】的新文件,所以"当前工作区 = 这一版源码"
#   · 自动附一份《源码说明.txt》(许可、上游、构建方法)
# ─────────────────────────────────────────────────────────────────────
set -euo pipefail

VER="${1:?用法: $0 <版本号,例如 v0.13.55> [输出目录]}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${2:-$HOME/projects/7kkernel/$VER/源码}"
TMP="$(mktemp -d)"
DIR="$TMP/7kimisu-$VER"
mkdir -p "$DIR"

# 1) 复制"源码"文件(遵守 .gitignore)
# ⚠️ 2026-09-23 修: `git ls-files` 读的是**索引**, 已经删掉但还没 `git add` 的文件
#    仍会被列出来 → `tar` 报 "Cannot stat: No such file or directory" 并整体失败。
#    v2.27 正好一次删了 5 个文件(module_registry.rs / RootOwnership.kt / ...)。
#    这里先按"文件确实存在"过滤一遍(删掉的、改名的都不会再进包)。
(cd "$ROOT" && git ls-files -co --exclude-standard \
  | while IFS= read -r f; do if [[ -e "$f" ]]; then printf '%s\n' "$f"; fi; done \
  | tar -cf - -T -) | tar -xf - -C "$DIR"

# 2) 附源码说明
cat > "$DIR/源码说明.txt" <<EOF
7kimisu $VER —— 完整源码
=========================================================

【这是什么】
  7kimisu 是 KernelSU 的第三方修改版(非官方分支)。
  对外发布的是编译好的 APK —— 按上游采用的开源协议要求,这里提供与该 APK
  【完全对应】的完整源码,任何人都可以查看、修改、再分发。

【许可(与上游 KernelSU 一致)】
  · kernel/ 及其子目录:   GPL-2.0-only
  · 其它全部(manager/ userspace/ uapi/ js/ 等): GPL-3.0-or-later
  本项目所做的修改同样以上述协议授权,不附加任何额外限制。
  (LICENSE / kernel/LICENSE 就是完整的协议原文)

【上游】
  KernelSU: https://github.com/tiann/KernelSU
  感谢上游作者 weishu(tiann) 与所有社区贡献者开源。

【对应版本】
  7kimisu-$VER
  与同目录发布的 7kimisu-$VER.apk 一一对应。

【怎么编译】
  1) 管理器 APK(Android SDK + JDK)
       cd manager
       ./gradlew :app:assembleRelease -PPREBUILT_NATIVE=1 -PKSU_NAME=7kimisu \\
         -PKSU_VERSION_NAME=7kimisu-$VER \\
         -PKEYSTORE_FILE=<你的签名库> -PKEYSTORE_PASSWORD=*** -PKEY_ALIAS=*** -PKEY_PASSWORD=***
     注:仓库不含签名库;预编译 native 库(libksud.so 等)属构建产物,请用下面的 3) 自行编译,
        或自行放入 manager/app/src/main/jniLibs/。
  ⚠️ 不要传 -PKSU_VERSION_CODE:versionCode 会按版本名自动推导(0.13.73 -> 130730)。
     这里以前写着钉 32649,结果是每个包的 versionCode 都一样,很多安装器
     当成「已安装相同版本」直接跳过不装 —— 这是真实踩过的坑(用户反馈「新版装不上」)。
  2) 内核模块:kernel/ 目录,按目标机型 KMI 编译。
  3) ksud(Rust,需 Android NDK):
       cd userspace/ksud && cargo build --target aarch64-linux-android --release -p ksud

【大致改了哪些】
  · 管理器界面二改:个性化(壁纸/状态卡图片/扁平化)、隐身模式、下雪与巨魔雨特效、
    重力感应、字体与字重、主题预设、导航图标自定义、开屏公告等。
  · 内核侧:隐身模式标志(/data/adb/sevenk/stealth 及对应 GET_INFO 行为)、
    信任机制调整(编译期写死签名,只信任本管理器)。
  · v2.27(本版)重点:**删掉我们自创的模块系统机械,退回上游 KernelSU 的做法** ——
    删除模块归属标记(.sevenk_owner)、安装完成标记(.install_complete)与登记簿
    (/data/adb/sevenk/module_registry/);升级/安装/卸载/批量标记一律照上游写
    (install_module_to_system / handle_updated_modules / uninstall_module /
    mark_all_modules 与上游逐字一致)。老版本留下的标记由 ksud 开机时一次性清掉。
  · 详见各文件的中文注释。

【免责】
  刷机有风险,请先备份。本项目免费开源;如果你是花钱买来的,你就是被骗了。
EOF

# 3) 打包
mkdir -p "$OUT"
rm -f "$OUT/7kimisu-$VER-src.tar.gz"
tar -czf "$OUT/7kimisu-$VER-src.tar.gz" -C "$TMP" "7kimisu-$VER"
rm -rf "$TMP"
echo "✅ 源码包: $OUT/7kimisu-$VER-src.tar.gz  ($(du -h "$OUT/7kimisu-$VER-src.tar.gz" | cut -f1), $(tar -tzf "$OUT/7kimisu-$VER-src.tar.gz" | wc -l | tr -d ' ') 个条目)"
