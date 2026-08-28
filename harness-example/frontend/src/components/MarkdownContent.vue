<template>
  <div class="md-content" v-html="html"></div>
</template>

<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import hljs from 'highlight.js/lib/core'
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
      return `<pre class="md-pre"><code class="hljs language-${language}">${highlighted}</code></pre>`
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
  font-size: 14px;
  word-break: break-word;
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
  font-weight: 600;
  line-height: 1.4;
}
.md-content :deep(h1) { font-size: 1.35em; }
.md-content :deep(h2) { font-size: 1.22em; }
.md-content :deep(h3) { font-size: 1.1em; }

.md-content :deep(ul),
.md-content :deep(ol) { padding-left: 22px; margin: 8px 0; }
.md-content :deep(li) { margin: 3px 0; }

.md-content :deep(a) { color: #5b8def; text-decoration: none; }
.md-content :deep(a:hover) { text-decoration: underline; }

.md-content :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid #3d4154;
  color: #8a8ca0;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 0 6px 6px 0;
}

.md-content :deep(code:not(.hljs)) {
  background: rgba(255, 255, 255, 0.09);
  border-radius: 4px;
  padding: 1px 5px;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 0.9em;
  color: #e8b7a0;
}

.md-content :deep(pre.md-pre) {
  background: #0d0e15;
  border: 1px solid #26293a;
  border-radius: 8px;
  padding: 12px 14px;
  margin: 10px 0;
  overflow-x: auto;
}
.md-content :deep(pre.md-pre code.hljs) {
  background: transparent;
  padding: 0;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
}

.md-content :deep(table) {
  border-collapse: collapse;
  margin: 10px 0;
  width: 100%;
  font-size: 13px;
}
.md-content :deep(th),
.md-content :deep(td) {
  border: 1px solid #2c2f42;
  padding: 6px 10px;
  text-align: left;
}
.md-content :deep(th) { background: rgba(255, 255, 255, 0.05); font-weight: 600; }

.md-content :deep(hr) {
  border: none;
  border-top: 1px solid #2c2f42;
  margin: 14px 0;
}
.md-content :deep(img) { max-width: 100%; border-radius: 6px; }
</style>
