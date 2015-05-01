(ns arcadia.core
  (:require [clojure.string :as string]
            [arcadia.reflect :as r]
            [arcadia.internal.map-utils :as mu]
            arcadia.messages
            arcadia.literals
            arcadia.internal.editor-interop)
  (:import [UnityEngine
            Application
            MonoBehaviour
            GameObject
            Component
            PrimitiveType]))

(defn- regex? [x]
  (instance? System.Text.RegularExpressions.Regex x))


;; ============================================================
;; application
;; ============================================================

(defn editor? 
  "Returns true if called from within the editor. Notably, calls
  from the REPL are considered to be form within the editor"
  []
  Application/isEditor)

;; ============================================================
;; lifecycle
;; ============================================================

;; this one's really handy
(defn destroyed? [^UnityEngine.Object x]
  (UnityEngine.Object/op_Equality x nil))

(defn ia?
  "Stands for in-aot?, which is too many characters when we start
  stringing it into larger constructions. Returns true if and only if
  compiler is currently AOT-ing. Very useful for controling side
  effects to the scene graph."
  []
  (boolean *compile-files*))

(defmacro if-ia [& body]
  `(if (ia?)
     ~@body))

(defmacro when-ia [& body]
  (when (ia?)
    ~@body))

(defmacro when-not-ia [& body]
  `(when-not (ia?)
     ~@body))

