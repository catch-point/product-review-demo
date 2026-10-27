/** @type {import('tailwindcss').Config} */
// The Keycloak login theme's classes: its templates and theme.properties.
module.exports = {
  content: [`${__dirname}/reviews/login/**/*.{ftl,properties}`],
  theme: {
    extend: {},
  },
  plugins: [],
};
