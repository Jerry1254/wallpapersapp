#!/usr/bin/env python3
"""Generate the official website legal pages from the App policy source."""

from __future__ import annotations

import html
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
POLICY_SOURCE = ROOT / "apps/mobile/lib/privacy/policies.dart"
DIST = Path(__file__).resolve().parents[1] / "dist"


def extract_document(source: str, constant: str, next_constant: str | None) -> tuple[str, list[tuple[str, str]]]:
    start = source.index(f"const {constant} = PolicyDocument(")
    end = source.index(f"const {next_constant} = PolicyDocument(") if next_constant else len(source)
    block = source[start:end]

    introduction_match = re.search(r"introduction:\s*'(?P<value>.*?)',\s*sections:", block, re.S)
    if not introduction_match:
        raise RuntimeError(f"Could not parse {constant} introduction")

    sections = re.findall(
        r"PolicySection\(\s*'(?P<title>.*?)',\s*'(?P<body>.*?)',\s*\)",
        block,
        re.S,
    )
    if not sections:
        raise RuntimeError(f"Could not parse {constant} sections")

    def decode(value: str) -> str:
        return value.replace(r"\n", "\n").replace(r"\'", "'")

    return decode(introduction_match.group("value")), [
        (decode(title), decode(body)) for title, body in sections
    ]


def paragraphs(body: str) -> str:
    return "\n".join(
        f"<p>{html.escape(part).replace(chr(10), '<br>')}</p>"
        for part in body.split("\n\n")
        if part.strip()
    )


def render_page(*, title: str, introduction: str, sections: list[tuple[str, str]], canonical: str) -> str:
    toc = "\n".join(
        f'<a href="#section-{index}">{html.escape(section_title)}</a>'
        for index, (section_title, _) in enumerate(sections, 1)
    )
    content = "\n".join(
        f'''<section class="legal-section" id="section-{index}">
          <h2>{html.escape(section_title)}</h2>
          {paragraphs(body)}
        </section>'''
        for index, (section_title, body) in enumerate(sections, 1)
    )
    other_path = "/terms/" if title == "隐私政策" else "/privacy/"
    other_label = "用户协议" if title == "隐私政策" else "隐私政策"
    description = "倾境壁纸隐私政策" if title == "隐私政策" else "倾境壁纸用户协议"

    return f'''<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="theme-color" content="#ffffff">
  <meta name="description" content="{description}">
  <link rel="canonical" href="{canonical}">
  <link rel="icon" href="/favicon.svg" type="image/svg+xml">
  <link rel="stylesheet" href="/styles.css">
  <title>{title}｜倾境壁纸</title>
</head>
<body class="legal-page">
  <header class="site-header">
    <a class="brand" href="/" aria-label="倾境壁纸首页">
      <span class="brand-mark" aria-hidden="true"><span></span></span>
      <span>倾境壁纸</span>
    </a>
    <nav class="top-nav" aria-label="主要导航">
      <a href="/">产品</a>
      <a href="/privacy/"{' class="active"' if title == '隐私政策' else ''}>隐私政策</a>
      <a href="/terms/"{' class="active"' if title == '用户协议' else ''}>用户协议</a>
    </nav>
    <a class="header-contact" href="mailto:qingjingwallpaper@126.com">联系我们</a>
  </header>

  <main class="legal-shell">
    <div class="legal-hero">
      <p class="eyebrow">倾境壁纸 · 法律文件</p>
      <h1>{title}</h1>
      <p class="legal-intro">{html.escape(introduction)}</p>
      <div class="legal-meta">
        <span>版本：2026-09-28-v3</span>
        <span>生效日期：2026年9月28日</span>
      </div>
    </div>

    <div class="legal-layout">
      <aside class="legal-toc" aria-label="目录">
        <strong>目录</strong>
        {toc}
      </aside>
      <article class="legal-content">
        {content}
        <div class="legal-next">
          <span>继续阅读</span>
          <a href="{other_path}">{other_label}<span aria-hidden="true">→</span></a>
        </div>
      </article>
    </div>
  </main>

  <footer class="site-footer">
    <div><strong>倾境壁纸</strong><span>让每一次点亮屏幕，都有喜欢的画面。</span></div>
    <div class="footer-links">
      <a href="/privacy/">隐私政策</a>
      <a href="/terms/">用户协议</a>
      <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">闽ICP备2026036761号-1</a>
      <a href="mailto:qingjingwallpaper@126.com">qingjingwallpaper@126.com</a>
    </div>
    <p>© 2026 倾境壁纸平台</p>
  </footer>
</body>
</html>
'''


def main() -> None:
    source = POLICY_SOURCE.read_text(encoding="utf-8")
    privacy_intro, privacy_sections = extract_document(source, "privacyPolicy", "userAgreement")
    terms_intro, terms_sections = extract_document(source, "userAgreement", None)

    privacy_dir = DIST / "privacy"
    terms_dir = DIST / "terms"
    privacy_dir.mkdir(parents=True, exist_ok=True)
    terms_dir.mkdir(parents=True, exist_ok=True)
    privacy_dir.joinpath("index.html").write_text(
        render_page(
            title="隐私政策",
            introduction=privacy_intro,
            sections=privacy_sections,
            canonical="https://biguo66.top/privacy/",
        ),
        encoding="utf-8",
    )
    terms_dir.joinpath("index.html").write_text(
        render_page(
            title="用户协议",
            introduction=terms_intro,
            sections=terms_sections,
            canonical="https://biguo66.top/terms/",
        ),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
