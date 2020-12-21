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

# gem install asciidoctor asciidoctor-diagram coderay
docs/user-guide/UserGuide.html: docs/user-guide/UserGuide.adoc
	(cd docs/user-guide; asciidoctor -o UserGuide.html -b html5 -r asciidoctor-diagram UserGuide.adoc)

# Requires asciidoctor-pdf, see readme
#docs/DevelopersGuide.pdf: DevelopersGuide.adoc
#	asciidoctor-pdf -o docs/DevelopersGuide.pdf -b pdf -r asciidoctor-diagram DevelopersGuide.adoc

#pdf: docs/DevelopersGuide.pdf
book: docs/user-guide/UserGuide.html

publish: book
	rsync -av docs/user-guide/UserGuide.html linode:/usr/share/nginx/html/copilot.html
	rsync -av docs/user-guide/images linode:/usr/share/nginx/html/
