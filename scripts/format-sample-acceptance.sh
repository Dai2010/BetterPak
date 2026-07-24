#!/usr/bin/env bash

set -euo pipefail

libarchive_ref="f5509ae993ac30417f81acc5118f232ae3f2d27d"
libarchive_base="${BETTERPAK_LIBARCHIVE_BASE:-https://raw.githubusercontent.com/libarchive/libarchive/${libarchive_ref}/libarchive/test}"
workdir="$(mktemp -d "${TMPDIR:-/tmp}/betterpak-format-samples.XXXXXX")"
sample_directory="${BETTERPAK_SAMPLE_DIRECTORY:-$workdir/samples}"
source_directory="$workdir/source"
input_directory="$workdir/input"
pass_count=0
skip_public_samples="${BETTERPAK_SKIP_PUBLIC_SAMPLES:-0}"

if [[ "$skip_public_samples" != "0" && "$skip_public_samples" != "1" ]]; then
    printf 'BETTERPAK_SKIP_PUBLIC_SAMPLES must be 0 or 1\n' >&2
    exit 2
fi

cleanup() {
    rm -rf "$workdir"
}

trap cleanup EXIT

require_command() {
    local command_name="$1"
    if ! command -v "$command_name" >/dev/null 2>&1; then
        printf 'missing required command: %s\n' "$command_name" >&2
        exit 2
    fi
}

pass() {
    pass_count=$((pass_count + 1))
    printf 'PASS %s\n' "$1"
}

fail() {
    printf 'FAIL %s\n' "$1" >&2
    exit 1
}

expect_success() {
    local label="$1"
    shift
    if "$@" >/dev/null 2>&1; then
        pass "$label"
    else
        fail "$label"
    fi
}

expect_failure() {
    local label="$1"
    shift
    if "$@" >/dev/null 2>&1; then
        fail "$label"
    else
        pass "$label"
    fi
}

expect_output() {
    local label="$1"
    local expected_text="$2"
    shift 2
    if "$@" 2>/dev/null | grep -Fq -- "$expected_text"; then
        pass "$label"
    else
        fail "$label"
    fi
}

assert_hash() {
    local label="$1"
    local expected_hash="$2"
    local sample_path="$3"
    local actual_hash
    actual_hash="$(sha256sum "$sample_path" | awk '{ print $1 }')"
    if [[ "$actual_hash" == "$expected_hash" ]]; then
        pass "$label"
    else
        printf 'expected %s, got %s\n' "$expected_hash" "$actual_hash" >&2
        fail "$label"
    fi
}

fetch_sample() {
    local sample_name="$1"
    if [[ -f "$sample_directory/$sample_name" ]]; then
        return
    fi
    if ! curl -LfsS --retry 4 --retry-delay 2 --connect-timeout 20 --max-time 90 \
        "$libarchive_base/${sample_name}.uu" \
        -o "$source_directory/${sample_name}.uu"; then
        printf 'unable to download public sample: %s\n' "$sample_name" >&2
        exit 1
    fi
}

decode_uu() {
    local source_path="$1"
    local destination_path="$2"
    python3 - "$source_path" "$destination_path" <<'PY'
import binascii
import pathlib
import sys

source_path = pathlib.Path(sys.argv[1])
destination_path = pathlib.Path(sys.argv[2])
with source_path.open("rb") as encoded, destination_path.open("wb") as decoded:
    for line in encoded:
        stripped = line.strip()
        if line.startswith(b"begin ") or stripped in {b"end", b"`", b""}:
            continue
        decoded.write(binascii.a2b_uu(line.rstrip(b"\r\n")))
PY
}

for command_name in awk grep unzip zip 7z truncate; do
    require_command "$command_name"
done
if [[ "$skip_public_samples" == "0" ]]; then
    for command_name in curl python3 sha256sum unrar; do
        require_command "$command_name"
    done
fi

mkdir -p "$sample_directory" "$source_directory" "$input_directory/中文目录/空目录"

declare -A sample_hashes=(
    [test_compat_zip_1.zip]=d593e51c7167de3a61ee060b1752d416038014d6e325a260c5529c65262c3d01
    [test_read_format_7zip_copy.7z]=67a85f85950c4aaa524eb3f22be1fbac586e90c76de21e10fc1c2ce7685e04c2
    [test_read_format_rar4_solid_encrypted.rar]=428d44a21042069bbc1891c30a66c48f55540aa37dd8b7ff92c39c5914e3a4a6
    [test_read_format_rar5_solid_encrypted.rar]=307de6b76f6b7cc3a221975e14b8a6d7a6b87a30fd1ee7a0e08e4333724e5657
    [test_read_format_rar5_unicode.rar]=062c77fb1d47efbd5a468609a9c283ecb0b4cae43b0f6778935c41a975cedee9
    [test_read_format_rar_unicode.rar]=3dc3d2a8f3f6cbfeeb6f45b3bb09fa7b2bf4b52e10e80df29217fdeaa9dbda45
    [test_read_format_7zip_encryption.7z]=91f5427859ad1391c9fb877c98e4b55213c479f39fd012882f7d112388842076
)

