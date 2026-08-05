FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG VITE_GOOGLE_CLIENT_ID
ENV VITE_GOOGLE_CLIENT_ID=$VITE_GOOGLE_CLIENT_ID
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/conf.d/default.conf.template
EXPOSE 80
# #415: nginx.conf.template's /api/ proxy_pass resolves its target hostname once, when the worker
# processes start -- no re-resolution, ever. On Railway, BACKEND_URL is the backend's private
# domain (#408/#414), and if Railway reassigns that domain's address on a backend-only restart,
# nginx keeps dialing the stale one until nginx itself restarts. The fix is a `resolver` directive
# plus a variable-based proxy_pass, which forces DNS-TTL-based re-resolution -- but `resolver`
# needs an actual DNS server address, appropriate for whatever network this container is on
# (Railway's private network resolves *.railway.internal names; docker-compose's is Docker's own
# embedded DNS). Rather than hardcode either, this reads the container's own /etc/resolv.conf --
# already correct for both, since it's how the container itself resolves BACKEND_URL's host today.
#
# The awk one-liner is copied from nginx:alpine's own /docker-entrypoint.d/15-local-resolvers.envsh
# (it brackets IPv6 nameservers, which the `resolver` directive requires) -- that script never runs
# for us, because docker-entrypoint.sh only launches /docker-entrypoint.d/ when $1 is `nginx` or
# `nginx-debug`, and this CMD's actual $1 is `/bin/sh`. The 127.0.0.11 fallback exists only so an
# empty resolv.conf can't produce a bare `resolver ;`, an nginx config error that would stop the
# container from starting at all -- it's Docker's own embedded-DNS address, i.e. a no-op fallback
# under docker-compose and unreachable (so inert, not silently wrong) on Railway.
CMD ["/bin/sh", "-c", "NGINX_RESOLVERS=$(awk 'BEGIN{ORS=\" \"} $1==\"nameserver\" {if ($2 ~ \":\") print \"[\"$2\"]\"; else print $2}' /etc/resolv.conf); NGINX_RESOLVERS=\"${NGINX_RESOLVERS% }\"; [ -n \"$NGINX_RESOLVERS\" ] || NGINX_RESOLVERS=127.0.0.11; export NGINX_RESOLVERS; envsubst '$BACKEND_URL $TRUST_EDGE_REAL_IP $NGINX_RESOLVERS' < /etc/nginx/conf.d/default.conf.template > /etc/nginx/conf.d/default.conf && nginx -g 'daemon off;'"]
