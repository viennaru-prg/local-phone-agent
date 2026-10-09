"""Builds a link-time stub libOpenCL.so for arm64 Android.

The APK does not ship this file. At runtime Android resolves libOpenCL.so to the phone's own
vendor OpenCL driver (declared with <uses-native-library> in the manifest).
"""
import pathlib
import re
import subprocess
import sys
import platform

root = pathlib.Path(__file__).resolve().parent.parent
headers = root / "third_party" / "OpenCL-Headers" / "CL"
out_dir = root / "third_party" / "opencl-stub" / "arm64-v8a"
ndk = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else r"E:\AI\Apps\android-sdk\ndk\28.2.13676358")

names = set()
for header in ["cl.h", "cl_gl.h", "cl_ext.h"]:
    text = (headers / header).read_text(encoding="utf-8")
    names.update(re.findall(r"CL_API_CALL\s+(cl\w+)\s*\(", text))
names = sorted(n for n in names if not n.endswith("_fn"))

out_dir.mkdir(parents=True, exist_ok=True)
source = out_dir / "stub.c"
source.write_text("".join(f"void {n}(void) {{}}\n" for n in names), encoding="utf-8")
host = 'windows-x86_64' if platform.system() == 'Windows' else ('darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64')
clang = ndk / "toolchains" / "llvm" / "prebuilt" / host / "bin" / ('clang.exe' if platform.system() == 'Windows' else 'clang')
subprocess.run([str(clang), "--target=aarch64-linux-android30", "-shared", "-fPIC", "-nostdlib",
                "-Wl,-soname,libOpenCL.so", "-o", str(out_dir / "libOpenCL.so"), str(source)], check=True)
print(f"stub with {len(names)} symbols -> {out_dir / 'libOpenCL.so'}")
