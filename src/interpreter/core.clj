(ns interpreter.core
  (:require [clojure.core.match :refer [match]]))

(def clojure-apply apply)

(def ^:dynamic *debug* true)

(defmacro log [& xs] `(when *debug* (println ~@xs)))

(def ^:dynamic *the-store* (atom {}))

(defn new-env [] '())

(defn fresh-store [] (reset! *the-store* {}) *the-store*)

(defn getval [[value env]] value)

(defn extend-environment
  [env frame]
  (conj env frame))

(def primitive-procedures
  { (symbol '+) +
    (symbol '-) -
    (symbol '*) *
    (symbol '/) /
    (symbol '=) =
    (symbol '<) <
    (symbol '>) >
    (symbol 'cons) cons
    (symbol 'rest) rest
    (symbol 'print) println
    'nil nil
   })

(defn make-frame
  "[& [variable value]] store -> [frame store].
  Takes a lists of bindings and returns a new frame and a new store."
  [bindings store]
  (loop [frame {}
         bindings bindings]
    (if (seq bindings)
      (let [[variable value] (first bindings)
            addr (gensym)]
        (swap! store assoc addr value)
        (recur (assoc frame variable addr)
               (rest bindings)))
      frame)))

(defn fresh-env []
  "-> [env store]"
  [(extend-environment (new-env)
                       (make-frame primitive-procedures (fresh-store)))
   *the-store*])

(declare scheme-eval)

(defn add-binding-to-frame!
  "mutates the frame by either adding a new binding to the variable or
  replacing the old binding with the value provided."
  [frame variable value]
  (swap! frame assoc variable value))

(defn lookup-variable-value
  "Symbol env store -> value (or nil).

  Finds the value of the variable in the first frame that contains it.
  Returns nil if no frames contain it."
  [variable env store]
  (when-let [frame (first (filter #(% variable) env))]
    (log "found-frame!" frame)
    (let [addr (frame variable)
          value (@store addr)]
      value)))

(def primitive-procedure?
  (memoize (fn [procedure]
             (contains? (into #{} (vals primitive-procedures))
                        procedure))))

(defn compound-procedure?
  [procedure]
  (:procedure procedure))

(defn make-procedure
  "Returns the datum representing procedure.
  Has a field :procedure which is set to true"
  [parameters body env store k]
  {:procedure true
   :k (fn [args env store k]
        (scheme-eval body
                     (extend-environment
                       (make-frame (partition 2 (interleave parameters args)) store))
                     store k))})

(declare eval-sequence)

(defn scheme-apply
  [procedure arguments env store k]
  (let [proc (getval procedure)
        args (map getval arguments)]

    (log "apply, proc:"  proc)
    (log "primtive-procedure?" (primitive-procedure? proc))
    (log "compound-procedure?" (compound-procedure? proc))

    (cond (primitive-procedure? proc)
            [(clojure-apply proc args) env]
          (compound-procedure? proc)
            (eval-sequence (:body proc)
                           (extend-environment (:env proc)
                                               (make-frame (map vector (:parameters proc) args) store))
                           store k)
            :else (throw (IllegalArgumentException.
                          (str "Undefined procedure: <" procedure "> on arguments: " (seq arguments)))))))

(defn definefun->lambda
  "(define (fun p q) <body>) -> (define fun (lambda p q) <body>)"
  [exp]
  (let [[fun & params] `~(nth exp 1)
        body `~(nth exp 2)]
    `(~'define ~fun (~'lambda ( ~@params) ~body))))

