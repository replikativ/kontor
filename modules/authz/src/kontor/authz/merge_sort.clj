(ns kontor.authz.merge-sort
  "Lazy sorted-merge-with-dedup — ADR-066.

   The permission-graph traversal (`kontor.authz.indexed`) fans out
   into many `index-range` scans, each already sorted ascending by
   trailing eid. This namespace merges those sorted seqs into one
   sorted, deduplicated lazy seq — parallel paths through the graph
   can reach the same resource, and the merge dedupes them in stable
   order so cursor pagination stays correct.

   `lazy-fold2-merge-dedupe-sorted-by` is the one entry point the
   traversal uses; the rest are its helpers. Tournament-style
   (`fold2`) folding so dedup happens at each merge level rather than
   once at the end. Pure — no datahike dependency. Ported from EACL's
   `eacl.lazy-merge-sort` (research note 41)."
  (:require [clojure.core]))

(defn- fold2
  "Repeatedly apply `f` to pairs of elements (tournament-style) until
   one remains."
  [f s]
  (loop [s s]
    (if (next (next s))
      (recur (map f (partition-all 2 s)))
      (f s))))

(defn- lazy-merge2-dedupe-by
  "Lazily merge two already-deduplicated sorted seqs `x` and `y`,
   keeping the result deduplicated. `keyfn` extracts the
   comparison/dedup key; `cmp` returns true when its first arg sorts
   before its second."
  ([keyfn cmp x y] (lazy-merge2-dedupe-by keyfn cmp nil x y))
  ([keyfn cmp last-key x y]
   (lazy-seq
    (cond
      (empty? x)
      (when-let [s (seq (drop-while #(= (keyfn %) last-key) y))] s)

      (empty? y)
      (when-let [s (seq (drop-while #(= (keyfn %) last-key) x))] s)

      :else
      (let [xf (first x), yf (first y)
            xk (keyfn xf), yk (keyfn yf)]
        (cond
          (= xk yk)
          (if (= xk last-key)
            (lazy-merge2-dedupe-by keyfn cmp last-key (rest x) (rest y))
            (cons xf (lazy-merge2-dedupe-by keyfn cmp xk (rest x) (rest y))))

          (cmp xf yf)
          (if (= xk last-key)
            (lazy-merge2-dedupe-by keyfn cmp last-key (rest x) y)
            (cons xf (lazy-merge2-dedupe-by keyfn cmp xk (rest x) y)))

          :else
          (if (= yk last-key)
            (lazy-merge2-dedupe-by keyfn cmp last-key x (rest y))
            (cons yf (lazy-merge2-dedupe-by keyfn cmp yk x (rest y))))))))))

(defn- lazy-merge-all-dedupe-by
  "Merge a collection of sorted, deduplicated seqs, keeping dedup."
  [keyfn cmp seqs]
  (lazy-seq
   (let [non-empty (seq (filter seq seqs))]
     (when non-empty
       (if-let [[y] (next non-empty)]
         (lazy-merge2-dedupe-by keyfn cmp (first non-empty) y)
         (first non-empty))))))

(defn lazy-fold2-merge-dedupe-sorted-by
  "Merge multiple sorted, deduplicated seqs into one sorted,
   deduplicated lazy seq. `keyfn` extracts the comparison key; the
   input seqs must already be sorted + deduplicated by `(keyfn elem)`.
   Tournament-style: dedup happens at each merge level.

     (lazy-fold2-merge-dedupe-sorted-by identity [[1 3 5 7] [1 2 4 6 8] [0 5 9]])
     ;; => (0 1 2 3 4 5 6 7 8 9)"
  [keyfn seqs]
  (fold2
   (partial lazy-merge-all-dedupe-by keyfn #(< (keyfn %1) (keyfn %2)))
   seqs))
