(ns com.fulcrologic.guardrails-analyzer.forms-spec
  "Tests for cp.forms/interpret -- in particular the metadata-preservation
   behavior added by `preserve-meta`. The daemon receives forms over the wire
   with line/column metadata attached, and `interpret` rebuilds native
   collections from a transit-friendly representation. If metadata is dropped
   during this rebuild the downstream analyzer cannot attach line/col info to
   sub-expressions, breaking error reporting for path-based analysis."
  (:require
   [com.fulcrologic.guardrails-analyzer.forms :as cp.forms]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions component specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "cp.forms/interpret round-trips primitives and quoted symbols"
               (assertions
                "passes simple scalars through unchanged"
                (cp.forms/interpret 1) => 1
                (cp.forms/interpret "x") => "x"
                (cp.forms/interpret :kw) => :kw
                (cp.forms/interpret nil) => nil
                "decodes quoted symbols back to bare symbols"
                (cp.forms/interpret '(quote foo)) => 'foo
                (cp.forms/interpret '(quote some.ns/bar)) => 'some.ns/bar
                "rebuilds a `(list ...)` form into an actual list"
                (cp.forms/interpret '(list (quote a) 1 2)) => '(a 1 2)
                "decodes a (with-meta x m) form by attaching metadata to the rebuilt value"
                (let [decoded (cp.forms/interpret
                               '(with-meta (list (quote f) 1) {:line 7 :column 3}))]
                  [decoded (meta decoded)])
                => ['(f 1) {:line 7 :column 3}]))

(specification "cp.forms/interpret preserves metadata when rebuilding native collections"
               (component "vectors"
                          (let [original (with-meta [1 2 3] {:line 4 :column 2})
                                out      (cp.forms/interpret original)]
                            (assertions
                             "value content is preserved"
                             out => [1 2 3]
                             "metadata survives the rebuild"
                             (meta out) => {:line 4 :column 2}
                             "result is still a vector"
                             (vector? out) => true)))

               (component "lists"
                          (let [original (with-meta '(a b c) {:line 9 :column 1})
                                out      (cp.forms/interpret original)]
                            (assertions
                             "value content is preserved"
                             out => '(a b c)
                             "metadata survives the rebuild"
                             (meta out) => {:line 9 :column 1}
                             "result is still a list"
                             (list? out) => true)))

               (component "maps"
                          (let [original (with-meta {:a 1 :b 2} {:line 11 :column 5})
                                out      (cp.forms/interpret original)]
                            (assertions
                             "value content is preserved"
                             out => {:a 1 :b 2}
                             "metadata survives the rebuild"
                             (meta out) => {:line 11 :column 5}
                             "result is still a map"
                             (map? out) => true)))

               (component "sets"
                          (let [original (with-meta #{1 2 3} {:line 13 :column 7})
                                out      (cp.forms/interpret original)]
                            (assertions
                             "value content is preserved"
                             out => #{1 2 3}
                             "metadata survives the rebuild"
                             (meta out) => {:line 13 :column 7}
                             "result is still a set"
                             (set? out) => true))))

(specification "cp.forms/interpret leaves un-metadata'd collections un-meta'd"
  ;; This guards against a regression where preserve-meta accidentally
  ;; assigned an empty map of metadata to every rebuilt collection.
               (assertions
                "vector with no incoming meta has nil meta"
                (meta (cp.forms/interpret [1 2 3])) => nil
                "map with no incoming meta has nil meta"
                (meta (cp.forms/interpret {:a 1})) => nil
                "set with no incoming meta has nil meta"
                (meta (cp.forms/interpret #{1 2 3})) => nil))
