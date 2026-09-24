# 为什么这个仓库里的 CI 是停用的

本目录里的工作流文件（以及同级的 `dependabot.yml.disabled`）**来自上游 KernelSU**，
原本用于上游项目的自动构建与检查。

本仓库是 **7kimisu 的源码分发仓库**：只用来公开发布与 `7kimisu-vX.Y.apk` 对应的源码，
**不需要也不运行任何 CI**。因此：

- `.github/workflows/` → 改名为 **`.github/workflows-disabled/`**（GitHub 只会读取 `workflows/`，
  改名后不会再被触发）
- `.github/dependabot.yml` → 改名为 **`.github/dependabot.yml.disabled`**（停止自动依赖更新 PR）

文件内容**原样保留**，方便需要的人参考上游是怎么构建的。

## 想恢复自动构建？

把目录/文件名改回去即可：

```bash
git mv .github/workflows-disabled .github/workflows
git mv .github/dependabot.yml.disabled .github/dependabot.yml
```

⚠️ 注意：恢复后每次 `git push` 都会触发多个构建任务，会消耗 GitHub Actions 配额。
本仓库的定位是「放源码」，建议保持停用。

---

本仓库源码基于 [KernelSU](https://github.com/tiann/KernelSU) 二次开发，遵循与上游一致的
GPL-3.0（`kernel/` 为 GPL-2.0）许可。
