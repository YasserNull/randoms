# chroot-distro
<p align="center">
  <img src="https://img.shields.io/github/downloads/Magisk-Modules-Alt-Repo/chroot-distro/total?label=Downloads"/>
  <img src="https://img.shields.io/github/v/release/Magisk-Modules-Alt-Repo/chroot-distro?include_prereleases&label=Latest%20Release"/>
  <img src="https://img.shields.io/badge/License-GPLv3-blue.svg"/>
</p>

![](https://github.com/YasserNull/test/blob/main/20250425_092417.jpg)

***chroot-distro***: Installs GNU/Linux distributions in a chroot environment on Android.  
> The idea is inspired by [proot-distro](https://github.com/termux/proot-distro).

+ Directory Structure
```
/data/local/chroot-distro/
├── .backup/         # Backup folder for distributions
├── .rootfs/         # Minimal root filesystem for bootstrapping distributions
├── <distro>/        # Folder for installed distributions (e.g., ubuntu, debian)
├── <distro>/        # Another distribution folder
├── android_bind     # Check file to bind Android environment with chroot
├── suid             # Check file to auto-fix SUID issue
└── ram              # Check file for RAM disk setup to optimize performance
```
System paths mount automatically, and the environment supports GUI applications through VNC or X11 forwarding.
___

You can use `chroot-distro` on any terminal, for example: MiXplorer, MT Manager, Termux, TWRP and Android Terminal Emulator (ADB Shell).

