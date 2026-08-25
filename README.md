# mcef-android-fcl-port

> Experimental port of MCEF & WebDisplays for FoldCraft Launcher Android arm64-v8a,
> experimental branch only, no stable guarantee.

## 项目限制

本项目受以下 Android 平台硬限制，**不保证可编译成功或正常运行**：

1. **Android 10+ W^X 安全策略**：SELinux 和动态链接器禁止从应用缓存/文件目录
   `dlopen()` native 库。libcef.so 必须内置进 FCL APK 的 jniLibs 目录，
   仅靠独立 Fabric 模组无法完成。
2. **FCL 内部 Java 环境限制**：Minecraft JVM 线程不直接持有 Android
   Activity/Context/Surface，CEF 初始化可能因缺少系统组件而失败。
3. **GitHub runner 内存限制**：Chromium 源码编译需要 16GB+ RAM、100GB+ 磁盘，
   免费 GitHub Actions runner (16GB RAM / 14GB SSD) 大概率 OOM，
   需要付费 larger runner 或自建编译环境。
4. **官方 CEF 不支持 Android**：CEF 官方无 Android 构建，需从 Chromium for
   Android 源码自行编译，API 与桌面 CEF 不兼容。

## 上游依赖开源项目

- [MCEF](https://github.com/CinemaMod/mcef) (LGPL-2.1) — Java 模组 + JNI 绑定
- [WebDisplays](https://github.com/CinemaMod/webdisplays) — Minecraft 浏览器屏幕方块
- [FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher) — Android Minecraft Java 启动器

## 项目结构

```
mcef-android-fcl-port/
├── README.md                     # 本文件
├── LICENSE                       # LGPL-2.1
├── .github/workflows/            # CI 工作流脚本
├── mcef-android/                 # 魔改 MCEF 模组源码 (Fork of CinemaMod/mcef)
├── fcl-patches/                  # FCL 启动器源码补丁 (Fork of FCL-Team/FoldCraftLauncher)
├── webdisplays-android/          # WebDisplays 适配 (Fork of CinemaMod/webdisplays)
├── proxy-web-mod/                # 代理式轻量替代模组（不依赖 CEF native 库）
└── docs/                         # 架构文档
    └── architecture.html         # 完整移植架构方案
```

## 分支说明

- `main` — 不使用 main 作为实验分支
- `feature/dev-cef-android` — 所有实验性修改放在此分支

## 许可证

LGPL-2.1-or-later（沿用上游 MCEF 许可证）

## 重要开源规则

1. 遵守 MCEF 的 LGPL-2.1 许可证；修改后的源码必须公开
2. 不直接修改各上游仓库 main 主分支；所有修改放在独立 Fork 的 feature 分支
3. 不使用预编译好的 libcef.so；Android-CEF 必须由 CI 从源码编译
4. 禁止自动发布 Release；仅生成源码、CI 脚本，人工确认后再发布
