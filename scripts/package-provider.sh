#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 1 ]]; then
  echo "Usage: $0 DESCRIPTOR" >&2
  exit 2
fi
project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
descriptor=$(realpath "$1")
if [[ ! -f "$descriptor" ]]; then
  echo "Provider descriptor not found: $descriptor" >&2
  exit 1
fi
export JAVA_HOME="${JAVA_HOME:-${project_dir}/.toolchain/jdk-21.0.9+10-linux-x64}"
export PATH="${JAVA_HOME}/bin:${PATH}"
cd "$project_dir"
./mvnw clean verify

descriptor_dir=$(dirname "$descriptor")
entry_point=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1],encoding="utf-8"))["backend"]["entryPoint"])' "$descriptor")
if [[ "$entry_point" = /* || "$entry_point" == *..* ]]; then
  echo "Backend entry point must remain a relative path inside the descriptor directory" >&2
  exit 1
fi
if [[ ! -f "${descriptor_dir}/${entry_point}" || ! -d "${descriptor_dir}/hades_sram_gen" ]]; then
  echo "Descriptor backend package is incomplete" >&2
  exit 1
fi
product_root="${project_dir}/products/cn.rismd.hades.wizard.product/target/products/cn.rismd.hades.wizard.product"
linux_dir="${product_root}/linux/gtk/x86_64"
windows_dir="${product_root}/win32/win32/x86_64"
for product_dir in "$linux_dir" "$windows_dir"; do
  provider_dir="${product_dir}/provider"
  rm -rf "$provider_dir"
  mkdir -p "$provider_dir"
  cp "$descriptor" "${provider_dir}/provider.json"
  cp -a "${descriptor_dir}/${entry_point}" "${provider_dir}/${entry_point}"
  tar -C "$descriptor_dir" --exclude='__pycache__' --exclude='*.pyc' -cf - hades_sram_gen | tar -C "$provider_dir" -xf -
  PYTHONPATH="$descriptor_dir" python3 -m hades_sram_gen.payload stage "${provider_dir}/payload"
done

archive_dir="${project_dir}/products/cn.rismd.hades.wizard.product/target/provider-products"
rm -rf "$archive_dir"
mkdir -p "$archive_dir"
linux_archive="${archive_dir}/hades-wizard-sram-linux.gtk.x86_64.tar.gz"
windows_archive="${archive_dir}/hades-wizard-sram-win32.win32.x86_64.zip"
tar -C "$linux_dir" -czf "$linux_archive" .
python3 -c 'import shutil,sys; shutil.make_archive(sys.argv[1], "zip", sys.argv[2])' "${windows_archive%.zip}" "$windows_dir"
(cd "$archive_dir" && sha256sum hades-wizard-sram-linux.gtk.x86_64.tar.gz hades-wizard-sram-win32.win32.x86_64.zip > SHA256SUMS)
printf 'Staged Linux product: %s\nStaged Windows product: %s\nArchives: %s\n' "$linux_dir" "$windows_dir" "$archive_dir"
