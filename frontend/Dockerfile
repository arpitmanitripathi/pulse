# Web image: the built React app inside Caddy. The Caddyfile is mounted at run time (deploy/Caddyfile).
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM caddy:2.10-alpine
COPY --from=build /app/dist /srv
