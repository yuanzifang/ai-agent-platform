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
3. 用 `scripts/merge_cyclonedx_sbom.py` 合并后端/前端组件（按 purl/bom-ref 去重，保留依赖与漏洞）；
4. 校验三个文件的 `bomFormat=CycloneDX` 且 `components` 非空；
5. 生成 `SHA256SUMS`；
6. 用 GitHub Actions OIDC 生成 build provenance attestation（`actions/attest-build-provenance`）；
7. 通过 `gh release upload --clobber` 附加到对应 GitHub Release。

资产命名：

```text
backend-sbom-<tag>.cdx.json
frontend-sbom-<tag>.cdx.json
merged-sbom-<tag>.cdx.json
SHA256SUMS
```

手动验证时可在 Actions 中运行 `Release SBOM`，输入已存在的 release tag。下载 Release 资产后可验证来源证明：

```bash
gh attestation verify merged-sbom-<tag>.cdx.json \
  --repo yuanzifang/ai-agent-platform
```

## 仍缺

- 独立 Sigstore/cosign 签名/attestation（当前使用 GitHub 内置 build provenance）
- 运行时镜像 SBOM 未并入 Release 合并清单；镜像 SBOM 也尚未单独做 attestation（当前 Release 合并清单只覆盖后端/前端应用依赖）
