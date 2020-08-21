(ns notespace.v2.note
  (:require [clojure.string :as string]
            [hiccup.core :as hiccup]
            [hiccup.page :as page]
            [notespace.v2.reader :as reader]
            [notespace.v2.util :refer [fmap only-one]]
            [notespace.v2.view :as view]
            [notespace.v2.css :as css]
            [rewrite-clj.node]
            [notespace.v2.cdn :as cdn]
            [notespace.v2.js :as js]
            [cambium.core :as log]
            [notespace.v2.source :as source]
            [notespace.v2.state :as state]
            [notespace.v2.init :as init])
  (:import java.io.File
           clojure.lang.IDeref))

(defonce init
  (init/init!))

;; A note has a kind, possibly a label, a collection of forms, and the reader metadata.
(defrecord Note [kind label forms metadata])

;; A note's state has a return value, a rendered result, and a status.
(defrecord NoteState [value rendered status])

(def initial-note-state (->NoteState nil nil {:changed true}))

(defn note->note-state [namespace anote]
  (->> anote
       :metadata
       :line
       (state/ns->line->index namespace)
       (state/ns->note-state namespace)))


;; We can collect all toplevel forms in a namespace,
;; together with the reader metadata.
(defn ->ns-topforms-with-metadata [namespace]
  (->> namespace
       source/ns->source-filename
       reader/file->topforms-with-metadata))



;; When the first form of a note is a keyword,
;; then it would be considered the form's label
(defn forms->label [[first-form & _]]
  (when (keyword? first-form)
    first-form))

;; Each note toplevel form can be converted to a Note.
(defn kind-forms-and-metadata->Note [kind forms metadata]
  (->Note kind
          (forms->label forms)
          (vec forms)
          metadata))

(defn topform-with-metadata->Note
  ([topform-with-metadata]
   (when (sequential? topform-with-metadata)
     (when-let [kind (-> topform-with-metadata first state/note-symbol->kind)]
       (let [[& forms] (rest topform-with-metadata)]
         (kind-forms-and-metadata->Note
          kind
          forms
          (meta topform-with-metadata)))))))


;; Thus we can collect all notes in a namespace.
(defn ns-notes [namespace]
  (->> namespace
       ->ns-topforms-with-metadata
       (map topform-with-metadata->Note)
       (filter some?)))

;; We can update our notes structures by reading the notes of a namespace.
;; We try not to update things that have not changed.

;; TODO: Rethink
(defn different-note? [old-note new-note]
  (not
   (and (->> [old-note new-note]
             (map (comp :source :metadata))
             (apply =))
        (->> [old-note new-note]
             (map (juxt :kind :forms))
             (apply =)))))


(defn read-notes-seq! [namespace]
  (let [old-notes            (state/ns->notes namespace)
        old-notes-states     (state/ns->note-states namespace)
        old-notes-and-states (map vector
                                  old-notes
                                  old-notes-states)
        source-modified      (source/source-file-modified? namespace)
        needs-update         (or (not old-notes)
                                 source-modified)
        notes-and-states     (if (not needs-update)
                               old-notes-and-states
                               (let [new-notes (ns-notes namespace)]
                                 (mapv (fn [[old-note old-note-state] new-note]
                                         (if (different-note? old-note new-note)
                                           [new-note initial-note-state]
                                           [(merge old-note
                                                   (select-keys new-note [:metadata]))
                                            old-note-state]))
                                       (concat old-notes-and-states (repeat nil))
                                       new-notes)))
        notes                (map first notes-and-states)]
    (when needs-update
      (let [line->index    (->> notes
                                (map-indexed (fn [idx {:keys [metadata]}]
                                               {:idx   idx
                                                :lines (range (:line metadata)
                                                              (-> metadata :end-line inc))}))
                                (mapcat (fn [{:keys [idx lines]}]
                                          (->> lines
                                               (map (fn [line]
                                                      {:idx  idx
                                                       :line line})))))
                                (group-by :line)
                                (fmap (comp :idx only-one)))
            label->indices (->> notes
                                (map-indexed (fn [idx anote]
                                               {:idx   idx
                                                :label (:label anote)}))
                                (filter :label)
                                (group-by :label)
                                (fmap (partial mapv :idx)))]
        (state/assoc-in-state!
         [:ns->notes namespace] (mapv first notes-and-states)
         [:ns->note-states namespace] (mapv second notes-and-states)
         [:ns->line->index namespace] line->index
         [:ns->label->indices namespace] label->indices)))
    [:notes
     notes-and-states
     (if needs-update :updated :not-updated)
     (count notes)]))


