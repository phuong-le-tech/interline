(() => {
  const parse = (c) => {
    const m = c.match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    const p = m[1].split(/[,\s\/]+/).filter(Boolean).map(Number);
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  };
  const lum = ({ r, g, b }) => {
    const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
  };
  const ratio = (a, b) => { const l1 = lum(a), l2 = lum(b); const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1]; return (hi + 0.05) / (lo + 0.05); };
  const bgOf = (el) => {
    let node = el;
    while (node && node !== document.documentElement) {
      const c = parse(getComputedStyle(node).backgroundColor);
      if (c && c.a > 0.85) return c;
      node = node.parentElement;
    }
    const b = parse(getComputedStyle(document.body).backgroundColor);
    return b && b.a > 0.85 ? b : { r: 255, g: 255, b: 255, a: 1 };
  };
  const visible = (el) => {
    const r = el.getBoundingClientRect();
    const s = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0';
  };

  const report = { contrast: [], targets: [], overflow: [], iconButtons: [], focus: [], unlabeled: [] };

  // 1. text contrast
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const seen = new Set();
  let n;
  while ((n = walker.nextNode())) {
    const t = n.textContent.trim();
    if (!t || t.length < 2) continue;
    const el = n.parentElement;
    if (!el || seen.has(el) || !visible(el)) continue;
    seen.add(el);
    const cs = getComputedStyle(el);
    const fg = parse(cs.color);
    if (!fg) continue;
    const bg = bgOf(el);
    const size = parseFloat(cs.fontSize);
    const bold = parseInt(cs.fontWeight, 10) >= 700;
    const large = size >= 24 || (size >= 18.66 && bold);
    const need = large ? 3 : 4.5;
    const cr = ratio(fg, bg);
    if (cr < need) report.contrast.push({ text: t.slice(0, 42), ratio: +cr.toFixed(2), need, size, cls: el.className || el.tagName });
  }

  // 2. tap targets
  document.querySelectorAll('button, a, [role=button], input, select, textarea').forEach((el) => {
    if (!visible(el)) return;
    const r = el.getBoundingClientRect();
    if (r.width < 44 || r.height < 44) {
      report.targets.push({ label: (el.getAttribute('aria-label') || el.textContent || el.tagName).trim().slice(0, 32), w: Math.round(r.width), h: Math.round(r.height) });
    }
  });

  // 3. horizontal overflow / clipped text
  document.querySelectorAll('*').forEach((el) => {
    if (!visible(el)) return;
    const cs = getComputedStyle(el);
    if (el.scrollWidth > el.clientWidth + 1 && cs.overflowX !== 'auto' && cs.overflowX !== 'scroll' && cs.textOverflow !== 'ellipsis') {
      if (el.children.length === 0 || el.scrollWidth - el.clientWidth > 8) {
        report.overflow.push({ cls: (el.className || el.tagName).toString().slice(0, 40), over: el.scrollWidth - el.clientWidth });
      }
    }
  });

  // 4. icon-only buttons without accessible name
  document.querySelectorAll('button').forEach((b) => {
    if (!visible(b)) return;
    const name = (b.getAttribute('aria-label') || b.getAttribute('title') || b.textContent || '').trim();
    if (!name) report.iconButtons.push({ html: b.outerHTML.slice(0, 70) });
  });

  // 5. focus visibility: :focus-visible does not fire on programmatic focus, so read the
  // stylesheet rule and confirm a real ring exists, instead of focusing each element.
  const cssText = [...document.styleSheets].flatMap((s) => { try { return [...s.cssRules].map((r) => r.cssText); } catch (e) { return []; } }).join("\n");
  const hasFocusVisibleRule = /:focus-visible[^{]*\{[^}]*(outline|box-shadow)/.test(cssText);
  if (!hasFocusVisibleRule) report.focus.push({ label: "no :focus-visible rule with an outline or ring" });

  // 6. form controls without label
  document.querySelectorAll('input, textarea, select').forEach((el) => {
    const labelled = el.getAttribute('aria-label') || el.getAttribute('aria-labelledby') || el.id && document.querySelector(`label[for="${el.id}"]`);
    if (!labelled) report.unlabeled.push(el.outerHTML.slice(0, 60));
  });

  const trim = (arr) => arr.slice(0, 12);
  return JSON.stringify({
    contrastFailures: trim(report.contrast),
    contrastCount: report.contrast.length,
    smallTargets: trim(report.targets),
    smallTargetCount: report.targets.length,
    overflow: trim(report.overflow),
    iconButtonsWithoutName: trim(report.iconButtons),
    missingFocusRing: trim(report.focus),
    unlabeledControls: trim(report.unlabeled),
  });
})()
