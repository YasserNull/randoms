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

<table><tr>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/kali_linux.png" width="48"><br><b>Kali</b><br><code>kali</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/parrot_security.png" width="48"><br><b>Parrot</b><br><code>parrot</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/alpine_linux.png" width="48"><br><b>Alpine</b><br><code>alpine</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/arch_linux.png" width="48"><br><b>Arch</b><br><code>archlinux</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/backbox_linux.png" width="48"><br><b>BackBox</b><br><code>backbox</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/centos.png" width="48"><br><b>CentOS</b><br><code>centos</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/chimera_linux.png" width="48"><br><b>Chimera</b><br><code>chimera</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/debian.png" width="48"><br><b>Debian</b><br><code>debian</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/deepin.png" width="48"><br><b>Deepin</b><br><code>deepin</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/fedora.png" width="48"><br><b>Fedora</b><br><code>fedora</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/manjaro.png" width="48"><br><b>Manjaro</b><br><code>manjaro</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/openkylin.png" width="48"><br><b>OpenKylin</b><br><code>openkylin</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/opensuse.png" width="48"><br><b>OpenSUSE</b><br><code>opensuse</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/pardus.png" width="48"><br><b>Pardus</b><br><code>pardus</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/rocky_linux.png" width="48"><br><b>Rocky</b><br><code>rocky</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/ubuntu.png" width="48"><br><b>Ubuntu</b><br><code>ubuntu</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/gentoo.png" width="48"><br><b>Gentoo</b><br><code>gentoo</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/artix_linux.png" width="48"><br><b>Artix</b><br><code>artix</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/adelie_linux.png" width="48"><br><b>Adélie</b><br><code>adelie</code></td>
<td align="center"><img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/void_linux.png" width="48"><br><b>Void</b><br><code>void</code></td>
</tr></table>