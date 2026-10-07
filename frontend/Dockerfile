# ---- Build ----
FROM node:20-alpine AS build
WORKDIR /app

ARG VITE_API_BASE_URL=http://localhost:8080/api/v1
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

COPY package.json package-lock.json ./
# Use `npm ci` (deterministic, uses lockfile) instead of `npm install`
# so a transient network blip doesn't fail the whole build. `--prefer-offline`
# falls back to the local npm cache when present, retry handles mid-flight
# ECONNRESET from the registry.
RUN npm ci --no-audit --no-fund --prefer-offline || npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---- Runtime ----
FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html

# Replace default nginx config with one that supports SPA routing
# (history API fallback to /index.html) and forwards nothing extra.
RUN rm /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY --from=build /app/dist ./

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]