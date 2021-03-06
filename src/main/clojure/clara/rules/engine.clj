(ns clara.rules.engine
  "The Clara rules engine. Most users should use only the clara.rules namespace."
  (:require [clojure.reflect :as reflect]
            [clojure.core.reducers :as r]
            [schema.core :as s]
            [clojure.string :as string]
            [clara.rules.memory :as mem]
            [clara.rules.listener :as l]
            [clara.rules.platform :as platform]))

;; The accumulator is a Rete extension to run an accumulation (such as sum, average, or similar operation)
;; over a collection of values passing through the Rete network. This object defines the behavior
;; of an accumulator. See the AccumulatorNode for the actual node implementation in the network.
(defrecord Accumulator [input-condition initial-value reduce-fn combine-fn convert-return-fn])

;; A Rete-style token, which contains two items:
;; * matches, a sequence of [fact, condition] tuples for the facts and corresponding conditions they matched
;; * bindings, a map of keyword-to-values for bound variables.
(defrecord Token [matches bindings])

;; A working memory element, containing a single fact and its corresponding bound variables.
(defrecord Element [fact bindings])

;; An activation for the given production and token.
(defrecord Activation [node token])

;; Token with no bindings, used as the root of beta nodes.
(def empty-token (->Token [] {}))

;; Schema for the structure returned by the components
;; function on the session protocol.
;; This is simply a comment rather than first-class schema
;; for now since it's unused for validation and created
;; undesired warnings as described at https://groups.google.com/forum/#!topic/prismatic-plumbing/o65PfJ4CUkI
(comment

  (def session-components-schema
    {:rulebase s/Any
     :memory s/Any
     :transport s/Any
     :listeners [s/Any]
     :get-alphas-fn s/Any}))

;; Returns a new session with the additional facts inserted.
(defprotocol ISession

  ;; Inserts a fact.
  (insert [session fact])

  ;; Retracts a fact.
  (retract [session fact])

  ;; Fires pending rules and returns a new session where they are in a fired state.
  (fire-rules [session])

  ;; Runs a query agains thte session.
  (query [session query params])

  ;; Returns the components of a session as defined in the session-components-schema
  (components [session]))

;; Left activation protocol for various types of beta nodes.
(defprotocol ILeftActivate
  (left-activate [node join-bindings tokens memory transport listener])
  (left-retract [node join-bindings tokens memory transport listener])
  (description [node])
  (get-join-keys [node]))

;; Right activation protocol to insert new facts, connecting alpha nodes
;; and beta nodes.
(defprotocol IRightActivate
  (right-activate [node join-bindings elements memory transport listener])
  (right-retract [node join-bindings elements memory transport listener]))

;; Specialized right activation interface for accumulator nodes,
;; where the caller has the option of pre-reducing items
;; to reduce the data sent to the node. This would be useful
;; if the caller is not in the same memory space as the accumulator node itself.
(defprotocol IAccumRightActivate
  ;; Pre-reduces elements, returning a map of bindings to reduced elements.
  (pre-reduce [node elements])

  ;; Right-activate the node with items reduced in the above pre-reduce step.
  (right-activate-reduced [node join-bindings reduced  memory transport listener]))

;; The transport protocol for sending and retracting items between nodes.
(defprotocol ITransport
  (send-elements [transport memory listener nodes elements])
  (send-tokens [transport memory listener nodes tokens])
  (retract-elements [transport memory listener nodes elements])
  (retract-tokens [transport memory listener nodes tokens]))


