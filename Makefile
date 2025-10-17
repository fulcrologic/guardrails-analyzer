DAEMONUI := $(shell find src/daemon* src/main -name '*.cljs' -o -name '*.cljc')
DAEMON := $(shell find src/daemon* src/main -name '*.clj' -o -name '*.cljc')

tests:
	clojure -J-Dtest -J-Dguardrails.mode=:all -A:dev:daemon:test:system-test:clj-tests

resources/public/js/daemon-ui/main.js: $(DAEMONUI)
	shadow-cljs release daemon-ui

Checker.jar: pom.xml $(CHECKERSRC)
	clojure -A:provided -X:checker-uberjar

Daemon.jar: pom.xml $(CHECKERSRC)
	clojure -A:daemon:provided -X:uberjar

deploy-daemon: Daemon.jar
	mvn deploy:deploy-file -Dfile=Daemon.jar -DpomFile=pom-daemon.xml -DrepositoryId=fulcrologic-publish -Durl=https://mvn.fulcrologic.com/mvn

install-daemon: Daemon.jar
	mvn install:install-file -Dfile=Daemon.jar -DpomFile=pom-daemon.xml

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

release: deploy-daemon deploy-checker publish
