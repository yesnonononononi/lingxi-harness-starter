<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import * as monaco from 'monaco-editor'
// 仅使用 Monaco 核心 worker（语法高亮 / diff 计算），无需语言智能感知，
// 因此不引入 json/css/html/ts 等语言 worker，减小打包体积。
// 注意：monaco-editor 0.56 的 exports 通配映射与 Vite 的 "?worker" 查询串冲突
// （rolldown 解析失败），故用相对物理路径导入绕过 exports 解析。
import editorWorker from '../../node_modules/monaco-editor/esm/vs/editor/editor.worker.js?worker'

self.MonacoEnvironment = {
  getWorker() {
    return new editorWorker()
  },
}

const props = defineProps({
  filePath: { type: String, default: '' },
  oldContent: { type: String, default: '' },
  newContent: { type: String, default: '' },
})

// Map common file extensions to Monaco language ids, fall back to plaintext.
function detectLanguage(filePath) {
  const ext = (filePath || '').split('.').pop().toLowerCase()
  const map = {
    js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'javascript',
    ts: 'typescript', mts: 'typescript', cts: 'typescript', tsx: 'typescript',
    vue: 'html', html: 'html', htm: 'html', svg: 'xml',
    css: 'css', scss: 'scss', less: 'less',
    json: 'json', jsonc: 'json', map: 'json',
    java: 'java', py: 'python', rb: 'ruby', go: 'go', rs: 'rust',
    c: 'c', h: 'c', cpp: 'cpp', hpp: 'cpp', cs: 'csharp',
    php: 'php', swift: 'swift', kt: 'kotlin', scala: 'scala',
    md: 'markdown', markdown: 'markdown', xml: 'xml', xhtml: 'xml',
    yml: 'yaml', yaml: 'yaml', toml: 'ini', ini: 'ini',
    sql: 'sql', sh: 'shell', bash: 'shell', zsh: 'shell',
    bat: 'bat', ps1: 'powershell', txt: 'plaintext',
  }
  return map[ext] || 'plaintext'
}

const containerRef = ref(null)
let diffEditor = null

onMounted(() => {
  diffEditor = monaco.editor.createDiffEditor(containerRef.value, {
    automaticLayout: true,
    readOnly: true,
    renderSideBySide: true,
    originalEditable: false,
    fontSize: 12,
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    folding: true,
    renderOverviewRuler: false,
    wordWrap: 'off',
    theme: 'vs',
  })
  const language = detectLanguage(props.filePath)
  diffEditor.setModel({
    original: monaco.editor.createModel(props.oldContent || '', language),
    modified: monaco.editor.createModel(props.newContent || '', language),
  })
})

onBeforeUnmount(() => {
  if (diffEditor) {
    const model = diffEditor.getModel()
    if (model) {
      model.original?.dispose()
      model.modified?.dispose()
    }
    diffEditor.dispose()
    diffEditor = null
  }
})
</script>

<template>
  <div ref="containerRef" class="file-diff"></div>
</template>

<style scoped>
.file-diff {
  width: 100%;
  height: 480px;
  border: 1px solid #eceef4;
  border-radius: 8px;
  overflow: hidden;
}
</style>
