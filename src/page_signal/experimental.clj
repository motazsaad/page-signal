(ns page-signal.experimental
  (:use [page-signal.core]))

;;; The code in this namespace is experimental and not fully coherent
;;; I include it here just in case. It explores various ideas borrowed from the Java
;;; Boilerpipe libaray as well as early versions of readability.js combined with
;;; my own ideas. These ideas were meant to be enhancements to the fundamental algo
;;; implemented in page-signal.core

;;; BLOCK IDENTIFICATION AND ANALYSIS WITHOUT DOM RETENTION

(defn merge-text
  [s1 s2]
  (if (s/blank? s2)
    s1
    (if (s/blank? s1)
      s2
      (str s1 " " (s/trim s2)))))

(defn process-node
  "Processes node and returns altered blocks."
  [blocks node]
  (if (string? node)
    (let [text (s/replace node "\n" "")
          block (peek blocks)]
      (if (s/blank? text)
        blocks ; return unchanged
        (conj (pop blocks) (merge-with merge-text block {:text text}))))

    (let [tag (:tag node)]
      (cond
        (ignorable-tag? tag)
        blocks ; return unchanged

        (= :a tag)
        (let [block (peek blocks)
              anchor-text (s/replace (get-anchor-text node) "\n" "")
              link-words (:link-words block 0)]
          (if (s/blank? anchor-text)
            blocks ; return unchanged
            (conj (pop blocks) {:text (merge-text (:text block) anchor-text)
                                :link-words (+ link-words
                                               (count-words anchor-text))})))

        :else
        (if-let [content (:content node)]
          (if (= (peek blocks) {}) ;; {} signifies a 'fresh' block
            (reduce process-node blocks content)
            (reduce process-node (conj blocks {}) content))
          blocks)))))

(defn text-blocks
  "Processes nodes and returns atomic text blocks =
any character sequence not interrupted by an HTML tag, except the A tag."
  [nodes]
  (let [body (h/select nodes [:body])
        blocks (reduce process-node [{}] body)]
    (if (= {} (peek blocks)) ;; sometimes end up with empty block
      (pop blocks)
      blocks)))

; some text blocks have no words => divide by zero i.e. :text "\n \n"
(defn assoc-word-count
  "Assocs the word-count into block."
  [{:keys [text] :as block}]
  (when text
    (assoc block :total-words (count-words text))))

(defn assoc-link-density
  "Assocs link density into block."
  [{:keys [total-words link-words] :as block}]
  (if (or (nil? link-words) (zero? total-words)) ;; zero? total-words re-examine
    (assoc block :link-density 0.0)
    (assoc block :link-density (double (/ link-words total-words)))))

(defn annotate-blocks
  "Annotates blocks with information necessary for boilerplate analysis."
  [blocks]
  (let [f (comp assoc-link-density
                assoc-word-count)]
    (map f blocks)))

;;;; DOM LEVEL BLOCK IDENTIFICATION WITH ZIPPERS

(defn remove
  "Removes loc and return next loc to be processed."
  [loc]
  (z/next (z/remove loc)))

