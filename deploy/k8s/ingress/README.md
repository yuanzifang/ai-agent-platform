# Agent Ingress（Traefik）

本目录是 k3d / Linux Docker 上验证 Agent Ingress 的最小安装配置。

## 安装

```bash
helm repo add traefik https://traefik.github.io/charts
helm repo update

helm upgrade --install traefik traefik/traefik -n traefik --create-namespace \
  -f deploy/k8s/ingress/traefik-values.yaml
```

镜像需要提前加载到 k3d 节点。国内环境可先拉取：

```bash
docker pull docker.m.daocloud.io/library/traefik:v3.7.16
docker tag docker.m.daocloud.io/library/traefik:v3.7.16 traefik:v3.7.16
k3d image import traefik:v3.7.16 -c aap-k8s
```

## k3d 端口映射

Traefik Service 使用 NodePort：

```text
web:        30090  -> 宿主 19003
websecure:  30443  -> 宿主 19004
```

```bash
k3d cluster edit aap-k8s \
  --port-add 0.0.0.0:19003:30090@server:0 \
  --port-add 0.0.0.0:19004:30443@server:0
```

## Agent Ingress 配置

```yaml
agentDeploy:
  ingress:
    enabled: true
    className: traefik
    hostTemplate: "agent-{agentId}.192-168-64-2.sslip.io"
    tlsSecret: "agents-wildcard-tls"
    externalPort: "19004"
```

`externalPort` 用于非 443 的测试端口；生产 TLS 走 443 时留空。

## 自签测试证书

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
  -keyout tls.key -out tls.crt \
  -subj '/CN=*.192-168-64-2.sslip.io' \
  -addext 'subjectAltName=DNS:*.192-168-64-2.sslip.io'

kubectl -n aap create secret tls agents-wildcard-tls \
  --cert=tls.crt --key=tls.key
```

`sslip.io` 只是临时测试域名，正式环境替换为真实泛域名 DNS 和 CA 证书。

## 验证

```bash
curl -k https://agent-1.192-168-64-2.sslip.io:19004/healthz

curl -k https://agent-1.192-168-64-2.sslip.io:19004/v1/models \
  -H "Authorization: Bearer <Agent 部署令牌>"
```

## 注意

- 自签证书浏览器会提示不安全，测试环境使用。
- `sslip.io` 解析到私网 IP，只适合同一网络/VPN 的测试。
- 生产使用真实 IngressClass、DNS、TLS 和防火墙。
- Agent Ingress 只暴露 nginx 的 80 端口，不暴露平台 actuator/internal。
