#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export JAVA_HOME="${JAVA_HOME:-${project_dir}/.toolchain/jdk-21.0.9+10-linux-x64}"
export PATH="${JAVA_HOME}/bin:${PATH}"
cd "$project_dir"
./mvnw clean verify
