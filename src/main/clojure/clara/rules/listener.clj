(ns clara.rules.listener
  "Event listeners for analyzing the flow through Clara.")

(defprotocol IPersistentEventListener
  (to-transient [listener]))

;; TODO: Handle add-accum-reduced
(defprotocol ITransientEventListener
  (left-activate [listener node tokens])
  (left-retract [listener node tokens])
  (right-activate [listener node elements])
  (right-retract [listener node elements])
  (add-facts [listener facts])
  (retract-facts [listener facts])
  (add-accum-reduced [listener node join-bindings result fact-bindings])
  (add-activations [listener node activations])
  (remove-activations [listener node activations])
  (fire-rules [listener node])
  (send-message [listener message])
  (to-persistent [listener]))

;; A listener that does nothing.
(deftype NullListener []
  ITransientEventListener
  (left-activate [listener node tokens]
    listener)
  (left-retract [listener node tokens]
    listener)
  (right-activate [listener node elements]
    listener)
  (right-retract [listener node elements]
    listener)
  (add-facts [listener facts]
    listener)
  (retract-facts [listener facts]
    listener)
  (add-accum-reduced [listener node join-bindings result fact-bindings]
    listener)
  (add-activations [listener node activations]
    listener)
  (remove-activations [listener node activations]
    listener)
  (fire-rules [listener node]
    listener)
  (send-message [listener message]
    listener)
  (to-persistent [listener]
    listener)

  IPersistentEventListener
  (to-transient [listener]
    listener))

(declare delegating-listener)

;; A listener that simply delegates to others
(deftype DelegatingListener [children]
  ITransientEventListener
  (left-activate [listener node tokens]
    (doseq [child children]
      (left-activate child node tokens)))

  (left-retract [listener node tokens]
    (doseq [child children]
      (left-retract child node tokens)))

  (right-activate [listener node elements]
    (doseq [child children]
      (right-activate child node elements)))

  (right-retract [listener node elements]
    (doseq [child children]
      (right-retract child node elements)))

  (add-facts [listener facts]
    (doseq [child children]
      (add-facts child facts)))

  (retract-facts [listener facts]
    (doseq [child children]
      (retract-facts child facts)))

  (add-accum-reduced [listener node join-bindings result fact-bindings]
    (doseq [child children]
      (add-accum-reduced child node join-bindings result fact-bindings)))

  (add-activations [listener node activations]
    (doseq [child children]
      (add-activations child node activations)))

  (remove-activations [listener node activations]
    (doseq [child children]
      (remove-activations child node activations)))

  (fire-rules [listener node]
    (doseq [child children]
      (fire-rules child node)))

  (send-message [listener message]
    (doseq [child children]
      (send-message child message)))

  (to-persistent [listener]
    (delegating-listener (map to-persistent children))))

(deftype PersistentDelegatingListener [children]
  IPersistentEventListener
  (to-transient [listener]
    (DelegatingListener. (map to-transient children))))

(defn delegating-listener
  "Returns a listener that delegates to its children."
  [children]
  (PersistentDelegatingListener. children))

(defn get-children
  "Returns the children of a delegating listener."
  [^PersistentDelegatingListener listener]
  (.-children listener))

;; Default listener.
(def default-listener (NullListener.))