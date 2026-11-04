#!/usr/bin/env python3
"""录制农业 Agent 竖屏演示（Playwright 页面视频）。

用法：
  DEMO_WIDGET_KEY=... python3 demo/agri/record_demo.py

输出：
  demo/agri/raw/agri-demo.webm
  可用 ffmpeg 转 mp4（脚本末尾会尝试自动转换）。
"""
import os
import subprocess
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent
BASE = os.environ.get("DEMO_BASE", "http://192.168.64.2:18080")
KEY = os.environ.get("DEMO_WIDGET_KEY", "").strip()
if not KEY:
    key_file = Path("/private/var/folders/v9/_zcwpw5n5kn77p_850hd06br0000gn/T/opencode/demo_widget_key.txt")
    KEY = key_file.read_text().strip() if key_file.exists() else ""
if not KEY:
    raise SystemExit("缺少 DEMO_WIDGET_KEY")

RAW_DIR = ROOT / "raw"
RAW_DIR.mkdir(parents=True, exist_ok=True)
IMAGE = ROOT / "assets" / "tomato-leaf.png"
CHROME = os.environ.get(
    "DEMO_CHROME",
    "/Users/wangpan/Library/Caches/ms-playwright/chromium-1248/chrome-mac-arm64/"
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")


def caption(page, text):
    page.evaluate(
        """(text) => {
          let el = document.getElementById('demo-caption');
          if (!el) {
            el = document.createElement('div');
            el.id = 'demo-caption';
            el.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:99999;' +
              'padding:22px 34px 30px;background:linear-gradient(transparent,rgba(0,0,0,.78));' +
              'color:#fff;font:600 30px/1.5 -apple-system,BlinkMacSystemFont,\"PingFang SC\",sans-serif;' +
              'text-align:center;text-shadow:0 2px 8px #000;pointer-events:none;';
            document.body.appendChild(el);
          }
          el.textContent = text;
        }""", text)


def wait_answer(page, count, min_len=60, timeout=120_000):
    page.wait_for_function(
        """([count, minLen]) => {
          const rows = [...document.querySelectorAll('.w-row.assistant .w-bubble')];
          const last = rows[rows.length - 1];
          return rows.length >= count && last && last.innerText.trim().length >= minLen;
        }""", arg=[count, min_len], timeout=timeout)


def send(page, text):
    box = page.locator('.w-foot textarea')
    box.fill(text)
    page.locator('.w-foot button.el-button--primary').click()


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, executable_path=CHROME)
        context = browser.new_context(
            viewport={"width": 1080, "height": 1920},
            record_video_dir=str(RAW_DIR),
            record_video_size={"width": 1080, "height": 1920},
        )
        page = context.new_page()
        # 开场卡
        page.set_content("""
        <html><body style="margin:0;width:1080px;height:1920px;background:
        radial-gradient(circle at 70% 15%,#35804f 0,#123b2a 45%,#071b13 100%);
        color:#fff;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif;
        display:flex;align-items:center;justify-content:center;text-align:center;">
          <div>
            <div style="font-size:92px;line-height:1.05;font-weight:900;letter-spacing:-3px;">
              农业知识<br/>问答助手
            </div>
            <div style="margin-top:36px;font-size:32px;color:#c8efd4;">157 篇农技资料 · RAG 检索</div>
            <div style="margin-top:18px;font-size:30px;color:#a9d8b8;">图片识别 / 语音提问 / 安全兜底</div>
            <div style="margin-top:74px;display:inline-block;padding:12px 26px;border:2px solid #7ed49a;
                 border-radius:999px;color:#9ee7b3;font-size:24px;">农业 AI 助手 POC 演示</div>
          </div>
        </body></html>""")
        page.wait_for_timeout(4500)

        # 进入挂件
        page.goto(f"{BASE}/widget?key={KEY}", wait_until="domcontentloaded")
        page.wait_for_selector(".w-foot textarea", timeout=30_000)
        page.wait_for_timeout(1800)
        caption(page, "拍一张叶片照片，问一句“怎么防治”")

        # 图片上传 + 提问
        if IMAGE.exists():
            page.locator('input[type="file"]').set_input_files(str(IMAGE))
            page.wait_for_timeout(1800)
            page.wait_for_selector(".w-attach", timeout=15_000)
            caption(page, "上传叶片照片，AI 结合图片识别和知识库回答")
            send(page, "这个叶子是什么病？怎么防治？")
            try:
                wait_answer(page, count=2, min_len=80, timeout=150_000)
            except Exception as e:
                print("image question wait warning:", e)
            page.wait_for_timeout(2500)

        # 文本问题
        caption(page, "再问一个具体病害：番茄灰霉病怎么防治？")
        send(page, "番茄灰霉病怎么防治？")
        wait_answer(page, count=3, min_len=100, timeout=120_000)
        page.wait_for_timeout(3000)

        # 安全兜底
        caption(page, "高风险问题：随便推荐一个农药？")
        send(page, "随便推荐一个农药就行")
        wait_answer(page, count=4, min_len=25, timeout=60_000)
        page.wait_for_timeout(4500)

        caption(page, "只做辅助查询 · 具体诊断和用药请咨询当地农技/植保部门")
        page.wait_for_timeout(3500)

        context.close()
        browser.close()

    videos = sorted(RAW_DIR.glob("*.webm"), key=lambda x: x.stat().st_mtime, reverse=True)
    if not videos:
        raise SystemExit("未生成 webm")
    src = videos[0]
    out = ROOT / "output" / "农业Agent演示-竖屏.mp4"
    out.parent.mkdir(parents=True, exist_ok=True)
    try:
        subprocess.run([
            "ffmpeg", "-y", "-i", str(src),
            "-vf", "scale=1080:1920:force_original_aspect_ratio=decrease,"
                   "pad=1080:1920:(ow-iw)/2:(oh-ih)/2:color=black,fps=30",
            "-c:v", "libx264", "-preset", "medium", "-crf", "20",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out),
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print("VIDEO", out)
    except Exception as e:
        print("ffmpeg convert failed:", e)
        print("RAW", src)


if __name__ == "__main__":
    main()
