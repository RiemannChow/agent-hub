# 贡献与 PR 规范

本仓库所有改动通过 Pull Request 合入 `main`。规范的目的是让「为什么改、改了什么、怎么验证、有什么遗留」在 PR 上可追溯，而不是靠口头或 commit 注释记忆。

## 1. Issue

- 标题：`[模块] 现象 / 风险`，例如 `[沙箱] COW 沙箱隔离可绕过`。
- 正文必须包含：**问题**、**证据（文件路径:行号 + 关键代码）**、**影响**、**修复方案**、**验收标准**。
- 一个 PR 只解决一个 issue，不混搭无关改动。

## 2. 分支

- 一律从最新 `main` 切出，命名 `<type>/<scope>-<short-desc>`，小写英文 + 连字符。
- `type` 取值：`fix` / `feat` / `refactor` / `docs` / `test` / `chore` / `ci`。
- 例：`fix/vector-scan-budget`、`fix/sandbox-isolation`。
- 合并后删除源分支，禁止对 `main` 强推。

## 3. Commit

- 采用 Conventional Commits：`<type>(<scope>): <subject>`。
- `subject` 使用英文，50 字以内，祈使句（imperative mood），不加句号；例：`fix(books): bound vector scan rows`。
- 一个 PR 建议 1~3 个 commit，每个 commit 自身可编译，不夹带无关文件。

## 4. PR 标题与描述

- 标题：`[<type>] <scope>: <一句话说明>`，例：`[fix] books: 为向量检索增加扫描预算与可选 ANN 提示`。
- 描述必须**关联 issue**（`Closes #N`）并按 [`.github/pull_request_template.md`](.github/pull_request_template.md) 逐项填写，尤其要写清：
  - 背景 / 根因（带路径:行号证据）
  - 改动点
  - **影响面与兼容性**：默认行为是否变化、是否需要迁移
  - 验证方式与结果
  - 风险、已知限制与后续项
- 新建配置文件必须同时给出默认值、`.env.example` 对应项与输入校验说明。

## 5. 本地门禁（提 PR 前必须通过）

```bash
mvn -B -ntp verify
```

该命令依次触发：`compile → test → spotless:check → checkstyle:check → spotbugs:check → package`。

- 新增逻辑**必须带单元测试**，且测试不依赖真实 DB / 外部服务 / 模型密钥。
- 能说明「修复前会失败、修复后通过」的用例优先。
- 不新增静态检查豁免；格式问题用 `mvn spotless:apply` 解决，不要手工对抗格式器。

## 6. CI

- `.github/workflows/ci.yml` 对 PR 跑 `Build & Quality Gate`（`mvn verify`）与 OSV 依赖扫描。
- OSV 当前为 non-blocking（历史技术债另行处理），但 **PR 不得新增已知高危依赖**。
- **CI 全绿方可合并**；CI 失败先修再合并，不能以「本地过了」为由跳过。

## 7. Review Checklist

- [ ] 单一职责、关联且仅关联一个 issue
- [ ] 默认行为不变；若有变化，已写明不兼容点与迁移方式
- [ ] 无新增硬编码密钥、口令、内网地址、默认弱口令
- [ ] 拼接进 SQL / Cypher / URL / DDL 的输入均已参数化或白名单校验
- [ ] 新增配置有默认值、有 `.env.example` 项、有输入校验
- [ ] 有单元测试且能说明「修复前会失败」
- [ ] `README` / `.env.example` / `doc` 已同步
- [ ] 已在描述中列出未覆盖的后续项，不假装一次做完

## 8. 合并

- 使用 **merge commit**（保留分支历史），不用 squash / rebase merge。
- 合并前确认已 rebase 到最新 `main`、无冲突、CI 全绿。
- 合并后删除源分支，并同步本地 `main`。

## 9. 关联 Issue 状态

PR 描述写 `Closes #N`，合并时自动关闭对应 issue；若只部分解决，写 `Refs #N` 并在 PR 里留下后续 issue。