(defn block-wrap
  "Wraps loc in block tag and returns next loc to be processed. Assumes the node at loc has no children. What if anchor tag has children?"
  [loc]
  (-> loc
      (z/edit loc #(array-map :tag :block :content [%]))
      z/down z/next))

(defn process-block-tag
  "Processes block tag and returns the next loc to be processed."
  [loc node]
  (if-let [left-loc (z/left loc)]
    (if (= :block (-> left-loc z/node :tag))
      (-> loc z/remove (z/insert-right node) z/down z/rightmost z/next)
      (block-wrap loc))
    (block-wrap loc)))

(defn process-loc
  [loc]
  (if (z/end? loc)
    loc
    (let [node (z/node loc)]
      (println "NODE: " node)
      (cond
       (ignorable-tag? (:tag node))
       (process-loc (remove loc))

       (string? node)
       (let [text (s/replace node "\n" "")]
         (if (s/blank? text) ;; allow whitespace?
           (process-loc (remove loc))
           (process-loc (process-block-tag text))))

       (block-tag? node)
       (process-loc (process-block-tag loc node))

       (nil? (:content node)) ;; treat same as block item for now
       (process-loc (process-block-tag loc node))

       :else ;; a separating tag
       (process-loc (z/next loc))))))

(defn id-blocks
  [root]
  (let [body (first (h/select root [:body]))
        loc (z/xml-zip body)
        blocks (process-loc loc)]
    (z/root blocks)))


;;;; ORIGINAL PARTITION AND PROCESS

(defn process-part
  "p is seq of nodes that are expected to either all be block nodes or all be non-block nodes. Seqs of atomic blocks are cleaned up and wrapped in a block tag. Seqs of non-block nodes are recursively processed by partition-and-process."
  [p]
  (if (block-item? (first p))
    (let [p (remove-nils (map clean-if-string p))]
      (when (pos? (count p))
        {:tag :block :content p}))
    (remove-nils (map partition-and-process p))))

(defn partition-and-process
  "Partitions child nodes of root into block nodes / non-block nodes and recursively submits each partition to be processed by process-part. Assumes root is a non-block node. Retruns nil for ignorable tags."
  [{:keys [tag content] :as root}]
  (when-not (ignorable-tag? tag)
    (if content
      (let [parts (partition-by block-item? content)]
        (assoc root :content (-> (map process-part parts)
                                 flatten
                                 remove-nils)))
      root)))

;;; Block Fusion

;; Recalculate text-density when blocks fused?

(defn slope-delta
  [den1 den2]
  (double (/ (Math/abs (- den1 den2)) (max den1 den2))))

(defn fuse?
  [{den1 :text-density :as block1} {den2 :text-density :as block2} thresh]
  (<= (slope-delta den1 den2) thresh))

;; Terminating Blocks

(def starts-with #{"comments"
                   "@reuters"
                   "please rate this"
                   "poast a comment"})

(def contains #{"what you think..."
                "add your comment"
                "add coment"
                "reader views"
                "have your say"
                "reader comments"
                "thanks for your comments - this feedback is now closed"})

