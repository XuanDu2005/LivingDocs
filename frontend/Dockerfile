# ---- Build ----
FROM node:20-alpine AS build
WORKDIR /app

ARG VITE_API_BASE_URL=http://localhost:8080/api/v1
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

COPY package.json ./
# If you also ship package-lock.json, switch to `npm ci` for reproducibility.
RUN npm install --no-audit --no-fund

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