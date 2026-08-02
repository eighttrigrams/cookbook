.PHONY: start stop build test lint clean

start:
	@if [ -f .env ]; then set -a && . ./.env && set +a; fi && ./scripts/start.sh

stop:
	./scripts/stop.sh

build:
	npm install
	npx shadow-cljs release app
	clj -T:build uber

test:
ifdef NS
	DEV=true clojure -M:test -n $(NS)
else
	DEV=true clj -X:test
endif

lint:
	clj-kondo --lint src/clj

clean:
	rm -rf target node_modules .shadow-cljs resources/public/cookbook/js
