(ns propagators.prototype-2.world-specs
  "Specs for prototype_2 worlds — lexical environments as a DAG of linked frames.

  World ≈ env spine (Church p-list) + id. Parent→child is mono-directional:
  child lookup falls through to parent; rewrites only extend the child frame.

  Algebra (hibernation stubs allowed in the experiment):

    current-world : () → World
    reify         : World → WorldSpec
    rewrite       : Rule → WorldSpec → WorldSpec
    instantiate   : WorldSpec → World
    revive        : Transformation → World → World
    observe       : World → Expression → Information
    retire        : World → Effect
    activate      : World → Effect"
  (:require [clojure.spec.alpha :as s]))

;; ---------------------------------------------------------------------------
;; Identifiers & expressions
;; ---------------------------------------------------------------------------

(s/def ::world-id (s/or :kw keyword? :uuid uuid? :str string?))
(s/def ::env-cell keyword?)
(s/def ::parent-id (s/nilable ::world-id))
(s/def ::expression any?) ; L-2 form or host data (symbols, lists, …)
(s/def ::information any?)

;; ---------------------------------------------------------------------------
;; Rewrites / rules (data — often from `quote`)
;; ---------------------------------------------------------------------------

(s/def ::replace-definition
  (s/cat :op #{'replace-definition :replace-definition}
         :name symbol?
         :value any?))

(s/def ::forget-definition
  (s/cat :op #{'forget-definition :forget-definition}
         :name symbol?))

(s/def ::rule
  (s/or :replace ::replace-definition
        :forget ::forget-definition))

(s/def ::transformation
  (s/or :rule ::rule
        :rules (s/coll-of ::rule :kind sequential?)))

;; ---------------------------------------------------------------------------
;; World & WorldSpec (lineage as linked list of frames)
;; ---------------------------------------------------------------------------

(s/def ::frame-spec
  (s/keys :req-un [::world-id]
          :opt-un [::rewrites ::bindings-snapshot]))

(s/def ::rewrites (s/coll-of ::rule :kind vector?))
(s/def ::bindings-snapshot map?)

;; Linked-list shaped lineage: () | (frame . parent-lineage) as host seq
(s/def ::lineage
  (s/or :empty #{'() nil}
        :frames (s/coll-of ::frame-spec :kind sequential?)))

(s/def ::world-spec
  (s/keys :req-un [::world-id ::lineage]
          :opt-un [::parent-id ::hibernated?]))

(s/def ::hibernated? boolean?)

;; Live world: Church list handle (cons id (cons env-ref lineage)) + host side fields.
(s/def ::handle keyword?)
(s/def ::world
  (s/keys :req-un [::world-id ::env-cell ::handle]
          :opt-un [::parent-id ::lineage ::network]))

;; ---------------------------------------------------------------------------
;; Algebra ops (contracts)
;; ---------------------------------------------------------------------------

(s/fdef current-world
  :args (s/cat :session any?)
  :ret ::world)

(s/fdef reify-world
  :args (s/cat :world ::world)
  :ret ::world-spec)

(s/fdef rewrite-spec
  :args (s/cat :transformation ::transformation :spec ::world-spec)
  :ret ::world-spec)

(s/fdef instantiate
  :args (s/cat :spec ::world-spec :network any?)
  :ret ::world)

(s/fdef revive
  :args (s/cat :world ::world :transformation ::transformation)
  :ret ::world)

(s/fdef observe
  :args (s/cat :world ::world :expression ::expression)
  :ret ::information)

(s/fdef retire
  :args (s/cat :world ::world)
  :ret any?)

(s/fdef activate
  :args (s/cat :world-or-spec any?)
  :ret ::world)
