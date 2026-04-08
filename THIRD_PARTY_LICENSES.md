# Third-Party Licenses

This project includes vendorized (re-namespaced) copies of the following
third-party libraries under `src/main/com/fulcrologicpro/`. All original
namespace prefixes have been relocated under `com.fulcrologicpro.*` to
avoid classpath conflicts when the analyzer runs in the same JVM as user code.

Original copyright headers are preserved in each source file.

## Vendorized Libraries

### clojure.tools.reader

- **Original coordinates:** org.clojure/tools.reader
- **Original authors:** Nicola Mometto, Rich Hickey, and contributors
- **License:** Eclipse Public License 1.0 (see `licenses/EPL-1.0.html`)
- **Source:** https://github.com/clojure/tools.reader
- **Vendorized path:** `com/fulcrologicpro/clojure/tools/reader/`

### clojure/data.json

- **Original coordinates:** org.clojure/data.json
- **Original authors:** Stuart Sierra, Rich Hickey, and contributors
- **License:** Eclipse Public License 1.0 (see `licenses/EPL-1.0.html`)
- **Source:** https://github.com/clojure/data.json
- **Vendorized path:** `com/fulcrologicpro/clojure/data/`

### transit-clj

- **Original coordinates:** com.cognitect/transit-clj
- **Original authors:** Cognitect, Inc.
- **License:** Apache License 2.0 (see `licenses/Apache-2.0.txt`)
- **Source:** https://github.com/cognitect/transit-clj
- **Vendorized path:** `com/fulcrologicpro/cognitect/`

### transit-java

- **Original coordinates:** com.cognitect/transit-java
- **Original authors:** Cognitect, Inc.
- **License:** Apache License 2.0 (see `licenses/Apache-2.0.txt`)
- **Source:** https://github.com/cognitect/transit-java
- **Vendorized path:** `com/fulcrologicpro/com/cognitect/transit/`

### jackson-core

- **Original coordinates:** com.fasterxml.jackson.core/jackson-core
- **Original authors:** Tatu Saloranta and contributors
- **License:** Apache License 2.0 (see `licenses/Apache-2.0.txt`)
- **Source:** https://github.com/FasterXML/jackson-core
- **Vendorized path:** `com/fulcrologicpro/com/fasterxml/jackson/`

### EQL (edn-query-language)

- **Original coordinates:** com.wsscode/eql
- **Original authors:** Wilker Lucio
- **License:** MIT License (see `licenses/MIT.html`)
- **Source:** https://github.com/edn-query-language/eql
- **Vendorized path:** `com/fulcrologicpro/edn_query_language/`

### Fulcro

- **Original coordinates:** com.fulcrologic/fulcro
- **Original authors:** Fulcrologic, LLC (Tony Kay)
- **License:** MIT License (see `licenses/MIT.html`)
- **Source:** https://github.com/fulcrologic/fulcro
- **Vendorized path:** `com/fulcrologicpro/fulcro/`

### javax.xml.bind (JAXB API)

- **Original coordinates:** javax.xml.bind/jaxb-api
- **Original authors:** Oracle and/or its affiliates
- **License:** CDDL 1.1 (see `licenses/CDDL-1.1.html`)
- **Source:** https://github.com/javaee/jaxb-spec
- **Vendorized path:** `com/fulcrologicpro/javax/xml/bind/`
- **Note:** Dual-licensed GPL 2.0 with Classpath Exception / CDDL 1.1.
  This project uses the code under the CDDL 1.1 license.

### commons-codec

- **Original coordinates:** commons-codec/commons-codec
- **Original authors:** The Apache Software Foundation
- **License:** Apache License 2.0 (see `licenses/Apache-2.0.txt`)
- **Source:** https://github.com/apache/commons-codec
- **Vendorized path:** `com/fulcrologicpro/org/apache/commons/`

### Java-WebSocket

- **Original coordinates:** org.java-websocket/Java-WebSocket
- **Original authors:** Nathan Rajlich and contributors
- **License:** MIT License (see `licenses/MIT.html`)
- **Source:** https://github.com/TooTallNate/Java-WebSocket
- **Vendorized path:** `com/fulcrologicpro/org/java_websocket/`

### json-simple

- **Original coordinates:** com.googlecode.json-simple/json-simple
- **Original authors:** Yidong Fang
- **License:** Apache License 2.0 (see `licenses/Apache-2.0.txt`)
- **Source:** https://github.com/fangyidong/json-simple
- **Vendorized path:** `com/fulcrologicpro/org/json/simple/`

### msgpack-java

- **Original coordinates:** org.msgpack/msgpack
- **Original authors:** FURUHASHI Sadayuki, Muga Nishizawa, and MessagePack contributors
- **License:** Apache License 2.0 (see `licenses/Apache-2.0.txt`)
- **Source:** https://github.com/msgpack/msgpack-java
- **Vendorized path:** `com/fulcrologicpro/org/msgpack/`
- **Note:** Includes modified Apache Harmony files under `org/apache/harmony/beans/`
  (also Apache 2.0 licensed).

### SLF4J stub

- **Original coordinates:** N/A (custom minimal stub implementation)
- **Original authors:** Fulcrologic, LLC
- **License:** N/A (custom stub, not derived from SLF4J source)
- **Vendorized path:** `com/fulcrologicpro/org/slf4j/`

### riddley

- **Original coordinates:** riddley/riddley
- **Original authors:** Zach Tellman
- **License:** MIT License (see `licenses/MIT.html`)
- **Source:** https://github.com/ztellman/riddley
- **Vendorized path:** `com/fulcrologicpro/riddley/`

### taoensso (encore, sente, timbre)

- **Original coordinates:** com.taoensso/encore, com.taoensso/sente, com.taoensso/timbre
- **Original authors:** Peter Taoussanis
- **License:** Eclipse Public License 1.0 (see `licenses/EPL-1.0.html`)
- **Source:** https://github.com/taoensso
- **Vendorized path:** `com/fulcrologicpro/taoensso/`

## License Summary

| License | Libraries |
|---------|-----------|
| Apache 2.0 | transit-clj, transit-java, jackson-core, commons-codec, json-simple, msgpack-java |
| EPL 1.0 | clojure.tools.reader, clojure/data.json, taoensso (encore/sente/timbre) |
| MIT | EQL, Fulcro, Java-WebSocket, riddley |
| CDDL 1.1 | javax.xml.bind (JAXB API) |
