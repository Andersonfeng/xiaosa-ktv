// ASS/SSA 字幕解析（纯函数，不依赖 DOM）。
//
// 只做 KTV 播放器需要的那一小部分：样式颜色/字号、\pos/\an 位置、
// \kf/\k 逐字时间、\fad 淡入淡出。解析结果交给 tv/index.html 里的
// KaraokeSubtitle 渲染成 HTML 覆盖层，实现「随着原唱逐字变色」。
//
// 在浏览器里挂到 window.KaraokeAss；在 Node 里可以 require 出来单独测试。
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.KaraokeAss = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  function parseTime(value) {
    const s = String(value || '').trim();
    let m = /^(\d+):(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?$/.exec(s);
    if (m) {
      const ms = Number((m[4] || '0').padEnd(3, '0'));
      return Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]) + ms / 1000;
    }
    m = /^(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?$/.exec(s);
    if (m) {
      const ms = Number((m[3] || '0').padEnd(3, '0'));
      return Number(m[1]) * 60 + Number(m[2]) + ms / 1000;
    }
    return 0;
  }

  // ASS 颜色是 &HAABBGGRR&（alpha 00=不透明，FF=全透明）。
  // 返回 { r,g,b,a }，a 是 CSS 用的 0~1 不透明度。
  function assColorToRgb(value) {
    let s = String(value == null ? '' : value).trim();
    s = s.replace(/^&H/i, '').replace(/&$/, '');
    if (!/^[0-9A-Fa-f]+$/.test(s)) return null;
    s = s.padStart(8, '0').slice(-8);
    const a = parseInt(s.slice(0, 2), 16);
    const b = parseInt(s.slice(2, 4), 16);
    const g = parseInt(s.slice(4, 6), 16);
    const r = parseInt(s.slice(6, 8), 16);
    return { r, g, b, a: Math.max(0, Math.min(1, 1 - a / 255)) };
  }

  function splitFields(line, count) {
    const out = [];
    let rest = line;
    for (let i = 0; i < count - 1; i++) {
      const idx = rest.indexOf(',');
      if (idx < 0) { out.push(rest); rest = ''; break; }
      out.push(rest.slice(0, idx));
      rest = rest.slice(idx + 1);
    }
    out.push(rest);
    return out;
  }

  function parseOverrideBlock(content) {
    const out = { karaoke: [] };
    let m;

    const karaokeRe = /\\k([fKo]?)(\d+)/g;
    while ((m = karaokeRe.exec(content))) out.karaoke.push(parseInt(m[2], 10) || 0);

    m = /\\pos\(\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)/.exec(content);
    if (m) out.pos = { x: Number(m[1]), y: Number(m[2]) };

    m = /\\an([1-9])/.exec(content);
    if (m) out.an = parseInt(m[1], 10);

    m = /\\fs(\d+(?:\.\d+)?)/.exec(content);
    if (m) out.fontSize = Number(m[1]);

    m = /\\1c&H([0-9A-Fa-f]+)&?/.exec(content) || /\\c&H([0-9A-Fa-f]+)&?/.exec(content);
    if (m) out.primary = assColorToRgb(m[1]);

    m = /\\2c&H([0-9A-Fa-f]+)&?/.exec(content);
    if (m) out.secondary = assColorToRgb(m[1]);

    m = /\\alpha&H([0-9A-Fa-f]+)&?/.exec(content);
    if (m) out.alpha = parseInt(m[1], 16) / 255;

    m = /\\fad\((\d+),(\d+)\)/.exec(content);
    if (m) { out.fadeIn = Number(m[1]); out.fadeOut = Number(m[2]); }

    m = /\\b([01])/.exec(content);
    if (m) out.bold = m[1] === '1';

    m = /\\i([01])/.exec(content);
    if (m) out.italic = m[1] === '1';

    return out;
  }

  function decodeAssText(text) {
    return String(text || '')
      .replace(/\\N/g, '\n')
      .replace(/\\n/g, '\n')
      .replace(/\\h/g, '\u00A0')
      .replace(/\\([{}])/g, '$1');
  }

  function tokenizeText(text) {
    const tokens = [];
    let i = 0;
    while (i < text.length) {
      if (text[i] === '{') {
        const end = text.indexOf('}', i + 1);
        if (end < 0) {
          tokens.push({ type: 'text', value: text.slice(i) });
          break;
        }
        tokens.push({ type: 'tag', value: text.slice(i + 1, end) });
        i = end + 1;
      } else {
        let end = text.indexOf('{', i);
        if (end < 0) end = text.length;
        tokens.push({ type: 'text', value: text.slice(i, end) });
        i = end;
      }
    }
    return tokens;
  }

  function parseDialogueText(text, start, end) {
    const tokens = tokenizeText(text || '');
    const segments = [];
    const pendingKaraoke = [];
    let pos = null, an = null, fontSize = null;
    let primary = null, secondary = null;
    let fadeIn = 0, fadeOut = 0;
    let bold = null, italic = null;

    for (const token of tokens) {
      if (token.type === 'tag') {
        const tags = parseOverrideBlock(token.value);
        if (tags.karaoke.length) pendingKaraoke.push(...tags.karaoke);
        if (tags.pos) pos = tags.pos;
        if (tags.an) an = tags.an;
        if (tags.fontSize != null) fontSize = tags.fontSize;
        if (tags.primary) primary = tags.primary;
        if (tags.secondary) secondary = tags.secondary;
        if (tags.fadeIn != null) { fadeIn = tags.fadeIn; fadeOut = tags.fadeOut; }
        if (tags.bold != null) bold = tags.bold;
        if (tags.italic != null) italic = tags.italic;
        continue;
      }

      const decoded = decodeAssText(token.value);
      if (!decoded) continue;
      const kdur = pendingKaraoke.length ? pendingKaraoke.shift() : 0;
      segments.push({ text: decoded, kdur, primary, secondary });
    }

    // 计算每个片段的起止时间：\kf 的时长是百分秒，从整句开始时间累加。
    let offset = 0;
    const hasKaraoke = segments.some(s => s.kdur > 0);
    for (let i = 0; i < segments.length; i++) {
      const seg = segments[i];
      seg.start = start + offset / 100;
      if (hasKaraoke) {
        if (seg.kdur > 0) {
          seg.end = seg.start + seg.kdur / 100;
          offset += seg.kdur;
        } else {
          const next = segments.slice(i + 1).find(s => s.kdur > 0);
          seg.end = next ? start + offset / 100 : end;
        }
      } else {
        seg.end = end;
      }
    }

    return { segments, pos, an, fontSize, primary, secondary, fadeIn, fadeOut, bold, italic };
  }

  const DEFAULT_STYLE = {
    fontName: 'Arial',
    fontSize: 46,
    primary: { r: 255, g: 255, b: 255, a: 1 },
    secondary: { r: 255, g: 255, b: 255, a: 1 },
    outline: { r: 32, g: 32, b: 32, a: 1 },
    back: { r: 0, g: 0, b: 0, a: 0.6 },
    bold: true,
    italic: false,
    alignment: 2,
    borderStyle: 1,
    outlineWidth: 2,
    shadow: 0,
    marginL: 40, marginR: 40, marginV: 0,
  };

  function parseStyle(fields, values) {
    const get = name => {
      const i = fields.indexOf(name);
      return i >= 0 ? values[i] : undefined;
    };
    const style = Object.assign({}, DEFAULT_STYLE);
    const name = String(get('name') || '').trim();
    if (name) style.name = name;
    const fontName = get('fontname');
    if (fontName) style.fontName = String(fontName).trim();
    const fontSize = Number(get('fontsize'));
    if (fontSize > 0) style.fontSize = fontSize;

    const primary = assColorToRgb(get('primarycolour'));
    if (primary) style.primary = primary;
    const secondary = assColorToRgb(get('secondarycolour'));
    if (secondary) style.secondary = secondary;
    const outline = assColorToRgb(get('outlinecolour'));
    if (outline) style.outline = outline;
    const back = assColorToRgb(get('backcolour'));
    if (back) style.back = back;

    const bold = get('bold');
    if (bold != null) style.bold = Number(bold) !== 0;
    const italic = get('italic');
    if (italic != null) style.italic = Number(italic) !== 0;
    const alignment = Number(get('alignment'));
    if (alignment > 0) style.alignment = alignment;
    const borderStyle = Number(get('borderstyle'));
    if (borderStyle > 0) style.borderStyle = borderStyle;
    const outlineWidth = Number(get('outline'));
    if (outlineWidth >= 0) style.outlineWidth = outlineWidth;
    const shadow = Number(get('shadow'));
    if (shadow >= 0) style.shadow = shadow;
    const marginL = Number(get('marginl'));
    if (marginL >= 0) style.marginL = marginL;
    const marginR = Number(get('marginr'));
    if (marginR >= 0) style.marginR = marginR;
    const marginV = Number(get('marginv'));
    if (marginV >= 0) style.marginV = marginV;
    return style;
  }

  function parse(text) {
    const lines = String(text || '').replace(/^\uFEFF/, '').split(/\r?\n/);
    const info = {};
    const styles = {};
    const events = [];
    let section = '';
    let styleFormat = null;
    let eventFormat = null;

    for (let raw of lines) {
      const line = raw.trim();
      if (!line || line.startsWith(';')) continue;
      if (line.startsWith('[') && line.endsWith(']')) {
        section = line.slice(1, -1).trim().toLowerCase();
        continue;
      }

      if (section === 'script info') {
        const idx = line.indexOf(':');
        if (idx > 0) info[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
        continue;
      }

      if (section === 'v4+ styles' || section === 'v4 styles') {
        if (/^format\s*:/i.test(line)) {
          styleFormat = line.slice(line.indexOf(':') + 1).split(',').map(s => s.trim().toLowerCase());
          continue;
        }
        if (/^style\s*:/i.test(line) && styleFormat) {
          const values = splitFields(line.slice(line.indexOf(':') + 1).trim(), styleFormat.length);
          const style = parseStyle(styleFormat, values);
          if (style.name) styles[style.name] = style;
        }
        continue;
      }

      if (section === 'events') {
        if (/^format\s*:/i.test(line)) {
          eventFormat = line.slice(line.indexOf(':') + 1).split(',').map(s => s.trim().toLowerCase());
          continue;
        }
        if (/^dialogue\s*:/i.test(line) && eventFormat) {
          const values = splitFields(line.slice(line.indexOf(':') + 1).trim(), eventFormat.length);
          const get = name => {
            const i = eventFormat.indexOf(name);
            return i >= 0 ? values[i] : undefined;
          };
          const start = parseTime(get('start'));
          const end = parseTime(get('end'));
          const textValue = get('text') || '';
          const parsed = parseDialogueText(textValue, start, end);
          const styleName = String(get('style') || '').trim();
          const style = styles[styleName] || DEFAULT_STYLE;
          events.push({
            layer: Number(get('layer')) || 0,
            start,
            end,
            styleName,
            style,
            pos: parsed.pos,
            an: parsed.an || style.alignment || 2,
            fontSize: parsed.fontSize || style.fontSize,
            primary: parsed.primary || style.primary,
            secondary: parsed.secondary || style.secondary,
            outline: style.outline,
            outlineWidth: style.outlineWidth,
            bold: parsed.bold == null ? style.bold : parsed.bold,
            italic: parsed.italic == null ? style.italic : parsed.italic,
            fadeIn: parsed.fadeIn || 0,
            fadeOut: parsed.fadeOut || 0,
            segments: parsed.segments,
            text: parsed.segments.map(s => s.text).join(''),
          });
        }
      }
    }

    const playResX = Number(info.PlayResX) || 1280;
    const playResY = Number(info.PlayResY) || 720;
    events.sort((a, b) => a.start - b.start || a.layer - b.layer);
    events.forEach((e, i) => { e.idx = i; });
    return { playResX, playResY, styles, events };
  }

  return { parse, parseTime, assColorToRgb };
});
