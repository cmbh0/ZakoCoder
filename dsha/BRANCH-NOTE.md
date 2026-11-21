# 这个目录是什么

`dsha/` 是 **DSHA 2.4.1** 的完整源码，放在 ZakoCoder 的一个分支里，用于实现对照。

DSHA 是一个 Android 端的 AI 对话客户端（Jetpack Compose，MIT）。它的本机 Ubuntu 运行环境
参考了 ZakoCoder 的做法：`--link2symlink` 与 `PROOT_L2S_DIR` 的配合、独立 loader 的路径处理、
安装后的自检流程，这几处都照着这个项目的落地方式写的。

- 上游仓库：<https://github.com/cmbh0/DshAengt>
- 版本：2.4.1（versionCode 83）
- 许可证：MIT，见 [`LICENSE`](LICENSE)

这个分支**不打算合并进主干**。它的用途是让两边的 proot 实现可以直接 diff；
真正的贡献会拆成单个补丁单独提交。

## 可能对上游有价值的部分

| 主题 | 位置 |
|---|---|
| proot 三套构建并存（termux / bionic / static），按可靠性依次探测 | `app/src/main/java/com/simplechat/app/agent/UbuntuEnv.kt` |
| 免 apt 的 Python 安装（python-build-standalone + 软链） | `app/src/main/java/com/simplechat/app/agent/PythonBundle.kt` |
| apt 读 `dpkg/status` 报 ENOSYS 时的环境整理 | `UbuntuEnv.prepareApt` |

## 已知问题

部分设备上 proot 的 `chdir` 返回 `ENOSYS`：进入环境后 `cd` 失败，命令实际停在 `/root`。
当前的探活只跑 `printf`（一次 `write`），测不出这个问题 —— 这也是把它单独列出来的原因。
