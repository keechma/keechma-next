(ns keechma.next.util)

(defn get-dirty-deps [prev-deps next-deps]
  (let [dirty
        (reduce
          (fn [m k]
            (let [v (get next-deps k)]
              (if-not (identical? v (get prev-deps k))
                (assoc m k v)
                m)))
          {}
          (set (concat (keys prev-deps) (keys next-deps))))]
    (if (empty? dirty)
      nil
      dirty)))

(defn lexicographic-compare
  ([xs ys]
   (lexicographic-compare compare xs ys))
  ([compare xs ys]
   (loop [xs (seq xs) ys (seq ys)]
     (if xs
       (if ys
         (let [c (compare (first xs) (first ys))]
           (if (not (zero? c))
             c
             (recur (next xs), (next ys))))
         1)
       (if ys
         -1
         0)))))

(defn sort-paths [paths]
  (sort lexicographic-compare paths))

(defn find-common-subvec [v1 v2]
  (let [max-idx (min (count v1) (count v2))]
    (if (pos? max-idx)
      (loop [idx 0]
        (if (and (= (get v1 idx) (get v2 idx))
                 (< idx max-idx))
          (recur (inc idx))
          (subvec v1 0 idx)))
      [])))

(defn get-lowest-common-ancestor-for-paths [paths]
  (let [[f & r] (reverse (sort paths))]
    (reduce
      (fn [acc v]
        (let [common-subvec (find-common-subvec acc v)]
          (if (= [] common-subvec)
            (reduced [])
            common-subvec)))
      f
      r)))