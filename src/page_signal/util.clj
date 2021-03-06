(ns page-signal.util
  (:use [clojure.pprint])
  (:require [page-signal.input :as in]
            [net.cgrand.enlive-html :as h]
            [clojure.string :as s])
  (:import [com.github.geodrome.wordmatcher WordMatcher]))

(defn count-matches
  "a1 and a2 should be arrays of strings. Returns the number of matching strings... per algo"
  [^objects a1 ^objects a2]
  (if (or (nil? a1)
          (nil? a2)
          (zero? (alength a1))
          (zero? (alength a2)))
    0
    (WordMatcher/count a1 a2)))

;;; Block Processing Helpers

(defn text*
  "Performs depth first traversal of root and returns all decendants of root that are strings in nested vectors."
  [root]
  (let [children (:content root)]
    (reduce (fn [str-vec child]
              (if (string? child)
                (conj str-vec child)
                (conj str-vec (text* child))))
            []
            children)))

; q tag should encapsulate in quotation marks, pre tag doesn't ignore whitespace
(defn convert-break-tags
  "Replaces line breaking tags with newline strings."
  [{:keys [tag] :as node}]
  (cond
   (#{:br} tag) "\n"
   (#{:img :map :hr} tag) "\n\n"
   :else node))

(defn text
  "Performs depth first traversal of root and returns a lazy seq of all decendants of root that are strings. Consider storing :full-text as block attribute. Eliminate spacing when dealing with inline elements? "
  [node]
  (cond
   (string? node) node
   (map? node) (->> node
                    :content
                    (map convert-break-tags ,,,)
                    (map text ,,,)
                    (remove empty? ,,,) ;; necessary?
                    (apply str ,,,)
                    )
   :else ""))

(defn clean-string
 [st]
  (let [txt (s/replace st #"[ \t\n\x0B\f\r]+" " ")]
    (when-not (s/blank? txt)
      (s/trim txt))))

(defn words [s] (re-seq #"\w+" s))

(defn count-words [s] (count (words s)))

;;; Testing Word Matchin Performance

(def pool "ABC")
(defn get-random-id [n] (apply str (repeatedly n #(rand-nth pool))))
(def a1 (into-array (take 10000 (repeatedly #(get-random-id 5)))))
(def a2 (into-array (take 10000 (repeatedly #(get-random-id 5)))))
