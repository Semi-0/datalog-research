(ns graph.vijual.stress-snapshot
  "Render actual solver output to a labeled PNG with Java2D; no browser required."
  (:require [graph.vijual.stress-comparison :as comparison])
  (:import (java.awt BasicStroke Color Font RenderingHints)
           (java.awt.image BufferedImage)
           (java.io File)
           (javax.imageio ImageIO)))

(defn- centered [nodes]
  (let [n (max 1 (count nodes))
        cx (/ (reduce + (map (comp first :position) nodes)) n)
        cy (/ (reduce + (map (comp second :position) nodes)) n)]
    (mapv (fn [node] (update node :position (fn [[x y]] [(- x cx) (- y cy)]))) nodes)))

(defn- bounds [nodes]
  (reduce (fn [[left top right bottom] {[x y] :position [w h] :size}]
            [(min left (- x (/ w 2))) (min top (- y (/ h 2)))
             (max right (+ x (/ w 2))) (max bottom (+ y (/ h 2)))])
          [Double/POSITIVE_INFINITY Double/POSITIVE_INFINITY
           Double/NEGATIVE_INFINITY Double/NEGATIVE_INFINITY] nodes))

(defn- boundary [{[ax ay] :position [w h] :size} {[bx by] :position}]
  (let [dx (- bx ax) dy (- by ay)
        tx (if (zero? dx) Double/POSITIVE_INFINITY (/ w 2 (Math/abs (double dx))))
        ty (if (zero? dy) Double/POSITIVE_INFINITY (/ h 2 (Math/abs (double dy))))]
    (if (and (zero? dx) (zero? dy))
      [ax ay]
      (let [t (min tx ty)] [(+ ax (* t dx)) (+ ay (* t dy))]))))

(defn- text! [graphics text x y size color]
  (.setColor graphics color)
  (.setFont graphics (Font. "SansSerif" Font/PLAIN size))
  (.drawString graphics (str text) (int x) (int y)))

(defn- edges! [graphics edges nodes project]
  (let [by-id (into {} (map (juxt :id identity) nodes))]
    (.setColor graphics (Color. 90 105 122))
    (.setStroke graphics (BasicStroke. 2.2))
    (doseq [[a b] edges]
      (let [[x1 y1] (project (boundary (by-id a) (by-id b)))
            [x2 y2] (project (boundary (by-id b) (by-id a)))]
        (.drawLine graphics (int x1) (int y1) (int x2) (int y2))))))

(defn- nodes! [graphics nodes project scale]
  ;; Paint every box before painting labels: even a bad baseline cannot hide IDs.
  (doseq [{:keys [position size]} nodes]
    (let [[cx cy] (project position) [w h] (map #(* scale %) size)
          x (int (- cx (/ w 2))) y (int (- cy (/ h 2)))]
      (.setColor graphics (Color. 223 237 255))
      (.fillRoundRect graphics x y (int w) (int h) 10 10)
      (.setColor graphics (Color. 35 73 118))
      (.setStroke graphics (BasicStroke. 2.0))
      (.drawRoundRect graphics x y (int w) (int h) 10 10)))
  (.setFont graphics (Font. "SansSerif" Font/BOLD 24))
  (.setColor graphics (Color. 17 39 66))
  (doseq [{:keys [id position]} nodes]
    (let [[cx cy] (project position) label (str id)
          metrics (.getFontMetrics graphics)
          x (- cx (/ (.stringWidth metrics label) 2))
          y (+ cy (/ (- (.getAscent metrics) (.getDescent metrics)) 2))]
      (.drawString graphics label (int x) (int y)))))

(defn- panel! [graphics result method nodes rectangle domain]
  (let [[x y width height] rectangle [left top right bottom] domain
        scale (min (/ (- width 48) (max 1.0 (- right left)))
                   (/ (- height 48) (max 1.0 (- bottom top))))
        ox (- (+ x (/ width 2)) (* scale (/ (+ left right) 2)))
        oy (- (+ y (/ height 2)) (* scale (/ (+ top bottom) 2)))
        project (fn [[px py]] [(+ ox (* scale px)) (+ oy (* scale py))])
        metrics (get-in result [method :metrics])
        title (case method
                :standard "Original stress"
                :symmetric "Balanced Stress"
                (throw (ex-info "Unknown snapshot method" {:method method})))]
    (text! graphics title (+ x 24) 105 24 (Color. 25 38 56))
    (edges! graphics (:edges result) nodes project)
    (nodes! graphics nodes project scale)
    (text! graphics (str "Shared edge overlaps: " (:edge-overlaps metrics)
                         "    Crossings: " (:edge-crossings metrics))
           (+ x 24) 535 18 (Color. 40 54 73))
    (text! graphics (str "Edges through nodes: " (:edge-node-overlaps metrics)
                         "    Node overlaps: " (:node-overlaps metrics))
           (+ x 24) 565 18 (Color. 40 54 73))))

(defn write-png! [result path]
  (let [image (BufferedImage. 1280 620 BufferedImage/TYPE_INT_RGB)
        graphics (.createGraphics image)
        standard (centered (get-in result [:standard :nodes]))
        symmetric (centered (get-in result [:symmetric :nodes]))
        domain (bounds (concat standard symmetric))
        file (File. path)]
    (try
      (.setRenderingHint graphics RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (.setColor graphics Color/WHITE)
      (.fillRect graphics 0 0 1280 620)
      (text! graphics "Stress layout: edges and nodes" 40 38 26 (Color. 17 39 66))
      (text! graphics (str (count (:input result)) " labeled nodes, " (count (:edges result))
                           " edges. Identical starting positions, sizes, scale and iteration budget.")
             40 67 18 (Color. 65 80 99))
      (panel! graphics result :standard standard [16 120 608 380] domain)
      (panel! graphics result :symmetric symmetric [656 120 608 380] domain)
      (text! graphics "Actual solver output; straight segments; no display-only snapping or rerouting." 40 605 16 (Color. 65 80 99))
      (when-let [parent (.getParentFile file)]
        (when-not (.exists parent)
          (when-not (.mkdirs parent)
            (throw (ex-info "Cannot create snapshot directory" {:path path})))))
      (when-not (ImageIO/write image "png" file)
        (throw (ex-info "PNG encoder unavailable" {:path path})))
      (.getAbsolutePath file)
      (finally (.dispose graphics)))))

(defn -main [& [path]]
  (when-not path (throw (ex-info "Supply an output PNG path" {})))
  (let [result (comparison/compare-layouts (comparison/overlap-graph))]
    (prn {:path (write-png! result path)
          :standard (get-in result [:standard :metrics])
          :symmetric (get-in result [:symmetric :metrics])})
    (shutdown-agents)))
