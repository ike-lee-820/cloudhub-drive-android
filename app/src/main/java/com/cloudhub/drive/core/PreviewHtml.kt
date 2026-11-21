package com.cloudhub.drive.core

import org.json.JSONObject

object PreviewHtml {

    fun modeFor(name: String): String = when (Util.ext(name)) {
        "js" -> "javascript"
        "ts" -> "javascript"
        "json" -> "javascript"
        "html", "htm", "xml" -> "xml"
        "css" -> "css"
        "scss", "less" -> "css"
        "py" -> "python"
        "go" -> "go"
        "rs" -> "rust"
        "java" -> "clike"
        "kt", "swift", "dart", "groovy" -> "clike"
        "c", "cpp", "h", "cs" -> "clike"
        "php" -> "php"
        "rb" -> "ruby"
        "lua" -> "lua"
        "sql" -> "sql"
        "sh" -> "shell"
        "md" -> "markdown"
        "yaml", "yml" -> "yaml"
        else -> ""
    }

    private fun htmlText(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    fun build(
        node: Node,
        previewUrl: String,
        downloadUrl: String,
        directUrl: String,
        editable: Boolean
    ): String {
        val category = Util.category(node.name, node.mime)
        return TEMPLATE
            .replace("__TITLE__", htmlText(node.name))
            .replace("__SIZE_TEXT__", htmlText(Util.size(node.size)))
            .replace("__PREVIEW__", JSONObject.quote(previewUrl))
            .replace("__DOWNLOAD__", JSONObject.quote(downloadUrl))
            .replace("__DIRECT__", JSONObject.quote(directUrl))
            .replace("__NAME__", JSONObject.quote(node.name))
            .replace("__CATEGORY__", JSONObject.quote(category))
            .replace("__MODE__", JSONObject.quote(modeFor(node.name)))
            .replace("__EDITABLE__", if (editable) "true" else "false")
    }

    private val TEMPLATE = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>__TITLE__</title>
<style>
:root { color-scheme: light; --accent: #0A84FF; --bg: #F2F2F7; --card: #FFFFFF; --text: #1C1C1E; --sub: #6E6E73; --line: rgba(60,60,67,0.12); }
* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
html, body { margin: 0; padding: 0; background: var(--bg); color: var(--text); font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif; }
body { padding: 16px 14px 32px; }
.card { background: var(--card); border-radius: 18px; padding: 16px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); margin-bottom: 14px; }
h1 { font-size: 22px; line-height: 1.25; margin: 0 0 6px; font-weight: 700; letter-spacing: -0.4px; }
.meta { color: var(--sub); font-size: 13px; }
.row { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 14px; }
button, .btn { appearance: none; border: 0; border-radius: 12px; background: var(--accent); color: #fff; font-size: 15px; font-weight: 600; padding: 11px 18px; text-decoration: none; display: inline-block; }
button.secondary, .btn.secondary { background: rgba(10,132,255,0.12); color: var(--accent); }
button:disabled { opacity: 0.5; }
img.preview { display: block; max-width: 100%; border-radius: 12px; background: #fff; }
video, audio { width: 100%; border-radius: 12px; background: #000; }
iframe { width: 100%; min-height: 70vh; border: 0; border-radius: 12px; background: #fff; }
canvas { display: block; max-width: 100%; margin: 0 auto 14px; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.12); }
table { border-collapse: collapse; width: 100%; font-size: 13px; }
td, th { border: 1px solid var(--line); padding: 6px 8px; text-align: left; }
.sheet-title { font-size: 15px; font-weight: 600; margin: 18px 0 8px; }
.doc { font-size: 15px; line-height: 1.65; overflow-wrap: anywhere; }
.doc img { max-width: 100%; }
.hint { color: var(--sub); font-size: 13px; line-height: 1.5; margin-top: 10px; }
.CodeMirror { height: auto; min-height: 55vh; border: 1px solid var(--line); border-radius: 12px; font-size: 13px; }
textarea.raw { width: 100%; min-height: 55vh; border: 1px solid var(--line); border-radius: 12px; padding: 12px; font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; font-size: 13px; display: none; }
.status { color: var(--sub); font-size: 13px; min-height: 18px; margin-top: 8px; }
.spinner { color: var(--sub); font-size: 14px; }
</style>
</head>
<body>
<div class="card">
  <h1 id="title">__TITLE__</h1>
  <div class="meta" id="meta">__SIZE_TEXT__</div>
  <div class="row">
    <a class="btn secondary" id="dl" href="#">下载文件</a>
    <button id="save" type="button">保存</button>
  </div>
  <div class="status" id="status"></div>
</div>
<div class="card" id="stage"><div class="spinner">正在准备预览内容</div></div>

<script>
var P = __PREVIEW__;
var D = __DOWNLOAD__;
var DIRECT = __DIRECT__;
var NAME = __NAME__;
var CATEGORY = __CATEGORY__;
var MODE = __MODE__;
var EDITABLE = __EDITABLE__;
var CDN = {
  mammoth: [
    "https://cdnjs.cloudflare.com/ajax/libs/mammoth/1.6.0/mammoth.browser.min.js",
    "https://cdn.jsdelivr.net/npm/mammoth@1.6.0/mammoth.browser.min.js"
  ],
  xlsx: [
    "https://cdnjs.cloudflare.com/ajax/libs/xlsx/0.18.5/xlsx.full.min.js",
    "https://cdn.jsdelivr.net/npm/xlsx@0.18.5/dist/xlsx.full.min.js"
  ],
  pdf: [
    "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js",
    "https://cdn.jsdelivr.net/npm/pdfjs-dist@3.11.174/build/pdf.min.js"
  ],
  cm: [
    "https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/codemirror.min.js",
    "https://cdn.jsdelivr.net/npm/codemirror@5.65.16/lib/codemirror.js"
  ],
  cmCss: [
    "https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/codemirror.min.css",
    "https://cdn.jsdelivr.net/npm/codemirror@5.65.16/lib/codemirror.css"
  ]
};

function q(id) { return document.getElementById(id); }
function setStatus(t) { q('status').textContent = t || ''; }
function notify(t) { setStatus(t); if (window.AndroidBridge && window.AndroidBridge.notify) window.AndroidBridge.notify(t); }
function loadScript(urls, i, cb) {
  if (i >= urls.length) { cb(false); return; }
  var s = document.createElement('script');
  s.src = urls[i];
  s.onload = function () { cb(true); };
  s.onerror = function () { loadScript(urls, i + 1, cb); };
  document.head.appendChild(s);
}
function loadCss(urls, i) {
  if (i >= urls.length) return;
  var l = document.createElement('link');
  l.rel = 'stylesheet'; l.href = urls[i];
  l.onerror = function () { loadCss(urls, i + 1); };
  document.head.appendChild(l);
}
function fallback(msg) {
  q('stage').innerHTML = '<div class="hint">' + (msg || '当前类型暂不支持在线预览') + '</div>' +
    '<div class="row"><a class="btn secondary" href="' + D + '">下载文件</a></div>';
}
function sanitize(html) {
  var doc = new DOMParser().parseFromString(html, 'text/html');
  var bad = doc.querySelectorAll('script,style,iframe,object,embed,link,meta');
  for (var i = 0; i < bad.length; i++) { if (bad[i].parentNode) bad[i].parentNode.removeChild(bad[i]); }
  var all = doc.body.querySelectorAll('*');
  for (var j = 0; j < all.length; j++) {
    var el = all[j];
    var attrs = Array.prototype.slice.call(el.attributes);
    attrs.forEach(function (a) {
      var n = a.name.toLowerCase();
      var v = (a.value || '').trim().toLowerCase();
      if (n.indexOf('on') === 0 || n === 'srcdoc' || (n === 'href' && v.indexOf('javascript:') === 0)) el.removeAttribute(a.name);
    });
  }
  return doc.body.innerHTML;
}
function escapeHtml(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
function mediaTag(tag) {
  q('stage').innerHTML = '<' + tag + ' controls playsinline preload="metadata" src="' + P + '"></' + tag + '>';
  q('save').style.display = 'none';
  setStatus('如无法播放，请使用下载');
}
function renderPdf(bytes) {
  if (!window.pdfjsLib) { fallback('PDF 组件加载失败'); return; }
  window.pdfjsLib.GlobalWorkerOptions.workerSrc = CDN.pdf[0].replace('pdf.min.js', 'pdf.worker.min.js');
  var task = window.pdfjsLib.getDocument({ data: bytes });
  task.promise.then(function (pdf) {
    var stage = q('stage'); stage.innerHTML = '';
    var chain = Promise.resolve();
    for (var i = 1; i <= pdf.numPages; i++) {
      (function (num) {
        chain = chain.then(function () {
          return pdf.getPage(num).then(function (page) {
            var scale = Math.min(1.6, (window.innerWidth - 60) / page.getViewport({ scale: 1 }).width);
            var vp = page.getViewport({ scale: scale });
            var c = document.createElement('canvas');
            c.width = Math.floor(vp.width); c.height = Math.floor(vp.height);
            stage.appendChild(c);
            return page.render({ canvasContext: c.getContext('2d'), viewport: vp }).promise;
          });
        });
      })(i);
    }
    chain.then(function () { setStatus('共 ' + pdf.numPages + ' 页'); }).catch(function () { fallback('PDF 渲染失败'); });
  }).catch(function () { fallback('PDF 解析失败'); });
}
function renderWord(bytes) {
  if (!window.mammoth) { fallback('Word 组件加载失败'); return; }
  window.mammoth.convertToHtml({ arrayBuffer: bytes }).then(function (r) {
    q('stage').innerHTML = '<div class="doc">' + sanitize(r.value) + '</div>';
    setStatus('已渲染文档内容');
  }).catch(function () { fallback('Word 解析失败'); });
}
function renderExcel(bytes) {
  if (!window.XLSX) { fallback('表格组件加载失败'); return; }
  try {
    var wb = window.XLSX.read(bytes, { type: 'array' });
    var html = '';
    wb.SheetNames.forEach(function (n) {
      var ws = wb.Sheets[n];
      html += '<div class="sheet-title">' + escapeHtml(n) + '</div>' +
        '<div style="overflow:auto">' + window.XLSX.utils.sheet_to_html(ws) + '</div>';
    });
    q('stage').innerHTML = sanitize(html);
    setStatus('共 ' + wb.SheetNames.length + ' 个工作表');
  } catch (e) { fallback('表格解析失败'); }
}
function renderOffice() {
  var src = 'https://view.officeapps.live.com/op/embed.aspx?src=' + encodeURIComponent(DIRECT);
  q('stage').innerHTML = '<iframe src="' + src + '"></iframe>' +
    '<div class="hint">若查看器不可访问，请使用下载文件。</div>';
  q('save').style.display = 'none';
}
function renderText() {
  q('stage').innerHTML = '<textarea class="raw" id="raw" spellcheck="false"></textarea><div class="hint" id="editorHint"></div>';
  fetch(P).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); }).then(function (txt) {
    var raw = q('raw'); raw.value = txt;
    loadCss(CDN.cmCss, 0);
    loadScript(CDN.cm, 0, function (ok) {
      if (!ok || !window.CodeMirror) {
        raw.style.display = 'block';
        q('editorHint').textContent = '编辑器组件加载失败，已降级为纯文本编辑，保存仍可用。';
        setStatus('纯文本编辑模式');
        return;
      }
      var modeUrls = [];
      if (MODE) {
        modeUrls = [
          'https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/mode/' + MODE + '/' + MODE + '.min.js',
          'https://cdn.jsdelivr.net/npm/codemirror@5.65.16/mode/' + MODE + '/' + MODE + '.js'
        ];
      }
      function create() {
        window.editor = window.CodeMirror.fromTextArea(raw, {
          lineNumbers: true, lineWrapping: true, indentUnit: 2, tabSize: 2,
          mode: MODE || null, readOnly: !EDITABLE
        });
        setStatus(EDITABLE ? '可以编辑并保存' : '只读预览');
      }
      if (modeUrls.length) loadScript(modeUrls, 0, function () { create(); }); else create();
    });
  }).catch(function () { fallback('文本内容读取失败'); });
  q('save').style.display = EDITABLE ? 'inline-block' : 'none';
}
function renderOther() {
  q('stage').innerHTML = '<div class="hint">该文件类型没有内置预览方式，可通过下载后使用其他应用打开。</div>';
  q('save').style.display = 'none';
}

q('dl').setAttribute('href', D);
q('save').onclick = function () {
  var txt = window.editor ? window.editor.getValue() : (q('raw') ? q('raw').value : '');
  if (window.AndroidBridge && window.AndroidBridge.save) {
    q('save').disabled = true; setStatus('正在保存');
    window.AndroidBridge.save(txt);
  } else { notify('当前环境不支持保存'); }
};
window.previewSaveDone = function (ok, msg) {
  q('save').disabled = false;
  setStatus(ok ? '保存成功' : ('保存失败：' + (msg || '未知原因')));
};

if (CATEGORY === 'image') {
  q('stage').innerHTML = '<img class="preview" src="' + P + '" alt="' + NAME + '">';
  q('save').style.display = 'none';
  setStatus('图片预览');
} else if (CATEGORY === 'video') { mediaTag('video'); }
else if (CATEGORY === 'audio') { mediaTag('audio'); }
else if (CATEGORY === 'other') { renderOther(); }
else if (CATEGORY === 'office') { renderOffice(); }
else if (CATEGORY === 'text') { renderText(); }
else {
  fetch(P).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.arrayBuffer(); }).then(function (bytes) {
    if (CATEGORY === 'pdf') {
      loadScript(CDN.pdf, 0, function (ok) { if (ok) renderPdf(bytes); else fallback('PDF 组件加载失败'); });
    } else if (CATEGORY === 'word') {
      loadScript(CDN.mammoth, 0, function (ok) { if (ok) renderWord(bytes); else fallback('Word 组件加载失败'); });
    } else if (CATEGORY === 'excel') {
      loadScript(CDN.xlsx, 0, function (ok) { if (ok) renderExcel(bytes); else fallback('表格组件加载失败'); });
    } else { renderOther(); }
  }).catch(function () { fallback('内容读取失败，可尝试下载'); });
}
</script>
</body>
</html>
""".trimIndent()
}
