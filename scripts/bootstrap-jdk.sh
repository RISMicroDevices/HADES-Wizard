#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
version=21.0.9+10
archive=OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz
url="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/${archive}"
sha256=810d3773df7e0d6c4394e4e244b264c8b30e0b05a0acf542d065fd78a6b65c2f
download_dir="${project_dir}/.toolchain/downloads"
jdk_dir="${project_dir}/.toolchain/jdk-${version}-linux-x64"
mkdir -p "$download_dir"
if [[ ! -f "${download_dir}/${archive}" ]]; then
  curl --fail --location --retry 3 --output "${download_dir}/${archive}" "$url"
fi
echo "${sha256}  ${download_dir}/${archive}" | sha256sum --check -
if [[ ! -x "${jdk_dir}/bin/java" ]]; then
  rm -rf "$jdk_dir"
  tar -xzf "${download_dir}/${archive}" -C "${project_dir}/.toolchain"
  mv "${project_dir}/.toolchain/jdk-${version}" "$jdk_dir"
fi
printf 'export JAVA_HOME=%q\n' "$jdk_dir"
printf 'Run: export JAVA_HOME=%q; export PATH="$JAVA_HOME/bin:$PATH"\n' "$jdk_dir"
