FROM nginx:alpine
# 本地/联调默认 nginx.conf（80-only）。生产 HTTPS 用 docker-compose.prod.yml 把
# NGINX_CONF 改成 nginx.https.conf，再挂 /etc/letsencrypt 只读卷——勿手改本行。
ARG NGINX_CONF=nginx.conf
COPY deploy/nginx/${NGINX_CONF} /etc/nginx/conf.d/default.conf
COPY deploy/nginx/50x.html /usr/share/nginx/html/50x.html
