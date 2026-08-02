#!/bin/bash
set -e

if [ ! -f config.edn ]; then
  echo "Creating default config.edn..."
  cat > config.edn << 'EOF'
{:db {:type :sqlite-file
      :path "data/cookbook.db"}
 :port #long #or [#env PORT 3170]
 :nrepl-port 7901
 :dangerously-skip-logins? true}
EOF
fi

# sqlite creates the file but not the directory holding it, and a fresh checkout
# has no data/ — without this the first start dies on "no such table:
# ragtime_migrations".
db_path=$(sed -n 's/.*:path *"\([^"]*\)".*/\1/p' config.edn | head -1)
mkdir -p "$(dirname "${db_path:-data/cookbook.db}")"

# Mark which environment owns the dev server so stop.sh refuses a cross-env
# stop (host vs container port-forward proxy). See tracker's scripts for the
# full rationale.
if [ -f /.dockerenv ]; then
  echo container > .dev-server.lock
else
  echo host > .dev-server.lock
fi

if [ ! -d node_modules ]; then
  echo "Installing npm dependencies..."
  npm install
fi

# SHADOW=false to skip hot reload and run a release build instead.
if [ "${SHADOW:-true}" = "true" ]; then
  echo "Starting shadow-cljs watch..."
  npx shadow-cljs watch app &
  echo $! > .shadow-cljs.pid
  for _ in $(seq 1 60); do
    if grep -q "shadow.cljs.devtools.client" resources/public/cookbook/js/main.js 2>/dev/null; then
      break
    fi
    sleep 1
  done
else
  echo "Building ClojureScript..."
  npx shadow-cljs release app
fi

echo "Starting server in development mode..."
DEV=true clojure -X:run
