import { defineConfig } from 'vitepress'

const repository = 'https://github.com/chenjicheng/magic-shulker-boxes'
const releases = `${repository}/releases`

export default defineConfig({
  title: 'Magic Shulker Boxes',
  description: 'Fabric 1.21.11 潜影盒自动收纳 / Automatic shulker storage',
  base: '/magic-shulker-boxes/',
  locales: {
    root: {
      label: '简体中文', lang: 'zh-CN',
      themeConfig: {
        nav: [{ text: '使用文档', link: '/guide' }, { text: '开发文档', link: '/development' }, { text: '下载', link: releases }],
        sidebar: [{ text: '魔法潜影盒', items: [
          { text: '介绍', link: '/' }, { text: '安装与使用', link: '/guide' },
          { text: '开发与发布', link: '/development' }, { text: '0.1.0-alpha', link: '/releases/0.1.0-alpha' },
          { text: '0.2.0-alpha', link: '/releases/0.2.0-alpha' },
          { text: '0.3.0-alpha', link: '/releases/0.3.0-alpha' },
          { text: '0.3.1', link: '/releases/0.3.1' },
          { text: '0.3.2', link: '/releases/0.3.2' },
          { text: '0.4.0', link: '/releases/0.4.0' },
          { text: '0.5.0', link: '/releases/0.5.0' },
          { text: '0.6.0', link: '/releases/0.6.0' },
          { text: '0.6.1', link: '/releases/0.6.1' },
          { text: '0.7.0', link: '/releases/0.7.0' },
          { text: '0.8.0', link: '/releases/0.8.0' },
          { text: '0.9.0', link: '/releases/0.9.0' },
          { text: '0.9.1', link: '/releases/0.9.1' },
          { text: '0.9.2', link: '/releases/0.9.2' },
          { text: '0.10.0', link: '/releases/0.10.0' },
        ] }],
        outline: { label: '本页内容', level: [2, 3] },
        docFooter: { prev: '上一页', next: '下一页' },
        sidebarMenuLabel: '目录', returnToTopLabel: '返回顶部',
        darkModeSwitchLabel: '外观', lightModeSwitchTitle: '切换浅色模式', darkModeSwitchTitle: '切换深色模式',
      },
    },
    en: {
      label: 'English', lang: 'en-US',
      themeConfig: {
        nav: [{ text: 'User guide', link: '/en/guide' }, { text: 'Development', link: '/en/development' }, { text: 'Download', link: releases }],
        sidebar: [{ text: 'Magic Shulker Boxes', items: [
          { text: 'Introduction', link: '/en/' }, { text: 'Install and configure', link: '/en/guide' },
          { text: 'Develop and release', link: '/en/development' }, { text: '0.1.0-alpha', link: '/releases/0.1.0-alpha' },
          { text: '0.2.0-alpha', link: '/releases/0.2.0-alpha' },
          { text: '0.3.0-alpha', link: '/releases/0.3.0-alpha' },
          { text: '0.3.1', link: '/releases/0.3.1' },
          { text: '0.3.2', link: '/releases/0.3.2' },
          { text: '0.4.0', link: '/releases/0.4.0' },
          { text: '0.5.0', link: '/releases/0.5.0' },
          { text: '0.6.0', link: '/releases/0.6.0' },
          { text: '0.6.1', link: '/releases/0.6.1' },
          { text: '0.7.0', link: '/releases/0.7.0' },
          { text: '0.8.0', link: '/releases/0.8.0' },
          { text: '0.9.0', link: '/releases/0.9.0' },
          { text: '0.9.1', link: '/releases/0.9.1' },
          { text: '0.9.2', link: '/releases/0.9.2' },
          { text: '0.10.0', link: '/releases/0.10.0' },
        ] }],
      },
    },
  },
  themeConfig: {
    socialLinks: [{ icon: 'github', link: repository }],
    search: {
      provider: 'local',
      options: {
        locales: { root: { translations: {
          button: { buttonText: '搜索文档', buttonAriaLabel: '搜索文档' },
          modal: { displayDetails: '显示详情', resetButtonTitle: '清空', backButtonTitle: '返回',
            noResultsText: '没有找到相关内容', footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' } },
        } } },
      },
    },
    editLink: { pattern: `${repository}/edit/main/docs/:path`, text: 'Edit this page on GitHub' },
    footer: { message: 'MIT License', copyright: 'Copyright © 2026 chenjicheng' },
  },
})
