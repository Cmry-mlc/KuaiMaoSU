#!/usr/bin/env bash
# 一条命令：把新的 susfs_guard_lkm.ko 打进 KuaiMaoSU APK 并发到 /workspace。
#
#   scripts/release-apk.sh                     # 最常用：android15-6.6 + 自动 +1 版本号
#   scripts/release-apk.sh android15-6.6 4.3.5 40305
#   KO=/path/to/xxx.ko scripts/release-apk.sh android15-6.6
#
# 为什么必须走这条链：ko 不是 APK 里的独立文件，它被 rust-embed（compression）
# 压缩后编进 ksud，ksud 再改名 lib/arm64-v8a/libdove.so 进 APK。
# 只替换裸 ko 或只换 APK，设备上都还是旧模块。
set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
KMI=${1:-android15-6.6}
OUT=/workspace

SDK=${ANDROID_HOME:-/opt/android-sdk}
NDK=${ANDROID_NDK_HOME:-$SDK/ndk/30.0.16248370}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
# 固定用 /opt/jdk21：容器默认的 JAVA_HOME 指向 sdkman 的 17，gradle 会报
# "invalid source release: 21"。不要继承外部 JAVA_HOME。
JAVA=${KUAIMAO_JAVA_HOME:-/opt/jdk21}
export JAVA_HOME=$JAVA ANDROID_HOME=$SDK ANDROID_SDK_ROOT=$SDK ANDROID_NDK_HOME=$NDK
export PATH=$JAVA/bin:$TC:/root/.cargo/bin:$PATH
export RUSTC_BOOTSTRAP=1
export CC_aarch64_linux_android=$TC/aarch64-linux-android26-clang
export CXX_aarch64_linux_android=$TC/aarch64-linux-android26-clang++
export AR_aarch64_linux_android=$TC/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$TC/aarch64-linux-android26-clang

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

# ---- 1. 取 ko -------------------------------------------------------------
KO=${KO:-$REPO/../susfs-out/${KMI}_susfs_guard_lkm.ko}
[ -f "$KO" ] || { echo "找不到 ko: $KO（用 KO=... 指定）" >&2; exit 1; }
KOSHA=$(sha256sum "$KO" | cut -d' ' -f1)
KOSIZE=$(stat -c%s "$KO")
say "ko: $KO  ${KOSIZE} bytes  sha256 ${KOSHA:0:16}…"
# 源码里 vermagic 前面是 \0，不是换行，所以不能按 ^ 匹配行首；也不能走 strings | grep -q
# （grep 提前关管道，pipefail 下会被判成失败）。
VK=$(grep -a -o -m1 'vermagic=[^[:cntrl:]]*' "$KO" || true)
[ -n "$VK" ] || { echo "不是内核模块: $KO" >&2; exit 1; }
echo "$VK"

DEST=$REPO/userspace/ksud/bin/aarch64/${KMI}_susfs_guard_lkm.ko
mkdir -p "$(dirname "$DEST")"
if [ "$(sha256sum "$DEST" 2>/dev/null | cut -d' ' -f1)" != "$KOSHA" ]; then
  cp -f "$KO" "$DEST"
  say "已替换 bin/aarch64/$(basename "$DEST")"
else
  say "bin/aarch64/$(basename "$DEST") 已是最新"
fi

# ---- 2. 重编 ksud ---------------------------------------------------------
# rust-embed 的 compression 在编译期压缩资源，缓存命中时会沿用旧字节（源码注释
# 明确要求 clean）。删掉 ksud 自己的 fingerprint 即可，不必全量重编依赖。
T=$REPO/userspace/ksud/target/aarch64-linux-android/release
rm -rf "$T"/.fingerprint/ksud-* "$T"/deps/ksud-* "$T"/ksud "$T"/ksud.d
say "重编 ksud（仅 ksud crate）"
cargo build --target aarch64-linux-android --release \
  --manifest-path "$REPO/userspace/ksud/Cargo.toml"

KS=$T/ksud
[ -f "$KS" ] || { echo "ksud 编译产物缺失" >&2; exit 1; }
KSSIZE=$(stat -c%s "$KS")
say "ksud: ${KSSIZE} bytes"

# ---- 3. 换进 jniLibs ------------------------------------------------------
DOVE=$REPO/manager/app/src/main/jniLibs/arm64-v8a/libdove.so
OLD=$(stat -c%s "$DOVE" 2>/dev/null || echo 0)
cp -f "$KS" "$DOVE"
chmod 755 "$DOVE"
say "libdove.so: ${OLD} → ${KSSIZE} (delta $((KSSIZE - OLD)))"

# ---- 4. 版本号 ------------------------------------------------------------
GRADLE=$REPO/manager/build.gradle.kts
if [ $# -ge 2 ]; then
  VN=$2
  VC=${3:-$(( $(sed -n 's/.*managerVersionCode"\] = \([0-9]*\).*/\1/p' "$GRADLE") + 1 ))}
else
  VC=$(sed -n 's/.*managerVersionCode"\] = \([0-9]*\).*/\1/p' "$GRADLE")
  VN=$(sed -n 's/.*managerVersionName"\] = "\(.*\)".*/\1/p' "$GRADLE")
  VC=$((VC + 1))
  # 4.3.4 → 4.3.5
  VN=$(awk -F. '{printf "%s.%s.%d", $1, $2, $3 + 1}' <<<"$VN")
fi
sed -i "s/managerVersionCode\"\] = [0-9]*/managerVersionCode\"] = $VC/" "$GRADLE"
sed -i "s/managerVersionName\"\] = \"[^\"]*\"/managerVersionName\"] = \"$VN\"/" "$GRADLE"
say "版本: $VN ($VC)"

# ---- 5. 出包 --------------------------------------------------------------
say "assembleRelease (JAVA_HOME=$JAVA_HOME, $(java -version 2>&1 | head -1))"
cd "$REPO/manager"
./gradlew :app:assembleRelease --no-daemon --no-parallel --max-workers=2 \
  -PKEYSTORE_FILE=/workspace/loomsu-release.jks \
  -PKEYSTORE_PASSWORD=loomsu123456 \
  -PKEY_ALIAS=loomsu \
  -PKEY_PASSWORD=loomsu123456 \
  -Dorg.gradle.jvmargs="-Xmx2560m -XX:MaxMetaspaceSize=768m" \
  -Dkotlin.daemon.jvmargs="-Xmx1024m" \
  | tail -5

APK=$REPO/manager/app/build/outputs/apk/release/KuaiMaoSU_${VN}_${VC}-arm64-v8a-release.apk
[ -f "$APK" ] || { echo "APK 缺失: $APK" >&2; exit 1; }

cp -f "$APK" "$OUT/"
BASE=$(basename "$APK")
say "产物 $OUT/$BASE"
ls -lh "$OUT/$BASE" | awk '{print $5}'
md5sum "$OUT/$BASE"
"$SDK/build-tools/36.1.0/aapt2" dump badging "$OUT/$BASE" 2>/dev/null | head -1
"$SDK/build-tools/36.1.0/apksigner" verify --print-certs "$OUT/$BASE" 2>/dev/null | grep -m1 "certificate DN"

cat <<EOF

装到设备后必须重载 LKM，否则跑的还是内存里的旧模块：
  ksud lkm unload; ksud lkm load        # 或在管理器里关一次再开
确认新代码生效：
  dmesg | grep policy_clean             # 旧模块没有这一行
EOF
