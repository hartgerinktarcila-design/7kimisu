# 7kimisu

**KernelSU 的第三方修改版**(非官方分支),管理器界面做了较多二次改造。

## ✨ 完全免费、开源

- 本项目 **完全免费** —— 没有付费版、没有会员、没有卡密、没有激活码,也不卖任何东西。
- **如果你是花钱买来的,那你就是被骗了。** 请找卖家退款、举报。
- 遵循上游 KernelSU 的开源协议,不附加任何额外限制:
  - `kernel/` 及其子目录:**GPL-2.0-only**
  - 其它全部(`manager/` `userspace/` `uapi/` `js/` 等):**GPL-3.0-or-later**
  - 协议原文:`LICENSE`(GPL-3.0)、`kernel/LICENSE`(GPL-2.0)

## 📥 下载

- APK 与各版本对应的源码包:见发布频道(管理器「关于」页内有入口)
- 源码同时在本仓库维护,每个发布版本会打 tag

## 🔧 相对上游的主要改动

**管理器(Android App)**
- 个性化:背景壁纸(含内置默认壁纸)、状态卡图片、界面扁平化、背景透明度
- 隐身模式:界面伪装成「未安装」(内核侧开关,状态持久化在 `/data/adb/sevenk/stealth`)
- 特效:下雪、巨魔雨(含重力感应/跟随倾斜)
- 外观:字体与字重、主题调色板预设、导航图标自定义、开屏公告

**内核侧**
- 隐身模式的 GET_INFO 行为(不上报 MANAGER 标志)
- 信任机制调整:内核编译期写死签名,只信任本管理器

## 🛠 构建

```bash
# 1) 管理器 APK(需要 Android SDK + JDK)
cd manager
./gradlew :app:assembleRelease -PPREBUILT_NATIVE=1 -PKSU_NAME=7kimisu \
  -PKSU_VERSION_CODE=32649 -PKSU_VERSION_NAME=7kimisu-vX.Y.Z \
  -PKEYSTORE_FILE=<签名库> -PKEYSTORE_PASSWORD=*** -PKEY_ALIAS=*** -PKEY_PASSWORD=***

# 2) ksud(需要 Android NDK)
cd userspace/ksud && cargo build --target aarch64-linux-android --release -p ksud

# 3) 内核模块:见 kernel/,按目标机型 KMI 编译

# 附:打一份"与 APK 对应"的源码包(开源协议要求)
bash scripts/pack-source.sh vX.Y.Z
```

## 🙏 上游与致谢

本项目基于 [**KernelSU**](https://github.com/tiann/KernelSU) 二次开发。
感谢作者 **weishu(tiann)** 与所有社区贡献者把项目开源 —— 没有上游,就没有 7kimisu。

## ⚠️ 免责

刷机有风险,操作前请先备份;因使用本软件造成的任何后果请自负。
别拿它去做坏事;看到倒卖的,顺手举报一下就好。
