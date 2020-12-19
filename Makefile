DAEMONUI := $(shell find src/daemon* src/main -name '*.cljs' -o -name '*.cljc')
DAEMON := $(shell find src/daemon* src/main -name '*.clj' -o -name '*.cljc')

tests:
	yarn
	npx shadow-cljs compile ci-tests
	npx karma start --single-run
	clojure -A:provided:cljs:dev:test:clj-tests -J-Dguardrails.config=guardrails-test.edn -J-Dguardrails.enabled

resources/public/js/daemon-ui/main.js: $(DAEMONUI)
	shadow-cljs release daemon-ui

Copilot.jar: pom-daemon.xml resources/public/js/daemon-ui/main.js $(DAEMON)
	clojure -A:provided:cljs:daemon -X:uberjar

deploy: Copilot.jar
	mvn deploy:deploy-file -Dfile=Copilot.jar -DpomFile=pom-daemon.xml -DrepositoryId=fulcrologic-publish -Durl=https://mvn.fulcrologic.com/mvn
