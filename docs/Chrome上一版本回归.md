# Chrome 上一版本回归

日期：2026-10-31

## 目标

主 E2E 使用系统 Google Chrome 最新稳定版；本机制允许指定 Chrome for Testing 历史版本做独立回归，不改变默认 Chrome-only 验收范围。

## 准备历史版本

示例（macOS arm64）：

```bash
cd frontend
npx --yes @puppeteer/browsers install chrome@155.0.8059.84 \
  --path /private/tmp/chrome-previous
```

可执行文件：

```text
/private/tmp/chrome-previous/chrome/mac_arm-155.0.8059.84/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing
```

## 运行回归

```bash
cd frontend
CHROME_PREVIOUS_PATH="/private/tmp/chrome-previous/chrome/mac_arm-155.0.8059.84/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing" \
  ./node_modules/.bin/playwright test e2e/smoke.spec.ts --project=chrome-previous
```

或运行完整套件：

```bash
CHROME_PREVIOUS_PATH="..." bash e2e/run-local.sh --project=chrome-previous
```

## 实测结果

Chrome for Testing 155.0.8059.84：

```text
smoke.spec.ts: 7 passed (24.3s)
完整 Chrome 上一版本 E2E: 69 passed / 7 skipped (2.0m)
```

默认 Chrome stable 基线：

```text
完整 Chrome E2E: 69 passed / 7 skipped
```

## 说明

- 默认 `chromium` project 仍使用本机/CI 的 Google Chrome stable。
- 只有设置 `CHROME_PREVIOUS_PATH` 时才会注册 `chrome-previous` project，不影响默认 CI。
- 历史版本下载地址来自 Chrome for Testing；版本号需要显式固定，避免自动漂移。
- 该回归仍是 Chrome，不引入 Edge。
