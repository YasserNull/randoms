# chroot-distro

<p align="center">
  <img src="https://img.shields.io/github/downloads/Magisk-Modules-Alt-Repo/chroot-distro/total?label=Downloads"/>
  <img src="https://img.shields.io/github/v/release/Magisk-Modules-Alt-Repo/chroot-distro?include_prereleases&label=Latest"/>
  <img src="https://img.shields.io/badge/License-GPLv3-blue.svg"/>
</p>

**chroot-distro** enables the installation and management of GNU/Linux distributions within a chroot environment on Android devices. Inspired by [proot-distro](https://github.com/termux/proot-distro), it provides a flexible way to run Linux distributions with support for GUI applications via VNC or X11 forwarding.

This tool is compatible with various terminals, including MiXplorer, MT Manager, Termux, TWRP, and Android Terminal Emulator (via ADB Shell).

## Features
- Install and manage multiple Linux distributions in a chroot environment.
- Automatic mounting of Android system paths for seamless integration.
- Support for GUI applications using VNC or X11.
- Backup, restore, and uninstall distributions with ease.
- Optimize performance with RAM disk setup.

## Directory Structure
```
/data/local/chroot-distro/
├── .backup/         # Stores distribution backups
├── .rootfs/         # Minimal root filesystem for bootstrapping
├── <distro>/        # Installed distribution (e.g., ubuntu, debian)
├── android_bind     # File to bind Android environment with chroot
├── suid             # File to auto-fix SUID issues
└── ram              # File for RAM disk setup to optimize performance
```

System paths are automatically mounted, enabling access to Android resources.

## Android Paths

### System Mounts
The following Android paths are mounted by default:
```
/data/local/chroot-distro/<distro>/
├── /dev
├── /sys
├── /proc
├── /dev/pts
├── /sdcard
└── /storage
```

### Optional Mounts
Use the `android-bind` command to bind additional Android root directories:
```
chroot-distro android-bind <enable|disable>
```
This provides full access to the Android environment when needed.

## Supported Distributions
The following distributions are supported, each identified by its unique identifier:

| Distribution | Identifier | Distribution | Identifier | Distribution | Identifier |
|--------------|------------|--------------|------------|--------------|------------|
| <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/ubuntu.png" width="50"><br>Ubuntu | `ubuntu` | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/arch_linux.png" width="50"><br>Arch Linux | `arch` | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/fedora.png" width="50"><br>Fedora | `fedora` |
| <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/debian.png" width="50"><br>Debian | `debian` | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/centos.png" width="50"><br>CentOS | `centos` | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/rocky_linux.png" width="50"><br>Rocky Linux | `rocky` |
| <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/centos.png" width="50"><br>CentOS Stream | `centos_stream` | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/manjaro.png" width="50"><br>Manjaro | `manjaro` | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/kali_linux.png" width="50"><br>Kali Linux | `kali` |
| <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/parrot_security.png" width="50"><br>Parrot | `parrot` | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/alpine_linux.png" width="50"><br>Alpine | `alpine` | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/opensuse.png" width="50"><br>openSUSE | `opensuse` |
| <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/deepin.png" width="50"><br>Deepin | `deepin` | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/backbox_linux.png" width="50"><br>BackBox | `backbox` | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/chimera_linux.png" width="50"><br>Chimera | `chimera` |
| <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/openkylin.png" width="50"><br>OpenKylin | `openkylin` | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/gentoo.png" width="50"><br>Gentoo | `gentoo` | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/artix_linux.png" width="50"><br>Artix | `artix` |
| <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/adelie_linux.png" width="50"><br>Adélie | `adelie` | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/void_linux.png" width="50"><br>Void | `void` | | |

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
1. Download the latest release from the table below.
2. Install via a module manager (e.g., Magisk) or flash through a custom recovery.

| Version | Release |
|---------|---------|
| v1.3.0  | [Download](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/releases/download/v1.3.0/chroot-distro.zip) |
| v1.4.0  | [Download](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/releases/download/v1.4.0/chroot-distro.zip) |
| Latest  | [Download](https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/releases/latest/download/chroot-distro.zip) |