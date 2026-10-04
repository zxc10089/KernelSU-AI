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
