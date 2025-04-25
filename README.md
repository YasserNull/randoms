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

| ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/kali_linux.png)<br>**Kali**<br>`kali` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/parrot_security.png)<br>**Parrot**<br>`parrot` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/alpine_linux.png)<br>**Alpine**<br>`alpine` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/arch_linux.png)<br>**Arch**<br>`archlinux` |
|---|---|---|---|
| ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/backbox_linux.png)<br>**BackBox**<br>`backbox` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/centos.png)<br>**CentOS**<br>`centos` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/chimera_linux.png)<br>**Chimera**<br>`chimera` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/debian.png)<br>**Debian**<br>`debian` |
| ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/deepin.png)<br>**Deepin**<br>`deepin` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/fedora.png)<br>**Fedora**<br>`fedora` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/manjaro.png)<br>**Manjaro**<br>`manjaro` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/openkylin.png)<br>**OpenKylin**<br>`openkylin` |
| ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/opensuse.png)<br>**OpenSUSE**<br>`opensuse` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/pardus.png)<br>**Pardus**<br>`pardus` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/rocky_linux.png)<br>**Rocky**<br>`rocky` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/ubuntu.png)<br>**Ubuntu**<br>`ubuntu` |
| ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/gentoo.png)<br>**Gentoo**<br>`gentoo` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/artix_linux.png)<br>**Artix**<br>`artix` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/adelie_linux.png)<br>**Adélie**<br>`adelie` | ![](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/void_linux.png)<br>**Void**<br>`void` |