(defn let->lambda
  "let form -> closed over lambda form"
  [exp]
  (let [[_let bindings body] exp
        vars (map first bindings)
        values (map second bindings)]
    `((~'lambda ~vars ~body) ~@values)))

(defn eval-definition
  "Variable value env store -> [nil env].

  Binds the variable to the value in the first frame containing the variable.
  If no frame contains the variable, adds the binding to the first frame.
  "
  [variable [value env] store]
  (log "eval-def:" variable ", first frame:" (first (filter #(% variable) env)))
  (if-let [frame (first (filter #(% variable) env))]
    (let [addr (frame variable)]
      (swap! store assoc addr value)
      [nil env])
    (let [frame (make-frame [[variable value]] store)
          new-frame (merge frame (first env))]
      [nil (cons new-frame (rest env))])))

(defn eval-assignment
  [variable [value env] store]
  (log "assignment!" variable value)
  (if-let [frame (first (filter #(% variable) env))]
    (let [addr (frame variable)]
      (swap! store assoc addr value)
      [value env])
    (throw (IllegalArgumentException.
             (str "SET! unbound symbol '" variable "'")))))

(defn begin-expressions
  [exp]
  (rest exp))

#_(defn eval-all
  "Evaluates each expression in exps in the environment env. Literally maps
  scheme-eval over exps."
  [exps env store k]
  (map #(scheme-eval % env store k) exps))

#_(defn eval-all
  ([exps env store k vs]
   (log vs)
   (if (empty? exps)
     vs
     (eval-all (rest exps) env store (fn [v env store]
                                       (scheme-eval (first exps) env store k))))))
(defn eval-all
  [f args env store k]
  (reduce args
          (fn [curr n])
          )
  )

(eval-all '(+ 1 (+ 2 3) 4 5 6) (first (fresh-env)) *the-store* (fn [v env store] (println "HALT!") v))

(defn eval-sequence
  "Evaluates each expressions in exps in the environment env. Returns the
  value of the last expression."
  [exps env store k]
  (loop [exps exps
         env env]
    (let [[value env] (scheme-eval (first exps) env store k)]
      (if (seq (rest exps))
        (recur (rest exps) env)
        [value env]))))

#_(defn eval-if
  [exp env store k]
  (log "eval-if" (scheme-eval (nth exp 1) env store k))
  (let [[v env] (scheme-eval (nth exp 1) env store k)]
    (or (and v (scheme-eval (nth exp 2) env store k))
        (scheme-eval (nth exp 3) env store k))))

(defn eval-if
  [exp env store k]
  (let [[pred x else] exp]
    (scheme-eval pred env store (fn [pred]
                                  (or (and pred (scheme-eval x env store k))
                                      (scheme-eval else env store k))))))


(defn cond->if
  [pairs]
  (if-let [pair (first pairs)]
    `(if ~(first pair) ~(second pair)
       ~(cond->if (rest pairs)))
    nil))

(defn halt
  "A slight variation on the identity function which prints 'HALT!' to stdout."
  [val env store]
  (println "HALT!" val)
  val)

;; TODO: optimize
(defn primitive?
  [sym]
  (contains? (set (keys primitive-procedures)) sym))

(defn scheme-eval
  "exp env store k -> [value env]."
  [exp env store k]
  (log "eval" exp)
  (match [exp]
         [(_ :guard #(or (number? %) (string? %) (false? %) (true? %)))] (k exp env store)
         [(_ :guard symbol?)] (k (lookup-variable-value exp env store) env store)
         [(['quote & e] :seq)] (k (first e) env store)
         [(['lambda & e] :seq)] (let [parameters (first e) body (rest e)]
                                  (k (make-procedure parameters body env store k) env store))
         ;[(['if & e] :seq)] (eval-if e env store k)
         ;[(['define (sym :guard (complement seq?)) v] :seq)] (eval-definition sym (scheme-eval v env store k) store)
         ;[(['set! sym v] :seq)] (eval-assignment sym (scheme-eval v env store k) store)
         ;[(['define (_ :guard seq?) & r] :seq)] (scheme-eval (definefun->lambda exp) env store k)
         ;[(['let ( _ :guard seq?) & r] :seq)] (scheme-eval (let->lambda exp) env store k)
         ;[(['begin & e] :seq)] (eval-sequence e env store k)
         ;[(['cond & e] :seq)] (scheme-eval (cond->if e) env store k)

         [([( f :guard primitive?) & r] :seq)] (scheme-eval (first r) env store
                                                            (fn [left env store]
                                                              (scheme-eval (second r) env store
                                                                           (fn [right env store]
                                                                             (scheme-eval f env store
                                                                                          (fn [f env store]
                                                                                            (scheme-eval (f left right) env store (fn [v env store] (k v env store)))))))))

         ;[([( f :guard #(:procedure %)) & r] :seq)] (eval-all (or (:k (first exp)) exp) env store k)
         :else (eval-all exp env store k)
         ;:else (scheme-apply (scheme-eval  (first exp) env store k)
         ;                    (eval-all     (rest exp) env store k)
         ;                    env store k)
  ))

(defn repl [[res env]]
  (println res)
  (print "=>  ")
  (flush)
  (recur (scheme-eval (read-string (read-line)) env *the-store*)))

(defn -main
  [& args]

  (set! *print-level* 4)

  (let [[env store] (fresh-env)]
    (if (seq? args)
      (doseq [filename args]
        (doseq [form (read-string (slurp filename))]
          (scheme-eval form env store)))
      (repl (scheme-eval '(cons "ponzi-scheme" '()) env store)))))
