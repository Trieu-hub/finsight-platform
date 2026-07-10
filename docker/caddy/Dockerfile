# Custom Caddy build = stock Caddy + the rate-limit plugin (not in the official image).
# Used to throttle the auth endpoints at the edge (e.g. bulk /register abuse, login brute-force).
# Ref: https://github.com/mholt/caddy-ratelimit
FROM caddy:2-builder AS builder
RUN xcaddy build --with github.com/mholt/caddy-ratelimit

FROM caddy:2-alpine
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
