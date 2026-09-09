# Static build served by nginx. API base URLs are baked in at build time (Vite env vars);
# docker-compose passes the host-facing ports so the browser can reach both services.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG VITE_BOOKING_API=http://localhost:8081
ARG VITE_NOTIFICATION_API=http://localhost:8082
ENV VITE_BOOKING_API=$VITE_BOOKING_API VITE_NOTIFICATION_API=$VITE_NOTIFICATION_API
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
