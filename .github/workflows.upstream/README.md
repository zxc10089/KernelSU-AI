# 已归档的上游 CI（本 fork 不执行）

本目录原为 `.github/workflows/`，包含上游的 12 个 GitHub Actions 工作流。

## 为什么归档

- 本 fork 没有上游所需的密钥与自建工具链，构建路径也不同，push 后这些工作流会大面积失败并发送失败邮件。
- GitHub **只执行** `.github/workflows/*.yml`；放在 `.github/workflows.upstream/` 下的文件不会被触发。

## 如何恢复

```bash
git mv .github/workflows.upstream .github/workflows
git commit -m "ci: 恢复上游工作流"
```

（可先只恢复需要的单个文件，例如 `build-manager.yml`。）

## 归档清单

`build-lkm.yml`、`build-manager.yml`、`clang-format.yml`、`clippy.yml`、`ddk-lkm.yml`、`deploy-website.yml`、`ksud-extra.yml`、`ksud.yml`、`ksuinit.yml`、`release.yml`、`rustfmt.yml`、`shellcheck.yml`

## 一并归档：Dependabot

上游的 `.github/dependabot.yml` 已改名为 `.github/dependabot.yml.upstream`。GitHub 只识别 `.github/dependabot.yml`，因此自动依赖升级 PR 不会再生成；把它改回原名即可恢复。

已经开出的 Dependabot PR 与分支不会随改名自动消失，需要在仓库的 Pull requests 页手动关闭（本 fork 的依赖版本经过专门选择，不建议直接合并这些升级）。
