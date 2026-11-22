# Native Terminal Components

Atom packages its terminal helper programs as standalone ELF executables under `jniLibs`. The files use a `.so` suffix so the Android installer extracts them to the read-only `nativeLibraryDir`; they are not loaded through JNI, and Android native helper programs are not executed from a writable directory.

| Program | ABI | Purpose |
| --- | --- | --- |
| `libeta_pty.so` | arm64-v8a | Creates a real pseudo-terminal for regular Android Shell and Linux sessions |
| `libproot_exec.so` | arm64-v8a | Runs a Linux userspace under the app UID |
| `libproot_loader.so` | arm64-v8a | A same-ABI loader produced by the same build as PRoot |

The APK supports ARM64 only. `ndk.abiFilters` in `app/build.gradle.kts` restricts both local and dependency-AAR native libraries, so Debug and Release both package only `arm64-v8a`.

PRoot only supports a 64-bit Linux rootfs matching the device; it does not execute heterogeneous instruction sets and does not provide device root permissions. PRoot statically links talloc and libandroid-shmem, and at runtime depends only on Android system libraries. The built ELF uses 16 KiB page alignment.

## Runtime Interface

The PTY arguments are `rows cols -- executable args...`; both root and regular-identity console sessions are set up as independent sessions by it. Standard output carries raw terminal bytes; standard input is transferred in frames with a 1-byte type and a 2-byte big-endian length header: type 0 is terminal bytes, written verbatim to the pseudo-terminal; type 1 is a window-size payload of big-endian rows and cols (2 bytes each, values 1 to 1000), after which the kernel sends SIGWINCH to the foreground process group; unknown types are skipped by length. Ctrl-C is sent as a terminal byte through the PTY to the current foreground process group; closing the input pipe sends a terminal EOF; the terminal process group is cleaned up on window close, parent exit, or output-pipe disconnect. The exit code preserves the child process status.

PRoot callers must set `PROOT_LOADER` to the current `nativeLibraryDir/libproot_loader.so` and `PROOT_TMP_DIR` to a private temporary directory writable by Atom. A missing loader will not fall back to another app's installation path. The shared-memory patch also uses the same temporary directory and does not rely on a global `/tmp`.

## Rebuilding

Building requires an Android NDK matching the `ndk` entry in `gradle/libs.versions.toml`, plus Python 3, GNU make, patch, and tar, run on macOS or Linux:

```sh
ANDROID_NDK_HOME=/path/to/android-ndk scripts/build-terminal-native.sh
```

The script first verifies the NDK's `source.properties` and version directory, then uses the pinned source archive shipped alongside the APK and checks its SHA-256; it only downloads from the upstream pinned-version URL when the source is missing. Third-party source versions, URLs, digests, and compile flags are authoritative from the script. `ETA_NATIVE_SOURCES` and `ETA_NATIVE_BUILD` can specify the source cache and temporary build directory respectively. Build logs are kept in the build directory and do not contain model configuration or user files.

The script only produces ARM64 ELFs, and automatically packs the actual script, version directory, PTY source, and patches into `app/src/main/assets/native-sources/eta-native-build.tgz`; that archive should not be edited by hand. The APK's `assets/native-sources` also contains the original sources of PRoot, talloc, and libandroid-shmem; source resources use a `.tgz` suffix to avoid Android resource packaging auto-extracting `.gz` files and changing names and checksums. `assets/licenses` contains the respective licenses. Extract the build archive to restore repository-relative paths, then pass the directory containing the original sources to `ETA_NATIVE_SOURCES` to rebuild the bundled components.

The source archive and the regenerated binaries need to be updated together. The relevant third-party programs and patches continue to apply their own open-source licenses; see [Third-Party Notices](THIRD_PARTY_NOTICES.md).
