<script setup lang="ts">
import { GithubOutlined, HomeOutlined } from '@ant-design/icons-vue'

const githubProfileUrl = 'https://github.com/zhangxiaohei666'
const projectHomeUrl = 'https://github.com/zhangxiaohei666/xiaozhi-esp32-laoda-rag'
</script>

<template>
  <div class="app-footer">
    <div class="footer-links">
      <a
        title="GitHub 主页"
        :href="githubProfileUrl"
        target="_blank"
        rel="noopener noreferrer"
        class="footer-link"
      >
        <GithubOutlined />
        <span>GitHub 主页</span>
      </a>

      <a
        title="项目主页"
        :href="projectHomeUrl"
        target="_blank"
        rel="noopener noreferrer"
        class="footer-link"
      >
        <HomeOutlined />
        <span>项目主页</span>
      </a>
    </div>
  </div>
</template>

<style scoped lang="scss">
.app-footer {
  text-align: center;
  padding: 2px 0;
}

.footer-links {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 24px;
  
  .footer-link {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--ant-color-text-secondary);
    font-size: 14px;
    transition: all 0.3s;
    cursor: pointer;
    
    &:hover {
      color: var(--ant-color-text);
    }
  }
}
</style>

