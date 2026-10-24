FROM node:24-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends clojure ca-certificates curl git procps && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN mkdir -p /app/src && printf '(ns pilot)\n(defn stable-distinct-by [key-fn values] (throw (ex-info "Implement me" {})))\n' > /app/src/pilot.clj