sample_names=(
    test_compat_zip_1.zip
    test_read_format_7zip_copy.7z
    test_read_format_rar4_solid_encrypted.rar
    test_read_format_rar5_solid_encrypted.rar
    test_read_format_rar5_unicode.rar
    test_read_format_rar_unicode.rar
    test_read_format_7zip_encryption.7z
)

if [[ "$skip_public_samples" == "0" ]]; then
    for sample_name in "${sample_names[@]}"; do
        fetch_sample "$sample_name"
        if [[ ! -f "$sample_directory/$sample_name" ]]; then
            decode_uu "$source_directory/${sample_name}.uu" "$sample_directory/$sample_name"
        fi
        assert_hash "sample hash: $sample_name" "${sample_hashes[$sample_name]}" "$sample_directory/$sample_name"
    done

    expect_output "RAR4 Unicode names" "表だよ" unrar lb "$sample_directory/test_read_format_rar_unicode.rar"
    expect_output "RAR5 Unicode names" "👋🌎.txt" unrar lb "$sample_directory/test_read_format_rar5_unicode.rar"
    expect_output "RAR5 Unicode zero-byte entry" "𝒮𝓎𝓂𝒷𝑜𝓁𝒾𝒸" unrar lb "$sample_directory/test_read_format_rar5_unicode.rar"
    expect_success "RAR4 solid correct password" unrar t -ppassword "$sample_directory/test_read_format_rar4_solid_encrypted.rar"
    expect_failure "RAR4 solid wrong password" unrar t -pwrong "$sample_directory/test_read_format_rar4_solid_encrypted.rar"
    expect_success "RAR5 solid correct password" unrar t -ppassword "$sample_directory/test_read_format_rar5_solid_encrypted.rar"
    expect_failure "RAR5 solid wrong password" unrar t -pwrong "$sample_directory/test_read_format_rar5_solid_encrypted.rar"
    expect_success "7z correct password" 7z t -p12345678 "$sample_directory/test_read_format_7zip_encryption.7z"
    expect_failure "7z wrong password" 7z t -pwrong "$sample_directory/test_read_format_7zip_encryption.7z"
    expect_output "7z regular entry" "file1" 7z l -p- "$sample_directory/test_read_format_7zip_copy.7z"
else
    printf 'SKIP public sample checks (BETTERPAK_SKIP_PUBLIC_SAMPLES=1)\n'
fi

printf '来自公开验收的 UTF-8 内容\n' > "$input_directory/中文目录/说明.txt"
: > "$input_directory/中文目录/零字节.bin"
printf 'plain\n' > "$input_directory/plain.txt"

(
    cd "$workdir"
    zip -qr -UN=UTF8 local.zip input
    7z a -bd -t7z local.7z input >/dev/null
)

generated_password="fixture-${BASHPID}-${RANDOM}"
(
    cd "$workdir"
    7z a -bd -t7z -p"$generated_password" -mhe=on local-password.7z input >/dev/null
)

expect_success "ZIP created archive opens in unzip" unzip -t "$workdir/local.zip"
expect_success "ZIP created archive opens in 7z" 7z t "$workdir/local.zip"
expect_success "7z created archive opens in 7z" 7z t "$workdir/local.7z"
expect_success "7z password archive opens with generated password" 7z t -p"$generated_password" "$workdir/local-password.7z"
expect_failure "7z password archive rejects wrong password" 7z t -pwrong "$workdir/local-password.7z"
expect_output "ZIP keeps Chinese directory" "中文目录/" unzip -Z1 "$workdir/local.zip"
expect_output "ZIP keeps zero-byte filename" "零字节.bin" unzip -Z1 "$workdir/local.zip"
expect_output "ZIP keeps empty directory" "空目录/" unzip -Z1 "$workdir/local.zip"

cp "$workdir/local.zip" "$workdir/corrupt.zip"
truncate -s -7 "$workdir/corrupt.zip"
expect_failure "corrupt ZIP is rejected" unzip -t "$workdir/corrupt.zip"

if [[ "$skip_public_samples" == "0" ]]; then
    cp "$sample_directory/test_read_format_rar5_unicode.rar" "$workdir/corrupt.rar"
    truncate -s -7 "$workdir/corrupt.rar"
    expect_failure "corrupt RAR5 is rejected" unrar t -p- "$workdir/corrupt.rar"

    cp "$sample_directory/test_read_format_7zip_copy.7z" "$workdir/corrupt.7z"
    truncate -s -7 "$workdir/corrupt.7z"
    expect_failure "corrupt 7z is rejected" 7z t "$workdir/corrupt.7z"
fi

if [[ "$skip_public_samples" == "0" ]]; then
    printf 'format sample acceptance: %d checks passed\n' "$pass_count"
else
    printf 'format sample acceptance: %d local checks passed; public sample checks skipped\n' "$pass_count"
fi
