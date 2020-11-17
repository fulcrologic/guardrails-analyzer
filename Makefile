tests:
	yarn
	npx shadow-cljs compile ci-tests
	npx karma start --single-run
	clojure -A:cljs:dev:test:clj-tests -J-Dguardrails.config=guardrails-test.edn -J-Dguardrails.enabled

daemon:
	shadow-cljs release daemon-ui
	clojure -A:cljs:daemon -X:uberjar