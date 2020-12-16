CLJSSRC := $(shell find src -name '*.cljs' -o -name '*.cljc')
CLJSRC := $(shell find src -name '*.clj' -o -name '*.cljc')

tests:
	yarn
	npx shadow-cljs compile ci-tests
	npx karma start --single-run
	clojure -A:provided:cljs:dev:test:clj-tests -J-Dguardrails.config=guardrails-test.edn -J-Dguardrails.enabled

resources/public/js/daemon-ui/main.js: $(CLJSSRC)
	shadow-cljs release daemon-ui

Copilot.jar: pom-daemon.xml resources/public/js/daemon-ui/main.js $(CLJSRC)
	clojure -A:provided:cljs:daemon -X:uberjar

deploy: Copilot.jar
	mvn deploy:deploy-file -Dfile=Copilot.jar -DpomFile=pom-daemon.xml -DrepositoryId=fulcrologic -Durl=https://mvn.fulcrologic.com/mvn
