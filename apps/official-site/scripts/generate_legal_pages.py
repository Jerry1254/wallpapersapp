#!/usr/bin/env python3
"""Generate website shells that read the published legal documents from the API."""

from __future__ import annotations

import html
from pathlib import Path


DIST = Path(__file__).resolve().parents[1] / "dist"


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
  <link rel="stylesheet" href="/styles.css?v=20261010-download">
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
      <a href="/#download">App 下载</a>
      <a href="/privacy/"{' class="active"' if title == '隐私政策' else ''}>隐私政策</a>
      <a href="/terms/"{' class="active"' if title == '用户协议' else ''}>用户协议</a>
    </nav>
    <a class="header-contact header-download" href="/#download">App 下载 <span aria-hidden="true">↓</span></a>
  </header>

  <main class="legal-shell">
    <div class="legal-hero">
      <p class="eyebrow">倾境壁纸 · 法律文件</p>
      <h1 id="legal-title">{title}</h1>
      <p id="legal-intro" class="legal-intro">{html.escape(introduction)}</p>
      <div class="legal-meta" id="legal-meta" aria-live="polite">
      </div>
    </div>

    <div class="legal-layout">
      <aside class="legal-toc" aria-label="目录">
        <strong>目录</strong>
        <div id="legal-toc">{toc}</div>
      </aside>
      <article class="legal-content">
        <div id="legal-content">{content}</div>
        <p id="legal-status" role="status"></p>
        <button id="legal-retry" type="button" hidden>重新加载</button>
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
      <a href="/#download">App 下载</a>
      <a href="/privacy/">隐私政策</a>
      <a href="/terms/">用户协议</a>
      <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">闽ICP备2026036761号-1</a>
      <a href="mailto:qingjingwallpaper@126.com">qingjingwallpaper@126.com</a>
    </div>
    <p>© 2026 倾境壁纸平台</p>
  </footer>
<script>
{legal_script("privacy" if title == "隐私政策" else "terms")}
</script>
</body>
</html>
'''


def legal_script(key: str) -> str:
    # All admin-authored content is rendered as text, never HTML.
    script = r"""
(() => {
  const key = '__KEY__', cacheKey = 'qingjing-legal-' + key;
  const el = id => document.getElementById(id);
  function validate(manifest) {
    if (!manifest || !Array.isArray(manifest.items) || manifest.items.length !== 2) throw Error('invalid');
    const item = manifest.items.find(d => d.key === key), c = item && item.content;
    const text = (v, max) => typeof v === 'string' && v.trim().length > 0 && v.length <= max;
    if (!item || !Number.isSafeInteger(item.revision) || item.revision < 1 || !c ||
        !text(c.title, 100) || !text(c.effectiveDate, 40) || !text(c.introduction, 10000) ||
        !Array.isArray(c.sections) || c.sections.length < 1 || c.sections.length > 60 ||
        !c.sections.every(s => text(s.title, 200) && text(s.body, 20000))) throw Error('invalid');
    return item;
  }
  function render(item) {
    const c = item.content;
    el('legal-title').textContent = c.title;
    document.title = c.title + '｜倾境壁纸';
    el('legal-intro').textContent = c.introduction;
    el('legal-meta').textContent = '版本：' + item.revision + '　生效日期：' + c.effectiveDate;
    el('legal-toc').replaceChildren(); el('legal-content').replaceChildren();
    c.sections.forEach((s, i) => {
      const section = document.createElement('section'); section.className = 'legal-section'; section.id = 'section-' + i;
      const heading = document.createElement('h2'); heading.textContent = s.title;
      const body = document.createElement('p'); body.textContent = s.body;
      body.style.whiteSpace = 'pre-wrap'; body.style.overflowWrap = 'anywhere';
      section.append(heading, body); el('legal-content').append(section);
      const link = document.createElement('a'); link.href = '#' + section.id; link.textContent = s.title;
      el('legal-toc').append(link);
    });
  }
  async function load() {
    el('legal-retry').hidden = true; el('legal-status').textContent = '正在加载…';
    const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 8000);
    try {
      const response = await fetch('https://wallpaper.biguo66.top/api/v1/public/legal-documents', {
        credentials: 'omit', cache: 'no-store', redirect: 'error', signal: controller.signal,
        headers: { Accept: 'application/json' }
      });
      if (!response.ok) throw Error('unavailable');
      const body = await response.text(); if (body.length > 500000) throw Error('too large');
      const manifest = JSON.parse(body); render(validate(manifest));
      try { localStorage.setItem(cacheKey, body); } catch (_) {}
      el('legal-status').textContent = '';
    } catch (_) {
      let cached = false;
      try { const body = localStorage.getItem(cacheKey); if (body && body.length <= 500000) {
        render(validate(JSON.parse(body))); cached = true;
      }} catch (_) {}
      if (!cached) el('legal-intro').textContent = '协议暂时无法加载，请检查网络后重试。';
      el('legal-status').textContent = cached ? '暂时无法连接服务器，当前展示上次获取的已发布内容。' : '暂时无法获取已发布内容。';
      el('legal-retry').hidden = false;
    } finally { clearTimeout(timer); }
  }
  el('legal-retry').addEventListener('click', load); load();
})();
"""
    return script.replace('__KEY__', key)


def main() -> None:

    privacy_dir = DIST / "privacy"
    terms_dir = DIST / "terms"
    privacy_dir.mkdir(parents=True, exist_ok=True)
    terms_dir.mkdir(parents=True, exist_ok=True)
    privacy_dir.joinpath("index.html").write_text(
        render_page(
            title="隐私政策",
            introduction="正在加载已发布的协议…",
            sections=[],
            canonical="https://biguo66.top/privacy/",
        ),
        encoding="utf-8",
    )
    terms_dir.joinpath("index.html").write_text(
        render_page(
            title="用户协议",
            introduction="正在加载已发布的协议…",
            sections=[],
            canonical="https://biguo66.top/terms/",
        ),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
