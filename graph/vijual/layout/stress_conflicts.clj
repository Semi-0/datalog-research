(ns graph.vijual.layout.stress-conflicts
  "Geometric edge conflicts, independent of the solver and renderer.")

(def ^:private epsilon 1.0e-8)

(defn point [geometry i]
  [(aget ^doubles (:xs geometry) i) (aget ^doubles (:ys geometry) i)])

(defn closest-point [[x y] [ax ay] [bx by]]
  (let [dx (- bx ax) dy (- by ay) length (+ (* dx dx) (* dy dy))
        t (if (pos? length)
            (max 0.0 (min 1.0 (/ (+ (* (- x ax) dx) (* (- y ay) dy)) length)))
            0.0)
        px (+ ax (* t dx)) py (+ ay (* t dy))]
    {:t t :point [px py] :distance (Math/hypot (- x px) (- y py))}))

(defn- cross [[ax ay] [bx by] [cx cy]]
  (- (* (- bx ax) (- cy ay)) (* (- by ay) (- cx ax))))

(defn crossing? [a b c d]
  (and (neg? (* (cross a b c) (cross a b d)))
       (neg? (* (cross c d a) (cross c d b)))))

(defn overlap-length [a b c d]
  (let [[ax ay] a [bx by] b length (Math/hypot (- bx ax) (- by ay))]
    (if (and (> length epsilon)
             (<= (Math/abs (double (cross a b c))) (* epsilon length))
             (<= (Math/abs (double (cross a b d))) (* epsilon length)))
      (let [ux (/ (- bx ax) length) uy (/ (- by ay) length)
            project (fn [[x y]] (+ (* (- x ax) ux) (* (- y ay) uy)))
            tc (project c) td (project d)]
        (max 0.0 (- (min length (max tc td)) (max 0.0 (min tc td)))))
      0.0)))

(defn- near-bounds? [[ax ay] [bx by] [cx cy] [dx dy] margin]
  (and (<= (- (min ax bx) margin) (max cx dx))
       (<= (- (min cx dx) margin) (max ax bx))
       (<= (- (min ay by) margin) (max cy dy))
       (<= (- (min cy dy) margin) (max ay by))))

(defn- normal [[ax ay] [bx by]]
  (let [dx (- bx ax) dy (- by ay) length (Math/hypot dx dy)]
    (if (> length epsilon) [(/ (- dy) length) (/ dx length)] [0.0 1.0])))

(defn segment-hits-box?
  "True for intersection with the box interior; tangency alone is not overlap."
  [[ax ay] [bx by] [cx cy] half-width half-height]
  (loop [axes [[ax bx cx half-width] [ay by cy half-height]] low 0.0 high 1.0]
    (if (empty? axes)
      (< low high)
      (let [[a b center half] (first axes)
            minimum (+ (- center half) epsilon) maximum (- (+ center half) epsilon)
            delta (- b a)]
        (if (zero? delta)
          (if (< minimum a maximum) (recur (rest axes) low high) false)
          (let [entry (/ (- minimum a) delta) exit (/ (- maximum a) delta)
                low (max low (min entry exit)) high (min high (max entry exit))]
            (if (< low high) (recur (rest axes) low high) false)))))))

(defn- node-event [geometry vertex a b clearance]
  (let [[x y :as position] (point geometry vertex)
        start (point geometry a) end (point geometry b)
        {:keys [point distance t]} (closest-point position start end)
        [px py] point
        direction (if (> distance epsilon)
                    [(/ (- x px) distance) (/ (- y py) distance)]
                    (normal start end))]
    {:vertex vertex :a a :b b :t t :normal direction
     :distance distance :clearance clearance :deficit (max 0.0 (- clearance distance))}))

(defn- add-event [result event weight]
  (let [cost (* weight (:deficit event) (:deficit event))
        event (assoc event :cost cost)]
    (cond-> (update result :penalty + cost)
      (> cost (get-in result [:worst :cost] -1.0)) (assoc :worst event))))

(defn- node-conflicts [config geometry result]
  (let [^longs aa (:aa config) ^longs bb (:bb config)
        ^doubles radii (:radii geometry) weight (:overlap-weight config)]
    (reduce
     (fn [result i]
       (let [a (aget aa i) b (aget bb i) start (point geometry a) end (point geometry b)]
         (reduce
          (fn [result vertex]
            (let [clearance (+ (aget radii vertex) (:edge-clearance config))]
              (if (and (not= vertex a) (not= vertex b)
                       (near-bounds? (point geometry vertex) (point geometry vertex) start end clearance))
                (let [event (node-event geometry vertex a b clearance)
                      hit? (segment-hits-box? start end (point geometry vertex)
                                             (aget ^doubles (:half-widths config) vertex)
                                             (aget ^doubles (:half-heights config) vertex))
                      result (if hit? (update result :edge-node-overlaps inc) result)]
                  (if (> (:deficit event) epsilon)
                    (-> result (update :node-edge-conflicts inc) (add-event event weight))
                    result))
                result)))
          result (range (alength radii)))))
     result (range (alength aa)))))

