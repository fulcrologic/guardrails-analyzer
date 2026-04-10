DAEMONUI := $(shell find src/daemon* src/main -name '*.cljs' -o -name '*.cljc')
DAEMON := $(shell find src/daemon* src/main -name '*.clj' -o -name '*.cljc')

tests:
	clojure -J-Dtest -J-Dguardrails.mode=:all -A:dev:daemon:test:system-test:clj-tests

resources/public/js/daemon-ui/main.js: $(DAEMONUI)
	shadow-cljs release daemon-ui

daemon-jar:
	rm -rf target/daemon-classes
	mkdir -p target/daemon-classes
	cp -r src/main/* target/daemon-classes/
	cp -r src/daemon/* target/daemon-classes/
	cp -r src/daemon_main/* target/daemon-classes/
	jar cf target/guardrails-analyzer-daemon.jar -C target/daemon-classes .

deploy-daemon: daemon-jar
	mvn deploy:deploy-file -Dfile=target/guardrails-analyzer-daemon.jar -DpomFile=pom-daemon.xml -DrepositoryId=clojars -Durl=https://clojars.org/repo

install-daemon: daemon-jar
	mvn install:install-file -Dfile=target/guardrails-analyzer-daemon.jar -DpomFile=pom-daemon.xml

analyzer-jar:
	rm -rf target/analyzer-classes
	mkdir -p target/analyzer-classes
	cp -r src/main/* target/analyzer-classes/
	jar cf target/guardrails-analyzer.jar -C target/analyzer-classes .

deploy-analyzer: analyzer-jar
	mvn deploy:deploy-file -Dfile=target/guardrails-analyzer.jar -DpomFile=pom.xml -DrepositoryId=clojars -Durl=https://clojars.org/repo

install-analyzer: analyzer-jar
	mvn install:install-file -Dfile=target/guardrails-analyzer.jar -DpomFile=pom.xml

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
