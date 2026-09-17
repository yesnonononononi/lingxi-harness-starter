<template>
  <div
    class="md-content"
    :class="{ 'is-streaming': streaming }"
    v-html="html"
    @click="onContentClick"
  ></div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { marked } from 'marked'
import hljs from 'highlight.js/lib/core'
// 与 .md-pre 深色背景 (#0d1117) 配套的 hljs 主题：未引入时 token 默认色为浅色设计，
// 落在深色块上会发暗看不清（注释几乎与背景同色）。
import 'highlight.js/styles/github-dark.css'
import DOMPurify from 'dompurify'

// register languages used by coding-agent replies on demand (keep bundle small)
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import java from 'highlight.js/lib/languages/java'
import python from 'highlight.js/lib/languages/python'
import xml from 'highlight.js/lib/languages/xml'
import json from 'highlight.js/lib/languages/json'
import bash from 'highlight.js/lib/languages/bash'
import sql from 'highlight.js/lib/languages/sql'
import yaml from 'highlight.js/lib/languages/yaml'

hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('java', java)
hljs.registerLanguage('python', python)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('json', json)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('yaml', yaml)

// marked v12+ renders code blocks through a custom renderer
marked.use({
  gfm: true,
  breaks: true,
  renderer: {
    code({ text, lang }) {
      const language = lang && hljs.getLanguage(lang) ? lang : 'plaintext'
      let highlighted
      try {
        highlighted = hljs.highlight(text, { language }).value
      } catch (e) {
        highlighted = hljs.highlightAuto(text).value
      }
      // 代码块外层包一层工具栏（语言名 + 复制按钮）；复制通过组件根节点的点击事件代理完成
      return `<div class="md-code"><div class="md-code-bar"><span class="md-code-lang">${language}</span>`
        + `<button type="button" class="md-copy-btn" aria-label="复制代码">复制</button></div>`
        + `<pre class="md-pre"><code class="hljs language-${language}">${highlighted}</code></pre></div>`
    }
  }
})

const props = defineProps({
  text: { type: String, default: '' }
})

const html = computed(() => {
  if (!props.text) return ''
  return DOMPurify.sanitize(marked.parse(props.text))
})
</script>

<style scoped>
.md-content {
  line-height: 1.7;
  font-size: 13.5px;
  word-break: break-word;
  color: var(--text-primary);
}
.md-content > :first-child { margin-top: 0; }
.md-content > :last-child { margin-bottom: 0; }

.md-content :deep(p) { margin: 8px 0; }
.md-content :deep(h1),
.md-content :deep(h2),
.md-content :deep(h3),
.md-content :deep(h4),
.md-content :deep(h5),
.md-content :deep(h6) {
  margin: 16px 0 8px;
  font-weight: 700;
  line-height: 1.4;
  color: #09090b;
}
.md-content :deep(h1) { font-size: 1.35em; letter-spacing: -0.3px; }
.md-content :deep(h2) { font-size: 1.22em; letter-spacing: -0.2px; }
.md-content :deep(h3) { font-size: 1.1em; }

.md-content :deep(ul),
.md-content :deep(ol) { padding-left: 20px; margin: 8px 0; }
.md-content :deep(li) { margin: 4px 0; }

.md-content :deep(a) {
  color: #09090b;
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 3px;
  transition: opacity 0.18s;
}
.md-content :deep(a:hover) { opacity: 0.75; }

.md-content :deep(blockquote) {
  margin: 10px 0;
  padding: 6px 14px;
  border-left: 3px solid #09090b;
  color: #52525b;
  background: #fafafa;
  border-radius: 0 8px 8px 0;
}

.md-content :deep(code:not(.hljs)) {
  background: #f4f4f5;
  border: 1px solid #e4e4e7;
  border-radius: 4px;
  padding: 2px 6px;
  font-family: var(--font-mono);
  font-size: 0.88em;
  color: #09090b;
  font-weight: 600;
}

.md-content :deep(.md-code) {
  margin: 12px 0;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid #27272a;
  background: #09090b;
}
.md-content :deep(.md-code-bar) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  background: #18181b;
  border-bottom: 1px solid #27272a;
}
.md-content :deep(.md-code-lang) {
  font-family: var(--font-mono);
  font-size: 11px;
  text-transform: uppercase;
  color: #a1a1aa;
  font-weight: 600;
  letter-spacing: 0.5px;
}
.md-content :deep(.md-copy-btn) {
  padding: 3px 8px;
  border-radius: 4px;
  background: #27272a;
  color: #f4f4f5;
  border: 1px solid #3f3f46;
  font-size: 11px;
  cursor: pointer;
  transition: all 0.16s ease;
}
.md-content :deep(.md-copy-btn):hover {
  background: #ffffff;
  color: #09090b;
  border-color: #ffffff;
}

.md-content :deep(pre.md-pre) {
  background: #09090b;
  margin: 0;
  padding: 12px 14px;
  overflow-x: auto;
}
.md-content :deep(pre.md-pre code.hljs) {
  background: transparent;
  padding: 0;
  font-family: var(--font-mono);
  font-size: 12.5px;
  line-height: 1.6;
}

.md-content :deep(table) {
  border-collapse: collapse;
  margin: 12px 0;
  width: 100%;
  font-size: 12.5px;
}
.md-content :deep(th),
.md-content :deep(td) {
  border: 1px solid #e4e4e7;
  padding: 7px 12px;
  text-align: left;
}
.md-content :deep(th) { background: #f4f4f5; font-weight: 700; color: #09090b; }

.md-content :deep(hr) {
  border: none;
  border-top: 1px solid #e4e4e7;
  margin: 16px 0;
}
.md-content :deep(img) { max-width: 100%; border-radius: 8px; }
</style>