(defn- node-pair-conflicts [config geometry result]
  (let [^doubles radii (:radii geometry)
        ^doubles widths (:half-widths config) ^doubles heights (:half-heights config)]
    (reduce
     (fn [result [a b]]
       (let [[ax ay] (point geometry a) [bx by] (point geometry b)
             dx (- ax bx) dy (- ay by) distance (Math/hypot dx dy)
             target (+ (aget radii a) (aget radii b) (:node-gap config))
             deficit (max 0.0 (- target distance))
             overlap? (and (< (Math/abs dx) (- (+ (aget widths a) (aget widths b)) epsilon))
                           (< (Math/abs dy) (- (+ (aget heights a) (aget heights b)) epsilon)))
             result (if overlap? (update result :node-overlaps inc) result)]
         (if (> deficit epsilon)
           (let [[nx ny] (if (> distance epsilon) [(/ dx distance) (/ dy distance)] [0.0 1.0])
                 step (* 1.1 deficit)
                 event {:node-pair? true :deficit deficit
                        :moves [[a [(* step nx) (* step ny)]]
                                [b [(* (- step) nx) (* (- step) ny)]]]}]
             (-> result (update :node-node-conflicts inc) (add-event event (:overlap-weight config))))
           result)))
     result (for [a (range (alength radii)) b (range (inc a) (alength radii))] [a b]))))

(defn- endpoint-events [geometry a b c d clearance]
  (keep (fn [[vertex start end]]
          (when (and (not= vertex start) (not= vertex end))
            (node-event geometry vertex start end clearance)))
        [[a c d] [b c d] [c a b] [d a b]]))

(defn- crossing-proposals [geometry a b c d clearance]
  ;; Endpoint moves and whole-edge shifts both preserve the other edge.
  (let [start (point geometry c) end (point geometry d)
        direction (normal start end) [nx ny] direction
        target (+ clearance (max (aget ^doubles (:radii geometry) a)
                                  (aget ^doubles (:radii geometry) b)))
        signed (fn [i] (let [[x y] (point geometry i) [cx cy] start]
                         (+ (* (- x cx) nx) (* (- y cy) ny))))
        da (signed a) db (signed b)
        sign (if (neg? db) -1.0 1.0)
        move (fn [i amount] [i [(* nx amount) (* ny amount)]])
        positive (- target (min da db)) negative (- (- target) (max da db))]
    [[(move a (- (* sign target) da))]
     [(move b (- (* (- sign) target) db))]
     [(move a positive) (move b positive)]
     [(move a negative) (move b negative)]]))

(defn- crossing-event [geometry a b c d clearance]
  {:crossing? true :clearance clearance
   :candidates (concat (crossing-proposals geometry a b c d clearance)
                       (crossing-proposals geometry c d a b clearance))})

(defn- edge-conflict [config geometry result [a b] [c d]]
  (let [pa (point geometry a) pb (point geometry b)
        pc (point geometry c) pd (point geometry d)
        clearance (:edge-clearance config) weight (:overlap-weight config)]
    (if (near-bounds? pa pb pc pd clearance)
      (let [shared? (some #{a b} [c d])
            overlap (overlap-length pa pb pc pd)
            crossing (and (not shared?) (crossing? pa pb pc pd))
            event (first (sort-by :distance (endpoint-events geometry a b c d clearance)))
            deficit (cond
                      (> overlap epsilon) (min clearance overlap)
                      crossing clearance
                      (and (not shared?) (some? event)) (:deficit event)
                      :else 0.0)
            event (if crossing (crossing-event geometry a b c d clearance) event)
            result (cond-> result
                     (> overlap epsilon) (update :edge-overlaps inc)
                     crossing (update :edge-crossings inc))]
        (if (and (> deficit epsilon) (some? event))
          (-> result (update :edge-near-conflicts inc)
              (add-event (assoc event :deficit deficit) weight))
          result))
      result)))

(def empty-analysis
  {:penalty 0.0 :edge-overlaps 0 :edge-crossings 0 :edge-near-conflicts 0
   :node-edge-conflicts 0 :edge-node-overlaps 0 :node-node-conflicts 0 :node-overlaps 0 :worst nil})

(defn analyze [config geometry]
  (if (and (some? config) (pos? (:overlap-weight config)))
    (let [edges (mapv vector (seq (:aa config)) (seq (:bb config)))
          result (->> empty-analysis (node-pair-conflicts config geometry)
                      (node-conflicts config geometry))]
      (reduce (fn [result [i j]] (edge-conflict config geometry result (edges i) (edges j)))
              result (for [i (range (count edges)) j (range (inc i) (count edges))] [i j])))
    empty-analysis))

(defn proposals [event pinned?]
  (if (some? event)
    (let [candidates
          (cond
            (:crossing? event) (:candidates event)
            (:node-pair? event) (mapv vector (:moves event))
            :else
            (let [{:keys [vertex a b t normal deficit distance]} event
                  [nx ny] normal
                  directions (if (> distance epsilon) [1.0] [1.0 -1.0])]
              (for [[i coefficient] [[vertex 1.0] [a (- t 1.0)] [b (- t)]]
                    :when (> (Math/abs (double coefficient)) epsilon)
                    sign directions
                    :let [step (/ (* 1.1 sign deficit) coefficient)]]
                [[i [(* step nx) (* step ny)]]])))]
      (remove (fn [moves] (some (fn [[i _]] (aget ^booleans pinned? i)) moves)) candidates))
    []))