(defn mark-terminating-block
  [{:keys [total-words link-words] :as block}]
  (let [^String text (u/text block)]
    ;; need a short-circuiting version?
    (reduce #(or %1 %2)
            (map #(.startsWith text %) starts-with))))

(defn ignore-blocks-after-content
  "Marks all blocks that occure after :end-of-text as :boiler"
  [])

;;; Extract Article Title

; combos
(defn f*
  [n v r]
  (if (>= (count v) n)
    (recur n (rest v) (conj r (take n v)))
    r))

(defn f
  [n v] (f* n v []))

(defn g
  [v]
  (mapcat f
          (map inc (range (count v)))
          (repeat v)))


(def sep #" \| | \- |: ")

(defn make-class-set
  [ts]
  (clojure.set/union (set (map #(keyword (str "." %)) ts))
                     (set (map #(keyword (str "#" %)) ts))))

(defn get-class-titles
  [ts hres]
  (let [tags (h/select hres [:body (make-class-set ts)])]
    (map u/text tags)))

(defn get-title-tag-titles
  [hres]
  (let [sep #" \| | \- |: "
        title-tag-text (doc-title-text hres)]
    (s/split title-tag-text sep)))

(defn mark-title-match
  [root title]
  (map-blocks #(assoc % :title-match
                        ((partial match-score title) (u/text %)))
              root))

(defn map-non-block-tag-nodes
  "Returns root with function f applied to every block node and every tag node that isn't inside a block, starting at root and proceeding via depth first traversal. Non-tag nodes (i.e. strings) and nodes wrapped in block tag are returned unchaged. If f returns nil for any tag node, its descendants are lost. Assumes root is a non-block tag node."
  [f root]
  (if (map? root)
    (if-let [{:keys [content] :as froot} (f root)]
      (if (and (not= tag :block) content)
        (assoc froot :content (map (partial map-non-block-tag-nodes f) content))
        froot))
    root))

(defn remove-linky
  [nodes]
  (filter #(zero? (:link-density %)) (get-htags nodes))) ;; link
;; density only in blocks

(defn find-headline-block
  [root]
  (let [candidates (filter #(and (> (:title-match %) 0.333)
                                 (zero? (:link-density %)))
                           (seq-view root))]
    (reduce #(max (:title-match %1)
                  (:title-match %2)) candidates)))

(defn get-htags
  [nodes]
  (group-by :tag (h/select nodes [#{:h1 :h2 :h3}])))

(defn h-tags
  [candidates]
  (filter #(or (= (:enclosing-tag %) :h1)
               (= (:enclosing-tag %) :h2)
               (= (:enclosing-tag %) :HEADLINE)) candidates))

(defn class-set
  [coll]
  (clojure.set/union (set (map #(h/attr-contains :class %) coll))
                     (set (map #(h/attr-contains :id %) coll))))

(def headline-strings #{"title" "headline" "heading" "header"})

(defn get-classes
  [class-names nodes]
  (h/select nodes [(class-set class-names)]))

(defn non-title-tag
  [nodes]
  (let [title-class-nodes ((get-classes title-strings nodes))]
    (if-not (empty? title-class-nodes)
      (first title-class-nodes)
      (let [htag-nodes (filter #(zero? (:link-density %)) (get-htags nodes))]))))

(defn mark-word-count*
  "Handle HR tag"
  [{:keys [content] :as block}]
  (reduce (fn [block node] ;; assumes node is a string or anchor tag - WRONG
            (if (string? node)
              (merge-with + block {:total-words (u/count-words node)
                                   :link-words 0})
              (let [word-count (u/count-words (u/text node))]
                (merge-with + block {:total-words word-count
                                     :link-words word-count}))))
          block content))

; old title matching
(defn match-score
  [s1 s2]
  (if (or (s/blank? s1) (s/blank? s2))
    0.0
    (let [^objects a1 (into-array (u/words s1))
          ^objects a2 (into-array (u/words s2))
          match-count (u/count-matches a1 a2)]
      (if (zero? match-count)
        0.0
        (double (/ match-count (max (alength a1) (alength a2))))))))

(defn max-match
  "Scores every string in block against title and returns the best matching string and its score in a vector: [best-matching-string score]."
  [block title]
  (reduce (fn [[s1 score1] [s2 score2]]
            (if (> score2 score1)
              [s2 score2]
              [s1 score1]))
          ["" 0.0]
          (map (fn [s]
                 [s (match-score title s)])
               (seq-strings block))))

(defn mark-title-match
  [root title]
  (map-blocks (fn [block]
                (let [[title-text score] (max-match block title)]
                  (assoc block :title-match score :title-text title-text)))
              root))

(defn headline-block
  [nodes]
  (let [candidates (filter #(and (> (:title-match %) 0.28)
                                 (zero? (:link-density %)))
                           (seq-blocks nodes))
        num-cand (count candidates)]
    (cond
     (zero? num-cand)
     (let [r {:title-text (non-title-tag (z/xml-zip nodes))}]
       nil)

     (= 1 num-cand)
     (first candidates)

     :else
     (first candidates)))) ; first works better than longest string
                                        ; (434 vs 396)

;;; OLD HEADLINE EVALUATION
(defn annot-headline
  [f]
  (let [span (-> f
                 in/file->nodes
                 first
                 (get-span #{(:headline annotations)})
                 first)
        headline (u/clean-string (u/text span))]
    (if span
      (if headline
        headline
        :span-but-no-headline)
      :not-annotated)))

(defn headline
  [f]
  (try (-> f
           h/html-resource
           c/process
           c/headline-block
           :title-text)
       (catch Exception e {:exception (.getMessage e)}))) ;; likely no
;; body

(defn eval-headline
  [n f-out]
  (let [orig (get-sample orig-dir n)
        annot (files-from-dir annot-dir orig)
        h-retriv (map (fn [f]
                     {:file (.getName f) :headline (headline f)}) orig)
        h-annot (map (fn [f]
                       {:file (.getName f) :headline (annot-headline f)}) annot)
        res (map (fn [{head-r :headline file-r :file} {head-a :headline file-a :file}]
                   (let [match (if (or (map? head-r) ; exception in head-r
                                       (= :not-annotated head-a)
                                       (= :span-but-no-headline head-a))
                                 :na
                                 (= head-r head-a))]
                     {:file file-r :head-r head-r :head-a head-a :match match}))
                 h-retriv
                 h-annot)
        no-headline-ret (filter #(and (false? (:match %)) (nil? (:head-r %))) res)
        headline-mismatch (filter #(and (false? (:match %)) (not (nil? (:head-r %)))) res)
        successes (filter #(true? (:match %)) res)
        exceptions (filter #(map? (:head-r %)) res)
        match-na (filter #(= :na (:match %)) res)
        not-annotated (filter #(= :not-annotated (:headline %)) h-annot)
        span-but-no-headline (filter #(= :span-but-no-headline (:headline %)) h-annot)]
    (spit f-out (str "REPORT: " (count successes) " out of " (- n (count match-na)) "\n\n"))
    (spit f-out (str (count no-headline-ret) " FAILURES -- NO HEADLINE RETRIEVED: \n")
          :append true)
    (doseq [r no-headline-ret]
      (spit f-out (str r "\n") :append true))
    (spit f-out (str "\n\n" (count headline-mismatch) " FAILURES -- MISMATCH: \n")
          :append true)
    (doseq [r headline-mismatch]
      (spit f-out (str r "\n") :append true))
    (spit f-out (str "\n\n" (count successes) " SUCCESSES: \n") :append true)
    (doseq [r successes]
      (spit f-out (str r "\n") :append true))
    (spit f-out (str "\n\n" (count exceptions) " EXCEPTIONS: \n") :append true)
    (doseq [r exceptions]
      (spit f-out (str r "\n") :append true))
    (spit f-out (str "\n\n" (count match-na) " MATCH N/A: \n") :append true)
    (doseq [r match-na]
      (spit f-out (str r "\n") :append true))
    (spit f-out (str "\n\n" (count not-annotated) " NOT ANNOTATED: \n")
          :append true)
    (doseq [r not-annotated]
      (spit f-out (str r "\n") :append true))
    (spit f-out (str "\n\n" (count span-but-no-headline) " SPAN BUT NO HEADLINE: \n")
          :append true)
    (doseq [r span-but-no-headline]
      (spit f-out (str r "\n") :append true))
    (println (count successes) " out of " (- n (count match-na)))))


;;; Extract Article Title

(defn headline-class?
  [class]
  (let [hclass #{"title" "headline"}]
    (when (and class
               (reduce #(or %1 (.contains class %2)) false hclass)) ; short-circuit
      true)))

(defn cand?
  [loc]
  (let [node (z/node loc)]
    (when (and (map? node)
               (or (#{:h1 :h2 :h3 :HEADLINE} (:tag node))
                   (headline-class? (-> node :attrs :class))
                   (headline-class? (-> node :attrs :id))))
      true)))

(defn find-cands*
  [loc cands]
  (cond
   (z/end? loc) cands
   (cand? loc) (recur (z/next loc) (conj cands loc))
   :else (recur (z/next loc) cands)))

(defn find-cands [loc] (find-cands* loc nil))

(defn see-cands [cands]
  (pprint (map #(z/node %) cands)))

(defn non-title-tag-title
  [loc]
  (let [cand-locs (filter #(zero? (count-link-words (z/node %)))
                      (find-cands loc))
        titles (remove s/blank? (map #(u/text (z/node %)) cand-locs))]
    (first titles))) ;; for now

;; getting title tag matching titles

(defn matching-title
  "more than one match is rare, so returning first is okay"
  [^String titles ^String s]
  (first (remove nil? (map #(when (.equals s %) s) titles))))

(defn longer-string
  [^String s1 ^String s2]
  (if (> (.length s2) (.length s1)) s2 s1))

(defn title-match
  "Checks every string in a block and returns an exact match from among titiles."
  [block titles]
  (let [match (reduce
                  longer-string
                  ""
                  (remove nil? (map (partial matching-title titles)
                                    (seq-strings block))))]
    (when-not (= "" match) match)))

(defn n-frags*
  [n frags seps res]
  (if (>= (count frags) n)
    (let [word-group (take n frags)
          sep (take n (cycle (concat seps [""])))]
      (recur n (rest frags) (rest seps)
             (conj res (butlast (interleave word-group sep)))))
    res))

(defn n-frags
  "Return a seq of all strings that are combinations of n fragments interleaved with corresponding separators."
  [n frags seps]
  (map s/trim (map s/join (n-frags* n frags seps []))))

(defn potential-titles
  "Given title tag text, return substrings of title likely to be the page title."
  [title]
  (let [sep-re #"[:|\-»,/]"
        frags (s/split title sep-re)
        seps (re-seq sep-re title)]
    (mapcat n-frags
            (map inc (range (count frags)))
            (repeat frags)
           (repeat seps))))

;; when two matches of decen length attmept further logic (e.g.
;; location - proximity to main text, enclosing tag)
(defn best-matching-block
  "Return the block from candidates most likely to contain the page title."
  [candidates]
  (reduce (fn [b1 b2]
            (if (> (.length (:title-text b2))
                   (.length (:title-text b1)))
              b2
              b1))
          candidates))

(defn mark-title-match
  [root title]
  (let [titles (potential-titles title)]
    (map-blocks (fn [block]
                  (let [title-text (title-match block titles)]
                    (assoc block :title-text title-text)))
                root)))

; get from meta tag
(defn headline-block
  "Return block most likely to contain the headline."
  [nodes]
  (let [candidates (filter #(:title-text %)
                           (seq-blocks nodes))
        num-cand (count candidates)
        r {:title-text (non-title-tag-title (z/xml-zip nodes))}]
    (cond
     (zero? num-cand)
     r

     (= 1 num-cand)
     (if r
       r
       (first candidates))

     :else
     (best-matching-block candidates))))

(defn doc-title-text
  [nodes]
  (u/text (first (h/select nodes [:title]))))

;;; Evaluate Headline

(defn annot-headline
  [nodes]
  (let [span (-> nodes
                 first
                 (h/select nodes [:body #{(:headline annotations)}])
                 first)
        headline (u/clean-string (u/text span))]
    (if span
      (if headline
        headline
        :span-but-no-headline)
      :not-annotated)))

(defn headline
  [nodes]
  (try (-> nodes
           c/process
           c/headline-block
           :title-text)
       (catch Exception e {:exception (.getMessage e)})))

(def h-annot (map (fn [fname]
                       {:file fname
                        :headline (annot-headline (annot-nodes fname))}) file-names))

(defn eval-headline
  [f-out]
  (let [n (count file-names)
        h-retriv (map (fn [fname]
                        {:file fname
                         :headline (headline (orig-nodes fname))}) file-names)
        res (map (fn [{head-r :headline file-r :file} {head-a :headline file-a :file}]
                   (let [match (if (or (map? head-r) ; exception in head-r
                                       (= :not-annotated head-a)
                                       (= :span-but-no-headline head-a))
                                 :na
                                 (= head-r head-a))]
                     {:file file-r :head-r head-r :head-a head-a :match match}))
                 h-retriv
                 h-annot)
        no-headline-ret (filter #(and (false? (:match %)) (nil? (:head-r %))) res)
        headline-mismatch (filter #(and (false? (:match %)) (not (nil? (:head-r %)))) res)
        successes (filter #(true? (:match %)) res)
        exceptions (filter #(map? (:head-r %)) res)
        match-na (filter #(= :na (:match %)) res)
        not-annotated (filter #(= :not-annotated (:headline %)) h-annot)
        span-but-no-headline (filter #(= :span-but-no-headline (:headline %)) h-annot)]
    (spit f-out (str "REPORT: " (count successes) " out of " (- n (count match-na)) "\n\n"))
    (spit f-out (str (count no-headline-ret) " FAILURES -- NO HEADLINE RETRIEVED: \n")
          :append true)
    (doseq [r no-headline-ret]
      (spit f-out (str r "\n") :append true))
    (spit f-out (str "\n\n" (count headline-mismatch) " FAILURES -- MISMATCH: \n")
          :append true)
    (doseq [r headline-mismatch]
      (spit f-out (str r "\n") :append true))
    (spit f-out (str "\n\n" (count successes) " SUCCESSES: \n") :append true)
    (doseq [r successes]
      (spit f-out (str r "\n") :append true))
    (spit f-out (str "\n\n" (count exceptions) " EXCEPTIONS: \n") :append true)
    (doseq [r exceptions]
      (spit f-out (str r "\n") :append true))
    (spit f-out (str "\n\n" (count match-na) " MATCH N/A: \n") :append true)
    (doseq [r match-na]
      (spit f-out (str r "\n") :append true))
    (spit f-out (str "\n\n" (count not-annotated) " NOT ANNOTATED: \n")
          :append true)
    (doseq [r not-annotated]
      (spit f-out (str r "\n") :append true))
    (spit f-out (str "\n\n" (count span-but-no-headline) " SPAN BUT NO HEADLINE: \n")
          :append true)
    (doseq [r span-but-no-headline]
      (spit f-out (str r "\n") :append true))
    (println (count successes) " out of " (- n (count match-na)))))


;; these are currently unused, but here to help me think
(def enclosing-tag? #{:h1 :h2 :h3 :h4 :h5 :h6 :p :div})


(def inline-tag? #{:b :big :i :small :tt :abbr :acronym :cite :code :dfn :em
                    :kbd :strong :samp :var :a :bdo :br :img :map :object :q
                    :span :sub :sup :button :input :label :select :textarea :font
                    :strike :u :s})

(def gap-enforcing-tags #{:h1 :h2 :h3 :h4 :h5 :h6
                          :ul :dl :ol :table :address :hr :img :script})
(def gap-avoiding-tags #{:a :b :br :em :font :i :s
                         :span :strong :sub :sup :u :tt})
