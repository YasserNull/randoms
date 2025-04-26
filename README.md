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

## Android Paths

### System Points
```
/data/local/chroot-distro/<distro>
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
*Note*: Use lowercase identifiers for it to be properly identified.
| <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/ubuntu.png" width="50"><br> Ubuntu | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/arch_linux.png" width="50"><br>    Arch    | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/fedora.png" width="50"><br>  Fedora   | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/debian.png" width="50"><br>  Debian  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/centos.png" width="50"><br>  CentOS  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/rocky_linux.png" width="50"><br>  Rocky | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/centos.png" width="50"><br> CentOS Stream  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/manjaro.png" width="50"><br>  Manjaro   | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/kali_linux.png" width="50"><br>   Kali    | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/parrot_security.png" width="50"><br>  Parrot  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/alpine_linux.png" width="50"><br>  Alpine  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/opensuse.png" width="50"><br> OpenSUSE  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/deepin.png" width="50"><br>  Deepin  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/backbox_linux.png" width="50"><br> BackBox | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/chimera_linux.png" width="50"><br> Chimera  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/openkylin.png" width="50"><br> OpenKylin | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/gentoo.png" width="50"><br>  Gentoo  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/artix_linux.png" width="50"><br>  Artix  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/adelie_linux.png" width="50"><br>  Adélie  | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/void_linux.png" width="50"><br>   Void  |
|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|:------:|
| `ubuntu`| `arch`  | `fedora`| `debian`| `centos`| `rocky` | `centos_stream`  | `manjaro`| `kali`  | `parrot`| `alpine`| `opensuse` | `deepin`| `backbox`  | `chimera`  | `openkylin`| `gentoo`| `artix` | `adelie`| `void`  |
## Commands

### Basic Commands
```
chroot-distro help                 # Display all commands and usage
chroot-distro env                  # Show environment details
chroot-distro list                 # List available distributions
chroot-distro download <distro>    # Download a distribution
chroot-distro redownload <distro>  # Refresh an existing distribution
chroot-distro delete <distro>      # Remove a distribution
```

### Installation Commands
```
chroot-distro install <distro>              # Install a distribution
chroot-distro reinstall <distro>            # Reinstall a distribution
chroot-distro reinstall --force <distro>    # Force reinstall
chroot-distro uninstall <distro>            # Uninstall a distribution
chroot-distro uninstall --force <distro>    # Force uninstall
```

### Backup and Restore
```
chroot-distro backup <distro> [path]           # Create a backup
chroot-distro unbackup <distro>                # Remove a backup
chroot-distro restore <distro> [path]          # Restore from a backup
chroot-distro restore --default <distro>       # Restore to default settings
```
**Notes:**
- Use `--default` or `-d` to restore the original installation settings.
- Specify a custom `[path]` for backup/restore operations.
- For older backups, use `--force` cautiously to avoid issues like system mount conflicts or storage limitations.

### Unmount Commands
```
chroot-distro unmount <distro>                 # Unmount system points
chroot-distro unmount --force --all <distro>   # Force unmount all mounts
```
**Notes:**
- Use `--force` to close processes accessing system points.
- Use `--all` to unmount system, normal, and loopback mounts.

### Execute Commands
```
chroot-distro command <distro> "command"       # Run a command in the distribution
chroot-distro login <distro>                   # Log in to the distribution
```
**Notes:**
- Enclose commands in quotes for the `command` operation.
- The `command` operation executes and returns to the host system.

### Example Usage
```
chroot-distro download ubuntu
chroot-distro install ubuntu
chroot-distro login ubuntu
chroot-distro command debian "sudo -i -u root"
chroot-distro backup ubuntu /sdcard/backup
```
*Replace `<distro>` with the desired distribution identifier.*

## Installation
1. Download the [latest release](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/releases/latest) from the table below.
2. Install via a module manager (e.g., Magisk) or flash through a custom recovery.

| Version | v1.3.0 | v1.4.0 | v1.5.0 | Latest |
|---------|--------|--------|--------|--------|
| Release | [Download](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/releases/download/v1.3.0/chroot-distro.zip) | [Download](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/releases/download/v1.4.0/chroot-distro.zip) | [Download](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/releases/download/v1.5.0/chroot-distro.zip) | [Download](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/releases/latest/download/chroot-distro.zip) |

## Screenshot Examples
Kali Linux 
![Kali Linux console](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/screenshot/kali-linux.png) 
Debian 
![Debian  console](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/screenshot/debian.png)

