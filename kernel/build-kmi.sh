#!/usr/bin/env bash
# 多 KMI 编译入口：把 kernel/ 编成各 KMI 的 kernelsu.ko（含内核态隐藏功能）
#
# 与 kernel/build-all.sh 的区别：不依赖 `ddk` 命令行工具，直接使用 host 模式
# 解压好的 /opt/ddk/{kdir,src,clang}，适合已装好 DDK 但没装 ddk CLI 的机器。
#
# 用法:
#   ./build-kmi.sh                      # 编译本机已具备的全部 KMI
#   ./build-kmi.sh android15-6.6        # 只编指定 KMI
#   KDIR_ROOT=/data/ddk ./build-kmi.sh  # 自定义 DDK 根目录
#
# 缺哪个 KMI 的 kdir/src，脚本会跳过并提示，不会中断其余目标。

set -uo pipefail

KDIR_ROOT="${KDIR_ROOT:-/opt/ddk}"
KSRC="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
OUT="${KMI_OUT:-$KSRC/out}"
JOBS="${JOBS:-$(nproc)}"

ALL_KMIS="${*:-android12-5.10 android13-5.10 android13-5.15 android14-5.15 android14-6.1 android15-6.6 android16-6.12 android17-6.18}"

mkdir -p "$OUT"

# KMI → clang 目录（优先用已解压的匹配版本，缺则回退 r510928，再缺则用 PATH）
pick_clang() {
    local kmi="$1" prefer="" cand
    # 按内核版本选工具链：5.10 只能用 clang 14（新 clang 会把
    # -enable-trivial-auto-var-init-zero 的弃用警告升级为错误）
    case "$kmi" in
        android12-*) prefer="clang-r450784e" ;;
        android13-5.10) prefer="clang-r450784e" ;;
        android13-5.15) prefer="clang-r450784e" ;;
        android14-*) prefer="clang-r487747c" ;;
        android15-*) prefer="clang-r510928" ;;
        android16-*|android17-*) prefer="clang-r536225" ;;
    esac
    for cand in "$prefer" clang-r510928 clang-r487747c clang-r536225; do
        [ -n "$cand" ] && [ -d "$KDIR_ROOT/clang/$cand/bin" ] && { echo "$KDIR_ROOT/clang/$cand/bin"; return; }
    done
    echo ""
}

# 隐藏功能只依赖 tracepoint syscall hook，所有 GKI2(5.10+) 通用
KCONFIG=(
    CONFIG_KSU=m
    CONFIG_KSU_TRACEPOINT_HOOK=y
    CONFIG_KSU_MULTI_MANAGER_SUPPORT=y
)

fail=0
built=()

for kmi in $ALL_KMIS; do
    kdir="$KDIR_ROOT/kdir/$kmi"
    src="$KDIR_ROOT/src/$kmi"

    if [ ! -d "$kdir" ] || [ ! -d "$src" ]; then
        echo "== 跳过 $kmi：缺少 $KDIR_ROOT/{kdir,src}/$kmi（用 ddk 拉取对应 KMI 后重试）"
        continue
    fi

    # 5.x 的内核树带 CONFIG_WERROR=y，clang 会把已弃用参数升级成错误；
    # 清掉 CONFIG_WERROR 才能过（只影响警告等级，不改生成代码）
    case "$kmi" in
        # 5.10 的构建树在 LLVM=1 下不会自动补 --target，得显式给交叉前缀；
        # 否则 clang 以 host 为目标，弃用参数警告被 -Werror 升级成错误
        android12-*) EXTRA_KBUILD="CONFIG_WERROR= CROSS_COMPILE=aarch64-linux-gnu-" ;;
        android13-*) EXTRA_KBUILD="CONFIG_WERROR=" ;;
        *) EXTRA_KBUILD="" ;;
    esac

    cbin="$(pick_clang "$kmi")"
    [ -n "$cbin" ] && export PATH="$cbin:$PATH"

    echo "== 编译 $kmi（clang: ${cbin:-PATH}）"
    if make -C "$KSRC" \
        KDIR="$kdir" ARCH=arm64 LLVM=1 LLVM_IAS=1 KBUILD_MODPOST_WARN=1 \
        "${KCONFIG[@]}" $EXTRA_KBUILD -j"$JOBS" >"$OUT/build-$kmi.log" 2>&1; then
        ko="$KSRC/kernelsu.ko"
        if [ -f "$ko" ]; then
            cp "$ko" "$OUT/kernelsu-$kmi.ko"
            "${cbin:+$cbin/}llvm-strip" -d "$OUT/kernelsu-$kmi.ko" 2>/dev/null || strip -g "$OUT/kernelsu-$kmi.ko" 2>/dev/null || true
            echo "   ok -> out/kernelsu-$kmi.ko"
            built+=("$kmi")
        else
            echo "   失败：未产出 kernelsu.ko（见 out/build-$kmi.log）"
            fail=1
        fi
    else
        echo "   失败（见 out/build-$kmi.log 末尾 30 行）:"
        tail -30 "$OUT/build-$kmi.log" | sed 's/^/     /'
        fail=1
    fi
    # 换 KMI 前清掉上一轮的中间产物，避免 .o 混用
    make -C "$KSRC" KDIR="$kdir" M="$KSRC" clean >/dev/null 2>&1 || true
done

echo
echo "已产出: ${built[*]:-无}"
[ "$fail" -eq 0 ] || exit 1
