/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // 纸感色板（后续 task 替换为品牌定稿）
        paper: {
          base: '#faf8f3',
          card: '#ffffff',
          ink: '#2b2b2b',
          muted: '#7a7770',
          line: '#e7e3d8',
          accent: '#c9a14a',
        },
      },
      fontFamily: {
        sans: ['system-ui', '-apple-system', 'PingFang SC', 'Microsoft YaHei', 'sans-serif'],
      },
    },
  },
  plugins: [],
};
