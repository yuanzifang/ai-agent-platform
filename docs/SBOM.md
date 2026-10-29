# SBOM（CycloneDX）

日期：2026-10-27

## 产物

- 后端：`backend/target/bom.json`
  - Maven CycloneDX 2.9.1，Aggregate BOM
  - 当前 193 个组件
- 前端：`frontend-sbom.cdx.json`
  - Trivy 0.76.1，CycloneDX 1.7
  - 当前 88 个组件
- 运行时镜像：`gateway-sbom.cdx.json` / `alert-relay-sbom.cdx.json`
  - CI `gateway` job 对两个镜像执行 Trivy image 扫描
  - 上传为 `runtime-image-sbom` artifact

CI 的 `deps` job 生成并上传应用 SBOM；`gateway` job 生成运行时镜像 SBOM。

## 本地生成

```bash
cd backend
mvn org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -DoutputFormat=json -DoutputName=bom

cd ../frontend
trivy fs --format cyclonedx --output ../frontend-sbom.cdx.json package-lock.json
```

## 用途

- 漏洞响应：知道某个 CVE 影响哪些组件/版本
- 许可证核对：与 `backend/target/licenses/THIRD-PARTY.txt` 和 `docs/依赖许可证.md` 对照
- 发布归档：GitHub Release 自动附加应用 SBOM 与 SHA256 校验和

## Release 自动附加

`.github/workflows/release.yml` 在 **Release published** 时触发，也支持手动传入已存在的 tag：

1. 按 Release tag checkout，构建并生成后端 CycloneDX BOM；
2. 用 Trivy 对 `frontend/package-lock.json` 生成前端 CycloneDX BOM；
3. 构建 `agent-gateway` / `alert-relay` 运行时镜像，用 Trivy image 生成两个镜像 BOM；
4. 用 `scripts/merge_cyclonedx_sbom.py` 合并四份组件（按 purl/bom-ref 去重，保留依赖与漏洞）；
5. 校验五个文件的 `bomFormat=CycloneDX` 且 `components` 非空；
6. 生成 `SHA256SUMS`；
7. 安装 cosign，对全部 SBOM 和 `SHA256SUMS` 执行 keyless `cosign sign-blob`；
8. 用 GitHub Actions OIDC 生成 build provenance attestation；
9. 通过 `gh release upload --clobber` 附加到对应 GitHub Release。

资产命名：

```text
backend-sbom-<tag>.cdx.json
frontend-sbom-<tag>.cdx.json
gateway-image-sbom-<tag>.cdx.json
alert-relay-image-sbom-<tag>.cdx.json
merged-sbom-<tag>.cdx.json
SHA256SUMS
*.cdx.json.sigstore.json
SHA256SUMS.sigstore.json
```

手动验证时可在 Actions 中运行 `Release SBOM`，输入已存在的 release tag。下载 Release 资产后可验证来源证明：

```bash
gh attestation verify merged-sbom-<tag>.cdx.json \
  --repo yuanzifang/ai-agent-platform
# 独立 Sigstore 签名
cosign verify-blob \
  --bundle merged-sbom-<tag>.cdx.json.sigstore.json \
  --certificate-identity-regexp 'https://github.com/yuanzifang/ai-agent-platform/.github/workflows/release.yml@refs/tags/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  merged-sbom-<tag>.cdx.json
```

## v0.1.4 实际验证（2026-10-29）

- Release：`v0.1.4`，workflow run `40860832341`，结论 `success`
- 5 份 CycloneDX SBOM + 5 份 Sigstore bundle + `SHA256SUMS` + 校验和签名，共 12 个文件
- 组件数：backend 209、frontend 88、gateway image 219、alert-relay image 231、merged 515
- `shasum -a 256 -c SHA256SUMS`：5/5 OK
- `cosign verify-blob`：6/6 `Verified OK`
- `gh attestation verify backend-sbom-v0.1.4.cdx.json --repo yuanzifang/ai-agent-platform`：exit 0

## v0.1.3 实际验证（2026-10-29）

- Release：`v0.1.3`，workflow run `40854096595`，结论 `success`
- 5 份 CycloneDX SBOM + 5 份 Sigstore bundle + `SHA256SUMS` + 校验和签名，共 12 个文件
- 组件数：backend 209、frontend 88、gateway image 219、alert-relay image 231、merged 515
- `shasum -a 256 -c SHA256SUMS`：5/5 OK
- `cosign verify-blob`：6/6 `Verified OK`
- `gh attestation verify backend-sbom-v0.1.3.cdx.json --repo yuanzifang/ai-agent-platform`：exit 0

## v0.1.2 实际验证（2026-10-29）

- Release：`v0.1.2`，workflow run `40845967067`，结论 `success`
- 5 份 CycloneDX SBOM + 5 份 Sigstore bundle + `SHA256SUMS` + 校验和签名，共 12 个文件
- 组件数：backend 209、frontend 88、gateway image 219、alert-relay image 231、merged 515
- `shasum -a 256 -c SHA256SUMS`：5/5 OK
- `cosign verify-blob`：6/6 `Verified OK`
- `gh attestation verify backend-sbom-v0.1.2.cdx.json --repo yuanzifang/ai-agent-platform`：exit 0

## v0.1.1 实际验证（2026-10-29）

- Release：`v0.1.1`，workflow run `40825276016`，结论 `success`
- 5 份 CycloneDX SBOM + 5 份 Sigstore bundle + `SHA256SUMS` + 校验和签名，共 12 个文件
- 组件数：backend 209、frontend 88、gateway image 219、alert-relay image 231、merged 515
- `shasum -a 256 -c SHA256SUMS`：5/5 OK
- `cosign verify-blob`：6/6 `Verified OK`
- `gh attestation verify backend-sbom-v0.1.1.cdx.json --repo yuanzifang/ai-agent-platform`：exit 0

## v0.1.0 实际验证（2026-10-29）

- Release：`v0.1.0`，workflow run `40807869832`，结论 `success`
- Release assets：5 份 CycloneDX SBOM + 5 份 Sigstore bundle + `SHA256SUMS` + 校验和签名，共 12 个文件
- 组件数：backend 209、frontend 88、gateway image 219、alert-relay image 231、merged 515
- `shasum -a 256 -c SHA256SUMS`：5/5 OK
- `cosign verify-blob`：6/6 `Verified OK`
  - identity：`https://github.com/yuanzifang/ai-agent-platform/.github/workflows/release.yml@refs/tags/v0.1.0`
  - issuer：`https://token.actions.githubusercontent.com`
- `gh attestation verify backend-sbom-v0.1.0.cdx.json --repo yuanzifang/ai-agent-platform`：exit 0

## 仍缺

- SBOM-specific attestation（当前是 build provenance + keyless 签名，不是完整 SBOM predicate）
- 合并清单按 purl/bom-ref 去重，可能合并跨生态同名组件；需要时按 purl 前缀拆分查看
