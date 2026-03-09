import adapter from '@sveltejs/adapter-static';

const config = {
  kit: {
    adapter: adapter({
      pages: '../server/src/main/resources/META-INF/resources/admin-ui',
      assets: '../server/src/main/resources/META-INF/resources/admin-ui'
    }),
    paths: {
      base: '/admin-ui'
    },
    prerender: {
      handleUnseenRoutes: 'ignore'
    }
  }
};

export default config;
