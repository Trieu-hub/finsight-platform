# Build the React SPA, then serve the static bundle with nginx.
# The app talks to a RELATIVE "/api/v1" base, so in production the reverse proxy (Caddy)
# routes /api/* to the gateway and everything else to this nginx — no build-time API URL.
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
