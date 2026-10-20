#!/usr/bin/env bash
# 启动 OceanBase 知识图谱用的 Neo4j 实例
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
docker compose up -d
echo "Neo4j 已启动："
echo "  Browser: http://localhost:7474   (账号 neo4j / oceanbase123)"
echo "  Bolt:    bolt://localhost:7687"
