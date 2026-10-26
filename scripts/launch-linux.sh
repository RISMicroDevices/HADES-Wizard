#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
product="${project_dir}/products/cn.rismd.hades.wizard.product/target/products/cn.rismd.hades.wizard.product/linux/gtk/x86_64/hades-wizard"
if [[ ! -x "$product" ]]; then
  echo "Linux product not built; run ./scripts/build-products.sh first." >&2
  exit 1
fi
exec "$product" "$@"
