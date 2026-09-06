.PHONY: start stop build test test-cljs test-all lint clean

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

# Both suites, because `make test` alone is 409 green tests that say nothing
# about the envelope: somebody could edit src/cljs/et/cb/seal.cljs, run it, and
# ship a client that no longer agrees with plurama-cli's. The third suite is in
# that repo and cannot be run from here; the README's Development block names it.
test-all: test test-cljs

# `src/cljc` came along when `et.cb.caution` moved there for the caution port:
# the file did not stop being Clojure by gaining a second host, and dropping out
# of the lint on the way past is how a namespace quietly stops being checked.
lint:
	clj-kondo --lint src/clj src/cljc

clean:
	rm -rf target node_modules .shadow-cljs resources/public/cookbook/js
