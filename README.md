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

<div style="overflow-x: auto; white-space: nowrap;">

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/adelie_linux.png" width="48"><br><strong>Adélie</strong><br><code>adelie</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/alpine_linux.png" width="48"><br><strong>Alpine</strong><br><code>alpine</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/arch_linux.png" width="48"><br><strong>Arch</strong><br><code>archlinux</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/artix_linux.png" width="48"><br><strong>Artix</strong><br><code>artix</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/backbox_linux.png" width="48"><br><strong>BackBox</strong><br><code>backbox</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/centos.png" width="48"><br><strong>CentOS</strong><br><code>centos</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/chimera_linux.png" width="48"><br><strong>Chimera</strong><br><code>chimera</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/debian.png" width="48"><br><strong>Debian</strong><br><code>debian</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/deepin.png" width="48"><br><strong>Deepin</strong><br><code>deepin</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/fedora.png" width="48"><br><strong>Fedora</strong><br><code>fedora</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/gentoo.png" width="48"><br><strong>Gentoo</strong><br><code>gentoo</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/kali_linux.png" width="48"><br><strong>Kali</strong><br><code>kali</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/manjaro.png" width="48"><br><strong>Manjaro</strong><br><code>manjaro</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/openkylin.png" width="48"><br><strong>OpenKylin</strong><br><code>openkylin</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/opensuse.png" width="48"><br><strong>OpenSUSE</strong><br><code>opensuse</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/pardus.png" width="48"><br><strong>Pardus</strong><br><code>pardus</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/parrot_security.png" width="48"><br><strong>Parrot</strong><br><code>parrot</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/rocky_linux.png" width="48"><br><strong>Rocky</strong><br><code>rocky</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/ubuntu.png" width="48"><br><strong>Ubuntu</strong><br><code>ubuntu</code>
</div>

<div style="display: inline-block; text-align: center; margin: 10px;">
<img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/void_linux.png" width="48"><br><strong>Void</strong><br><code>void</code>
</div>

</div>