# 阶段一：构建前端产物
FROM node:22-alpine AS web-build
WORKDIR /app
COPY sks-web/package.json sks-web/package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY sks-web/ ./
RUN npm run build

# 阶段二：nginx 运行时
FROM nginx:alpine
COPY --from=web-build /app/dist /usr/share/nginx/html
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf
