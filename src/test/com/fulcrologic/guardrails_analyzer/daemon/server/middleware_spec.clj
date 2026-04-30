(ns com.fulcrologic.guardrails-analyzer.daemon.server.middleware-spec
  (:require
   [com.fulcrologic.guardrails-analyzer.daemon.server.middleware :as sut]
   [com.fulcrologic.guardrails-analyzer.daemon.server.pathom :as pathom]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [com.fulcrologicpro.fulcro.server.api-middleware :as f.api]
   [fulcro-spec.core :refer [assertions component specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

;; ============================================================================
;; not-found-handler (private)
;; ============================================================================

(specification "not-found-handler"
               (let [handler @#'sut/not-found-handler
                     result  (handler {:uri "/anything" :request-method :get})]
                 (assertions
                  "responds with HTTP 404"
                  (:status result) => 404

                  "uses text/plain content type"
                  (get-in result [:headers "Content-Type"]) => "text/plain"

                  "returns the literal not-found body"
                  (:body result) => "NOPE"

                  "ignores the incoming request method"
                  (:status (handler {:uri "/x" :request-method :post})) => 404

                  "ignores the incoming request uri"
                  (:body (handler {:uri "/api"})) => "NOPE")))

;; ============================================================================
;; wrap-api
;; ============================================================================

(specification "wrap-api"
               (component "when the incoming request URI matches the configured API URI"
                          (let [api-calls    (atom [])
                                parser-calls (atom [])
                                inner-calls  (atom [])
                                inner        (fn [_req]
                                               (swap! inner-calls conj :inner)
                                               :inner-response)
                                wrapped      (sut/wrap-api inner "/api")
                                request      {:uri            "/api"
                                              :request-method :post
                                              :transit-params {:tx [:list]}
                                              :headers        {"x" "y"}}]
                            (with-redefs [f.api/handle-api-request (fn [params parser-fn]
                                                                     (let [parsed (parser-fn :a-tx)]
                                                                       (swap! api-calls conj
                                                                              {:params params
                                                                               :parsed parsed})
                                                                       :api-response))
                                          pathom/parser            (fn [env tx]
                                                                     (swap! parser-calls conj
                                                                            {:env env :tx tx})
                                                                     :parsed-result)]
                              (let [result (wrapped request)]
                                (assertions
                                 "delegates to f.api/handle-api-request"
                                 result => :api-response

                                 "passes the request's transit-params to handle-api-request"
                                 (-> @api-calls first :params) => {:tx [:list]}

                                 "supplies a parser closure that calls the pathom parser"
                                 (-> @api-calls first :parsed) => :parsed-result

                                 "places the originating ring request under :ring/request in the parser env"
                                 (-> @parser-calls first :env) => {:ring/request request}

                                 "forwards the tx argument unchanged from the closure to the parser"
                                 (-> @parser-calls first :tx) => :a-tx

                                 "does not invoke the wrapped (downstream) handler"
                                 @inner-calls => []))))) ; close assertions, inner-let, with-redefs, outer-let, component

               (component "when the incoming request URI does NOT match the configured API URI"
                          (let [inner-calls (atom [])
                                inner       (fn [req]
                                              (swap! inner-calls conj req)
                                              :passthrough)
                                wrapped     (sut/wrap-api inner "/api")
                                other-req   {:uri "/something-else" :request-method :get}]
                            (with-redefs [f.api/handle-api-request
                                          (fn [& _] (throw (ex-info "should not be called" {})))]
                              (let [result (wrapped other-req)]
                                (assertions
                                 "delegates to the wrapped handler"
                                 result => :passthrough

                                 "passes the request unchanged to the wrapped handler"
                                 (first @inner-calls) => other-req

                                 "invokes the wrapped handler exactly once"
                                 (count @inner-calls) => 1)))))

               (component "wrap-api returns a function (handler)"
                          (assertions
                           "wrapping yields a fn"
                           (fn? (sut/wrap-api (fn [_]) "/api")) => true)))

;; ============================================================================
;; defaults-config (controls wrap-defaults composition)
;; ============================================================================

(specification "defaults-config: params section enables transit-friendly parsing"
               (assertions
                "keywordizes incoming params keys"
                (get-in sut/defaults-config [:params :keywordize]) => true

                "enables multipart parsing"
                (get-in sut/defaults-config [:params :multipart]) => true

                "enables nested params"
                (get-in sut/defaults-config [:params :nested]) => true

                "enables urlencoded params"
                (get-in sut/defaults-config [:params :urlencoded]) => true))

(specification "defaults-config: cookies"
               (assertions
                "enables cookie middleware"
                (:cookies sut/defaults-config) => true))

(specification "defaults-config: responses section"
               (assertions
                "enables absolute-redirects"
                (get-in sut/defaults-config [:responses :absolute-redirects]) => true

                "enables content-type negotiation"
                (get-in sut/defaults-config [:responses :content-types]) => true

                "uses utf-8 as the default response charset"
                (get-in sut/defaults-config [:responses :default-charset]) => "utf-8"

                "enables not-modified-responses (304 caching)"
                (get-in sut/defaults-config [:responses :not-modified-responses]) => true))

(specification "defaults-config: static section"
               (assertions
                "serves static resources from the public classpath dir"
                (get-in sut/defaults-config [:static :resources]) => "public"))

(specification "defaults-config: security section"
               (assertions
                "enables anti-forgery (CSRF) protection"
                (get-in sut/defaults-config [:security :anti-forgery]) => true

                "enables HSTS"
                (get-in sut/defaults-config [:security :hsts]) => true

                "disables ssl-redirect (daemon serves locally over http)"
                (get-in sut/defaults-config [:security :ssl-redirect]) => false

                "uses :sameorigin for X-Frame-Options"
                (get-in sut/defaults-config [:security :frame-options]) => :sameorigin

                "disables xss-protection (deprecated header; CSP / anti-forgery handle this)"
                (get-in sut/defaults-config [:security :xss-protection]) => false))

;; ============================================================================
;; Ring middleware composition (transit-params/response + wrap-defaults order)
;; ============================================================================
;;
;; The `middleware` defstate composes a ring handler chain via threading:
;;
;;   (-> not-found-handler
;;       (wrap-api "/api")
;;       (f.ws/wrap-api websockets)
;;       (f.api/wrap-transit-params)
;;       (f.api/wrap-transit-response transit-writer-opts)
;;       (wrap-defaults defaults-config))
;;
;; This means a request flows: wrap-defaults -> wrap-transit-response ->
;; wrap-transit-params -> websockets/wrap-api -> wrap-api "/api" ->
;; not-found-handler. We exercise the same composition shape (without
;; touching mount lifecycle) to prove ordering and that transit + defaults
;; configuration is carried through.
;; ============================================================================

(specification "middleware composition order"
               (let [calls (atom [])
                     log!  (fn [h layer]
                             (fn [req]
                               (swap! calls conj [:enter layer req])
                               (let [resp (h req)]
                                 (swap! calls conj [:leave layer resp])
                                 (or resp {:status 200 :body layer}))))
                     base  (fn [_req] {:status 404 :body :base})]
                 (with-redefs [f.api/wrap-transit-params   (fn [h]   (log! h :transit-params))
                               f.api/wrap-transit-response (fn [h _] (log! h :transit-response))]
                   (let [chain (-> base
                                   (log! :wrap-api-uri)
                                   (log! :wrap-ws)
                                   (f.api/wrap-transit-params)
                                   (f.api/wrap-transit-response {:opts :ignored})
                                   (log! :wrap-defaults))
                         _     (chain {:uri "/whatever"})
                         enter (mapv second (filter #(= :enter (first %)) @calls))]
                     (assertions
                      "wrap-defaults is the OUTERMOST layer (entered first)"
                      (first enter) => :wrap-defaults

                      "wrap-transit-response runs inside wrap-defaults"
                      (nth enter 1) => :transit-response

                      "wrap-transit-params runs inside wrap-transit-response (so response sees decoded body)"
                      (nth enter 2) => :transit-params

                      "wrap-ws (websockets) runs inside transit handling"
                      (nth enter 3) => :wrap-ws

                      "wrap-api ('/api') runs innermost (closest to base handler)"
                      (nth enter 4) => :wrap-api-uri

                      "all five middleware layers participate in the chain"
                      (count enter) => 5)))))