;; We support various update transformations for notes' states.
(defn update-note-state! [namespace transf anote]
  (let [idx (->> anote
                 :metadata
                 :line
                 (state/ns->line->index namespace))]
    (state/update-in-state!
     [:ns->note-states
      namespace
      idx]
     transf)))

;; A note is computed by evaluating its form to compute its value.
(defn evaluate-note [anote]
  (try
    (->> anote
         :forms
         (cons 'do)
         eval)
    (catch Exception e
      (throw (ex-info "Note evaluation failed."
                      {:note      anote
                       :exception e}))) ))

(defn compute-note [anote note-state]
  (let [value    (evaluate-note anote)
        renderer (-> anote :kind (state/kind->behaviour) :value-renderer)
        rendered (renderer value)]
    (assoc note-state
           :value value
           :rendered rendered)))

(defn compute-note! [namespace anote]
  (update-note-state! namespace
                      (partial compute-note anote)
                      anote))

(defn ns->out-dir [namespace]
  (let [dirname (str (state/config [:target-path])
                     "/"
                     (-> namespace str (string/replace "." "/"))
                     "/")
        dir (File. dirname)]
    (when-not (.exists dir)
      (.mkdirs dir))
    dirname))

(defn copy-to-ns-target-path [source-uri target-filename]
  (io/copy source-uri
           (str (ns->out-dir *ns*)
                "/"
                target-filename)))

;; Any namespace has a corresponding output html file.
(defn ns->out-filename [namespace]
  (format "%s/index.html" (ns->out-dir namespace)))

(defn render-to-file! [render-fn path]
  (let [path-to-use (or path (str (File/createTempFile "rendered" ".html")))
        html (page/html5 (render-fn))]
    (spit path-to-use html)
    (log/info [::wrote path-to-use])
    html))

(defn notes->hiccup [namespace notes]
  (->> notes
       (map (partial note->note-state namespace))
       (view/notes-and-states->hiccup namespace notes)))

(defn render-notes! [namespace notes & {:keys [file]}]
  (render-to-file! (partial notes->hiccup namespace notes)
                   file))

(defn render-ns [namespace]
  (hiccup.core/html
   [:html
    (into [:head
           (js/mirador-setup)
           (css/include-css (state/config [:css]))]
          (mapcat cdn/header [:prettify :datatables :fonts]))
    [:body
     (if (not namespace)
       "Waiting for a first notespace to appear ..."
       (do (read-notes-seq! namespace)
           (notes->hiccup
            namespace
            (state/ns->notes namespace))))]]))

(defn render-ns! [namespace]
  (render-to-file! (partial render-ns namespace)
                   (ns->out-filename namespace))
  (state/assoc-in-state! [:last-ns-rendered] namespace)
  [:rendered {:ns namespace}])

(defn render-this-ns []
  (render-ns *ns*))

(defn render-this-ns! []
  (render-ns! *ns*))

(defn check [pred & args]
  [(if (apply pred args)
     :PASSED
     :FAILED)
   (last args)])

(defn compute-note-at-line! [line]
  (read-notes-seq! *ns*)
  (some->> line
           (state/ns->line->index *ns*)
           (state/ns->note *ns*)
           (compute-note! *ns*))
  [[:computed {:ns   *ns*
               :line line}]
   (render-this-ns!)])

(defn compute-this-notespace! []
  (read-notes-seq! *ns*)
  (->> *ns*
       (state/ns->notes)
       (run! (partial compute-note! *ns*)))
  [[:computed {:ns *ns*}]
   (render-this-ns!)])