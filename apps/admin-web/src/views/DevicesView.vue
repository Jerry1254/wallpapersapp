<template>
  <section class="page-shell">
    <header class="page-heading"><div><h1>设备权益</h1><p>以匿名设备为主体查看已获得壁纸与最后活跃状态。</p></div></header>
    <ElAlert title="App 不要求用户登录" description="WP-P08 将由服务端签发设备凭证，并提供设备、权益和恢复审计的管理查询接口。" type="info" show-icon :closable="false" />
    <section class="surface content-table">
      <ElResult icon="info" title="等待 WP-P08 服务端接口" sub-title="当前页面不再展示浏览器 Mock 数据。" />
    </section>
  </section>
</template>
