(ns com.fulcrologic.copilot.licensing
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    (java.security KeyStore MessageDigest Key)
    (java.util Base64 Arrays)
    (javax.crypto Cipher)
    (java.io ByteArrayInputStream ByteArrayOutputStream)
    (java.util.zip GZIPInputStream)))

(def keystore-alias "license")

(defn load!
  ^KeyStore [^String path password]
  (let [keystore (KeyStore/getInstance "PKCS12")]
    (.load keystore (io/input-stream (io/resource path)) (.toCharArray password))
    keystore))

(defn sha256 ^bytes [bytes] (.digest (MessageDigest/getInstance "SHA-256") bytes))
(defn decrypt ^bytes [^Key key ^bytes bytes]
  (let [cipher (doto (Cipher/getInstance "RSA")
                 (.init Cipher/DECRYPT_MODE key))]
    (.doFinal cipher bytes)))

(defn license-details [keystore password license-string]
  (try
    (let [store            (load! keystore password)
          cert             (.getCertificate store keystore-alias)
          public-key       (.getPublicKey cert)
          lines            (str/split license-string #"[\n\r]")
          data-lines       (filter #(not (str/includes? % "LICENSE ---")) lines)
          encoded-str      (str/replace (str/join "" data-lines) #"[ \t\n\r]" "")
          compressed-bytes (.decode (Base64/getDecoder) encoded-str)
          raw-bytes        ^bytes (with-open [bais    (ByteArrayInputStream. compressed-bytes)
                                              zstream (GZIPInputStream. bais)
                                              baos    (ByteArrayOutputStream.)]
                                    (io/copy zstream baos)
                                    (.toByteArray baos))
          {:keys [license signature]} (read-string (String. raw-bytes "UTF-8"))
          license-bytes    (.decode (Base64/getDecoder) ^String license)
          signature-bytes  (.decode (Base64/getDecoder) ^String signature)
          expected-hash    (sha256 license-bytes)
          actual-hash      (decrypt public-key signature-bytes)
          license          (read-string (String. license-bytes "UTF-8"))]
      (assoc license
        :valid? (Arrays/equals expected-hash actual-hash)))
    (catch Exception _
      {:valid? false})))
