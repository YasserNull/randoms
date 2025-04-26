# chroot-distro
<p align="center">
  <img src="https://img.shields.io/github/downloads/Magisk-Modules-Alt-Repo/chroot-distro/total?label=Downloads"/>
  <img src="https://img.shields.io/github/v/release/Magisk-Modules-Alt-Repo/chroot-distro?include_prereleases&label=Latest"/>
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

You can use `chroot-distro` on any terminal, for example: MiXplorer, MT Manager, Termux, TWRP and Android Terminal Emulator (ADB Shell).

## Usage Warning
### Notice
chroot-distro requires root access to function. While running as root:
* There's a small possibility of unintended file deletion
* System files could be accidentally modified
* Corner cases might exist despite thorough testing

### Careful
Before running chroot-distro:
* Always backup your important files
* Always backup your system partitions

### Remember
This warning applies to all root-level operations, not just chroot-distro.

As they say: ***With great power comes great responsibility.***
## Android Paths

### System Points
```
/chroot-distro/<distro>
├── /dev
├── /sys
├── /proc
├── /dev/pts
├── /sdcard
└── /storage
```

### Optional Mounts

`chroot-distro android-bind <enable|disable>`: 

Binding all Android root directories not mounted by default for full environment access. 

## Supported Distributions
| <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/ubuntu.png" width="50"><br> Ubuntu | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/arch_linux.png" width="50"><br>    Arch    | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/fedora.png" width="50"><br>  Fedora   | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/debian.png" width="50"><br>  Debian  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/centos.png" width="50"><br>  CentOS  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/rocky_linux.png" width="50"><br>  Rocky | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/centos.png" width="50"><br> CentOS Stream  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/manjaro.png" width="50"><br>  Manjaro   | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/kali_linux.png" width="50"><br>   Kali    | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/parrot_security.png" width="50"><br>  Parrot  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/alpine_linux.png" width="50"><br>  Alpine  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/opensuse.png" width="50"><br> OpenSUSE  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/deepin.png" width="50"><br>  Deepin  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/backbox_linux.png" width="50"><br> BackBox | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/chimera_linux.png" width="50"><br> Chimera  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/openkylin.png" width="50"><br> OpenKylin | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/gentoo.png" width="50"><br>  Gentoo  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/artix_linux.png" width="50"><br>  Artix  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/adelie_linux.png" width="50"><br>  Adélie  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/void_linux.png" width="50"><br>   Void  |
|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|
| `ubuntu`| `arch`  | `fedora`| `debian`| `centos`| `rocky` | `centos_stream`  | `manjaro`| `kali`  | `parrot`| `alpine`| `opensuse` | `deepin`| `backbox`  | `chimera`  | `openkylin`| `gentoo`| `artix` | `adelie`| `void`  |