(defmacro def-ia [& body]
  `(let [v# (declare ~name)]
     (when-ia (def ~name ~@body))
     v#))

(defmacro def-not-ia [name & body]
  `(let [v# (declare ~name)]
     (when-not-ia (def ~name ~@body))
     v#))

(defn bound-var? [v]
  (and (var? v)
    (not
      (instance? clojure.lang.Var+Unbound
        (var-get v)))))

;; this one could use some more work
(defmacro defscn [name & body]
  `(let [v# (declare ~name)]
     (when-not-ia
       (let [bldg# (do ~@body)]
         (when (and (bound-var? (resolve (quote ~name)))
                 (or (instance? UnityEngine.GameObject ~name)
                   (instance? UnityEngine.Component ~name))
                 (not (destroyed? ~name)))
           (destroy ~name))
         (def ~name bldg#)))
     v#))

;; ============================================================
;; defcomponent 
;; ============================================================

(defmacro defleaked [var]
  `(def ~(with-meta (symbol (name var)) {:private true})
     (var-get (find-var '~var))))

(defleaked clojure.core/validate-fields)
(defleaked clojure.core/parse-opts+specs)
(defleaked clojure.core/build-positional-factory)

(defn- emit-defclass* 
  "Do not use this directly - use defcomponent"
  [tagname name extends assem fields interfaces methods]
  (assert (and (symbol? extends) (symbol? assem)))
  (let [classname (with-meta
                    (symbol
                      (str (namespace-munge *ns*) "." name))
                    (meta name))
        interfaces (conj interfaces 'clojure.lang.IType)]
    `(defclass*
       ~tagname ~classname
       ~extends ~assem
       ~fields 
       :implements ~interfaces 
       ~@methods)))

;; ported from deftype. should remove opts+specs, bizarre as a key. 
(defn- component-defform [{:keys [name fields constant opts+specs ns-squirrel-sym]}]
  (validate-fields fields name)
  (let [gname name ;?
        [interfaces methods opts] (parse-opts+specs opts+specs)
        ns-part (namespace-munge *ns*)
        classname (symbol (str ns-part "." gname))
        hinted-fields fields
        fields (vec (map #(with-meta % nil) fields))
        [field-args over] (split-at 20 fields)
        frm `(do
               ~(emit-defclass*
                  name
                  gname
                  'UnityEngine.MonoBehaviour
                  'UnityEngine
                  (vec hinted-fields)
                  (vec interfaces)
                  methods)
               (import ~classname)
               ~(build-positional-factory gname classname fields)
               ~classname)]
    (if constant
      `(when-not (instance? System.MonoType (resolve (quote ~name)))
         ~frm)
      frm)))

(defn- normalize-method-implementations [mimpls]
  (for [[[interface] impls] (partition 2
                             (partition-by symbol? mimpls))
        [name args & fntail] impls]
    (mu/lit-map interface name args fntail)))

(defn- find-message-interface-symbol [s]
  (when (contains? arcadia.messages/messages s) ;; bit janky
    (symbol (str "arcadia.messages.I" s))))

(defn- awake-method? [{:keys [name]}]
  (= name 'Awake))

(defn- normalize-message-implementations [msgimpls]
  (for [[name args & fntail] msgimpls
        :let [interface (find-message-interface-symbol name)]]
    (mu/lit-map interface name args fntail)))

(defn- process-method [{:keys [name args fntail]}]
  `(~name ~args ~@fntail))

;; (defn- ensure-has-message [interface-symbol args mimpls]
;;   (if (some #(= (:name %) interface-symbol) mimpls)
;;     mimpls
;;     (cons {:interface (find-message-interface-symbol interface-symbol)
;;            :name     interface-symbol
;;            :args     args
;;            :fntail   nil}
;;       mimpls)))

(defn- ensure-has-method [{msg-name :name,
                           :keys [args interface]
                           :or {args '[_]},
                           :as default},
                          mimpls]
  (let [{:keys [interface]
         :or {interface (find-message-interface-symbol msg-name)}} default]
    (assert interface "Must declare interface or use known message name")
    (if (some #(= (:name %) msg-name) mimpls)
      mimpls
      (cons
        (merge {:interface interface
                :name     msg-name
                :args     args
                :fntail   nil}
          default)
        mimpls))))

(defn- process-require-trigger [impl ns-squirrel-sym]
  (update-in impl [:fntail]
    #(cons `(do
              (require (quote ~(ns-name *ns*))))
       %)))

(defn- require-trigger-method? [mimpl]
  (boolean
    (#{'Awake 'OnDrawGizmosSelected 'OnDrawGizmos 'Start}
     (:name mimpl))))

(defn- collapse-method [impl]
  (mu/checked-keys [[name args fntail] impl]
    `(~name ~args ~@fntail)))

(defn default-on-after-deserialize [this]
  (require 'arcadia.literals)
  (try 
    (doseq [[field-name field-value]
            (eval (read-string (. this _serialized_data)))]
      (.. this
          GetType
          (GetField field-name)
          (SetValue this field-value)))
    (catch ArgumentException e
      (throw (ArgumentException. (str 
                                   "Could not deserialize "
                                   this
                                   ". EDN might be invalid."))))))

(defn default-on-before-serialize [this]
  (require 'arcadia.internal.editor-interop)
  (let [field-map (arcadia.internal.editor-interop/field-map this)
        serializable-fields (mapv #(.Name %) (arcadia.internal.editor-interop/serializable-fields this))
        field-map-to-serialize (apply dissoc field-map serializable-fields)]
    (set! (. this _serialized_data) (pr-str field-map-to-serialize))))

(defn- process-defcomponent-method-implementations [mimpls ns-squirrel-sym]
  (let [[msgimpls impls] ((juxt take-while drop-while)
                          (complement symbol?)
                          mimpls)]
    (->>
      (concat
        (normalize-message-implementations msgimpls)
        (normalize-method-implementations impls))
      (ensure-has-method {:name 'Start})
      (ensure-has-method {:name 'Awake})
      (ensure-has-method
        {:name 'OnAfterDeserialize
         :interface 'UnityEngine.ISerializationCallbackReceiver
         :args '[this]
         :fntail '[(require 'arcadia.core)
                   (arcadia.core/default-on-after-deserialize this)]})
      
      (ensure-has-method
        {:name 'OnBeforeSerialize
         :interface 'UnityEngine.ISerializationCallbackReceiver
         :args '[this]
         :fntail '[(require 'arcadia.core)
                   (arcadia.core/default-on-before-serialize this)]})
      (map (fn [impl]
             (if (require-trigger-method? impl)
               (process-require-trigger impl ns-squirrel-sym)
               impl)))
      (group-by :interface)
      (mapcat
        (fn [[k impls]]
          (cons k (map collapse-method impls)))))))


(defmacro defcomponent*
  "Defines a new component. See defcomponent for version with defonce
  semantics."
  [name fields & method-impls]
  (let [fields2 (mapv #(vary-meta % assoc :unsynchronized-mutable true) fields) ;make all fields mutable
        ns-squirrel-sym (gensym (str "ns-required-state-for-" name "_"))
        method-impls2 (process-defcomponent-method-implementations method-impls ns-squirrel-sym)]
    (component-defform
      {:name name
       :fields fields2
       :opts+specs method-impls2
       :ns-squirrel-sym ns-squirrel-sym})))

(defmacro defcomponent-once
  "Defines a new component. defcomponent forms will not evaluate if
  name is already bound, thus avoiding redefining the name of an
  existing type (possibly with live instances). For redefinable
  defcomponent, use defcomponent*."
  [name fields & method-impls] 
  (let [fields2 (conj (mapv #(vary-meta % assoc :unsynchronized-mutable true) fields) ;make all fields mutable
                      (with-meta '_serialized_data {:tag 'String
                                                    :unsynchronized-mutable true
                                                    UnityEngine.HideInInspector {}}))
        ns-squirrel-sym (gensym (str "ns-required-state-for-" name "_"))
        method-impls2 (process-defcomponent-method-implementations method-impls ns-squirrel-sym)]
    `(do
       ~(component-defform
          {:name name
           :constant true
           :fields fields2
           :opts+specs method-impls2
           :ns-squirrel-sym ns-squirrel-sym}))))

(defmacro defeditor
  [sym]
  (let [target (resolve sym)
        editor-name (symbol (str (.Name target) "Editor"))
        editor-classname (with-meta
                           (symbol (str (.FullName target) "Editor"))
                           {UnityEditor.CustomEditor target})]
    `(defclass*
       ~editor-name
       ~editor-classname
       UnityEditor.Editor ~'UnityEditor
       []
       :implements [clojure.lang.IType]
       (OnInspectorGUI
         [this#]
         (require 'arcadia.inspectors)
         (arcadia.inspectors/render-gui (.target this#))))))

(defmacro defcomponent [sym & rest]
  `(do
     (defcomponent-once ~sym ~@rest)
     ; (defeditor ~sym)
     ))


;; ============================================================
;; type utils
;; ============================================================

(defn- same-or-subclass? [^Type a ^Type b]
  (or (= a b)
    (.IsSubclassOf a b)))

;; put elsewhere
(defn- some-2
  "Uses reduced, should be faster + less garbage + more general than clojure.core/some"
  [pred coll]
  (reduce #(when (pred %2) (reduced %2)) nil coll))

(defn- in? [x coll]
  (boolean (some-2 #(= x %) coll)))
 ; reference to tagged var, or whatever 

;; really ought to be testing for arity as well
(defn- type-has-method? [t mth]
  (in? (symbol mth) (map :name (r/methods t :ancestors true))))

(defn- type-name? [x]
  (boolean
    (and (symbol? x)
      (when-let [y (resolve x)]
        (instance? System.MonoType y)))))

(defn- type-of-local-reference [x env]
  (assert (contains? env x))
  (let [lclb ^clojure.lang.CljCompiler.Ast.LocalBinding (env x)]
    (when (.get_HasClrType lclb)
      (.get_ClrType lclb))))

(defn- type? [x]
  (instance? System.MonoType x))

(defn- ensure-type [x]
  (cond
    (type? x) x
    (symbol? x) (let [xt (resolve x)]
                  (if (type? xt)
                    xt
                    (throw
                      (Exception.
                        (str "symbol does not resolve to a type")))))
    :else (throw
            (Exception.
              (str "expects type or symbol")))))

(defn- tag-type [x]
  (when-let [t (:tag (meta x))]
    (ensure-type t)))

(defn- type-of-reference [x env]
  (or (tag-type x)
    (and (symbol? x)
      (if (contains? env x)
        (type-of-local-reference x env) ; local
        (let [v (resolve x)] ;; dubious
          (when (not (and (var? v) (fn? (var-get v))))
            (tag-type v))))))) 

;; ============================================================
;; condcast->
;; ============================================================

(defn- maximize
  ([xs]
   (maximize (comparator >) xs))
  ([compr xs]
   (when (seq xs)
     (reduce
       (fn [mx x]
         (if (= 1 (compr mx x))
           x
           mx))
       xs))))

(defn- most-specific-type ^Type [& types]
  (maximize (comparator same-or-subclass?)
    (remove nil? types)))

(def ccc-log (atom []))

(defn- contract-condcast-clauses [expr xsym clauses env]
  (let [etype (most-specific-type
                (type-of-reference expr env)
                (tag-type xsym))]
    (swap! ccc-log conj etype)
    (if etype
      (if-let [[_ then] (first
                          (filter #(= etype (ensure-type (first %)))
                            (partition 2 clauses)))]
        [then]
        (->> clauses
          (partition 2)
          (filter
            (fn [[t _]]
              (same-or-subclass? etype (ensure-type t))))
          (apply concat)))
      clauses)))

;; note this takes an optional default value. This macro is potentially
;; annoying in the case that you want to branch on a supertype, for
;; instance, but the cast would remove interface information. Use with
;; this in mind.
(defmacro condcast-> [expr xsym & clauses]
  (let [[clauses default] (if (even? (count clauses))
                            [clauses nil] 
                            [(butlast clauses)
                             [:else
                              `(let [~xsym ~expr]
                                 ~(last clauses))]])
        clauses (contract-condcast-clauses
                  expr xsym clauses &env)]
    (cond
      (= 0 (count clauses))
      `(let [~xsym ~expr]
         ~default) ;; might be nil obvi

      (= 1 (count clauses)) ;; corresponds to exact type match. janky but fine
      `(let [~xsym ~expr]
         ~@clauses)

      :else
      `(let [~xsym ~expr]
         (cond
           ~@(->> clauses
               (partition 2)
               (mapcat
                 (fn [[t then]]
                   `[(instance? ~t ~xsym)
                     (let [~(with-meta xsym {:tag t}) ~xsym]
                       ~then)]))))))))

;; ============================================================
;; get-component
;; ============================================================

(defn- camels-to-hyphens [s]
  (string/replace s #"([a-z])([A-Z])" "$1-$2"))

(defn- dedup-by [f coll]
  (map peek (vals (group-by f coll))))

;; maybe we should be passing full method sigs around rather than
;; method names. 
(defn- known-implementer-reference? [x method-name env]
  (boolean
    (when-let [tor (type-of-reference x env)]
      (type-has-method? tor method-name))))

(defn- raise-args [[head & rst]]
  (let [gsyms (repeatedly (count rst) gensym)]
    `(let [~@(interleave gsyms rst)]
       ~(cons head gsyms))))

(defn- raise-non-symbol-args [[head & rst]]
  (let [bndgs (zipmap 
                (remove symbol? rst)
                (repeatedly gensym))]
    `(let [~@(mapcat reverse bndgs)]
       ~(cons head (replace bndgs rst)))))

(defn- gc-default-body [obj t]
  `(condcast-> ~t t2#
     Type   (condcast-> ~obj obj2#
              UnityEngine.GameObject (.GetComponent obj2# t2#)
              UnityEngine.Component (.GetComponent obj2# t2#))
     String (condcast-> ~obj obj2#
              UnityEngine.GameObject (.GetComponent obj2# t2#)
              UnityEngine.Component (.GetComponent obj2# t2#))))

(defmacro ^:private gc-default-body-mac [obj t]
  (gc-default-body obj t))

(defn- gc-rt [obj t env]
  (let [implref (known-implementer-reference? obj 'GetComponent env)
        t-tr (type-of-reference t env)]
    (cond
      (isa? t-tr Type)
      (if implref
        `(.GetComponent ~obj (~'type-args ~t))
        `(condcast-> ~obj obj#
           UnityEngine.GameObject (.GetComponent obj# (~'type-args ~t))
           UnityEngine.Component (.GetComponent obj# (~'type-args ~t))))

      (isa? t-tr String)
      (if implref
        `(.GetComponent ~obj ~t)
        `(condcast-> ~obj obj#
           UnityEngine.GameObject (.GetComponent obj# ~t)
           UnityEngine.Component (.GetComponent obj# ~t)))
      
      :else
      (if implref
        `(condcast-> ~t t#
           Type (.GetComponent ~obj t#)
           String (.GetComponent ~obj t#))
        (gc-default-body obj t)))))

(defmacro get-component* [obj t]
  (if (not-every? symbol? [obj t])
    (raise-non-symbol-args
      (list 'arcadia.core/get-component* obj t))
    (gc-rt obj t &env)))

(defn get-component
  "Returns the component of Type type if the game object has one attached, nil if it doesn't.
  
  * obj - the object to query, a GameObject or Component
  * type - the type of the component to get, a Type or String"
  {:inline (fn [gameobject type]
             (list 'arcadia.core/get-component* gameobject type))
   :inline-arities #{2}}
  [obj t]
  (gc-default-body-mac obj t))

(defn add-component 
  "Add a component to a gameobject
  
  * gameobject - the GameObject recieving the component, a GameObject
  * type       - the type of the component, a Type"
  {:inline (fn [gameobject type]
             `(.AddComponent ~gameobject ~type))
   :inline-arities #{2}}
  [^GameObject gameobject ^Type type]
  (.AddComponent gameobject type))


;; ============================================================
;; parent/child
;; ============================================================

(defn unparent ^GameObject [^GameObject child]
  (set! (.parent (.transform child)) nil)
  child)

(defn unchild ^GameObject [^GameObject parent ^GameObject child]
  (when (= parent (.parent (.transform child)))
    (unparent child))
  parent)

(defn set-parent ^GameObject [^GameObject child ^GameObject parent]
  (set! (.parent (.transform child)) (.transform parent))
  child)

(defn set-child ^GameObject [^GameObject parent child]
  (set-parent child parent)
  parent)

;; ============================================================
;; wrappers
;; ============================================================

(defn- hintable-type [t]
  (cond (= t System.Single) System.Double
    (= t System.Int32) System.Int64
    (= t System.Boolean) nil
    :else t))

(defmacro ^:private defwrapper
  "Wrap static methods of C# classes
  
  * class - the C# class to wrap, a Symbol
  * method - the C# method to wrap, a Symbol
  * docstring - the documentation for the wrapped method, a String
  * name - the name of the corresponding Clojure function, a Symbol
  
  Used internally to wrap parts of the Unity API, but generally useful."
  ([class]
   `(do ~@(map (fn [m]
          `(defwrapper
             ~class
             ~(symbol (.Name m))
             ~(str "TODO No documentation for " class "/" (.Name m))))
        (->>
          (.GetMethods
            (resolve class)
            (enum-or BindingFlags/Public BindingFlags/Static))
          (remove #(or (.IsSpecialName %) (.IsGenericMethod %)))))))
  ([class method docstring]
   `(defwrapper
     ~(symbol (string/lower-case
                (camels-to-hyphens (str method))))
     ~class
     ~method
     ~docstring))
  ([name class method docstring & body]
   `(defn ~name
      ~(str docstring (let [link-name (str (.Name (resolve class)) "." method)]
                        (str "\n\nSee also ["
                        link-name
                        "](http://docs.unity3d.com/ScriptReference/"
                        link-name
                        ".html) in Unity's reference.")))
      ~@(->> (.GetMethods
               (resolve class)
               (enum-or BindingFlags/Public BindingFlags/Static))
             (filter #(= (.Name %) (str method)))
             (remove #(.IsGenericMethod %))
             (dedup-by #(.Length (.GetParameters %)))
             (map (fn [m]
                    (let [params (map #(with-meta (symbol (.Name %))
                                                  {:tag (hintable-type (.ParameterType %))})
                                      (.GetParameters m))] 
                      (list (with-meta (vec params) {:tag (hintable-type (.ReturnType m))})
                            `(~(symbol (str class "/" method)) ~@params))))))
      ~@body)))

(defwrapper instantiate UnityEngine.Object Instantiate
  "Clones the object original and returns the clone.
  
  * original the object to clone, GameObject or Component
  * position the position to place the clone in space, a Vector3
  * rotation the rotation to apply to the clone, a Quaternion"
  ([^UnityEngine.Object original ^UnityEngine.Vector3 position]
   (UnityEngine.Object/Instantiate original position Quaternion/identity)))

(defn create-primitive
  "Creates a game object with a primitive mesh renderer and appropriate collider.
  
  * prim - the kind of primitive to create, a Keyword or a PrimitiveType.
           Keyword can be one of :sphere :capsule :cylinder :cube :plane :quad"
  [prim]
  (if (= PrimitiveType (type prim))
    (GameObject/CreatePrimitive prim)
    (GameObject/CreatePrimitive (case prim
                                  :sphere   PrimitiveType/Sphere
                                  :capsule  PrimitiveType/Capsule
                                  :cylinder PrimitiveType/Cylinder
                                  :cube     PrimitiveType/Cube
                                  :plane    PrimitiveType/Plane
                                  :quad     PrimitiveType/Quad))))

(defn destroy 
  "Removes a gameobject, component or asset.
  
  * obj - the object to destroy, a GameObject, Component, or Asset
  * t   - timeout before destroying object, a float"
  ([^UnityEngine.Object obj]
   (if (editor?)
    (UnityEngine.Object/DestroyImmediate obj)
    (UnityEngine.Object/Destroy obj)))
  ([^UnityEngine.Object obj ^double t]
   (UnityEngine.Object/Destroy obj t)))

(defwrapper object-typed UnityEngine.Object FindObjectOfType
  "Returns the first active loaded object of Type type.
  
  * type - The type to search for, a Type")

(defwrapper objects-typed UnityEngine.Object FindObjectsOfType
  "Returns a list of all active loaded objects of Type type.
  
  * type - The type to search for, a Type")

(defwrapper object-named GameObject Find
  "Finds a game object by name and returns it.
  
  * name - The name of the object to find, a String")

;; type-hinting of condcast isn't needed here, but seems a good habit to get into
(defn objects-named
  "Finds game objects by name.
  
  * name - the name of the objects to find, can be A String or regex"
  [name]
  (condcast-> name name
    System.String
    (for [^GameObject obj (objects-typed GameObject)
          :when (= (.name obj) name)]
      obj)
    
    System.Text.RegularExpressions.Regex
    (for [^GameObject obj (objects-typed GameObject)
          :when (re-matches name (.name obj))]
      obj)
    
   ; (throw (Exception. (str "Expects String or Regex, instead got " (type name))))
    ))

(defwrapper object-tagged GameObject FindWithTag
  "Returns one active GameObject tagged tag. Returns null if no GameObject was found.
  
  * tag - the tag to seach for, a String")

(defwrapper objects-tagged GameObject FindGameObjectsWithTag
  "Returns a list of active GameObjects tagged tag. Returns empty array if no GameObject was found.
  
  * tag - the tag to seach for, a String")
