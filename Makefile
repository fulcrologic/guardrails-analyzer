DAEMONUI := $(shell find src/daemon* src/main -name '*.cljs' -o -name '*.cljc')
DAEMON := $(shell find src/daemon* src/main -name '*.clj' -o -name '*.cljc')

tests:
	clojure -J-Dtest -J-Dguardrails.mode=:all -A:dev:daemon:test:system-test:clj-tests

resources/public/js/daemon-ui/main.js: $(DAEMONUI)
	shadow-cljs release daemon-ui

install-analyzer:
	rm -rf target
	mvn install -f pom.xml

deploy-analyzer:
	rm -rf target
	mvn deploy -f pom.xml

# Daemon depends on guardrails-analyzer; install the analyzer locally first.
install-daemon: install-analyzer
	rm -rf target
	mvn install -f pom-daemon.xml

deploy-daemon:
	rm -rf target
	mvn deploy -f pom-daemon.xml

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

release: deploy-daemon deploy-analyzer publish
