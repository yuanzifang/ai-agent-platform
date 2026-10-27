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
- 发布归档：建议把两个 SBOM 与 release 资产一起附加

## 仍缺

- 发布流程自动把 SBOM 附加到 GitHub Release
- SBOM 签名/attestation（Sigstore/cosign）
- 后端/前端/镜像 SBOM 的统一合并清单
