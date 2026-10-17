<template>
  <div class="nf">
    <h1>404</h1>
    <p>页面不存在或已被移除</p>
    <el-button type="primary" @click="$router.push('/agents')">回首页</el-button>
  </div>
</template>

<style scoped>
.nf {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
h1 { font-size: 64px; color: #c0c4cc; margin: 0; }
p { color: #909399; }
</style>
