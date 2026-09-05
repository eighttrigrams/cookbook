.PHONY: start stop build test test-cljs lint clean

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

# The ClojureScript suite, which exists for one reason: the seal envelope is
# implemented twice — here on WebCrypto and in plurama-cli on javax.crypto — and
# the only honest guard against two spellings of one control drifting is a
# fixture both suites read. Separate from `test` rather than folded into it
# because it needs node_modules, which the Clojure suite does not.
test-cljs:
	npx shadow-cljs compile test
	node target/node-tests.js

lint:
	clj-kondo --lint src/clj

clean:
	rm -rf target node_modules .shadow-cljs resources/public/cookbook/js