;; Simple, in-memory transport.
(deftype LocalTransport []
  ITransport
  (send-elements [transport memory listener nodes elements]

    (doseq [node nodes
            :let [join-keys (get-join-keys node)]]

      (if (> (count join-keys) 0)

        ;; Group by the join keys for the activation.
        (doseq [[join-bindings element-group] (platform/tuned-group-by #(select-keys (:bindings %) join-keys) elements)]
          (right-activate node
                          join-bindings
                          element-group
                          memory
                          transport
                          listener))

        ;; The node has no join keys, so just send everything at once
        ;; (if there is something to send.)
        (when (seq elements)
          (right-activate node
                          {}
                          elements
                          memory
                          transport
                          listener)))))

  (send-tokens [transport memory listener nodes tokens]

    (doseq [node nodes
            :let [join-keys (get-join-keys node)]]

      (if (> (count join-keys) 0)
        (doseq [[join-bindings token-group] (platform/tuned-group-by #(select-keys (:bindings %) join-keys) tokens)]

          (left-activate node
                         join-bindings
                         token-group
                         memory
                         transport
                         listener))

        ;; The node has no join keys, so just send everything at once.
        (when (seq tokens)
          (left-activate node
                         {}
                         tokens
                         memory
                         transport
                         listener)))))

  (retract-elements [transport memory listener nodes elements]
    (doseq  [[bindings element-group] (group-by :bindings elements)
             node nodes]
      (right-retract node
                     (select-keys bindings (get-join-keys node))
                     element-group
                     memory
                     transport
                     listener)))

  (retract-tokens [transport memory listener nodes tokens]
    (doseq  [[bindings token-group] (group-by :bindings tokens)
             node nodes]
      (left-retract  node
                     (select-keys bindings (get-join-keys node))
                     token-group
                     memory
                     transport
                     listener))))

;; Protocol for activation of Rete alpha nodes.
(defprotocol IAlphaActivate
  (alpha-activate [node facts memory transport listener])
  (alpha-retract [node facts memory transport listener]))


;; Active session during rule execution.
(def ^:dynamic *current-session* nil)

;; The token that triggered a rule to fire.
(def ^:dynamic *rule-context* nil)

;; Record for the production node in the Rete network.
(defrecord ProductionNode [id production rhs]
  ILeftActivate
  (left-activate [node join-bindings tokens memory transport listener]

    (l/left-activate! listener node tokens)

    ;; Fire the rule if it's not a no-loop rule, or if the rule is not
    ;; active in the current context.
    (when (or (not (get-in production [:props :no-loop]))
              (not (= production (get-in *rule-context* [:node :production]))))

      ;; Preserve tokens that fired for the rule so we
      ;; can perform retractions if they become false.
      (mem/add-tokens! memory node join-bindings tokens)

      (let [activations (for [token tokens]
                          (->Activation node token))]

        (l/add-activations! listener node activations)

        ;; The production matched, so add the tokens to the activation list.
        (mem/add-activations! memory activations))))

  (left-retract [node join-bindings tokens memory transport listener]

    (l/left-retract! listener node tokens)

    ;; Remove any tokens to avoid future rule execution on retracted items.
    (mem/remove-tokens! memory node join-bindings tokens)

    ;; Remove pending activations triggered by the retracted tokens.
    (let [activations (for [token tokens]
                        (->Activation node token))]

      (l/remove-activations! listener node activations)
      (mem/remove-activations! memory activations))

    ;; Retract any insertions that occurred due to the retracted token.
    (let [insertions (mem/remove-insertions! memory node tokens)]
      (doseq [[cls fact-group] (group-by type insertions)
              root (get-in (mem/get-rulebase memory) [:alpha-roots cls])]
        (alpha-retract root fact-group memory transport listener))))

  (get-join-keys [node] [])

  (description [node] "ProductionNode"))

;; The QueryNode is a terminal node that stores the
;; state that can be queried by a rule user.
(defrecord QueryNode [id query param-keys]
  ILeftActivate
  (left-activate [node join-bindings tokens memory transport listener]
    (l/left-activate! listener node tokens)
    (mem/add-tokens! memory node join-bindings tokens))

  (left-retract [node join-bindings tokens memory transport listener]
    (l/left-retract! listener node tokens)
    (mem/remove-tokens! memory node join-bindings tokens))

  (get-join-keys [node] param-keys)

  (description [node] (str "QueryNode -- " query)))

;; Record representing alpha nodes in the Rete network,
;; each of which evaluates a single condition and
;; propagates matches to its children.
(defrecord AlphaNode [env children activation]
  IAlphaActivate
  (alpha-activate [node facts memory transport listener]
    (send-elements
     transport
     memory
     listener
     children
     (for [fact facts
           :let [bindings (activation fact env)] :when bindings] ; FIXME: add env.
       (->Element fact bindings))))

  (alpha-retract [node facts memory transport listener]

    (retract-elements
     transport
     memory
     listener
     children
     (for [fact facts
           :let [bindings (activation fact env)] :when bindings] ; FIXME: add env.
       (->Element fact bindings)))))

(defrecord RootJoinNode [id condition children binding-keys]
  ILeftActivate
  (left-activate [node join-bindings tokens memory transport listener]
    ;; This specialized root node doesn't need to deal with the
    ;; empty token, so do nothing.
    )

  (left-retract [node join-bindings tokens memory transport listener]
    ;; The empty token can't be retracted from the root node,
    ;; so do nothing.
    )

  (get-join-keys [node] binding-keys)

  (description [node] (str "RootJoinNode -- " (:text condition)))

  IRightActivate
  (right-activate [node join-bindings elements memory transport listener]

    (l/right-activate! listener node elements)

    ;; Add elements to the working memory to support analysis tools.
    (mem/add-elements! memory node join-bindings elements)
    ;; Simply create tokens and send it downstream.
    (send-tokens
     transport
     memory
     listener
     children
     (for [{:keys [fact bindings] :as element} elements]
       (->Token [[fact condition]] bindings))))

  (right-retract [node join-bindings elements memory transport listener]

    (l/right-retract! listener node elements)

    ;; Remove matching elements and send the retraction downstream.
    (retract-tokens
     transport
     memory
     listener
     children
     (for [{:keys [fact bindings] :as element} (mem/remove-elements! memory node join-bindings elements)]
       (->Token [[fact condition]] bindings)))))

;; Record for the join node, a type of beta node in the rete network. This node performs joins
;; between left and right activations, creating new tokens when joins match and sending them to
;; its descendents.
(defrecord JoinNode [id condition children binding-keys]
  ILeftActivate
  (left-activate [node join-bindings tokens memory transport listener]
    ;; Add token to the node's working memory for future right activations.
    (mem/add-tokens! memory node join-bindings tokens)
    (send-tokens
     transport
     memory
     listener
     children
     (for [element (mem/get-elements memory node join-bindings)
           token tokens
           :let [fact (:fact element)
                 fact-binding (:bindings element)]]
       (->Token (conj (:matches token) [fact condition]) (conj fact-binding (:bindings token))))))

  (left-retract [node join-bindings tokens memory transport listener]
    (retract-tokens
     transport
     memory
     listener
     children
     (for [token (mem/remove-tokens! memory node join-bindings tokens)
           element (mem/get-elements memory node join-bindings)
           :let [fact (:fact element)
                 fact-bindings (:bindings element)]]
       (->Token (conj (:matches token) [fact condition]) (conj fact-bindings (:bindings token))))))

  (get-join-keys [node] binding-keys)

  (description [node] (str "JoinNode -- " (:text condition)))

  IRightActivate
  (right-activate [node join-bindings elements memory transport listener]
    (mem/add-elements! memory node join-bindings elements)
    (send-tokens
     transport
     memory
     listener
     children
     (for [token (mem/get-tokens memory node join-bindings)
           {:keys [fact bindings] :as element} elements]
       (->Token (conj (:matches token) [fact condition]) (conj (:bindings token) bindings)))))

  (right-retract [node join-bindings elements memory transport listener]
    (retract-tokens
     transport
     memory
     listener
     children
     (for [{:keys [fact bindings] :as element} (mem/remove-elements! memory node join-bindings elements)
           token (mem/get-tokens memory node join-bindings)]
       (->Token (conj (:matches token) [fact condition]) (conj (:bindings token) bindings))))))

;; The NegationNode is a beta node in the Rete network that simply
;; negates the incoming tokens from its ancestors. It sends tokens
;; to its descendent only if the negated condition or join fails (is false).
(defrecord NegationNode [id condition children binding-keys]
  ILeftActivate
  (left-activate [node join-bindings tokens memory transport listener]
    ;; Add token to the node's working memory for future right activations.
    (mem/add-tokens! memory node join-bindings tokens)
    (when (empty? (mem/get-elements memory node join-bindings))
      (send-tokens transport memory listener children tokens)))

  (left-retract [node join-bindings tokens memory transport listener]
    (when (empty? (mem/get-elements memory node join-bindings))
      (retract-tokens transport memory listener children tokens)))

  (get-join-keys [node] binding-keys)

  (description [node] (str "NegationNode -- " (:text condition)))

  IRightActivate
  (right-activate [node join-bindings elements memory transport listener]
    (mem/add-elements! memory node join-bindings elements)
    ;; Retract tokens that matched the activation, since they are no longer negatd.
    (retract-tokens transport memory listener children (mem/get-tokens memory node join-bindings)))

  (right-retract [node join-bindings elements memory transport listener]
    (mem/remove-elements! memory node elements join-bindings) ;; FIXME: elements must be zero to retract.
    (send-tokens transport memory listener children (mem/get-tokens memory node join-bindings))))

;; The test node represents a Rete extension in which
(defrecord TestNode [id test children]
  ILeftActivate
  (left-activate [node join-bindings tokens memory transport listener]
    (send-tokens
     transport
     memory
     listener
     children
     (filter test tokens)))

  (left-retract [node join-bindings tokens memory transport listener]
    (retract-tokens transport memory listener children tokens))

  (get-join-keys [node] [])

  (description [node] (str "TestNode -- " (:text test))))

(defn- retract-accumulated
  "Helper function to retract an accumulated value."
  [node accum-condition accumulator result-binding token result fact-bindings transport memory listener]
  (let [converted-result ((:convert-return-fn accumulator) result)
        new-facts (conj (:matches token) [converted-result accum-condition])
        new-bindings (merge (:bindings token)
                            fact-bindings
                            (when result-binding
                              { result-binding
                                converted-result}))]

    (retract-tokens transport memory listener (:children node)
                    [(->Token new-facts new-bindings)])))

(defn- send-accumulated
  "Helper function to send the result of an accumulated value to the node's children."
  [node accum-condition accumulator result-binding token result fact-bindings transport memory listener]
  (let [converted-result ((:convert-return-fn accumulator) result)
        new-bindings (merge (:bindings token)
                            fact-bindings
                            (when result-binding
                              { result-binding
                                converted-result}))]

    (send-tokens transport memory listener (:children node)
                 [(->Token (conj (:matches token) [converted-result accum-condition]) new-bindings)])))

(defn- has-keys?
  "Returns true if the given map has all of the given keys."
  [m keys]
  (every? (partial contains? m) keys))

;; The AccumulateNode hosts Accumulators, a Rete extension described above, in the Rete network
;; It behavios similarly to a JoinNode, but performs an accumulation function on the incoming
;; working-memory elements before sending a new token to its descendents.
(defrecord AccumulateNode [id accum-condition accumulator result-binding children binding-keys]
  ILeftActivate
  (left-activate [node join-bindings tokens memory transport listener]
    (let [previous-results (mem/get-accum-reduced-all memory node join-bindings)]
      (mem/add-tokens! memory node join-bindings tokens)

      (doseq [token tokens]

        (cond

         ;; If there are previously accumulated results to propagate, simply use them.
         (seq previous-results)
         (doseq [[fact-bindings previous] previous-results]
           (send-accumulated node accum-condition accumulator result-binding token previous fact-bindings transport memory listener))

         ;; There are no previously accumulated results, but we still may need to propagate things
         ;; such as a sum of zero items.
         ;; If all variables in the accumulated item are bound and an initial
         ;; value is provided, we can propagate the initial value as the accumulated item.

         (and (has-keys? (:bindings token)
                         binding-keys) ; All bindings are in place.
              (:initial-value accumulator)) ; An initial value exists that we can propagate.
         (let [fact-bindings (select-keys (:bindings token) binding-keys)
               previous (:initial-value accumulator)]

           ;; Send the created accumulated item to the children.
           (send-accumulated node accum-condition accumulator result-binding token previous fact-bindings transport memory listener)

           (l/add-accum-reduced! listener node join-bindings previous fact-bindings)

           ;; Add it to the working memory.
           (mem/add-accum-reduced! memory node join-bindings previous fact-bindings))

         ;; Propagate nothing if the above conditions don't apply.
         :default nil))))

  (left-retract [node join-bindings tokens memory transport listener]
    (let [previous-results (mem/get-accum-reduced-all memory node join-bindings)]
      (doseq [token (mem/remove-tokens! memory node join-bindings tokens)
              [fact-bindings previous] previous-results]
        (retract-accumulated node accum-condition accumulator result-binding token previous fact-bindings transport memory listener))))

  (get-join-keys [node] binding-keys)

  (description [node] (str "AccumulateNode -- " accumulator))

  IAccumRightActivate
  (pre-reduce [node elements]
    ;; Return a map of bindings to the pre-reduced value.
    (for [[bindings element-group] (platform/tuned-group-by :bindings elements)]
      [bindings
       (r/reduce (:reduce-fn accumulator)
                 (:initial-value accumulator)
                 (r/map :fact element-group))]))

  (right-activate-reduced [node join-bindings reduced-seq  memory transport listener]
    ;; Combine previously reduced items together, join to matching tokens,
    ;; and emit child tokens.
    (doseq [:let [matched-tokens (mem/get-tokens memory node join-bindings)]
            [bindings reduced] reduced-seq
            :let [previous (mem/get-accum-reduced memory node join-bindings bindings)]]

      ;; If the accumulation result was previously calculated, retract it
      ;; from the children.
      (when previous

        (doseq [token (mem/get-tokens memory node join-bindings)]
          (retract-accumulated node accum-condition accumulator result-binding token previous bindings transport memory listener)))

      ;; Combine the newly reduced values with any previous items.
      (let [combined (if previous
                       ((:combine-fn accumulator) previous reduced)
                       reduced)]

        (l/add-accum-reduced! listener node join-bindings combined bindings)

        (mem/add-accum-reduced! memory node join-bindings combined bindings)
        (doseq [token matched-tokens]
          (send-accumulated node accum-condition accumulator result-binding token combined bindings transport memory listener)))))

  IRightActivate
  (right-activate [node join-bindings elements memory transport listener]

    ;; Simple right-activate implementation simple defers to
    ;; accumulator-specific logic.
    (right-activate-reduced
     node
     join-bindings
     (pre-reduce node elements)
     memory
     transport
     listener))

  (right-retract [node join-bindings elements memory transport listener]

    (doseq [:let [matched-tokens (mem/get-tokens memory node join-bindings)]
            {:keys [fact bindings] :as element} elements
            :let [previous (mem/get-accum-reduced memory node join-bindings bindings)]

            ;; No need to retract anything if there was no previous item.
            :when previous

            ;; Get all of the previously matched tokens so we can retract and re-send them.
            token matched-tokens

            ;; Compute the new version with the retracted information.
            :let [retracted ((:retract-fn accumulator) previous fact)]]

      ;; Add our newly retracted information to our node.
      (mem/add-accum-reduced! memory node join-bindings retracted bindings)

      ;; Retract the previous token.
      (retract-accumulated node accum-condition accumulator result-binding token previous bindings transport memory listener)

      ;; Send a new accumulated token with our new, retracted information.
      (when retracted
        (send-accumulated node accum-condition accumulator result-binding token retracted bindings transport memory listener)))))


(defn variables-as-keywords
  "Returns symbols in the given s-expression that start with '?' as keywords"
  [expression]
  (into #{} (for [item (flatten expression)
                  :when (and (symbol? item)
                             (= \? (first (name item))))]
              (keyword item))))

(defn conj-rulebases
  "DEPRECATED. Simply concat sequences of rules and queries.

   Conjoin two rulebases, returning a new one with the same rules."
  [base1 base2]
  (concat base1 base2))

(defn fire-rules*
  "Fire rules for the given nodes."
  [rulebase nodes transient-memory transport listener get-alphas-fn]
  (binding [*current-session* {:rulebase rulebase
                               :transient-memory transient-memory
                               :transport transport
                               :insertions (atom 0)
                               :get-alphas-fn get-alphas-fn
                               :listener listener}]

    ;; Continue popping and running activations while they exist.
    (loop [activation (mem/pop-activation! transient-memory)]

      (when activation

        (let [{:keys [node token]} activation]

            (binding [*rule-context* {:token token :node node}]
              ((:rhs node) token (:env (:production node))))

          (recur (mem/pop-activation! transient-memory)))))))

(deftype LocalSession [rulebase memory transport listener get-alphas-fn]
  ISession
  (insert [session facts]
    (let [transient-memory (mem/to-transient memory)
          transient-listener (l/to-transient listener)]

      (l/insert-facts! transient-listener facts)

      (doseq [[alpha-roots fact-group] (get-alphas-fn facts)
              root alpha-roots]
        (alpha-activate root fact-group transient-memory transport transient-listener))

      (LocalSession. rulebase
                     (mem/to-persistent! transient-memory)
                     transport
                     (l/to-persistent! transient-listener)
                     get-alphas-fn)))

  (retract [session facts]

    (let [transient-memory (mem/to-transient memory)
          transient-listener (l/to-transient listener)]

      (l/retract-facts! transient-listener facts)

      (doseq [[alpha-roots fact-group] (get-alphas-fn facts)
              root alpha-roots]
        (alpha-retract root fact-group transient-memory transport transient-listener))

      (LocalSession. rulebase
                     (mem/to-persistent! transient-memory)
                     transport
                     (l/to-persistent! transient-listener)
                     get-alphas-fn)))

  (fire-rules [session]

    (let [transient-memory (mem/to-transient memory)
          transient-listener (l/to-transient listener)]
      (fire-rules* rulebase
                   (:production-nodes rulebase)
                   transient-memory
                   transport
                   transient-listener
                   get-alphas-fn)

      (LocalSession. rulebase
                     (mem/to-persistent! transient-memory)
                     transport
                     (l/to-persistent! transient-listener)
                     get-alphas-fn)))

  ;; TODO: queries shouldn't require the use of transient memory.
  (query [session query params]
    (let [query-node (get-in rulebase [:query-nodes query])]
      (when (= nil query-node)
        (platform/throw-error (str "The query " query " is invalid or not included in the rule base.")))
      (map :bindings (mem/get-tokens (mem/to-transient memory) query-node params))))

  (components [session]
    {:rulebase rulebase
     :memory memory
     :transport transport
     :listeners (if (l/null-listener? listener)
                  []
                  (l/get-children listener))
     :get-alphas-fn get-alphas-fn}))

(defn assemble
  "Assembles a session from the given components, which must be a map
   containing the following:

   :rulebase A recorec matching the clara.rules.compiler/Rulebase structure.
   :memory An implementation of the clara.rules.memory/IMemoryReader protocol
   :transport An implementation of the clara.rules.engine/ITransport protocol
   :listeners A vector of listeners implementing the clara.rules.listener/IPersistentListener protocol
   :get-alphas-fn The function used to

     "
  [{:keys [rulebase memory transport listeners get-alphas-fn]}]
  (LocalSession. rulebase
                 memory
                 transport
                 (if (> (count listeners) 0)
                   (l/delegating-listener listeners)
                   l/default-listener)
                 get-alphas-fn))

(defn local-memory
  "Returns a local, in-process working memory."
  [rulebase transport]
  (let [memory (mem/to-transient (mem/local-memory rulebase))]
    (doseq [beta-node (:beta-roots rulebase)]
      (left-activate beta-node {} [empty-token] memory transport l/default-listener))
    (mem/to-persistent! memory)))
