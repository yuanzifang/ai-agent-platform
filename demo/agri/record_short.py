#!/usr/bin/env python3
"""录制 45-70 秒小红书 4:5 演示。

只使用文本问答 + 安全兜底，避免图片识别等待导致视频过长。
"""
import os
import subprocess
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent
BASE = os.environ.get("DEMO_BASE", "http://192.168.64.2:18080")
KEY = os.environ.get("DEMO_WIDGET_KEY", "").strip()
if not KEY:
    KEY = Path("/private/var/folders/v9/_zcwpw5n5kn77p_850hd06br0000gn/T/opencode/demo_widget_key.txt").read_text().strip()
RAW = ROOT / "raw-short"
RAW.mkdir(parents=True, exist_ok=True)
CHROME = "/Users/wangpan/Library/Caches/ms-playwright/chromium-1248/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"


def caption(page, text):
    page.evaluate("""(text) => {
      let el = document.getElementById('demo-caption');
      if (!el) {
        el = document.createElement('div'); el.id='demo-caption';
        el.style.cssText='position:fixed;left:0;right:0;bottom:0;z-index:99999;'+
          'padding:26px 32px 34px;background:linear-gradient(transparent,rgba(0,0,0,.82));'+
          'color:#fff;font:700 30px/1.45 -apple-system,BlinkMacSystemFont,\"PingFang SC\",sans-serif;'+
          'text-align:center;text-shadow:0 2px 8px #000;pointer-events:none;';
        document.body.appendChild(el);
      }
      el.textContent=text;
    }""", text)


def wait_rows(page, count, min_len=40, timeout=120_000):
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
        context = browser.new_context(viewport={"width": 1080, "height": 1350},
                                      record_video_dir=str(RAW),
                                      record_video_size={"width": 1080, "height": 1350})
        page = context.new_page()
        page.set_content("""
        <html><body style="margin:0;width:1080px;height:1350px;background:
        radial-gradient(circle at 70% 15%,#35804f 0,#123b2a 45%,#071b13 100%);
        color:#fff;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif;
        display:flex;align-items:center;justify-content:center;text-align:center;">
          <div>
            <div style="font-size:86px;line-height:1.05;font-weight:900;letter-spacing:-3px;">
              农业知识<br/>问答助手
            </div>
            <div style="margin-top:32px;font-size:30px;color:#c8efd4;">157 篇农技资料 · RAG 检索</div>
            <div style="margin-top:16px;font-size:28px;color:#a9d8b8;">图片识别 / 语音提问 / 安全兜底</div>
            <div style="margin-top:66px;display:inline-block;padding:12px 26px;border:2px solid #7ed49a;
                 border-radius:999px;color:#9ee7b3;font-size:22px;">农业 AI 助手 POC 演示</div>
          </div>
        </body></html>""")
        page.wait_for_timeout(3500)

        page.goto(f"{BASE}/widget?key={KEY}", wait_until="domcontentloaded")
        page.wait_for_selector(".w-foot textarea", timeout=30_000)
        page.add_style_tag(content="""
          .w-name{font-size:34px!important}.w-status{font-size:20px!important}
          .w-body{padding:24px 18px!important}
          .w-bubble{font-size:28px!important;line-height:1.62!important;padding:18px 20px!important;max-width:90%!important}
          .w-foot{padding:16px!important;gap:12px!important}
          .w-foot textarea{font-size:25px!important;line-height:1.45!important;padding:14px!important}
          .w-tool{width:54px!important;height:54px!important;flex:0 0 54px!important}
          .w-foot button.el-button--primary{font-size:23px!important;height:54px!important;padding:0 22px!important}
          .w-head{padding:18px!important}
        """)
        page.wait_for_timeout(1800)
        caption(page, "农户问：番茄灰霉病怎么防治？")
        send(page, "番茄灰霉病怎么防治？")
        wait_rows(page, count=2, min_len=100, timeout=120_000)
        page.wait_for_timeout(5000)

        caption(page, "再问：随便推荐一个农药就行？")
        send(page, "随便推荐一个农药就行")
        wait_rows(page, count=3, min_len=20, timeout=60_000)
        page.wait_for_timeout(4000)

        caption(page, "AI 只做资料辅助，不替代当地农技/植保部门")
        page.wait_for_timeout(3500)
        context.close()
        browser.close()

    videos = sorted(RAW.glob("*.webm"), key=lambda x: x.stat().st_mtime, reverse=True)
    src = videos[0]
    out = ROOT / "output" / "农业Agent演示-小红书4x5.mp4"
    out.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        "ffmpeg", "-y", "-i", str(src),
        "-vf", "scale=1080:1350:force_original_aspect_ratio=decrease,"
               "pad=1080:1350:(ow-iw)/2:(oh-ih)/2:color=black,fps=30",
        "-c:v", "libx264", "-preset", "medium", "-crf", "20",
        "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out),
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(out)


if __name__ == "__main__":
    main()
