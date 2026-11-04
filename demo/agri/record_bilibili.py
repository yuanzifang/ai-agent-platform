#!/usr/bin/env python3
"""录制 B站 16:9 演示：图片提问 + 文本提问 + 安全兜底。"""
import os
import subprocess
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent
BASE = os.environ.get("DEMO_BASE", "http://192.168.64.2:18080")
KEY = os.environ.get("DEMO_WIDGET_KEY", "").strip()
if not KEY:
    KEY = Path("/private/var/folders/v9/_zcwpw5n5kn77p_850hd06br0000gn/T/opencode/demo_widget_key.txt").read_text().strip()
IMAGE = ROOT / "assets" / "tomato-leaf.png"
RAW = ROOT / "raw-bilibili"
RAW.mkdir(parents=True, exist_ok=True)
CHROME = "/Users/wangpan/Library/Caches/ms-playwright/chromium-1248/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"


def caption(page, text):
    page.evaluate("""(text) => {
      let el = document.getElementById('demo-caption');
      if (!el) {
        el = document.createElement('div'); el.id='demo-caption';
        el.style.cssText='position:fixed;left:0;right:0;bottom:0;z-index:99999;'+
          'padding:16px 24px 22px;background:linear-gradient(transparent,rgba(0,0,0,.82));'+
          'color:#fff;font:700 22px/1.4 -apple-system,BlinkMacSystemFont,\"PingFang SC\",sans-serif;'+
          'text-align:center;text-shadow:0 2px 6px #000;pointer-events:none;';
        document.body.appendChild(el);
      }
      el.textContent=text;
    }""", text)


def wait_rows(page, count, min_len=40, timeout=180_000):
    page.wait_for_function("""([count,minLen]) => {
      const rows=[...document.querySelectorAll('.w-row.assistant .w-bubble')];
      const last=rows[rows.length-1];
      return rows.length>=count && last && last.innerText.trim().length>=minLen;
    }""", arg=[count, min_len], timeout=timeout)


def send(page, text):
    page.locator('.w-foot textarea').fill(text)
    page.locator('.w-foot button.el-button--primary').click()


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, executable_path=CHROME)
        context = browser.new_context(viewport={"width": 1280, "height": 720},
                                      record_video_dir=str(RAW),
                                      record_video_size={"width": 1280, "height": 720})
        page = context.new_page()
        page.set_content("""
        <html><body style="margin:0;width:1280px;height:720px;background:
        radial-gradient(circle at 72% 18%,#35804f 0,#123b2a 45%,#071b13 100%);
        color:#fff;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif;
        display:flex;align-items:center;justify-content:center;text-align:center;">
          <div>
            <div style="font-size:72px;line-height:1;font-weight:900;letter-spacing:-2px;">农业知识问答助手</div>
            <div style="margin-top:24px;font-size:24px;color:#c8efd4;">157 篇农技资料 · RAG 检索 · 图片识别 · 语音提问</div>
            <div style="margin-top:44px;display:inline-block;padding:10px 24px;border:2px solid #7ed49a;
                 border-radius:999px;color:#9ee7b3;font-size:18px;">农业 AI 助手 POC 演示</div>
          </div>
        </body></html>""")
        page.wait_for_timeout(3000)

        page.goto(f"{BASE}/widget?key={KEY}", wait_until="domcontentloaded")
        page.wait_for_selector(".w-foot textarea", timeout=30_000)
        page.add_style_tag(content="""
          .w-body{padding:18px 20px!important}
          .w-bubble{font-size:18px!important;line-height:1.55!important;padding:14px 16px!important;max-width:86%!important}
          .w-foot{padding:12px!important;gap:9px!important}
          .w-foot textarea{font-size:18px!important;padding:10px!important}
          .w-tool{width:40px!important;height:40px!important;flex:0 0 40px!important}
          .w-foot button.el-button--primary{font-size:17px!important;height:40px!important}
        """)
        page.wait_for_timeout(1500)
        caption(page, "先上传一张叶片照片，问：这个叶子是什么病？")
        if IMAGE.exists():
            page.locator('input[type="file"]').set_input_files(str(IMAGE))
            page.wait_for_timeout(1000)
            send(page, "这个叶子是什么病？怎么防治？")
            try:
                wait_rows(page, count=2, min_len=80, timeout=180_000)
            except Exception as e:
                print("image wait:", e)
            page.wait_for_timeout(3000)

        caption(page, "再问：番茄灰霉病怎么防治？")
        send(page, "番茄灰霉病怎么防治？")
        wait_rows(page, count=3, min_len=100, timeout=120_000)
        page.wait_for_timeout(3000)

        caption(page, "高风险问题：随便推荐一个农药？")
        send(page, "随便推荐一个农药就行")
        wait_rows(page, count=4, min_len=20, timeout=60_000)
        page.wait_for_timeout(3500)

        caption(page, "AI 辅助查询，不替代当地农技/植保部门")
        page.wait_for_timeout(3000)
        context.close()
        browser.close()

    videos = sorted(RAW.glob("*.webm"), key=lambda x: x.stat().st_mtime, reverse=True)
    src = videos[0]
    out = ROOT / "output" / "农业Agent演示-B站16x9.mp4"
    subprocess.run([
        "ffmpeg", "-y", "-i", str(src),
        "-vf", "scale=1280:720:force_original_aspect_ratio=decrease,"
               "pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black,fps=30",
        "-c:v", "libx264", "-preset", "medium", "-crf", "20",
        "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out),
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(out)


if __name__ == "__main__":
    main()
