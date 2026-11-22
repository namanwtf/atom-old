# Terminal native component patches

`proot-bionic-headers.patch` adds the standard library declarations needed for Android NDK compilation, provided under PRoot's GPL-2.0-or-later license.

`shmem-app-temp.patch` places the temporary files for shared memory keys in the caller-provided `PROOT_TMP_DIR`, validates path lengths, and returns an error when the filesystem refuses creation, provided under libandroid-shmem's BSD-3-Clause license.

Patches are applied only to the build copy. Pinned downloaded sources and SHA-256 checksums are kept in the parent build script; source caches and build artifacts live in `.analysis/`. The build script uses GPL-3.0-or-later and does not change the license of the Eta app or the standalone PTY program.
